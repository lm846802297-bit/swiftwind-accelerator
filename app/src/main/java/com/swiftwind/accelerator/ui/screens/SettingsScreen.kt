package com.swiftwind.accelerator.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.swiftwind.accelerator.BuildConfig
import com.swiftwind.accelerator.R
import com.swiftwind.accelerator.ui.GlassCard
import com.swiftwind.accelerator.ui.theme.Pink
import com.swiftwind.accelerator.ui.theme.Rose
import com.swiftwind.accelerator.ui.theme.Stroke
import com.swiftwind.accelerator.ui.theme.TextPrimary
import com.swiftwind.accelerator.ui.theme.TextSecondary
import com.swiftwind.accelerator.viewmodel.UiState

@Composable
fun SettingsScreen(
    ui: UiState,
    notificationsEnabled: Boolean,
    onOpenVpnSettings: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onOpenPrivacy: () -> Unit
) {
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp)
    ) {
        Spacer(Modifier.height(12.dp))
        Text("设置", style = MaterialTheme.typography.headlineMedium, color = TextPrimary)
        Text(
            "连接偏好与应用信息",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            modifier = Modifier.padding(top = 4.dp)
        )

        Spacer(Modifier.height(20.dp))
        SectionLabel("连接")
        Spacer(Modifier.height(8.dp))
        GlassCard {
            Column {
                SettingRow(
                    icon = Icons.Rounded.Public,
                    title = "当前节点",
                    value = ui.selectedNode?.name ?: "未选择"
                )
                RowDivider()
                SettingRow(
                    icon = Icons.Rounded.Shield,
                    title = "VPN 协议",
                    value = "WireGuard"
                )
                RowDivider()
                SettingRow(
                    icon = Icons.Rounded.Notifications,
                    title = "通知权限",
                    subtitle = "前台保活通知",
                    value = if (notificationsEnabled) "已开启" else "未开启",
                    onClick = onOpenNotificationSettings,
                    showArrow = true
                )
                RowDivider()
                SettingRow(
                    icon = Icons.AutoMirrored.Rounded.OpenInNew,
                    title = "系统 VPN 设置",
                    subtitle = "可设置始终开启 VPN",
                    onClick = onOpenVpnSettings,
                    showArrow = true
                )
            }
        }

        Spacer(Modifier.height(22.dp))
        SectionLabel("关于")
        Spacer(Modifier.height(8.dp))
        GlassCard {
            Column {
                SettingRow(
                    icon = Icons.Rounded.Info,
                    title = "版本",
                    value = BuildConfig.VERSION_NAME
                )
                RowDivider()
                SettingRow(
                    icon = Icons.Rounded.Code,
                    title = "开源组件",
                    subtitle = "WireGuard for Android · Apache-2.0",
                    value = ""
                )
                RowDivider()
                SettingRow(
                    icon = Icons.Rounded.Lock,
                    title = "隐私",
                    subtitle = "不收集任何用户数据；WARP 节点由 Cloudflare 提供",
                    onClick = onOpenPrivacy,
                    showArrow = true
                )
            }
        }

        Spacer(Modifier.height(24.dp))
        Text(
            "${stringResource(R.string.app_name)} · HUAYI",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary.copy(alpha = 0.55f),
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )
        Text(
            "仅供个人学习与测试使用",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary.copy(alpha = 0.45f),
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 2.dp)
        )
        Spacer(Modifier.height(18.dp))
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = Rose.copy(alpha = 0.80f)
    )
}

@Composable
private fun SettingRow(
    icon: ImageVector,
    title: String,
    value: String = "",
    subtitle: String = "",
    onClick: (() -> Unit)? = null,
    showArrow: Boolean = false
    ) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(34.dp)
                .background(Pink.copy(alpha = 0.10f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = Pink.copy(alpha = 0.90f), modifier = Modifier.size(18.dp))
        }
        Column(Modifier.weight(1f).padding(horizontal = 11.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = TextPrimary)
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary.copy(alpha = 0.7f)
                )
            }
        }
        if (value.isNotBlank()) {
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                maxLines = 1
            )
        }
        if (showArrow) {
            Icon(
                Icons.AutoMirrored.Rounded.OpenInNew,
                contentDescription = null,
                tint = TextSecondary,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
private fun RowDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Stroke)
    )
}
