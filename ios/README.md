# Finexy iOS

原生 SwiftUI 客户端开发基线，复用 Finexy Go REST API。本轮从服务端读取数据，不修改账目。

## 当前范围

- 自托管服务器地址配置，支持反向代理子路径。
- 账号密码登录、TOTP 和恢复码两步验证。
- 系统钥匙串保存最终会话；冷启动重新核验用户与可访问账本。
- 默认个人账本与其他账本切换、流水游标分页、流水详情、下拉刷新。
- 真实账户币种、分类、转账双侧金额、退款与金额隐藏语义。
- 原生深浅主题、系统字号、VoiceOver 标签、进入后台时隐私遮罩。

这不是 Android 全功能的移植或已发布安装包。新增/编辑流水、离线写入与同步、账户管理、预算、报告、AI/OCR、附件、账本成员管理均未在 iOS 接入。当前没有服务端令牌自动续期，过期后重新登录；退出仅清除本设备会话，不宣称撤销所有设备令牌。

## 工程

```text
ios/
  Finexy.xcodeproj/         # iPhone / iPad App，共享 Finexy scheme
  FinexyApp/               # SwiftUI 页面、会话状态、Keychain
  Configuration/Info.plist
  Package.swift            # 无第三方依赖的 FinexyCore 包
  Sources/FinexyCore/      # URLSession、API 模型、定点金额
  Tests/FinexyCoreTests/   # 不接触真实账本的接口合同测试
```

最低部署目标 iOS 17；工程使用 Swift 5 语言模式，可用 Xcode 15 或更新版本打开。测试包同时支持 macOS 14。主程序入口是 `Finexy.xcodeproj`，不要只打开 Package.swift 来运行 App。

## 运行

1. 安装完整 Xcode 和 iOS Simulator。只有 Command Line Tools 时不能构建或运行 iOS App。
2. 打开 `ios/Finexy.xcodeproj`，选择 **Finexy** scheme 与 iPhone 模拟器，运行。
3. 真机运行时，在 Signing & Capabilities 中选择自己的 Team；必要时改成自己可签名的 Bundle Identifier。仓库不包含开发者证书或签名账号。
4. 输入实际服务地址和已有账号。地址填写网站根路径，例如 `https://finexy.example.com/` 或 `https://example.com/finexy/`，不要追加 `/api`。
5. 局域网服务须与设备网络互通，并在系统弹窗中允许本地网络访问。

服务地址不内置用户 NAS，测试不登录真实账号。使用 HTTPS 时正常验证系统证书，不绕过 TLS 错误。为兼容自托管 HTTP 服务，Info.plist 开启 ATS 的 HTTP 支持；登录界面必须先明确同意明文传输。API 拒绝重定向，请填写最终地址。HTTP 同意不会让证书验证失效。

## 验证

从仓库根目录运行，不需要 npm、CocoaPods 或外部 Swift 依赖：

```sh
swift test --package-path ios --scratch-path /tmp/finexy-ios-tests
```

安装完整 Xcode 后再执行真实 iOS 编译：

```sh
xcodebuild -project ios/Finexy.xcodeproj -scheme Finexy \
  -configuration Debug -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath /tmp/finexy-ios-derived CODE_SIGNING_ALLOWED=NO build
```

本机已使用 Xcode 27.2 完成标准 XCTest 和 iOS Simulator 目标编译。当前 `simctl list runtimes` 为空，尚未安装 iOS 模拟器运行时，因此未启动 App 做设备界面验收。可在 Xcode 中下载 iOS Simulator 运行时，再选择 iPhone 模拟器运行。

首次设备验收须验证：普通/两步登录、恢复码、HTTP 同意、反向代理子路径、无权限与令牌失效、分页末页、快速切换账本与账号、隐藏金额、外部转账及跨币种、深浅主题、最大字号、横屏、VoiceOver、后台快照。本轮不声称这些设备场景已经验收。App 图标、正式签名、归档和发布仍待补齐。

2026-11-08 本机验证记录：

- `swift build --package-path ios --scratch-path /tmp/finexy-ios-core-build` 通过。
- 完整 Xcode 环境中，`swift test --package-path ios --scratch-path /tmp/finexy-ios-xcode-tests` 通过，10 项 XCTest、0 失败。
- 上述 `xcodebuild` 命令通过，`BUILD SUCCEEDED`，生成 arm64 / x86_64 模拟器 App：`/tmp/finexy-ios-derived/Build/Products/Debug-iphonesimulator/Finexy.app`。
- 两个一次性本机 HTTP 服务验证跨源 302：默认及注入 session 均拒绝跳转，目标服务没有收到请求。未访问真实财务服务。
- `AppSession.swift` 与 `KeychainStore.swift` 基于真实 macOS SDK 的类型检查通过；全部 App Swift 文件语法检查及工程/Info plist 格式检查通过。
- 已加入独立的 `.github/workflows/ios.yml`，在 macOS runner 执行核心测试与 iOS 模拟器目标编译；尚未推送或触发远程 CI。
- 本机完整日志保存在被 Git 忽略的 `artifacts/ios-native-baseline/`。

## 数据合同

- 服务器、会话和账本变化时立即清除旧财务内容；迟到响应不能更新新的上下文。
- ID 与游标保留字符串，金额使用 Int64；所有币种固定按 100 倍定点存储，不按 ISO 币种小数位改变缩放。
- 默认账本 `0` 由客户端补入，服务端账本列表不包含它。每次读取明确传 `ledgerId`。
- 只有成功 envelope 且存在完整 `result` 才能展示数据；失败不当成空账本或零余额。
- 密码和 2FA challenge 不持久化，最终令牌只保存在 `WhenUnlockedThisDeviceOnly` 的钥匙串项中；不记录财务内容或令牌日志。

接口依据：`pkg/models/auth_response.go`、`pkg/models/transaction.go`、`pkg/models/account.go`、`pkg/utils/api.go` 与现有 Android API 实现。界面遵循 `docs/UI_UX_DESIGN.md` 的品牌、状态、账本隔离及金额语义。
