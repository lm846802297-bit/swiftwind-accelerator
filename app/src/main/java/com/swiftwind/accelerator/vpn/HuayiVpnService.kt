package com.swiftwind.accelerator.vpn

import android.net.IpPrefix
import android.net.VpnService
import android.os.Build
import android.util.Log
import com.wireguard.android.backend.GoBackend
import java.net.InetAddress

/**
 * 官方 GoBackend 的 VpnService 每次建隧道都会新建 Builder，外部拿不到
 * 真正用于 establish() 的 Builder。这里继承它并覆盖 getBuilder()，在
 * Android 13+ 上尝试排除 IPv6 链路本地网段。
 *
 * 注意：Android 会为任何带 IPv6 的 VPN 接口自动生成 fe80::/64 kernel
 * 路由，优先级高于 excludeRoute 的 throw 路由，所以整段 /10 无法删除；
 * 精确排除默认网关 fe80::1/128 可以生效，其他 fe80 地址仍可能进 tun0。
 */
class HuayiVpnService : GoBackend.VpnService() {
    override fun getBuilder(): VpnService.Builder {
        val builder = super.getBuilder()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching {
                val linkLocal = IpPrefix(InetAddress.getByName("fe80::"), 10)
                val linkLocal64 = IpPrefix(InetAddress.getByName("fe80::"), 64)
                val gateway = IpPrefix(InetAddress.getByName("fe80::1"), 128)
                builder.excludeRoute(linkLocal)
                builder.excludeRoute(linkLocal64)
                builder.excludeRoute(gateway)
                Log.d(TAG, "excludeRoute fe80::/10, fe80::/64, fe80::1/128 applied")
            }.onFailure {
                Log.w(TAG, "excludeRoute fe80::/10 failed", it)
            }
        }
        return builder
    }

    private companion object {
        const val TAG = "HuayiVpnService"
    }
}
