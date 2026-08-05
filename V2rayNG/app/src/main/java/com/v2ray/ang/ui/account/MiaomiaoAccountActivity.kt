package com.v2ray.ang.ui.account

import android.os.Bundle
import android.text.Html
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.v2ray.ang.R
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.ui.compose.AppTopBar
import com.v2ray.ang.util.Utils
import com.v2ray.ang.xboard.XBoardAccountState
import com.v2ray.ang.xboard.XBoardNotice
import com.v2ray.ang.xboard.XBoardOperationState
import com.v2ray.ang.xboard.XBoardPaymentMethod
import com.v2ray.ang.xboard.XBoardPlan
import java.text.DateFormat
import java.util.Date
import java.util.Locale

class MiaomiaoAccountActivity : BaseComponentActivity() {
    private val viewModel: MiaomiaoAccountViewModel by viewModels()
    private var awaitingExternalPayment = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        if (awaitingExternalPayment) {
            awaitingExternalPayment = false
            viewModel.checkPendingPayment()
        }
    }

    @Composable
    override fun ScreenContent() {
        val uiState by viewModel.uiState.collectAsStateWithLifecycle()

        LaunchedEffect(uiState.paymentUrl) {
            val url = uiState.paymentUrl ?: return@LaunchedEffect
            awaitingExternalPayment = true
            Utils.openUri(this@MiaomiaoAccountActivity, url)
            viewModel.consumePaymentUrl()
        }

        MiaomiaoAccountScreen(
            uiState = uiState,
            onBackClick = ::finish,
            onLogin = viewModel::login,
            onRegister = {
                uiState.registrationUrl?.let { Utils.openUri(this, it) }
            },
            onRefresh = viewModel::refresh,
            onLogout = viewModel::logout,
            onCheckPayment = viewModel::checkPendingPayment,
            onPurchase = viewModel::beginPurchase,
            onCheckout = viewModel::checkout,
            onDismissPaymentMethods = viewModel::dismissPaymentMethods,
            onDismissMigrationNotice = viewModel::consumeMigrationNotice,
            onMessageShown = viewModel::consumeMessage,
        )
    }
}

