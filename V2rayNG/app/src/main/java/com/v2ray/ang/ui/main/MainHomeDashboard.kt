package com.v2ray.ang.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R
import com.v2ray.ang.dto.entities.ServersCache
import com.v2ray.ang.ui.account.accountTrafficGigabytes
import com.v2ray.ang.ui.account.formatAccountGigabytes
import com.v2ray.ang.xboard.XBoardAccountState
import com.v2ray.ang.xboard.XBoardOperationState
import java.text.DateFormat
import java.util.Date

private val HomeAccent = Brush.linearGradient(
    listOf(Color(0xFF0D9B87), Color(0xFF2E6FE4)),
)

@Composable
fun MainHomeDashboard(
    account: XBoardAccountState,
    isRunning: Boolean,
    selectedServer: ServersCache?,
    onToggleService: () -> Unit,
    onOpenAccount: () -> Unit,
    onOpenNotices: () -> Unit,
    onRefreshAccount: () -> Unit,
    onOpenRoutes: () -> Unit,
    onOpenRouting: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AccountResourceStrip(
            account = account,
            onOpenAccount = onOpenAccount,
            onOpenNotices = onOpenNotices,
            onRefresh = onRefreshAccount,
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(15.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(184.dp)
                    .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(160.dp)
                        .border(9.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.1f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(136.dp)
                            .shadow(18.dp, CircleShape, clip = false)
                            .clip(CircleShape)
                            .background(HomeAccent)
                            .clickable(onClick = onToggleService),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(
                                if (isRunning) R.drawable.ic_stop_24dp else R.drawable.ic_play_24dp,
                            ),
                            contentDescription = stringResource(
                                if (isRunning) R.string.miaomiao_disconnect else R.string.miaomiao_connect,
                            ),
                            modifier = Modifier.size(50.dp),
                            tint = Color.White,
                        )
                    }
                }
            }
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface,
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant,
                ),
            ) {
                Text(
                    text = stringResource(
                        if (isRunning) R.string.miaomiao_connected else R.string.miaomiao_disconnected,
                    ),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 7.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isRunning) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }

        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surface,
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant,
            ),
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(
                            text = stringResource(R.string.miaomiao_proxy_mode),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = stringResource(R.string.miaomiao_proxy_mode_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    GradientAction(
                        text = stringResource(R.string.miaomiao_rule_mode),
                        modifier = Modifier.weight(1f),
                        onClick = onOpenRouting,
                    )
                    OutlinedButton(
                        onClick = onOpenSettings,
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp),
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Text(stringResource(R.string.miaomiao_vpn_mode))
                    }
                }
            }
        }

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenRoutes),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surface,
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant,
            ),
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Surface(
                    modifier = Modifier.size(40.dp),
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            painter = painterResource(R.drawable.ic_routing_24dp),
                            contentDescription = null,
                            modifier = Modifier.size(21.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = selectedServer?.profile?.remarks
                            ?.takeIf(String::isNotBlank)
                            ?: stringResource(R.string.miaomiao_select_node),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = stringResource(R.string.miaomiao_current_route),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = selectedServer.delayLabel(),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Icon(
                    painter = painterResource(R.drawable.ic_expand_more_24dp),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(2.dp))
    }
}

@Composable
private fun AccountResourceStrip(
    account: XBoardAccountState,
    onOpenAccount: () -> Unit,
    onOpenNotices: () -> Unit,
    onRefresh: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        if (!account.authenticated) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenAccount)
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.miaomiao_login_for_usage),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = stringResource(R.string.miaomiao_local_routes_available),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                GradientAction(
                    text = stringResource(R.string.miaomiao_login),
                    onClick = onOpenAccount,
                )
            }
            return@Surface
        }

        val subscription = account.subscription
        val usedGb = accountTrafficGigabytes(subscription?.usedTraffic ?: 0L)
        val totalGb = accountTrafficGigabytes(subscription?.transferEnable ?: 0L)
        val remainingGb = (totalGb - usedGb).coerceAtLeast(0.0)
        val progress = if (totalGb > 0.0) (usedGb / totalGb).coerceIn(0.0, 1.0).toFloat() else 0f

        Column(
            modifier = Modifier.padding(15.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
            ) {
                Metric(
                    label = stringResource(R.string.miaomiao_remaining_traffic),
                    value = formatAccountGigabytes(remainingGb),
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.primary,
                )
                Metric(
                    label = stringResource(R.string.miaomiao_valid_until),
                    value = formatHomeExpiry(
                        subscription?.expiredAt,
                        stringResource(R.string.miaomiao_long_term),
                    ),
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.secondary,
                )
                if (account.operation == XBoardOperationState.LOADING) {
                    Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                } else {
                    IconButton(onClick = onRefresh) {
                        Icon(
                            painter = painterResource(R.drawable.ic_cloud_download_24dp),
                            contentDescription = stringResource(R.string.miaomiao_refresh_account),
                        )
                    }
                }
                IconButton(onClick = onOpenNotices) {
                    Icon(
                        painter = painterResource(R.drawable.ic_description_24dp),
                        contentDescription = stringResource(R.string.miaomiao_notices),
                    )
                }
            }
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(7.dp)
                    .clip(MaterialTheme.shapes.extraSmall),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            )
            Text(
                text = stringResource(
                    R.string.miaomiao_traffic_usage,
                    formatAccountGigabytes(usedGb),
                    formatAccountGigabytes(totalGb),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Metric(
    label: String,
    value: String,
    modifier: Modifier,
    color: Color,
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun GradientAction(
    text: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .height(46.dp)
            .clip(MaterialTheme.shapes.small)
            .background(HomeAccent)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = Color.White,
        )
    }
}

@Composable
private fun ServersCache?.delayLabel(): String {
    if (this == null) return "--"
    if (testDelayMillis > 0L) return "$testDelayMillis ms"
    return testDelayString.takeIf(String::isNotBlank)
        ?: stringResource(R.string.miaomiao_not_tested)
}

private fun formatHomeExpiry(epochSeconds: Long?, fallback: String): String {
    if (epochSeconds == null || epochSeconds <= 0L) return fallback
    return runCatching {
        DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(epochSeconds * 1000L))
    }.getOrDefault(fallback)
}
