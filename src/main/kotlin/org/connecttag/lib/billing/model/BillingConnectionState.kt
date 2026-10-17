package org.connecttag.lib.billing.model

/**
 * State of the connection to the billing service provider.
 */
enum class BillingConnectionState {
    /** The client is not connected to the billing service. */
    DISCONNECTED,

    /** Connection is actively being established. */
    CONNECTING,

    /** Successfully connected and ready to process requests. */
    CONNECTED,

    /** The billing client has been shut down or closed. */
    CLOSED
}
