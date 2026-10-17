package org.connecttag.lib.billing.model

/**
 * Supported product types for in-app billing.
 */
enum class BillingProductType {
    /** One-time in-app purchases (e.g. lifetime licenses, consumable/non-consumable items). */
    INAPP,

    /** Recurring subscription products. */
    SUBS
}
