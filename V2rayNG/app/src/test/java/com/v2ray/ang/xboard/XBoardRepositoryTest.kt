package com.v2ray.ang.xboard

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XBoardRepositoryTest {
    @Test
    fun persistsOnlyReturnedTokenAndRestoresLocalStateWithoutNetwork() = runBlocking {
        val service = FakeXBoardService()
        val tokenStore = MemoryTokenStore()
        val repository = XBoardRepository(service, tokenStore, Dispatchers.Unconfined)

        assertFalse(repository.restoreLocalSession().authenticated)
        val login = repository.login("person@example.com", "ephemeral-password")

        assertTrue(login.isSuccess)
        assertEquals("server-token", tokenStore.value)
        assertEquals("ephemeral-password", service.receivedPassword)
        assertTrue(repository.state.value.authenticated)

        val refreshed = repository.refreshAccount()
        assertTrue(refreshed.isSuccess)
        assertEquals(11, repository.state.value.subscription?.planId)
        assertEquals(XBoardOperationState.READY, repository.state.value.operation)

        repository.logout()
        assertNull(tokenStore.value)
        assertFalse(repository.state.value.authenticated)
    }

    @Test
    fun clearsExpiredTokenAfterUnauthorizedResponse() = runBlocking {
        val service = FakeXBoardService().apply { unauthorized = true }
        val tokenStore = MemoryTokenStore("expired-token")
        val repository = XBoardRepository(service, tokenStore, Dispatchers.Unconfined)

        val refreshed = repository.refreshAccount()

        assertTrue(refreshed.isFailure)
        assertNull(tokenStore.value)
        assertFalse(repository.state.value.authenticated)
    }
}

private class MemoryTokenStore(var value: String? = null) : XBoardTokenStore {
    override fun readToken() = value
    override fun writeToken(token: String) {
        value = token
    }
    override fun clear() {
        value = null
    }
}

private class FakeXBoardService : XBoardService {
    var receivedPassword: String? = null
    var unauthorized = false

    override fun login(email: String, password: String): String {
        receivedPassword = password
        return "server-token"
    }

    override fun getSubscribe(token: String): XBoardSubscription {
        if (unauthorized) throw XBoardApiException("expired", statusCode = 401)
        return XBoardSubscription(planId = 11)
    }
    override fun fetchPlans(token: String) = listOf(XBoardPlan(id = 11, name = "Plan"))
    override fun fetchNotices(token: String) = listOf(XBoardNotice(id = 1, title = "Notice"))
    override fun fetchInviteInfo(token: String) = XBoardInviteInfo()
    override fun generateInviteCode(token: String) = XBoardInviteInfo(
        codes = listOf(XBoardInviteCode("invite")),
    )
    override fun fetchOrders(token: String) = emptyList<XBoardOrderRecord>()
    override fun saveOrder(token: String, order: XBoardSaveOrderRequest) = XBoardOrder("trade")
    override fun getPaymentMethods(token: String, tradeNo: String) =
        listOf(XBoardPaymentMethod(id = 1, name = "Pay"))
    override fun checkout(token: String, checkout: XBoardCheckoutRequest) = XBoardCheckoutResult()
    override fun getOrderStatus(token: String, tradeNo: String, timeoutMillis: Long) =
        XBoardOrderStatus(false)
}