@Composable
private fun MiaomiaoAccountScreen(
    uiState: MiaomiaoAccountUiState,
    onBackClick: () -> Unit,
    onLogin: (String, String) -> Unit,
    onRegister: () -> Unit,
    onRefresh: () -> Unit,
    onLogout: () -> Unit,
    onCheckPayment: () -> Unit,
    onPurchase: (XBoardPlan, String) -> Unit,
    onCheckout: (Int) -> Unit,
    onDismissPaymentMethods: () -> Unit,
    onDismissMigrationNotice: () -> Unit,
    onMessageShown: () -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val isLoading = uiState.account.operation == XBoardOperationState.LOADING ||
        uiState.isPurchasing

    LaunchedEffect(uiState.message) {
        val message = uiState.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        onMessageShown()
    }

    Scaffold(
        contentWindowInsets = ScaffoldDefaults.contentWindowInsets,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            AppTopBar(
                title = stringResource(R.string.miaomiao_account_title),
                onBackClick = onBackClick,
                isLoading = isLoading,
                actions = {
                    if (uiState.account.authenticated) {
                        IconButton(onClick = onRefresh, enabled = !isLoading) {
                            Icon(
                                painter = painterResource(R.drawable.ic_cloud_download_24dp),
                                contentDescription = stringResource(R.string.miaomiao_refresh),
                            )
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        if (uiState.account.authenticated) {
            AccountContent(
                uiState = uiState,
                onLogout = onLogout,
                onCheckPayment = onCheckPayment,
                onPurchase = onPurchase,
                modifier = Modifier.padding(innerPadding),
            )
        } else {
            LoginContent(
                registrationAvailable = uiState.registrationUrl != null,
                isLoading = isLoading,
                onLogin = onLogin,
                onRegister = onRegister,
                modifier = Modifier.padding(innerPadding),
            )
        }
    }

    if (uiState.paymentMethods.isNotEmpty()) {
        PaymentMethodDialog(
            planName = uiState.pendingPlanName.orEmpty(),
            methods = uiState.paymentMethods,
            isLoading = uiState.isPurchasing,
            onSelect = onCheckout,
            onDismiss = onDismissPaymentMethods,
        )
    }

    uiState.migrationNotice?.let { notice ->
        AlertDialog(
            onDismissRequest = onDismissMigrationNotice,
            title = { Text(stringResource(R.string.miaomiao_domain_updated)) },
            text = { Text(notice) },
            confirmButton = {
                TextButton(onClick = onDismissMigrationNotice) {
                    Text(stringResource(android.R.string.ok))
                }
            },
        )
    }
}

@Composable
private fun LoginContent(
    registrationAvailable: Boolean,
    isLoading: Boolean,
    onLogin: (String, String) -> Unit,
    onRegister: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val submit = {
        val submittedPassword = password
        password = ""
        onLogin(email.trim(), submittedPassword)
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .imePadding(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = stringResource(R.string.miaomiao_login_title),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.miaomiao_login_subtitle),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.miaomiao_email)) },
                singleLine = true,
                enabled = !isLoading,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Email,
                    imeAction = ImeAction.Next,
                ),
            )
        }
        item {
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.miaomiao_password)) },
                singleLine = true,
                enabled = !isLoading,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { submit() }),
            )
        }
        item {
            Button(
                onClick = submit,
                enabled = !isLoading && email.isNotBlank() && password.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text(stringResource(R.string.miaomiao_login))
                }
            }
        }
        item {
            OutlinedButton(
                onClick = onRegister,
                enabled = registrationAvailable && !isLoading,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
            ) {
                Text(stringResource(R.string.miaomiao_register))
            }
        }
        item {
            Text(
                text = stringResource(R.string.miaomiao_login_cache_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AccountContent(
    uiState: MiaomiaoAccountUiState,
    onLogout: () -> Unit,
    onCheckPayment: () -> Unit,
    onPurchase: (XBoardPlan, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            SubscriptionOverview(
                account = uiState.account,
                paymentPending = uiState.pendingTradeNo != null ||
                    uiState.awaitingPaymentTradeNo != null,
                onCheckPayment = onCheckPayment,
            )
        }
        item {
            SectionHeader(
                title = stringResource(R.string.miaomiao_plans),
                subtitle = stringResource(R.string.miaomiao_plans_subtitle),
            )
        }
        if (uiState.account.plans.isEmpty()) {
            item { EmptyText(stringResource(R.string.miaomiao_plans_empty)) }
        } else {
            items(uiState.account.plans, key = XBoardPlan::id) { plan ->
                PlanCard(
                    plan = plan,
                    enabled = !uiState.isPurchasing,
                    onPurchase = { cycle -> onPurchase(plan, cycle) },
                )
            }
        }
        item {
            SectionHeader(
                title = stringResource(R.string.miaomiao_notices),
                subtitle = stringResource(R.string.miaomiao_notices_subtitle),
            )
        }
        if (uiState.account.notices.isEmpty()) {
            item { EmptyText(stringResource(R.string.miaomiao_notices_empty)) }
        } else {
            items(uiState.account.notices, key = XBoardNotice::id) { notice ->
                NoticeItem(notice)
            }
        }
        item {
            TextButton(
                onClick = onLogout,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(R.string.miaomiao_logout),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun SubscriptionOverview(
    account: XBoardAccountState,
    paymentPending: Boolean,
    onCheckPayment: () -> Unit,
) {
    val subscription = account.subscription
    val transferLimit = subscription?.transferEnable ?: 0L
    val used = subscription?.usedTraffic ?: 0L
    val progress = if (transferLimit > 0L) {
        (used.toDouble() / transferLimit.toDouble()).coerceIn(0.0, 1.0).toFloat()
    } else {
        0f
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.miaomiao_service_active),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = if (subscription?.planId != null) {
                        stringResource(R.string.miaomiao_plan_id, subscription.planId)
                    } else {
                        stringResource(R.string.miaomiao_no_plan)
                    },
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            if (transferLimit > 0L) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp),
                    trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.14f),
                )
                Text(
                    text = stringResource(
                        R.string.miaomiao_traffic_usage,
                        formatAccountTrafficBytes(used),
                        formatAccountTrafficBytes(transferLimit),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Text(
                    text = stringResource(R.string.miaomiao_traffic_unavailable),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Text(
                text = stringResource(
                    R.string.miaomiao_expires_at,
                    formatExpiry(subscription?.expiredAt),
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (paymentPending) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = stringResource(R.string.miaomiao_payment_pending),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                    TextButton(onClick = onCheckPayment) {
                        Text(stringResource(R.string.miaomiao_check_payment))
                    }
                }
            }
        }
    }
}

@Composable
private fun PlanCard(
    plan: XBoardPlan,
    enabled: Boolean,
    onPurchase: (String) -> Unit,
) {
    val cycles = availableCycles(plan)
    var selectedCycleCode by rememberSaveable(plan.id) {
        mutableStateOf(cycles.firstOrNull()?.code)
    }
    val selectedCycle = cycles.firstOrNull { it.code == selectedCycleCode }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = plan.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = stringResource(
                            R.string.miaomiao_plan_traffic,
                            formatPlanTransferGigabytes(plan.transferEnable),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                plan.speedLimit?.takeIf { it > 0 }?.let { speed ->
                    Text(
                        text = stringResource(R.string.miaomiao_speed_limit, speed),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }
            plainText(plan.content)?.let { content ->
                Text(
                    text = content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (cycles.isEmpty()) {
                EmptyText(stringResource(R.string.miaomiao_plan_unavailable))
            } else {
                Column(modifier = Modifier.selectableGroup()) {
                    cycles.forEach { cycle ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = selectedCycleCode == cycle.code,
                                    enabled = enabled,
                                    onClick = { selectedCycleCode = cycle.code },
                                )
                                .padding(vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = selectedCycleCode == cycle.code,
                                onClick = null,
                                enabled = enabled,
                            )
                            Text(
                                text = stringResource(cycle.labelRes),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            Text(
                                text = formatPrice(cycle.price),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
                Button(
                    onClick = { selectedCycle?.let { onPurchase(it.code) } },
                    enabled = enabled && selectedCycle != null,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = selectedCycle?.let {
                            stringResource(R.string.miaomiao_purchase_price, formatPrice(it.price))
                        } ?: stringResource(R.string.miaomiao_purchase),
                    )
                }
            }
        }
    }
}

@Composable
private fun NoticeItem(notice: XBoardNotice) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = notice.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        plainText(notice.content)?.let { content ->
            Text(
                text = content,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun SectionHeader(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EmptyText(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(vertical = 8.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun PaymentMethodDialog(
    planName: String,
    methods: List<XBoardPaymentMethod>,
    isLoading: Boolean,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!isLoading) onDismiss() },
        title = { Text(stringResource(R.string.miaomiao_choose_payment)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (planName.isNotBlank()) {
                    Text(
                        text = planName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                methods.forEach { method ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !isLoading) { onSelect(method.id) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = method.name,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isLoading) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}

private data class PlanCycle(
    val code: String,
    val labelRes: Int,
    val price: Long,
)

private fun availableCycles(plan: XBoardPlan): List<PlanCycle> = buildList {
    plan.monthPrice?.let { add(PlanCycle("month_price", R.string.miaomiao_cycle_month, it)) }
    plan.quarterPrice?.let { add(PlanCycle("quarter_price", R.string.miaomiao_cycle_quarter, it)) }
    plan.halfYearPrice?.let { add(PlanCycle("half_year_price", R.string.miaomiao_cycle_half_year, it)) }
    plan.yearPrice?.let { add(PlanCycle("year_price", R.string.miaomiao_cycle_year, it)) }
    plan.twoYearPrice?.let { add(PlanCycle("two_year_price", R.string.miaomiao_cycle_two_year, it)) }
    plan.threeYearPrice?.let { add(PlanCycle("three_year_price", R.string.miaomiao_cycle_three_year, it)) }
    plan.onetimePrice?.let { add(PlanCycle("onetime_price", R.string.miaomiao_cycle_onetime, it)) }
    plan.resetPrice?.let { add(PlanCycle("reset_price", R.string.miaomiao_cycle_reset, it)) }
}

private fun formatPrice(price: Long): String = String.format(
    Locale.getDefault(),
    "¥%.2f",
    price / 100.0,
)

private fun formatExpiry(epochSeconds: Long?): String {
    if (epochSeconds == null || epochSeconds <= 0L) return "-"
    return runCatching {
        DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(epochSeconds * 1000L))
    }.getOrDefault("-")
}

private fun plainText(html: String?): String? {
    val source = html?.trim().orEmpty()
    if (source.isEmpty()) return null
    return Html.fromHtml(source, Html.FROM_HTML_MODE_LEGACY).toString().trim().ifEmpty { null }
}
