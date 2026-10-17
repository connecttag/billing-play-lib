package org.connecttag.lib.billing.api

import org.connecttag.lib.billing.model.BillingPurchaseRecord

/**
 * Strategy interface to verify incoming purchases before acknowledging them.
 *
 * Implementations may verify against an external backend server or perform
 * local signature checks.
 */
fun interface PurchaseVerificationHandler {
    /**
     * Determines whether a given purchase record is authentic, valid, and should be acknowledged.
     *
     * @param purchase The purchase record received from Google Play.
     * @return True if the purchase should be acknowledged and accepted; false otherwise.
     */
    suspend fun verifyPurchase(purchase: BillingPurchaseRecord): Boolean

    companion object {
        /**
         * Default handler that accepts all purchases with PURCHASED state.
         */
        val ACCEPT_ALL: PurchaseVerificationHandler = PurchaseVerificationHandler { purchase ->
            purchase.purchaseState == org.connecttag.lib.billing.model.BillingPurchaseState.PURCHASED
        }
    }
}
