package org.connecttag.lib.billing

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.connecttag.lib.billing.fake.FakeBillingEngine
import org.connecttag.lib.billing.model.BillingConnectionState
import org.connecttag.lib.billing.model.BillingOpResult
import org.connecttag.lib.billing.model.BillingProductConfig
import org.connecttag.lib.billing.model.BillingProductType
import org.connecttag.lib.billing.model.BillingPurchaseState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FakeBillingEngineTest {

    private lateinit var billingEngine: FakeBillingEngine

    @Before
    fun setUp() {
        billingEngine = FakeBillingEngine(initialConnected = true)
    }

    @Test
    fun initialState_isConnected() {
        assertEquals(BillingConnectionState.CONNECTED, billingEngine.connectionState.value)
    }

    @Test
    fun queryProductDetails_returnsSimulatedProducts() = runTest {
        val configs = listOf(
            BillingProductConfig("premium_monthly", BillingProductType.SUBS),
            BillingProductConfig("premium_lifetime", BillingProductType.INAPP)
        )
        val result = billingEngine.queryProductDetails(configs)

        assertEquals(2, result.size)
        assertEquals("premium_monthly", result[0].productId)
        assertEquals(BillingProductType.SUBS, result[0].type)
        assertNotNull(result[0].offerToken)

        assertEquals("premium_lifetime", result[1].productId)
        assertEquals(BillingProductType.INAPP, result[1].type)
    }

    @Test
    fun simulatePurchase_updatesActivePurchases() = runTest {
        billingEngine.simulatePurchase("item_pro")

        val purchases = billingEngine.queryPurchases()
        assertEquals(1, purchases.size)
        assertEquals(listOf("item_pro"), purchases[0].productIds)
        assertEquals(BillingPurchaseState.PURCHASED, purchases[0].purchaseState)
        assertTrue(purchases[0].isAcknowledged)
    }

    @Test
    fun simulateRefund_clearsActivePurchases() = runTest {
        billingEngine.simulatePurchase("item_pro")
        assertEquals(1, billingEngine.queryPurchases().size)

        billingEngine.simulateRefund("item_pro")
        assertEquals(0, billingEngine.queryPurchases().size)
    }

    @Test
    fun acknowledgePurchase_marksPurchaseAcknowledged() = runTest {
        billingEngine.autoAcknowledge = false
        billingEngine.simulatePurchase("item_pro")

        val token = billingEngine.queryPurchases().first().purchaseToken
        val acknowledged = billingEngine.acknowledgePurchase(token)

        assertTrue(acknowledged)
        assertTrue(billingEngine.queryPurchases().first().isAcknowledged)
    }

    @Test
    fun launchBillingFlow_simulatesSuccessAndAddsPurchase() = runTest {
        billingEngine.nextOpResult = BillingOpResult.Success

        val configs = listOf(BillingProductConfig("lifetime", BillingProductType.INAPP))
        val details = billingEngine.queryProductDetails(configs).first()

        val result = billingEngine.launchBillingFlow(
            activity = mockActivity(),
            productDetails = details
        )

        assertEquals(BillingOpResult.Success, result)
        val purchases = billingEngine.queryPurchases()
        assertEquals(1, purchases.size)
        assertEquals("lifetime", purchases[0].productIds.first())
    }

    private fun mockActivity(): android.app.Activity {
        val field = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = field.get(null) as sun.misc.Unsafe
        return unsafe.allocateInstance(android.app.Activity::class.java) as android.app.Activity
    }
}
