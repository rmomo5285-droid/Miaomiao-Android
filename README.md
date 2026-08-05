# 喵喵 Android (Miaomiao Android)

喵喵 Android 是基于 [v2rayNG](https://github.com/2dust/v2rayNG) 的 GPL-3.0 衍生客户端。
项目保留 v2rayNG 的代理与 VPN 能力，并增加喵喵账户、套餐购买、公告和托管订阅。

## 客户端策略

- 登录后从账户接口取得 HTTPS 托管订阅，不要求用户手工输入机场地址。
- 托管订阅每 48 小时自动更新；用户主动刷新、重新登录和支付完成可立即更新。
- 更新失败时继续使用本地缓存节点，并使用有限次数的退避重试。
- 服务入口由 ECDSA P-256 签名清单迁移；客户端拒绝回滚、旧式字符串公告和远程命令字段。
- 启动刷新在 45 秒总预算内扫描全部镜像并选择最高版本；业务 API 回退按当前内核明确使用
  Xray 的 SOCKS 入站或 sing-box 的独立 HTTP 入站，清单兜底明确使用 SOCKS。
- 已验签的最后可用入口即使到期也会作为离线兜底保留，但到期清单不能作为新的更新被接受。
- 登录后可生成、复制、展示二维码及系统分享邀请链接；生成结果不确定时会阻止盲目重试。
- 普通公告按公告 ID 仅提示一次，迁移公告和强制客户端更新始终具有更高显示优先级。
- Android 固定使用 v2rayNG 官方 AndroidLibXrayLite v26.7.31（Xray-core v26.7.28），该版本包含原生 Hysteria2 客户端实现；桌面端的 HY2 则固定使用 sing-box。
- VPN 默认 MTU 为 1280。

## 构建与签名

正式 APK 只通过 [GitHub Actions](.github/workflows/build.yml) 构建，输出 arm64-v8a、
armeabi-v7a、x86、x86_64 和 universal 五个版本。工作流会校验：

- Gradle 发行包 SHA-256；
- AndroidLibXrayLite 的固定提交、标签和归档 SHA-256；
- 每个 APK 的签名证书一致，证书主题包含 `Miaomiao`；
- 稳定版生成 SHA-256 清单、GPG 签名和发布公钥。

## Orange 图标

品牌母版保存在 `branding/orange-icon.png`，其 SHA-256 和各 Android density 尺寸由
`branding/verify-orange-icons.sh` 校验。安装 ImageMagick 后可运行
`bash branding/generate-orange-icons.sh` 重建 legacy、round 和 adaptive foreground 资源。
普通 push/PR 的 CI 会上传 `miaomiao-android-orange-icon-review`，供真机审核前下载比对。

## 开发与许可

源代码继承上游 v2rayNG，并继续遵循仓库中的 [GNU GPL v3](LICENSE)。分发修改版时必须同时满足
GPL-3.0 的源代码提供义务。上游项目与代理内核仍分别归其原作者所有；`Miaomiao` / `喵喵`
仅表示本衍生客户端的产品品牌。

上游协议说明与开发资料：[v2rayNG](https://github.com/2dust/v2rayNG)。
