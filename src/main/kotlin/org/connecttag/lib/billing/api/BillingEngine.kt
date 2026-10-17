package org.connecttag.lib.billing.api

import android.app.Activity
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import org.connecttag.lib.billing.model.BillingConnectionState
import org.connecttag.lib.billing.model.BillingOpResult
import org.connecttag.lib.billing.model.BillingProductConfig
import org.connecttag.lib.billing.model.BillingProductDetails
import org.connecttag.lib.billing.model.BillingPurchaseRecord

/**
 * Universal abstraction for in-app billing engines.
 *
 * Provides reactive observable states, asynchronous query methods, and flow launchers.
 */
interface BillingEngine {
    /** Real-time state of the connection to the billing service provider. */
    val connectionState: StateFlow<BillingConnectionState>

    /** Emits lists of newly received or updated purchases. */
    val purchasesUpdatedFlow: SharedFlow<List<BillingPurchaseRecord>>

    /** Currently cached product details list queried from the store. */
    val productDetailsList: StateFlow<List<BillingProductDetails>>

    /**
     * Connects to the underlying billing service if not already connected.
     * @return True if connected successfully within the timeout period.
     */
    suspend fun startConnection(): Boolean

    /**
     * Queries product metadata (pricing, currency, title) for the specified configurations.
     */
    suspend fun queryProductDetails(products: List<BillingProductConfig>): List<BillingProductDetails>

    /**
     * Queries the user's active purchases from the store across in-app and subscriptions.
     * @return A list of active purchases, or null if the query failed.
     */
    suspend fun queryPurchases(): List<BillingPurchaseRecord>?

    /**
     * Launches the billing checkout flow in the given Activity.
     */
    fun launchBillingFlow(
        activity: Activity,
        productDetails: BillingProductDetails,
        offerToken: String? = null
    ): BillingOpResult

    /**
     * Explicitly acknowledges a purchase token with the store.
     */
    suspend fun acknowledgePurchase(purchaseToken: String): Boolean

    /**
     * Tears down the client and releases held resources.
     */
    fun destroy()
}
