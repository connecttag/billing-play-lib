package org.connecttag.lib.billing.model

/**
 * Domain representation of product details retrieved from the store.
 */
data class BillingProductDetails(
    val productId: String,
    val type: BillingProductType,
    val title: String,
    val description: String,
    val formattedPrice: String?,
    val priceAmountMicros: Long?,
    val priceCurrencyCode: String?,
    val offerToken: String? = null,
    val rawProductDetails: Any? = null
)
