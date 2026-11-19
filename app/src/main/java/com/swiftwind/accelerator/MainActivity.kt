package com.swiftwind.accelerator

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.core.view.WindowCompat
import com.swiftwind.accelerator.ui.SwiftWindApp
import com.swiftwind.accelerator.ui.theme.SwiftWindTheme
import com.swiftwind.accelerator.viewmodel.AppViewModel
import com.swiftwind.accelerator.vpn.TunnelController
import com.swiftwind.accelerator.vpn.VpnState

class MainActivity : ComponentActivity() {

    private val viewModel: AppViewModel by viewModels()
    private lateinit var vpnPermissionLauncher: ActivityResultLauncher<Intent>
    private lateinit var notificationPermissionLauncher: ActivityResultLauncher<String>
    private var afterNotificationPermission: (() -> Unit)? = null
    private var pendingProbe = false
    private var pendingConnectAfterNotification = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashLogger.install(this)
        pendingProbe = savedInstanceState?.getBoolean(KEY_PENDING_PROBE) ?: false
        pendingConnectAfterNotification =
            savedInstanceState?.getBoolean(KEY_PENDING_CONNECT_AFTER_NOTIFICATION) ?: false
        WindowCompat.setDecorFitsSystemWindows(window, false)

        vpnPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == RESULT_OK) {
                if (pendingProbe) {
                    pendingProbe = false
                    viewModel.probeNodes()
                } else {
                    viewModel.connect()
                }
            } else {
                pendingProbe = false
                Toast.makeText(this, "未授权系统 VPN，无法建立连接", Toast.LENGTH_SHORT).show()
            }
        }

        notificationPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            // 即使拒绝通知权限，也不阻断连接；只是前台服务的通知不会展示。
            if (!granted) {
                Toast.makeText(
                    this,
                    "通知权限未开启：后台保活通知不会显示，可在系统设置中开启",
                    Toast.LENGTH_LONG
                ).show()
            }
            val pendingConnect = pendingConnectAfterNotification
            pendingConnectAfterNotification = false
            val action = afterNotificationPermission
            afterNotificationPermission = null
            if (action != null) {
                action()
            } else if (pendingConnect) {
                // Activity 在权限弹窗期间被重建时，lambda 无法跨进程保存，
                // 只恢复一个布尔标记，回到这里再继续申请 VPN 权限。
                requestVpnAndConnect()
            }
        }

        setContent {
            SwiftWindTheme {
                val ui by viewModel.ui.collectAsState()
                SwiftWindApp(
                    ui = ui,
                    viewModel = viewModel,
                    onToggleConnection = ::toggleConnection,
                    onProbeNodes = ::probeNodes,
                    onOpenVpnSettings = ::openVpnSettings,
                    onOpenPrivacy = ::openPrivacy
                )
            }
        }

        // 权限弹窗期间 Activity 被重建、旧 Result 回调丢失时，若权限其实已经授予，
        // 在首帧后补一次连接，避免点过“连接”却停在未连接状态。
        if (pendingConnectAfterNotification && hasNotificationPermission()) {
            pendingConnectAfterNotification = false
            window.decorView.post {
                if (!isFinishing && !isDestroyed) requestVpnAndConnect()
            }
        }
        if (pendingProbe && VpnService.prepare(this) == null) {
            pendingProbe = false
            window.decorView.post {
                if (!isFinishing && !isDestroyed) viewModel.probeNodes()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(KEY_PENDING_PROBE, pendingProbe)
        outState.putBoolean(
            KEY_PENDING_CONNECT_AFTER_NOTIFICATION,
            pendingConnectAfterNotification
        )
        super.onSaveInstanceState(outState)
    }

    private fun toggleConnection() {
        when (TunnelController.state.value) {
            is VpnState.Connected -> viewModel.disconnect()
            is VpnState.Connecting,
            is VpnState.Disconnecting -> Unit
            else -> {
                ensureNotificationPermission { requestVpnAndConnect() }
            }
        }
    }

    private fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun ensureNotificationPermission(then: () -> Unit) {
        if (hasNotificationPermission()) {
            then()
            return
        }
        afterNotificationPermission = then
        pendingConnectAfterNotification = true
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun requestVpnAndConnect() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            pendingProbe = false
            vpnPermissionLauncher.launch(intent)
        } else {
            pendingProbe = false
            viewModel.connect()
        }
    }

    private fun probeNodes() {
        if (TunnelController.state.value is VpnState.Connected ||
            TunnelController.state.value is VpnState.Connecting ||
            TunnelController.state.value is VpnState.Disconnecting
        ) {
            Toast.makeText(this, "请先断开当前连接再体检", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = VpnService.prepare(this)
        if (intent != null) {
            pendingProbe = true
            vpnPermissionLauncher.launch(intent)
        } else {
            viewModel.probeNodes()
        }
    }

    private fun openVpnSettings() {
        runCatching {
            startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
        }.onFailure {
            Toast.makeText(this, "无法打开系统 VPN 设置", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openPrivacy() {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_URL)))
        }.onFailure {
            Toast.makeText(this, "无法打开隐私说明", Toast.LENGTH_SHORT).show()
        }
    }

    private companion object {
        const val KEY_PENDING_PROBE = "pending_probe"
        const val KEY_PENDING_CONNECT_AFTER_NOTIFICATION = "pending_connect_after_notification"
        const val PRIVACY_URL = "https://aka.doubaocdn.com/s/9EX5YNoJts"
    }
}
