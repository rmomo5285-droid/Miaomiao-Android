package com.v2ray.ang.ui.account

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.dto.entities.SubscriptionCache
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SubscriptionUpdater
import com.v2ray.ang.xboard.EndpointManifestRepository
import com.v2ray.ang.xboard.EndpointMigrationNoticeStore
import com.v2ray.ang.xboard.MiaomiaoEndpointUpdater
import com.v2ray.ang.xboard.XBoardAccountState
import com.v2ray.ang.xboard.XBoardApiClient
import com.v2ray.ang.xboard.XBoardCheckoutRequest
import com.v2ray.ang.xboard.XBoardPaymentMethod
import com.v2ray.ang.xboard.XBoardPaymentState
import com.v2ray.ang.xboard.XBoardPlan
import com.v2ray.ang.xboard.XBoardInviteInfo
import com.v2ray.ang.xboard.XBoardRepository
import com.v2ray.ang.xboard.XBoardSaveOrderRequest
import com.v2ray.ang.xboard.XBoardApiException
import com.v2ray.ang.xboard.XBoardOrderPolicy
import com.v2ray.ang.xboard.XBoardOrderRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.URI

private object MiaomiaoAccountSession {
    val repository: XBoardRepository by lazy {
        XBoardRepository(
            service = XBoardApiClient(
                endpointProvider = { MiaomiaoEndpointUpdater.current().apiEndpoints },
            ),
        )
    }
    val operationMutex = Mutex()

    @Volatile
    var lastRefreshElapsedMillis: Long = 0L

    fun restoreState(): XBoardAccountState {
        val current = repository.state.value
        return if (current.authenticated) current else repository.restoreLocalSession()
    }
}

data class MiaomiaoAccountUiState(
    val account: XBoardAccountState = XBoardAccountState(),
    val isPurchasing: Boolean = false,
    val paymentMethods: List<XBoardPaymentMethod> = emptyList(),
    val pendingTradeNo: String? = null,
    val pendingPlanName: String? = null,
    val awaitingPaymentTradeNo: String? = null,
    val paymentUrl: String? = null,
    val registrationUrl: String? = null,
    val migrationNotice: String? = null,
    val inviteInfo: XBoardInviteInfo? = null,
    val isLoadingInvite: Boolean = false,
    val message: String? = null,
)

class MiaomiaoAccountViewModel(application: Application) : AndroidViewModel(application) {
    private val endpointRepository = EndpointManifestRepository()
    private val accountRepository = MiaomiaoAccountSession.repository
    private val mutableUiState = MutableStateFlow(
        MiaomiaoAccountUiState(
            account = MiaomiaoAccountSession.restoreState(),
            registrationUrl = endpointRepository.current().registrationUrl,
        ),
    )
    val uiState: StateFlow<MiaomiaoAccountUiState> = mutableUiState.asStateFlow()
    private val pendingPaymentStore: PendingPaymentStore = MmkvPendingPaymentStore
    private val accountOperationMutex = MiaomiaoAccountSession.operationMutex
    private var paymentPollingJob: Job? = null
    private var paymentPollingTradeNo: String? = null

    init {
        var wasAuthenticated = mutableUiState.value.account.authenticated
        viewModelScope.launch {
            accountRepository.state.collect { account ->
                var accountToPublish = account
                if (wasAuthenticated && !account.authenticated) {
                    accountToPublish = accountOperationMutex.withLock {
                        accountRepository.state.value.also { currentAccount ->
                            if (!currentAccount.authenticated) {
                                detachManagedSubscription()
                                clearPendingPaymentState()
                            }
                        }
                    }
                }
                mutableUiState.update { it.copy(account = accountToPublish) }
                wasAuthenticated = accountToPublish.authenticated
            }
        }
        viewModelScope.launch {
            if (mutableUiState.value.account.authenticated) {
                restorePersistedTradeNo()
            } else {
                accountOperationMutex.withLock {
                    if (!accountRepository.state.value.authenticated) {
                        detachManagedSubscription()
                        clearPendingPaymentState()
                    }
                }
            }
            mutableUiState.update {
                it.copy(migrationNotice = EndpointMigrationNoticeStore.pendingNotice())
            }
            if (mutableUiState.value.account.authenticated) {
                accountOperationMutex.withLock {
                    val snapshot = accountRepository.state.value
                    if (AccountRefreshPolicy.shouldRefresh(
                            force = false,
                            authenticated = snapshot.authenticated,
                            hasSnapshot = snapshot.subscription != null || snapshot.plans.isNotEmpty(),
                            lastRefreshElapsedMillis = MiaomiaoAccountSession.lastRefreshElapsedMillis,
                            nowElapsedMillis = SystemClock.elapsedRealtime(),
                        )
                    ) {
                        refreshAccount(forceSubscriptionRefresh = false)
                    }
                }
            }
        }
    }

