package com.swiftwind.accelerator.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.LocalFlorist
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.swiftwind.accelerator.R
import com.swiftwind.accelerator.data.Node
import com.swiftwind.accelerator.ui.GlassCard
import com.swiftwind.accelerator.ui.theme.Danger
import com.swiftwind.accelerator.ui.theme.Mint
import com.swiftwind.accelerator.ui.theme.Pink
import com.swiftwind.accelerator.ui.theme.PinkDeep
import com.swiftwind.accelerator.ui.theme.Rose
import com.swiftwind.accelerator.ui.theme.Sky
import com.swiftwind.accelerator.ui.theme.Stroke
import com.swiftwind.accelerator.ui.theme.TextPrimary
import com.swiftwind.accelerator.ui.theme.TextSecondary
import com.swiftwind.accelerator.util.formatBytes
import com.swiftwind.accelerator.util.formatDuration
import com.swiftwind.accelerator.viewmodel.UiState
import com.swiftwind.accelerator.vpn.VpnState

@Composable
fun HomeScreen(
    ui: UiState,
    onToggle: () -> Unit,
    onOpenNodes: () -> Unit
) {
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 22.dp)
    ) {
        Spacer(Modifier.height(10.dp))
        Header(state = ui.vpnState, online = ui.networkAvailable, reconnecting = ui.reconnecting)
        Spacer(Modifier.height(18.dp))
        NodeSelector(node = ui.selectedNode, onClick = onOpenNodes)
        Spacer(Modifier.weight(0.9f))
        ConnectOrb(
            state = ui.vpnState,
            enabled = !ui.busy &&
                ui.vpnState !is VpnState.Connecting &&
                ui.vpnState !is VpnState.Disconnecting,
            onClick = onToggle
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = statusTitle(ui.vpnState, ui.networkAvailable, ui.reconnecting),
            style = MaterialTheme.typography.headlineMedium,
            color = when {
                ui.vpnState is VpnState.Connected && !ui.networkAvailable -> Rose
                ui.vpnState is VpnState.Connected && ui.reconnecting -> Sky
                ui.vpnState is VpnState.Connected -> Mint
                ui.vpnState is VpnState.Error -> Danger
                else -> TextPrimary
            },
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = statusSubtitle(ui.vpnState, ui.networkAvailable, ui.reconnecting),
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary
        )
        Spacer(Modifier.weight(1f))
        StatsBar(ui)
        Spacer(Modifier.height(14.dp))
        Text(
            "免费节点由 Cloudflare WARP 提供 · WireGuard 加密",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary.copy(alpha = 0.65f),
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun Header(state: VpnState, online: Boolean, reconnecting: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Brush.linearGradient(listOf(Pink, Rose))),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Rounded.LocalFlorist, contentDescription = null, tint = Color(0xFF2A0B1B), modifier = Modifier.size(25.dp))
        }
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleLarge,
                color = TextPrimary
            )
            Text(
                "HUAYI · STAGE",
                style = MaterialTheme.typography.labelLarge.copy(letterSpacing = 1.2.sp, fontSize = 9.sp),
                color = Rose.copy(alpha = 0.85f)
            )
        }
        StatusPill(state, online, reconnecting)
    }
}

@Composable
private fun StatusPill(state: VpnState, online: Boolean, reconnecting: Boolean) {
    val connected = state is VpnState.Connected
    val offline = connected && !online
    val rebooting = connected && online && reconnecting
    val dotColor = when {
        offline -> Rose
        rebooting -> Sky
        connected -> Mint
        state is VpnState.Connecting || state is VpnState.Disconnecting -> Sky
        state is VpnState.Error -> Danger
        else -> TextSecondary
    }
    Row(
        Modifier
            .clip(CircleShape)
            .background(Color(0xFF24122B))
            .padding(horizontal = 11.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(dotColor))
        Text(
            when {
                offline -> "等待网络"
                rebooting -> "重连中"
                state is VpnState.Connected -> "已连接"
                state is VpnState.Connecting -> "连接中"
                state is VpnState.Disconnecting -> "断开中"
                state is VpnState.Error -> "异常"
                else -> "未连接"
            },
            style = MaterialTheme.typography.labelLarge,
            color = when {
                offline -> Rose
                rebooting -> Sky
                connected -> Mint
                else -> TextSecondary
            },
            modifier = Modifier.padding(start = 6.dp)
        )
    }
}

