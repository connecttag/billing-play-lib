package org.connecttag.lib.billing.api

/**
 * Pluggable logger interface to avoid hard dependencies on specific logging libraries.
 */
interface BillingLogger {
    fun d(tag: String, message: String)
    fun w(tag: String, message: String, throwable: Throwable? = null)
    fun e(tag: String, message: String, throwable: Throwable? = null)

    companion object {
        val DEFAULT: BillingLogger = object : BillingLogger {
            override fun d(tag: String, message: String) {
                android.util.Log.d(tag, message)
            }

            override fun w(tag: String, message: String, throwable: Throwable?) {
                android.util.Log.w(tag, message, throwable)
            }

            override fun e(tag: String, message: String, throwable: Throwable?) {
                android.util.Log.e(tag, message, throwable)
            }
        }
    }
}