    fun login(email: String, password: String) {
        if (email.isBlank() || password.isBlank()) {
            setMessage("请输入邮箱和密码")
            return
        }
        viewModelScope.launch {
            accountOperationMutex.withLock {
                accountRepository.login(email, password)
                    .onSuccess { refreshAccount(forceSubscriptionRefresh = true) }
                    .onFailure { setMessage(it.message ?: "登录失败") }
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            accountOperationMutex.withLock {
                refreshAccount(forceSubscriptionRefresh = true)
            }
        }
    }

    fun restoreSessionAndRefresh(force: Boolean = false) {
        viewModelScope.launch {
            accountOperationMutex.withLock {
                val current = accountRepository.state.value
                val restored = if (current.authenticated) {
                    current
                } else {
                    accountRepository.restoreLocalSession()
                }
                if (!mutableUiState.value.account.authenticated) {
                    mutableUiState.update { it.copy(account = restored) }
                }
                val snapshot = mutableUiState.value.account
                if (AccountRefreshPolicy.shouldRefresh(
                        force = force,
                        authenticated = restored.authenticated,
                        hasSnapshot = snapshot.subscription != null || snapshot.plans.isNotEmpty(),
                        lastRefreshElapsedMillis = MiaomiaoAccountSession.lastRefreshElapsedMillis,
                        nowElapsedMillis = SystemClock.elapsedRealtime(),
                    )
                ) {
                    refreshAccount(forceSubscriptionRefresh = false)
                }
            }
        }
    }

    fun generateInviteCode() {
        viewModelScope.launch {
            accountOperationMutex.withLock {
                mutableUiState.update { it.copy(isLoadingInvite = true, message = null) }
                accountRepository.generateInviteCode()
                    .onSuccess { info ->
                        mutableUiState.update {
                            it.copy(
                                inviteInfo = info,
                                isLoadingInvite = false,
                                message = "邀请码已生成",
                            )
                        }
                    }
                    .onFailure { error ->
                        mutableUiState.update {
                            it.copy(
                                isLoadingInvite = false,
                                message = if ((error as? XBoardApiException)?.outcomeUnknown == true) {
                                    "邀请码生成结果暂时无法确认，请先刷新，不要重复提交"
                                } else {
                                    error.message ?: "邀请码生成失败"
                                },
                            )
                        }
                    }
            }
        }
    }

    fun inviteCopied() {
        setMessage("邀请链接已复制")
    }

    fun logout() {
        viewModelScope.launch {
            accountOperationMutex.withLock {
                mutableUiState.update { it.copy(isPurchasing = true, message = null) }
                clearPendingPaymentState()
                detachManagedSubscription()
                accountRepository.logout()
                mutableUiState.update {
                    it.copy(
                        isPurchasing = false,
                        paymentMethods = emptyList(),
                        inviteInfo = null,
                        isLoadingInvite = false,
                        pendingPlanName = null,
                        message = "已退出登录，本机缓存节点仍可继续使用",
                    )
                }
            }
        }
    }

    fun beginPurchase(plan: XBoardPlan, cycle: String) {
        viewModelScope.launch {
            accountOperationMutex.withLock {
                mutableUiState.update {
                    it.copy(
                        isPurchasing = true,
                        paymentMethods = emptyList(),
                        pendingPlanName = plan.name,
                        message = null,
                    )
                }
                val ordersResult = accountRepository.fetchOrders()
                if (ordersResult.isFailure) {
                    mutableUiState.update { state ->
                        state.copy(
                            isPurchasing = false,
                            message = "暂时无法确认是否存在未完成订单，请稍后重试",
                        )
                    }
                    return@withLock
                }
                val existingOrder = reconcileOrders(ordersResult.getOrThrow())
                if (existingOrder != null) {
                    resumeExistingOrder(existingOrder)
                    return@withLock
                }

                val orderResult = accountRepository.createOrder(
                    XBoardSaveOrderRequest(planId = plan.id, period = cycle),
                )
                if (orderResult.isFailure) {
                    val error = orderResult.exceptionOrNull()
                    if ((error as? XBoardApiException)?.outcomeUnknown == true) {
                        reconcileAfterAmbiguousMutation()
                    }
                    mutableUiState.update { state ->
                        state.copy(
                            isPurchasing = false,
                            paymentMethods = if ((error as? XBoardApiException)?.outcomeUnknown == true) {
                                emptyList()
                            } else {
                                state.paymentMethods
                            },
                            message = if ((error as? XBoardApiException)?.outcomeUnknown == true) {
                                "订单提交结果暂时无法确认，请先查询未完成订单，不要重复购买"
                            } else {
                                error?.message ?: "创建订单失败"
                            },
                        )
                    }
                    return@withLock
                }
                val order = orderResult.getOrThrow()
                persistPendingPayment(
                    PendingPayment(order.tradeNo, PendingPaymentPhase.ORDER_CREATED),
                )
                val methods = accountRepository.getPaymentMethods(order.tradeNo).getOrElse {
                    mutableUiState.update { state ->
                        state.copy(isPurchasing = false, message = it.message ?: "无法获取支付方式")
                    }
                    return@withLock
                }
                if (methods.isEmpty()) {
                    mutableUiState.update {
                        it.copy(isPurchasing = false, message = "当前没有可用的支付方式")
                    }
                    return@withLock
                }
                mutableUiState.update {
                    it.copy(
                        isPurchasing = false,
                        paymentMethods = methods,
                        pendingTradeNo = order.tradeNo,
                    )
                }
            }
        }
    }

    fun checkout(methodId: Int) {
        val tradeNo = mutableUiState.value.pendingTradeNo
            ?: mutableUiState.value.awaitingPaymentTradeNo
            ?: pendingPaymentStore.read()?.tradeNo
            ?: return
        viewModelScope.launch {
            accountOperationMutex.withLock {
                if (mutableUiState.value.paymentMethods.none { it.id == methodId }) {
                    return@withLock
                }
                val pendingPayment = pendingPaymentStore.read()
                if (pendingPayment == null || pendingPayment.tradeNo != tradeNo) return@withLock
                if (!PendingPaymentPolicy.canSubmitCheckout(pendingPayment)) {
                    mutableUiState.update {
                        it.copy(
                            isPurchasing = false,
                            paymentMethods = emptyList(),
                            message = "支付请求已经提交，正在查询订单状态",
                        )
                    }
                    startPaymentPolling(tradeNo)
                    return@withLock
                }
                persistPendingPayment(
                    pendingPayment.copy(phase = PendingPaymentPhase.CHECKOUT_SUBMITTED),
                )
                mutableUiState.update { it.copy(isPurchasing = true, message = null) }
                val result = accountRepository.checkout(
                    XBoardCheckoutRequest(tradeNo = tradeNo, methodId = methodId),
                ).getOrElse { error ->
                    mutableUiState.update { state ->
                        state.copy(
                            isPurchasing = false,
                            paymentMethods = if ((error as? XBoardApiException)?.outcomeUnknown == true) {
                                emptyList()
                            } else {
                                state.paymentMethods
                            },
                            message = if ((error as? XBoardApiException)?.outcomeUnknown == true) {
                                "支付提交结果暂时无法确认，请查询订单状态，不会自动重复提交"
                            } else {
                                error.message ?: "发起支付失败"
                            },
                        )
                    }
                    if ((error as? XBoardApiException)?.outcomeUnknown == true) {
                        startPaymentPolling(tradeNo)
                    } else {
                        persistPendingPayment(
                            PendingPayment(tradeNo, PendingPaymentPhase.ORDER_CREATED),
                        )
                    }
                    return@withLock
                }
                if (result.completed) {
                    clearPendingPaymentState()
                    mutableUiState.update {
                        it.copy(isPurchasing = false, message = "订单已完成，正在更新套餐和节点")
                    }
                    refreshAccount(
                        forceSubscriptionRefresh = true,
                        reconcilePaymentOrders = false,
                    )
                    return@withLock
                }
                val paymentUrl = result.paymentUrl?.takeIf(::isSafeHttpsUrl)
                mutableUiState.update {
                    it.copy(
                        isPurchasing = false,
                        paymentMethods = emptyList(),
                        pendingTradeNo = tradeNo,
                        pendingPlanName = null,
                        awaitingPaymentTradeNo = tradeNo,
                        paymentUrl = paymentUrl,
                        message = if (paymentUrl == null) "支付入口无效，请稍后重试" else null,
                    )
                }
                startPaymentPolling(tradeNo)
            }
        }
    }

    fun dismissPaymentMethods() {
        mutableUiState.update {
            it.copy(
                paymentMethods = emptyList(),
                pendingPlanName = null,
            )
        }
    }

    fun consumePaymentUrl() {
        mutableUiState.update { it.copy(paymentUrl = null) }
    }

    fun checkPendingPayment() {
        val tradeNo = mutableUiState.value.awaitingPaymentTradeNo
            ?: mutableUiState.value.pendingTradeNo
            ?: pendingPaymentStore.read()?.tradeNo
            ?: return
        viewModelScope.launch {
            checkPendingPaymentCore(tradeNo, showErrors = true)
        }
    }

    fun consumeMigrationNotice() {
        EndpointMigrationNoticeStore.dismissPending()
        mutableUiState.update { it.copy(migrationNotice = null) }
    }

    fun consumeMessage() {
        mutableUiState.update { it.copy(message = null) }
    }

    private suspend fun refreshAccount(
        forceSubscriptionRefresh: Boolean,
        reconcilePaymentOrders: Boolean = true,
    ) {
        accountRepository.refreshAccount()
            .onSuccess { state ->
                MiaomiaoAccountSession.lastRefreshElapsedMillis = SystemClock.elapsedRealtime()
                accountRepository.fetchInviteInfo().onSuccess { info ->
                    mutableUiState.update { it.copy(inviteInfo = info, isLoadingInvite = false) }
                }.onFailure {
                    mutableUiState.update { it.copy(isLoadingInvite = false) }
                }
                if (reconcilePaymentOrders) {
                    val recoveredOrder = reconcileOrders(state.orders)
                    if (recoveredOrder != null &&
                        XBoardOrderPolicy.paymentState(recoveredOrder.status) ==
                        XBoardPaymentState.PENDING &&
                        pendingPaymentStore.read()?.let {
                            PendingPaymentPolicy.canSubmitCheckout(it)
                        } == true
                    ) {
                        resumeExistingOrder(recoveredOrder)
                    }
                }
                syncManagedSubscription(
                    subscriptionUrl = state.subscription?.subscribeUrl,
                    forceRefresh = forceSubscriptionRefresh,
                )
            }
            .onFailure { setMessage(it.message ?: "账号信息更新失败") }
    }

    private suspend fun syncManagedSubscription(subscriptionUrl: String?, forceRefresh: Boolean) {
        val url = subscriptionUrl?.takeIf(::isSafeHttpsUrl) ?: return
        val updateResult = withContext(Dispatchers.IO) {
            val existing = MmkvManager.decodeSubscription(AppConfig.MIAOMIAO_MANAGED_SUBSCRIPTION_ID)
            val shouldReschedule = ManagedSubscriptionSchedulePolicy.shouldReschedule(
                existing = existing,
                url = url,
                intervalMinutes = MANAGED_UPDATE_INTERVAL_MINUTES,
            )
            val item = existing
                ?: SubscriptionItem(remarks = MANAGED_SUBSCRIPTION_REMARKS)
            if (item.url != url) item.lastUpdated = -1L
            item.remarks = MANAGED_SUBSCRIPTION_REMARKS
            item.url = url
            item.enabled = true
            item.autoUpdate = true
            item.updateInterval = MANAGED_UPDATE_INTERVAL_MINUTES
            item.allowInsecureUrl = false
            MmkvManager.encodeSubscription(AppConfig.MIAOMIAO_MANAGED_SUBSCRIPTION_ID, item)
            val result = if (forceRefresh) {
                AngConfigManager.updateConfigViaSub(
                    SubscriptionCache(AppConfig.MIAOMIAO_MANAGED_SUBSCRIPTION_ID, item),
                )
            } else {
                null
            }
            // Successful manual updates reschedule themselves after persisting lastUpdated.
            if ((!forceRefresh && shouldReschedule) ||
                (forceRefresh && result?.successCount != 1 && shouldReschedule)
            ) {
                SubscriptionUpdater.syncOne(subId = AppConfig.MIAOMIAO_MANAGED_SUBSCRIPTION_ID)
            }
            result
        }
        if (forceRefresh && updateResult?.successCount != 1) {
            setMessage("订阅更新失败，已保留本机现有节点")
        }
    }

    private fun restorePersistedTradeNo() {
        val tradeNo = pendingPaymentStore.read()?.tradeNo ?: return
        mutableUiState.update {
            it.copy(
                pendingTradeNo = tradeNo,
                awaitingPaymentTradeNo = tradeNo,
            )
        }
    }

    private fun persistPendingPayment(payment: PendingPayment) {
        pendingPaymentStore.write(payment)
        mutableUiState.update {
            it.copy(
                pendingTradeNo = payment.tradeNo,
                awaitingPaymentTradeNo = payment.tradeNo,
            )
        }
    }

    private fun reconcileOrders(orders: List<XBoardOrderRecord>): XBoardOrderRecord? {
        val persistedPayment = pendingPaymentStore.read()
        val persistedOrder = persistedPayment?.let { persisted ->
            orders.firstOrNull { it.tradeNo == persisted.tradeNo }
        }
        val persistedOrderState = XBoardOrderPolicy.paymentState(persistedOrder?.status)
        val recoverable = when {
            persistedPayment != null && persistedOrder == null ->
                XBoardOrderRecord(tradeNo = persistedPayment.tradeNo, status = 0)
            persistedOrder != null &&
                persistedOrderState in setOf(
                    XBoardPaymentState.PENDING,
                    XBoardPaymentState.PROCESSING,
                    XBoardPaymentState.UNKNOWN,
                ) -> persistedOrder.copy(status = persistedOrder.status ?: 0)
            else -> XBoardOrderPolicy.findRecoverable(orders)
        }
        if (recoverable == null) {
            if (persistedPayment != null ||
                mutableUiState.value.pendingTradeNo != null ||
                mutableUiState.value.awaitingPaymentTradeNo != null
            ) {
                clearPendingPaymentState()
            }
            return null
        }

        val paymentState = XBoardOrderPolicy.paymentState(recoverable.status)
        val phase = PendingPaymentPolicy.phaseAfterOrderRefresh(
            orderStatus = recoverable.status,
            paymentId = recoverable.paymentId,
            persistedPhase = persistedPayment
                ?.takeIf { it.tradeNo == recoverable.tradeNo }
                ?.phase,
        )
        persistPendingPayment(PendingPayment(recoverable.tradeNo, phase))
        when {
            paymentState == XBoardPaymentState.PROCESSING ||
                phase == PendingPaymentPhase.CHECKOUT_SUBMITTED ->
                startPaymentPolling(recoverable.tradeNo)
            paymentState == XBoardPaymentState.PENDING -> Unit
            else -> clearPendingPaymentState()
        }
        return recoverable
    }

    private suspend fun resumeExistingOrder(order: XBoardOrderRecord) {
        when (XBoardOrderPolicy.paymentState(order.status)) {
            XBoardPaymentState.PENDING -> {
                val pendingPayment = pendingPaymentStore.read()
                if (pendingPayment != null &&
                    !PendingPaymentPolicy.canSubmitCheckout(pendingPayment)
                ) {
                    mutableUiState.update {
                        it.copy(
                            isPurchasing = false,
                            paymentMethods = emptyList(),
                            message = "支付请求已经提交，将继续查询订单状态",
                        )
                    }
                    startPaymentPolling(order.tradeNo)
                    return
                }
                val methodsResult = accountRepository.getPaymentMethods(order.tradeNo)
                if (methodsResult.isFailure) {
                    mutableUiState.update {
                        it.copy(
                            isPurchasing = false,
                            message = methodsResult.exceptionOrNull()?.message
                                ?: "已有待支付订单，但暂时无法获取支付方式",
                        )
                    }
                    return
                }
                val methods = methodsResult.getOrThrow()
                mutableUiState.update {
                    it.copy(
                        isPurchasing = false,
                        paymentMethods = methods,
                        pendingTradeNo = order.tradeNo,
                        pendingPlanName = mutableUiState.value.account.plans
                            .firstOrNull { it.id == order.planId }
                            ?.name,
                        awaitingPaymentTradeNo = order.tradeNo,
                        message = if (methods.isEmpty()) "当前没有可用的支付方式" else "已恢复未完成订单",
                    )
                }
            }
            XBoardPaymentState.PROCESSING -> {
                mutableUiState.update {
                    it.copy(
                        isPurchasing = false,
                        pendingTradeNo = order.tradeNo,
                        awaitingPaymentTradeNo = order.tradeNo,
                        message = "订单正在处理中，将在限定时间内自动查询",
                    )
                }
                startPaymentPolling(order.tradeNo)
            }
            else -> {
                clearPendingPaymentState()
                mutableUiState.update { it.copy(isPurchasing = false) }
            }
        }
    }

    private suspend fun reconcileAfterAmbiguousMutation() {
        val orders = accountRepository.fetchOrders().getOrNull() ?: return
        reconcileOrders(orders)
    }

    private suspend fun checkPendingPaymentCore(
        tradeNo: String,
        showErrors: Boolean,
        pollingDeadlineMillis: Long? = null,
    ): Boolean = accountOperationMutex.withLock {
        checkPendingPaymentLocked(tradeNo, showErrors, pollingDeadlineMillis)
    }

    private suspend fun checkPendingPaymentLocked(
        tradeNo: String,
        showErrors: Boolean,
        pollingDeadlineMillis: Long?,
    ): Boolean {
        val storedPayment = pendingPaymentStore.read() ?: return true
        if (storedPayment.tradeNo != tradeNo) return true
        val timeoutMillis = pollingDeadlineMillis
            ?.let { it - SystemClock.elapsedRealtime() }
            ?.coerceAtMost(PaymentPollingPolicy.MAX_STATUS_REQUEST_MILLIS)
            ?: PaymentPollingPolicy.MAX_STATUS_REQUEST_MILLIS
        if (timeoutMillis <= 0L) return false
        val statusResult = accountRepository.getOrderStatus(tradeNo, timeoutMillis)
        if (statusResult.isFailure) {
            if (showErrors) {
                setMessage(statusResult.exceptionOrNull()?.message ?: "订单状态查询失败")
            }
            return false
        }

        val status = statusResult.getOrThrow()
        val paymentState = if (status.paid) {
            XBoardPaymentState.COMPLETED
        } else {
            XBoardOrderPolicy.paymentState(status.statusCode)
        }
        return when (paymentState) {
            XBoardPaymentState.COMPLETED -> {
                clearPendingPaymentState(cancelPolling = false)
                mutableUiState.update { it.copy(message = "支付成功，正在更新套餐和节点") }
                refreshAccount(
                    forceSubscriptionRefresh = true,
                    reconcilePaymentOrders = false,
                )
                true
            }
            XBoardPaymentState.CANCELED -> {
                clearPendingPaymentState(cancelPolling = false)
                mutableUiState.update { it.copy(message = "订单已取消") }
                refreshAccount(
                    forceSubscriptionRefresh = false,
                    reconcilePaymentOrders = false,
                )
                true
            }
            XBoardPaymentState.PROCESSING -> {
                persistPendingPayment(
                    PendingPayment(tradeNo, PendingPaymentPhase.CHECKOUT_SUBMITTED),
                )
                if (showErrors) {
                    setMessage("订单正在处理中，将继续自动查询")
                    startPaymentPolling(tradeNo)
                }
                false
            }
            XBoardPaymentState.PENDING,
            XBoardPaymentState.UNKNOWN -> {
                persistPendingPayment(storedPayment)
                if (showErrors) setMessage("订单尚未完成，请完成支付后再次查询")
                false
            }
        }
    }

    private fun startPaymentPolling(tradeNo: String) {
        if (paymentPollingTradeNo == tradeNo && paymentPollingJob?.isActive == true) return
        stopPaymentPolling()
        paymentPollingTradeNo = tradeNo
        paymentPollingJob = viewModelScope.launch {
            val startedAtMillis = SystemClock.elapsedRealtime()
            val deadlineMillis = startedAtMillis + PaymentPollingPolicy.MAX_DURATION_MILLIS
            for (attempt in 0 until PaymentPollingPolicy.MAX_ATTEMPTS) {
                val remainingMillis = PaymentPollingPolicy.remainingMillis(
                    startedAtMillis = startedAtMillis,
                    nowMillis = SystemClock.elapsedRealtime(),
                )
                if (remainingMillis <= 0L) break
                delay(minOf(PaymentPollingPolicy.INTERVAL_MILLIS, remainingMillis))
                if (PaymentPollingPolicy.remainingMillis(
                        startedAtMillis = startedAtMillis,
                        nowMillis = SystemClock.elapsedRealtime(),
                    ) <= 0L
                ) {
                    break
                }
                val storedPayment = pendingPaymentStore.read()
                if (!mutableUiState.value.account.authenticated ||
                    storedPayment == null ||
                    storedPayment.tradeNo != tradeNo ||
                    storedPayment.phase != PendingPaymentPhase.CHECKOUT_SUBMITTED
                ) {
                    return@launch
                }
                if (checkPendingPaymentCore(
                        tradeNo = tradeNo,
                        showErrors = false,
                        pollingDeadlineMillis = deadlineMillis,
                    )
                ) {
                    return@launch
                }
            }
            val storedPayment = pendingPaymentStore.read()
            if (storedPayment != null &&
                storedPayment.tradeNo == tradeNo &&
                storedPayment.phase == PendingPaymentPhase.CHECKOUT_SUBMITTED
            ) {
                setMessage("自动查询已暂停，可点击手动查询订单状态")
            }
        }
    }

    private fun stopPaymentPolling() {
        paymentPollingTradeNo = null
        paymentPollingJob?.cancel()
        paymentPollingJob = null
    }

    private fun clearPendingPaymentState(cancelPolling: Boolean = true) {
        if (cancelPolling) stopPaymentPolling() else paymentPollingTradeNo = null
        pendingPaymentStore.clear()
        mutableUiState.update {
            it.copy(
                paymentMethods = emptyList(),
                pendingTradeNo = null,
                pendingPlanName = null,
                awaitingPaymentTradeNo = null,
                paymentUrl = null,
            )
        }
    }

    private suspend fun detachManagedSubscription() {
        withContext(Dispatchers.IO) {
            SubscriptionUpdater.cancelOne(subId = AppConfig.MIAOMIAO_MANAGED_SUBSCRIPTION_ID)
            val item = MmkvManager.decodeSubscription(AppConfig.MIAOMIAO_MANAGED_SUBSCRIPTION_ID)
                ?: return@withContext
            item.url = ""
            item.enabled = false
            item.autoUpdate = false
            item.updateInterval = MANAGED_UPDATE_INTERVAL_MINUTES
            MmkvManager.encodeSubscription(AppConfig.MIAOMIAO_MANAGED_SUBSCRIPTION_ID, item)
        }
    }

    private fun setMessage(message: String) {
        mutableUiState.update { it.copy(message = message.take(300)) }
    }

    private fun isSafeHttpsUrl(rawUrl: String): Boolean {
        val uri = runCatching { URI(rawUrl) }.getOrNull() ?: return false
        return uri.scheme.equals("https", ignoreCase = true) &&
            !uri.host.isNullOrBlank() &&
            uri.userInfo == null
    }

    private companion object {
        const val MANAGED_SUBSCRIPTION_REMARKS = "喵喵订阅"
        const val MANAGED_UPDATE_INTERVAL_MINUTES = 48L * 60L
    }
}
