# NodePoolManager V2

这是给手机用户使用的 Android 工程，带 GitHub Actions 自动编译 APK。

功能：
- 内置 5 个订阅源
- 手动添加任意 HTTP/HTTPS TXT、Base64、Clash/V2Ray/Xray 订阅地址
- 多源下载、节点提取、去重
- 支持常见 vmess/vless/trojan/ss/ssr/hysteria/hysteria2/tuic/socks/http URI
- TCP 端口并发测速、按延迟排序
- 自定义导出前 N 个
- TXT 导出
- Clash YAML 导出（当前为简化导出格式）
- GitHub Actions 自动生成 APK

注意：
当前测速是“节点服务器 TCP 端口可达性/延迟”，不是通过 Xray 核心建立真实代理链路的网页测速。
当前 Clash 导出是简化格式，后续如需正式 Clash Meta 配置，可继续升级。
