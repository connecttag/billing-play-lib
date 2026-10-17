package org.connecttag.lib.billing.model

/**
 * Configuration descriptor for a product to query from the billing service.
 */
data class BillingProductConfig(
    val productId: String,
    val type: BillingProductType
)
