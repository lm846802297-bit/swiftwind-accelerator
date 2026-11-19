package com.swiftwind.accelerator.vpn

import android.content.Context
import android.content.Intent
import android.util.Log
import com.wireguard.android.backend.GoBackend
import java.lang.reflect.Field
import java.util.concurrent.TimeUnit

/**
 * 把 GoBackend 的静态 vpnService future 指向 [HuayiVpnService]。
 *
 * GoBackend 在断开/切换时会重新请求官方 VpnService，因此不能在进程启动时
 * 只注入一次；每次建立隧道前都重建 future 并拉起自定义服务，等待它完成
 * future 后再让 GoBackend 执行 UP。
 */
object HuayiVpnServiceBridge {
    private const val TAG = "HuayiVpnBridge"
    private const val VPN_SERVICE_FIELD = "vpnService"
    private const val FUTURE_CLASS = "com.wireguard.android.backend.GoBackend\$GhettoCompletableFuture"

    @Volatile
    private var installed = false
    private var serviceField: Field? = null

    fun install(context: Context) {
        if (installed) return
        installed = true
        runCatching {
            serviceField = GoBackend::class.java.getDeclaredField(VPN_SERVICE_FIELD).apply {
                isAccessible = true
            }
            // 进程启动时先拉起服务，缩短首次连接等待。
            requestCustomService(context)
            Log.d(TAG, "custom VpnService requested")
        }.onFailure {
            Log.w(TAG, "custom VpnService install failed", it)
        }
    }

    /**
     * 每次 UP 前调用：替换 future，拉起自定义服务，并等待该 future 完成。
     * 返回 false 时 GoBackend 会回退到官方服务；调用方仍可继续连接。
     */
    fun prepare(context: Context, timeoutMs: Long = 1500L): Boolean {
        if (!installed) install(context)
        val field = serviceField ?: return false
        val appContext = context.applicationContext
        return runCatching {
            val future = newFuture()
            field.set(null, future)
            requestCustomService(appContext)
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            while (System.nanoTime() < deadline) {
                if (isDone(field)) return true
                Thread.sleep(40L)
            }
            false
        }.getOrElse {
            Log.w(TAG, "custom VpnService prepare failed", it)
            false
        }
    }

    private fun requestCustomService(context: Context) {
        context.startService(Intent(context, HuayiVpnService::class.java))
    }

    private fun newFuture(): Any {
        val futureClass = Class.forName(FUTURE_CLASS)
        return futureClass.getDeclaredConstructor().apply {
            isAccessible = true
        }.newInstance()
    }

    private fun isDone(field: Field): Boolean {
        val future = field.get(null) ?: return false
        val method = future.javaClass.getMethod("isDone")
        return method.invoke(future) as Boolean
    }
}
