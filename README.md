# 花意加速器（Huayi）

一个轻量的 Android WireGuard 客户端，内置 Cloudflare WARP 免费节点一键注册，也支持手动导入 `.conf` 与订阅链接。

## 功能

- 一键注册 Cloudflare WARP，并根据注册接口分配的 v4/v6 入口生成多条线路
- 内置 Cloudflare anycast 备用入口（优选线路），线路失效时可切换
- 一键体检：真实拉起 WireGuard 隧道检查握手，自动标记可用/不可用
- 手动粘贴 / 导入 WireGuard 配置，支持一次导入多个
- 订阅链接拉取，自动去重
- WireGuard 隧道连接、断开、实时上下行流量与连接时长
- 节点切换、删除、持久化选择；切换失败时自动恢复原节点或尝试备用 WARP
- 连接方式：智能连接（中国 IPv4、私有/局域网地址直连，非中国 IPv4 与全球 IPv6 走 WARP）与全局连接（当前节点的全部 IPv4 / IPv6 流量都走 VPN）。IPv6 链路本地 `fe80::1` 默认网关通过自定义 VpnService 的 `excludeRoute(fe80::1/128)` 保持直连；其余 `fe80::/64` 仍由 Android 内核为 tun0 自动生成，应用层无法整段删除。
- 连接期间前台保活服务，降低 Android 在后台回收 VPN 进程的概率
- 深色粉黑舞台风 Jetpack Compose 界面

## 技术栈

- Kotlin + Jetpack Compose（Material 3）
- 官方 `com.wireguard.android:tunnel`（Apache-2.0）负责 WireGuard 隧道
- OkHttp 负责 WARP 注册与订阅拉取
- minSdk 24 / targetSdk 34

## 构建

```bash
# 需要 JDK 17 与 Android SDK（platform 34、build-tools 34.0.0）
./gradlew assembleDebug
```

APK 输出：`app/build/outputs/apk/debug/app-debug.apk`

### Windows 中文路径

项目路径包含中文时，Windows 下的 Kotlin 增量缓存和单测类路径可能把 `X:\` 与中文真实路径混用，出现 `ClassNotFoundException`。构建和跑单测前先建立 ASCII 盘符映射，并从映射盘运行 Gradle：

```cmd
subst X: C:\Users\84680\Desktop\加速器开发
cd /d X:\
gradle :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease
```

如果已经用中文路径构建过，先执行 `gradle :app:clean` 再重新跑，避免旧缓存继续指向已失效的盘符。

### 发布构建

1. 将正式 keystore 放到本地，创建 `keystore.properties`（不要提交到仓库）：

```properties
storeFile=C:/path/to/huayi-release.jks
storePassword=你的密码
keyAlias=huayi
keyPassword=你的密码
```

2. 执行：

```bash
gradle --no-daemon assembleRelease
```

APK 输出：`app/build/outputs/apk/release/app-release.apk`

没有 `keystore.properties` 时，release 会回退到 debug 签名，仅适合本地验证，不能用于公开分发。

## 隐私

应用不收集账号、位置或使用行为数据，节点配置只保存在本机。详细说明见 [docs/PRIVACY.md](docs/PRIVACY.md)。

公开可访问版本：<https://aka.doubaocdn.com/s/9EX5YNoJts>（`docs/privacy.html` 的在线副本，用于应用商店资料或隐私政策链接）。

崩溃日志写入应用专属目录 `Android/data/com.swiftwind.accelerator/files/crash-logs/`，不会自动上传。

## 免费节点说明

「Cloudflare WARP」是 Cloudflare 提供的免费 WireGuard 服务。App 会在设备本地生成一对密钥并调用 WARP 注册接口，配置只保存在本机，不会上传到任何第三方服务器。

WARP 注册接口会返回具体分配到的 IPv4 / IPv6 入口和端口列表，App 以它为主线路，并补充若干 Cloudflare anycast 备用入口。不同运营商、不同地区的 UDP 可达性差异很大，所以「节点页 → 一键体检」会真实发起 WireGuard 握手；绿色「可用」才代表当前网络下握手成功，体检后如果当前节点不可用会自动切到第一条可用线路。

### 智能连接的分流规则

智能连接不是“自动选节点”，而是在建立隧道时改写 WireGuard 的 `AllowedIPs`，由 Android 的 VPN 路由表真正分流：

- 绝大多数中国 IPv4 目标不加入 VPN 路由，继续走 Wi‑Fi / 5G 本地网络；
- 非中国 IPv4 目标加入 VPN 路由，走 WARP；
- IPv6 使用 `2000::/3`（全球单播）走 VPN，避免 IPv6 目标绕过代理；
- 内网、回环、组播和保留地址不加入 VPN 路由；IPv6 `fe80::1` 默认网关通过自定义 VpnService 排除，其他 `fe80::/64` 地址仍受 Android 内核 link-local 路由影响；
- 全局连接则使用 `0.0.0.0/0, ::/0`，所有流量都走 VPN；
- 当前智能连接使用 `foreign_ipv4.txt` 的压缩 CIDR 表（1208 条），由 `ChinaIPsBGP`（blackmatrix7/ios_rule_script，上游 `misakaio/chnroutes2`）按 65536 个 IP 的间隙合并生成，并额外剔除 RFC1918、回环、链路本地、CGNAT、组播与保留网段。压缩是为了避开 Android `NetworkMonitorManager` 的 Binder 事务上限；代价是少量中国 IPv4 段会与海外网段合并而走 VPN，实测约覆盖 6.6% 的中国 IPv4 地址；不压缩的 2550 条版本在模拟器上会触 `TransactionTooLargeException`。

分流清单位于 `app/src/main/assets/foreign_ipv4.txt`，当前由 `ChinaIPsBGP`（blackmatrix7/ios_rule_script，上游 `misakaio/chnroutes2`）生成，并在压缩后剔除私有与保留网段。它是 IP 级分流：国内网站若使用海外 CDN 节点，仍可能走 VPN；海外网站若使用中国境内 CDN，也可能直连。这属于 IP 分流的边界，不是假连接。

第三方免费 WireGuard（如 OpenTunnel）通常需要网页验证码注册、账号有 7 天有效期，无法在 App 内可靠地自动签发。本项目保留「导入配置 / 订阅链接」入口，你可以把这类服务生成的配置手动导入；不要使用来源不明的公开私钥配置。

## 免责声明

本项目仅供个人学习与技术测试使用。请遵守所在国家或地区的法律法规以及 Cloudflare 的服务条款。
