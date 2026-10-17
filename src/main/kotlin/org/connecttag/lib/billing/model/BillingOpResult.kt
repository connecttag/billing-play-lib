package org.connecttag.lib.billing.model

/**
 * Result status for billing operations (e.g. launching purchase flow).
 */
sealed interface BillingOpResult {
    /** The operation completed or was launched successfully. */
    data object Success : BillingOpResult

    /** The user explicitly dismissed or canceled the purchase sheet. */
    data object UserCanceled : BillingOpResult

    /** The billing service is unavailable or disconnected. */
    data object ServiceUnavailable : BillingOpResult

    /** The item is already owned by the user. */
    data object ItemAlreadyOwned : BillingOpResult

    /** A billing error occurred with response code and message. */
    data class Error(val responseCode: Int, val debugMessage: String) : BillingOpResult
}
