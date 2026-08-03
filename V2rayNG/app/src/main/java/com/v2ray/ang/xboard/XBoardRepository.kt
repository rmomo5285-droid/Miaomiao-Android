package com.v2ray.ang.xboard

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

class XBoardRepository(
    private val service: XBoardService,
    private val tokenStore: XBoardTokenStore = AndroidKeystoreTokenStore(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutableState = MutableStateFlow(XBoardAccountState())
    val state: StateFlow<XBoardAccountState> = mutableState.asStateFlow()

    /** Restores local session state without contacting the network. */
    fun restoreLocalSession(): XBoardAccountState {
        val restored = XBoardAccountState(authenticated = tokenStore.readToken() != null)
        mutableState.value = restored
        return restored
    }

    suspend fun login(email: String, password: String): Result<Unit> = runOperation {
        val token = service.login(email.trim(), password)
        tokenStore.writeToken(token)
        mutableState.value = XBoardAccountState(
            authenticated = true,
            operation = XBoardOperationState.READY,
        )
    }

    fun logout() {
        tokenStore.clear()
        mutableState.value = XBoardAccountState()
    }

    suspend fun refreshAccount(): Result<XBoardAccountState> = runOperationWithResult {
        val token = requireToken()
        val subscription = service.getSubscribe(token)
        val plans = service.fetchPlans(token)
        val notices = service.fetchNotices(token)
        val orders = service.fetchOrders(token)
        XBoardAccountState(
            authenticated = true,
            operation = XBoardOperationState.READY,
            subscription = subscription,
            plans = plans,
            notices = notices,
            orders = orders,
        ).also { mutableState.value = it }
    }

    suspend fun fetchOrders(): Result<List<XBoardOrderRecord>> = runOperationWithResult {
        service.fetchOrders(requireToken()).also { orders ->
            mutableState.value = mutableState.value.copy(orders = orders)
        }
    }

    suspend fun fetchNotices(): Result<List<XBoardNotice>> = runOperationWithResult {
        service.fetchNotices(requireToken()).also { notices ->
            mutableState.value = mutableState.value.copy(notices = notices)
        }
    }

    suspend fun fetchInviteInfo(): Result<XBoardInviteInfo> = runOperationWithResult {
        service.fetchInviteInfo(requireToken())
    }

    suspend fun generateInviteCode(): Result<XBoardInviteInfo> = runOperationWithResult {
        service.generateInviteCode(requireToken())
    }

    suspend fun createOrder(order: XBoardSaveOrderRequest): Result<XBoardOrder> =
        runOperationWithResult { service.saveOrder(requireToken(), order) }

    suspend fun getPaymentMethods(tradeNo: String): Result<List<XBoardPaymentMethod>> =
        runOperationWithResult { service.getPaymentMethods(requireToken(), tradeNo) }

    suspend fun checkout(checkout: XBoardCheckoutRequest): Result<XBoardCheckoutResult> =
        runOperationWithResult { service.checkout(requireToken(), checkout) }

    suspend fun getOrderStatus(
        tradeNo: String,
        timeoutMillis: Long = DEFAULT_ORDER_STATUS_TIMEOUT_MILLIS,
    ): Result<XBoardOrderStatus> = runOperationWithResult {
        service.getOrderStatus(requireToken(), tradeNo, timeoutMillis)
    }

    private fun requireToken(): String {
        return tokenStore.readToken()
            ?: throw XBoardApiException("Authentication is required")
    }

    private suspend fun runOperation(block: () -> Unit): Result<Unit> = withContext(ioDispatcher) {
        setLoading()
        try {
            block()
            Result.success(Unit)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            setError(error)
            Result.failure(error)
        }
    }

    private suspend fun <T> runOperationWithResult(block: () -> T): Result<T> =
        withContext(ioDispatcher) {
            setLoading()
            try {
                val value = block()
                if (mutableState.value.operation == XBoardOperationState.LOADING) {
                    mutableState.value = mutableState.value.copy(
                        operation = XBoardOperationState.READY,
                        errorMessage = null,
                    )
                }
                Result.success(value)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                setError(error)
                Result.failure(error)
            }
        }

    private fun setLoading() {
        mutableState.value = mutableState.value.copy(
            operation = XBoardOperationState.LOADING,
            errorMessage = null,
        )
    }

    private fun setError(error: Exception) {
        if ((error as? XBoardApiException)?.statusCode == 401) {
            tokenStore.clear()
        }
        mutableState.value = mutableState.value.copy(
            authenticated = tokenStore.readToken() != null,
            operation = XBoardOperationState.ERROR,
            errorMessage = error.message ?: "XBoard operation failed",
        )
    }
}

internal const val DEFAULT_ORDER_STATUS_TIMEOUT_MILLIS = 20_000L
