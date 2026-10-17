package org.connecttag.lib.billing.play

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.connecttag.lib.billing.api.BillingEngine
import org.connecttag.lib.billing.api.BillingLogger
import org.connecttag.lib.billing.api.PurchaseVerificationHandler
import org.connecttag.lib.billing.model.BillingConnectionState
import org.connecttag.lib.billing.model.BillingOpResult
import org.connecttag.lib.billing.model.BillingProductConfig
import org.connecttag.lib.billing.model.BillingProductDetails
import org.connecttag.lib.billing.model.BillingProductType
import org.connecttag.lib.billing.model.BillingPurchaseRecord
import org.connecttag.lib.billing.model.BillingPurchaseState
import kotlin.coroutines.resume

/**
 * Robust Google Play Billing engine implementation conforming to BillingClient 7.x & 8.x standards.
 *
 * Encapsulates thread safety, connection lifecycle, coroutine cancellation, auto-reconnection,
 * product resolution, and receipt acknowledgment.
 */
class PlayBillingEngine(
    context: Context,
    private val verificationHandler: PurchaseVerificationHandler = PurchaseVerificationHandler.ACCEPT_ALL,
    private val logger: BillingLogger = BillingLogger.DEFAULT,
    private val autoAcknowledgePurchases: Boolean = true,
    private val connectionTimeoutMs: Long = 10_000L,
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate
) : BillingEngine, PurchasesUpdatedListener {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val connectionMutex = Mutex()
    private val operationMutex = Mutex()

    private val _connectionState = MutableStateFlow(BillingConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<BillingConnectionState> = _connectionState.asStateFlow()

    private val _purchasesUpdatedFlow = MutableSharedFlow<List<BillingPurchaseRecord>>(extraBufferCapacity = 16)
    override val purchasesUpdatedFlow: SharedFlow<List<BillingPurchaseRecord>> = _purchasesUpdatedFlow.asSharedFlow()

    private val _productDetailsList = MutableStateFlow<List<BillingProductDetails>>(emptyList())
    override val productDetailsList: StateFlow<List<BillingProductDetails>> = _productDetailsList.asStateFlow()

    private var billingClient: BillingClient? = null

    companion object {
        private const val TAG = "PlayBillingEngine"
    }

    init {
        initializeClient()
    }

    private fun initializeClient() {
        try {
            val pendingPurchasesParams = PendingPurchasesParams.newBuilder()
                .enableOneTimeProducts()
                .build()

            billingClient = BillingClient.newBuilder(appContext)
                .setListener(this)
                .enablePendingPurchases(pendingPurchasesParams)
                .enableAutoServiceReconnection()
                .build()
        } catch (e: Exception) {
            logger.e(TAG, "Failed to instantiate BillingClient", e)
        }
    }

    override suspend fun startConnection(): Boolean {
        return connectedClient() != null
    }

    private suspend fun connectedClient(): BillingClient? = connectionMutex.withLock {
        val client = billingClient ?: return@withLock null
        if (client.isReady) {
            _connectionState.value = BillingConnectionState.CONNECTED
            return@withLock client
        }

        _connectionState.value = BillingConnectionState.CONNECTING
        val connected = withTimeoutOrNull(connectionTimeoutMs) {
            suspendCancellableCoroutine { continuation ->
                client.startConnection(object : BillingClientStateListener {
                    override fun onBillingSetupFinished(billingResult: BillingResult) {
                        val isOk = billingResult.responseCode == BillingClient.BillingResponseCode.OK
                        if (isOk) {
                            _connectionState.value = BillingConnectionState.CONNECTED
                            logger.d(TAG, "BillingClient connected successfully.")
                        } else {
                            _connectionState.value = BillingConnectionState.DISCONNECTED
                            logger.w(TAG, "Billing connection setup finished with code: ${billingResult.responseCode}")
                        }
                        if (continuation.isActive) {
                            continuation.resume(client.takeIf { isOk })
                        }
                    }

                    override fun onBillingServiceDisconnected() {
                        _connectionState.value = BillingConnectionState.DISCONNECTED
                        logger.w(TAG, "Billing service disconnected.")
                        if (continuation.isActive) {
                            continuation.resume(null)
                        }
                    }
                })
            }
        }

        if (connected == null) {
            _connectionState.value = BillingConnectionState.DISCONNECTED
        }
        connected
    }

    override suspend fun queryProductDetails(products: List<BillingProductConfig>): List<BillingProductDetails> =
        operationMutex.withLock {
            val client = connectedClient() ?: return emptyList()
            if (products.isEmpty()) return emptyList()

            val inAppIds = products.filter { it.type == BillingProductType.INAPP }.map { it.productId }
            val subsIds = products.filter { it.type == BillingProductType.SUBS }.map { it.productId }

            val inAppDetails = if (inAppIds.isNotEmpty()) {
                fetchProductDetailsList(client, BillingClient.ProductType.INAPP, inAppIds)
            } else emptyList()

            val subsDetails = if (subsIds.isNotEmpty()) {
                fetchProductDetailsList(client, BillingClient.ProductType.SUBS, subsIds)
            } else emptyList()

            val combined = inAppDetails + subsDetails
            _productDetailsList.value = combined
            combined
        }

    private suspend fun fetchProductDetailsList(
        client: BillingClient,
        productType: String,
        productIds: List<String>
    ): List<BillingProductDetails> = withTimeoutOrNull(connectionTimeoutMs) {
        suspendCancellableCoroutine { continuation ->
            val productList = productIds.map { id ->
                QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(id)
                    .setProductType(productType)
                    .build()
            }
            val params = QueryProductDetailsParams.newBuilder().setProductList(productList).build()

            client.queryProductDetailsAsync(params) { billingResult, queryProductDetailsList ->
                if (continuation.isActive) {
                    if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                        val mapped = queryProductDetailsList.productDetailsList.map { details ->
                            mapToDomainProductDetails(details, productType)
                        }
                        continuation.resume(mapped)
                    } else {
                        logger.w(TAG, "QueryProductDetailsAsync failed: ${billingResult.responseCode} - ${billingResult.debugMessage}")
                        continuation.resume(emptyList())
                    }
                }
            }
        }
    } ?: emptyList()

    private fun mapToDomainProductDetails(details: ProductDetails, productType: String): BillingProductDetails {
        val isSubscription = productType == BillingClient.ProductType.SUBS
        val type = if (isSubscription) BillingProductType.SUBS else BillingProductType.INAPP

        val formattedPrice: String?
        val priceAmountMicros: Long?
        val priceCurrencyCode: String?
        val offerToken: String?

        if (isSubscription) {
            val offer = details.subscriptionOfferDetails?.firstOrNull()
            offerToken = offer?.offerToken
            val phase = offer?.pricingPhases?.pricingPhaseList?.firstOrNull()
            formattedPrice = phase?.formattedPrice
            priceAmountMicros = phase?.priceAmountMicros
            priceCurrencyCode = phase?.priceCurrencyCode
        } else {
            val oneTime = details.oneTimePurchaseOfferDetails
            offerToken = null
            formattedPrice = oneTime?.formattedPrice
            priceAmountMicros = oneTime?.priceAmountMicros
            priceCurrencyCode = oneTime?.priceCurrencyCode
        }

        return BillingProductDetails(
            productId = details.productId,
            type = type,
            title = details.title,
            description = details.description,
            formattedPrice = formattedPrice,
            priceAmountMicros = priceAmountMicros,
            priceCurrencyCode = priceCurrencyCode,
            offerToken = offerToken,
            rawProductDetails = details
        )
    }

    override suspend fun queryPurchases(): List<BillingPurchaseRecord>? = operationMutex.withLock {
        val client = connectedClient() ?: return null

        val subs = fetchActivePurchases(client, BillingClient.ProductType.SUBS)
        val inApp = fetchActivePurchases(client, BillingClient.ProductType.INAPP)

        if (subs == null && inApp == null) return null

        val allPurchases = (subs.orEmpty() + inApp.orEmpty())
        val mappedRecords = allPurchases.map(::mapToDomainPurchaseRecord)

        if (autoAcknowledgePurchases) {
            mappedRecords.forEach { record ->
                if (!record.isAcknowledged && record.purchaseState == BillingPurchaseState.PURCHASED) {
                    processAutoAcknowledgment(record)
                }
            }
        }

        mappedRecords
    }

    private suspend fun fetchActivePurchases(client: BillingClient, productType: String): List<Purchase>? =
        withTimeoutOrNull(connectionTimeoutMs) {
            suspendCancellableCoroutine { continuation ->
                val params = QueryPurchasesParams.newBuilder().setProductType(productType).build()
                client.queryPurchasesAsync(params) { billingResult, purchases ->
                    if (continuation.isActive) {
                        if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                            continuation.resume(purchases)
                        } else {
                            logger.w(TAG, "queryPurchasesAsync failed for $productType: ${billingResult.responseCode}")
                            continuation.resume(null)
                        }
                    }
                }
            }
        }

    override fun launchBillingFlow(
        activity: Activity,
        productDetails: BillingProductDetails,
        offerToken: String?
    ): BillingOpResult {
        val client = billingClient
        if (client == null || !client.isReady) {
            logger.w(TAG, "BillingClient not ready when launchBillingFlow invoked")
            return BillingOpResult.ServiceUnavailable
        }

        val rawDetails = productDetails.rawProductDetails as? ProductDetails
            ?: run {
                logger.e(TAG, "ProductDetails raw reference missing for ${productDetails.productId}")
                return BillingOpResult.Error(BillingClient.BillingResponseCode.ITEM_UNAVAILABLE, "Invalid product details")
            }

        val productDetailsParamsBuilder = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(rawDetails)

        if (productDetails.type == BillingProductType.SUBS) {
            val token = offerToken ?: productDetails.offerToken ?: rawDetails.subscriptionOfferDetails?.firstOrNull()?.offerToken
            if (token != null) {
                productDetailsParamsBuilder.setOfferToken(token)
            } else {
                logger.e(TAG, "No subscription offer token found for ${productDetails.productId}")
                return BillingOpResult.Error(
                    BillingClient.BillingResponseCode.DEVELOPER_ERROR,
                    "Subscription requires an offer token"
                )
            }
        }

        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productDetailsParamsBuilder.build()))
            .build()

        val billingResult = client.launchBillingFlow(activity, flowParams)
        return when (billingResult.responseCode) {
            BillingClient.BillingResponseCode.OK -> BillingOpResult.Success
            BillingClient.BillingResponseCode.USER_CANCELED -> BillingOpResult.UserCanceled
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> BillingOpResult.ItemAlreadyOwned
            BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE -> BillingOpResult.ServiceUnavailable
            else -> BillingOpResult.Error(billingResult.responseCode, billingResult.debugMessage)
        }
    }

    override fun onPurchasesUpdated(billingResult: BillingResult, purchases: MutableList<Purchase>?) {
        if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            val mapped = purchases.map(::mapToDomainPurchaseRecord)
            scope.launch {
                if (autoAcknowledgePurchases) {
                    mapped.forEach { record ->
                        if (!record.isAcknowledged && record.purchaseState == BillingPurchaseState.PURCHASED) {
                            processAutoAcknowledgment(record)
                        }
                    }
                }
                _purchasesUpdatedFlow.emit(mapped)
            }
        } else if (billingResult.responseCode == BillingClient.BillingResponseCode.USER_CANCELED) {
            logger.d(TAG, "User canceled the purchase flow.")
        } else {
            logger.w(TAG, "onPurchasesUpdated error code: ${billingResult.responseCode} - ${billingResult.debugMessage}")
        }
    }

    private suspend fun processAutoAcknowledgment(record: BillingPurchaseRecord) {
        try {
            if (verificationHandler.verifyPurchase(record)) {
                acknowledgePurchase(record.purchaseToken)
            } else {
                logger.w(TAG, "Purchase verification rejected for token: ${record.purchaseToken}")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(TAG, "Error during auto-acknowledgment", e)
        }
    }

    override suspend fun acknowledgePurchase(purchaseToken: String): Boolean = withTimeoutOrNull(connectionTimeoutMs) {
        val client = connectedClient() ?: return@withTimeoutOrNull false
        suspendCancellableCoroutine { continuation ->
            val params = AcknowledgePurchaseParams.newBuilder()
                .setPurchaseToken(purchaseToken)
                .build()

            client.acknowledgePurchase(params) { billingResult ->
                if (continuation.isActive) {
                    val isOk = billingResult.responseCode == BillingClient.BillingResponseCode.OK
                    if (isOk) {
                        logger.d(TAG, "Purchase acknowledged successfully.")
                    } else {
                        logger.w(TAG, "Failed to acknowledge purchase: ${billingResult.responseCode}")
                    }
                    continuation.resume(isOk)
                }
            }
        }
    } ?: false

    private fun mapToDomainPurchaseRecord(purchase: Purchase): BillingPurchaseRecord {
        val state = when (purchase.purchaseState) {
            Purchase.PurchaseState.PURCHASED -> BillingPurchaseState.PURCHASED
            Purchase.PurchaseState.PENDING -> BillingPurchaseState.PENDING
            else -> BillingPurchaseState.UNSPECIFIED
        }

        return BillingPurchaseRecord(
            orderId = purchase.orderId,
            productIds = purchase.products,
            purchaseToken = purchase.purchaseToken,
            purchaseTime = purchase.purchaseTime,
            purchaseState = state,
            isAcknowledged = purchase.isAcknowledged,
            isAutoRenewing = purchase.isAutoRenewing,
            isSuspended = purchase.isSuspended,
            originalJson = purchase.originalJson,
            signature = purchase.signature
        )
    }

    override fun destroy() {
        _connectionState.value = BillingConnectionState.CLOSED
        try {
            billingClient?.endConnection()
        } catch (e: Exception) {
            logger.w(TAG, "Error ending billing client connection", e)
        }
        scope.cancel()
    }
}