@Composable
private fun NodeSelector(node: Node?, onClick: () -> Unit) {
    GlassCard(modifier = Modifier.clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("当前节点", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                Spacer(Modifier.height(4.dp))
                Text(
                    node?.name ?: "尚未添加节点",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (node != null) {
                    Text(
                        node.endpoint.ifBlank { "WireGuard" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Text("切换", style = MaterialTheme.typography.labelLarge, color = Pink)
            Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = Pink, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun ConnectOrb(state: VpnState, enabled: Boolean, onClick: () -> Unit) {
    val active = state is VpnState.Connected || state is VpnState.Connecting
    val interactionSource = remember { MutableInteractionSource() }
    val transition = rememberInfiniteTransition(label = "orb-pulse")
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "orb-pulse-value"
    )

    Box(Modifier.size(252.dp), contentAlignment = Alignment.Center) {
        if (active) {
            Canvas(Modifier.fillMaxSize()) {
                val base = size.minDimension / 2f
                repeat(3) { index ->
                    val phase = (pulse + index / 3f) % 1f
                    drawCircle(
                        color = Pink.copy(alpha = (1f - phase) * 0.22f),
                        radius = base * (0.60f + 0.40f * phase),
                        style = Stroke(width = 1.3.dp.toPx())
                    )
                }
            }
        }

        Box(
            Modifier
                .size(196.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        listOf(Pink.copy(alpha = 0.17f), Color.Transparent)
                    )
                )
        )

        Box(
            Modifier
                .size(166.dp)
                .clip(CircleShape)
                .background(
                    if (active) {
                        Brush.linearGradient(listOf(Pink, PinkDeep))
                    } else {
                        Brush.linearGradient(listOf(Color(0xFF2C1832), Color(0xFF160C1D)))
                    }
                )
                .clickable(
                    interactionSource = interactionSource,
                    // 连接瞬间按钮会被 busy 临时禁用，默认涟漪可能停留在按下态，
                    // 让已连接的粉色主按钮看起来像一块黑圆。这里只保留点击行为。
                    indication = null,
                    enabled = enabled,
                    onClick = onClick
                ),
            contentAlignment = Alignment.Center
        ) {
            if (!enabled) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color(0x33000000))
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Rounded.PowerSettingsNew,
                    contentDescription = if (active) "断开连接" else "连接",
                    tint = if (active) Color(0xFF2A0B1B) else TextPrimary,
                    modifier = Modifier.size(52.dp)
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    when (state) {
                        is VpnState.Connected -> "断开"
                        is VpnState.Connecting -> "连接中"
                        is VpnState.Disconnecting -> "断开中"
                        else -> "连接"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = if (active) Color(0xFF2A0B1B) else TextPrimary.copy(alpha = 0.85f)
                )
            }
        }
    }
}

@Composable
private fun StatsBar(ui: UiState) {
    val stats = ui.stats
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatItem("下行", formatBytes(stats.downloadBytes), Modifier.weight(1f))
            Divider()
            StatItem("上行", formatBytes(stats.uploadBytes), Modifier.weight(1f))
            Divider()
            StatItem("时长", formatDuration(stats.durationSeconds), Modifier.weight(1f))
        }
    }
}

@Composable
private fun StatItem(label: String, value: String, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        Spacer(Modifier.height(3.dp))
        Text(value, style = MaterialTheme.typography.titleMedium, color = TextPrimary, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Divider() {
    Box(Modifier.width(1.dp).height(28.dp).background(Stroke))
}

private fun statusTitle(state: VpnState, online: Boolean, reconnecting: Boolean): String = when {
    state is VpnState.Connected && !online -> "网络已断开"
    state is VpnState.Connected && reconnecting -> "正在恢复网络"
    state is VpnState.Connected -> "已连接"
    state is VpnState.Connecting -> "正在连接"
    state is VpnState.Disconnecting -> "正在断开"
    state is VpnState.Error -> "连接失败"
    else -> "未连接"
}

private fun statusSubtitle(state: VpnState, online: Boolean, reconnecting: Boolean): String = when {
    state is VpnState.Connected && !online -> "VPN 隧道保持待命，网络恢复后会自动重连"
    state is VpnState.Connected && reconnecting -> "底层网络已切换，正在重新建立安全隧道…"
    state is VpnState.Connected -> "加密隧道运行中，流量已受保护"
    state is VpnState.Connecting -> "正在建立安全的 WireGuard 隧道…"
    state is VpnState.Disconnecting -> "正在关闭隧道…"
    state is VpnState.Error -> state.message
    else -> "点击上方按钮，开启安全加速"
}
