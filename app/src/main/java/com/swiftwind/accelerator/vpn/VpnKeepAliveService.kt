package com.swiftwind.accelerator.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.swiftwind.accelerator.MainActivity
import com.swiftwind.accelerator.R

/**
 * 连接期间的前台保活服务。
 *
 * 它不负责建立隧道（隧道仍由 GoBackend/VpnService 管理），只把本进程
 * 提升到前台服务优先级，降低 ColorOS 等系统在后台清理时把 VPN 一起
 * 回收的概率。服务停止时不会主动拆隧道，隧道生命周期仍由 TunnelController 控制。
 */
class VpnKeepAliveService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val syncRunnable = object : Runnable {
        override fun run() {
            TunnelController.syncState()
            // 切换节点的短暂 Connecting 阶段不要撤掉保活通知；
            // 只有明确回到 Idle / Error 才说明隧道已经结束。
            if (TunnelController.state.value is VpnState.Idle ||
                TunnelController.state.value is VpnState.Error
            ) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return
            }
            handler.postDelayed(this, 3000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 进程被系统重启后，先确认系统里是否仍有本 App 的隧道。
        // 没有隧道时立即退出，避免留下一个“假连接”通知。
        TunnelController.init(this)
        TunnelController.syncState()
        if (TunnelController.state.value !is VpnState.Connected) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!startForegroundSafely()) {
            // 前台服务受限时宁可退出保活，也不要让系统因未按时
            // startForeground 把进程判为异常。
            stopSelf()
            return START_NOT_STICKY
        }
        handler.removeCallbacks(syncRunnable)
        handler.postDelayed(syncRunnable, 2000L)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacks(syncRunnable)
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun startForegroundSafely(): Boolean {
        val notification = buildNotification()
        return try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        } catch (_: Throwable) {
            // 某些系统会限制后台启动前台服务；连接本身不应因此崩溃。
            false
        }
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("VPN 已连接，网络保护中")
            .setContentIntent(openApp)
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "VPN 连接状态",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "保持 VPN 连接时在前台运行"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "vpn_keepalive"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, VpnKeepAliveService::class.java)
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, VpnKeepAliveService::class.java))
        }
    }
}
