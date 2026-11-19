# 花意加速器发布检查清单

更新时间：2026-11-19

## 已就绪

- [x] 包名：`com.swiftwind.accelerator`
- [x] 版本：`versionCode 2 / versionName 1.1.0`
- [x] 正式签名的 release APK：`花意加速器-1.1.0-release.apk`
- [x] 签名证书 SHA-256：`be991e5d3c5ec2f1febbcf4ef76ec55877ab770aacccb5c4446319c6674c1d40`
- [x] 隐私说明公开地址：<https://aka.doubaocdn.com/s/9EX5YNoJts>
- [x] 仓库内隐私说明：`docs/PRIVACY.md`、`docs/privacy.html`
- [x] 商店资料草稿：`docs/STORE_LISTING.md`
- [x] Data safety 填写稿：`docs/DATA_SAFETY.md`
- [x] VPN / 前台服务声明：`docs/VPN_DECLARATION.md`
- [x] GitHub Actions 构建配置：`.github/workflows/android-ci.yml`
- [x] 应用内崩溃日志：仅写入应用专属目录，不上传
- [x] 前台 VPN 保活服务与通知权限说明
- [x] 通知权限被拒绝时的应用内提示
- [x] 设置页隐私说明入口
- [x] 节点切换失败时恢复原节点，原节点不可用时尝试备用 WARP 线路
- [x] 体检取消后等待旧隧道真正停止，避免与下一次连接抢占状态
- [x] 体检标签超过 10 分钟显示「待复检」，并标明上次体检时间

## 发布前仍需完成的外部动作

- [ ] 使用开发者自己的 Google Play / 应用市场开发者账号完成主体认证
- [ ] 将 `huayi-release.jks` 与 `keystore.properties` 离线备份；不要提交到仓库
- [ ] 按 `docs/STORE_LISTING.md` 填写应用名称、简介、截图、分类、联系方式与内容分级
- [ ] 按 `docs/VPN_DECLARATION.md` 完成 Google Play 的 VPN 应用政策声明与 `VpnService` 使用说明
- [ ] 按 `docs/DATA_SAFETY.md` 填写 Data safety / 隐私表单，并填入上面的公开隐私说明地址
- [ ] 确认 Android 14 `FOREGROUND_SERVICE_SPECIAL_USE` 前台服务类型声明与商店表单一致
- [ ] 如需公开分发，补充可长期使用的隐私政策域名（当前为 CDN 在线副本）
- [ ] 配置正式崩溃收集服务（可选；应用当前只写本地日志）
- [ ] 在干净设备上安装 release APK，完成连接、断开、系统撤销 VPN、后台保活与通知权限回归

## 免费 WARP 的边界

Cloudflare WARP 免费入口是 anycast，线路会随运营商、地区和时间波动；不保证流媒体解锁或游戏低延迟。要稳定提升质量，应使用 WARP+ 或自建 WireGuard VPS，而不是继续堆积未经验证的免费地址。
