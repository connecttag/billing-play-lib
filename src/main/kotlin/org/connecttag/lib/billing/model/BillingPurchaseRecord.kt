package org.connecttag.lib.billing.model

/**
 * Domain representation of a validated store purchase record.
 */
data class BillingPurchaseRecord(
    val orderId: String?,
    val productIds: List<String>,
    val purchaseToken: String,
    val purchaseTime: Long,
    val purchaseState: BillingPurchaseState,
    val isAcknowledged: Boolean,
    val isAutoRenewing: Boolean = false,
    val isSuspended: Boolean = false,
    val originalJson: String? = null,
    val signature: String? = null
)
