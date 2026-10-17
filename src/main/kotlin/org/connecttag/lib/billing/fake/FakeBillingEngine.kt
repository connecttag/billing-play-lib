package org.connecttag.lib.billing.fake

import android.app.Activity
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import org.connecttag.lib.billing.api.BillingEngine
import org.connecttag.lib.billing.model.BillingConnectionState
import org.connecttag.lib.billing.model.BillingOpResult
import org.connecttag.lib.billing.model.BillingProductConfig
import org.connecttag.lib.billing.model.BillingProductDetails
import org.connecttag.lib.billing.model.BillingProductType
import org.connecttag.lib.billing.model.BillingPurchaseRecord
import org.connecttag.lib.billing.model.BillingPurchaseState

/**
 * Mock implementation of [BillingEngine] for UI previews, unit tests, and development mock modes.
 */
class FakeBillingEngine(
    initialConnected: Boolean = true
) : BillingEngine {

    private val _connectionState = MutableStateFlow(
        if (initialConnected) BillingConnectionState.CONNECTED else BillingConnectionState.DISCONNECTED
    )
    override val connectionState: StateFlow<BillingConnectionState> = _connectionState.asStateFlow()

    private val _purchasesUpdatedFlow = MutableSharedFlow<List<BillingPurchaseRecord>>(extraBufferCapacity = 16)
    override val purchasesUpdatedFlow: SharedFlow<List<BillingPurchaseRecord>> = _purchasesUpdatedFlow.asSharedFlow()

    private val _productDetailsList = MutableStateFlow<List<BillingProductDetails>>(emptyList())
    override val productDetailsList: StateFlow<List<BillingProductDetails>> = _productDetailsList.asStateFlow()

    private val activePurchases = mutableListOf<BillingPurchaseRecord>()

    var nextOpResult: BillingOpResult = BillingOpResult.Success
    var autoAcknowledge: Boolean = true

    override suspend fun startConnection(): Boolean {
        _connectionState.value = BillingConnectionState.CONNECTED
        return true
    }

    override suspend fun queryProductDetails(products: List<BillingProductConfig>): List<BillingProductDetails> {
        val current = _productDetailsList.value
        val simulated = products.map { config ->
            current.find { it.productId == config.productId } ?: BillingProductDetails(
                productId = config.productId,
                type = config.type,
                title = "Mock ${config.productId}",
                description = "Mock description for ${config.productId}",
                formattedPrice = "$4.99",
                priceAmountMicros = 4_990_000L,
                priceCurrencyCode = "USD",
                offerToken = if (config.type == BillingProductType.SUBS) "mock_offer_token" else null
            )
        }
        _productDetailsList.value = simulated
        return simulated
    }

    override suspend fun queryPurchases(): List<BillingPurchaseRecord> {
        return synchronized(activePurchases) { activePurchases.toList() }
    }

    override fun launchBillingFlow(
        activity: Activity,
        productDetails: BillingProductDetails,
        offerToken: String?
    ): BillingOpResult {
        val result = nextOpResult
        if (result == BillingOpResult.Success) {
            val record = createMockPurchaseRecord(productDetails.productId)
            synchronized(activePurchases) {
                activePurchases.removeAll { it.productIds.contains(productDetails.productId) }
                activePurchases.add(record)
            }
            _purchasesUpdatedFlow.tryEmit(listOf(record))
        }
        return result
    }

    override suspend fun acknowledgePurchase(purchaseToken: String): Boolean {
        synchronized(activePurchases) {
            val index = activePurchases.indexOfFirst { it.purchaseToken == purchaseToken }
            if (index != -1) {
                activePurchases[index] = activePurchases[index].copy(isAcknowledged = true)
                return true
            }
        }
        return false
    }

    fun simulatePurchase(productId: String, type: BillingProductType = BillingProductType.INAPP) {
        val record = createMockPurchaseRecord(productId)
        synchronized(activePurchases) {
            activePurchases.removeAll { it.productIds.contains(productId) }
            activePurchases.add(record)
        }
        _purchasesUpdatedFlow.tryEmit(listOf(record))
    }

    fun simulateRefund(productId: String) {
        synchronized(activePurchases) {
            activePurchases.removeAll { it.productIds.contains(productId) }
        }
        _purchasesUpdatedFlow.tryEmit(emptyList())
    }

    fun setAvailableProducts(products: List<BillingProductDetails>) {
        _productDetailsList.value = products
    }

    private fun createMockPurchaseRecord(productId: String): BillingPurchaseRecord {
        return BillingPurchaseRecord(
            orderId = "GPA.MOCK-${System.currentTimeMillis()}",
            productIds = listOf(productId),
            purchaseToken = "mock_token_${System.currentTimeMillis()}",
            purchaseTime = System.currentTimeMillis(),
            purchaseState = BillingPurchaseState.PURCHASED,
            isAcknowledged = autoAcknowledge,
            isAutoRenewing = true
        )
    }

    override fun destroy() {
        _connectionState.value = BillingConnectionState.CLOSED
    }
}
