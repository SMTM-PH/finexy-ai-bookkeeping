# Finexy iOS

Finexy 原生 SwiftUI 客户端，连接自托管 Go 服务。当前开发版本为 0.2.0。

## 当前范围

- 自托管服务器地址配置，支持反向代理子路径。
- 账号密码登录、TOTP 和恢复码两步验证。
- 系统钥匙串保存最终会话；冷启动重新核验用户与可访问账本。
- 默认个人账本与共享账本切换、流水分页和详情。记账权限来自当前账本的成员角色，修改与删除还核对单笔流水的权限。
- 在线新增支出、收入、退款、收入冲回、账户间转账、外部转入和外部转出。默认个人账本可录入期初余额，受服务端账户状态限制。
- 支持对符合条件的流水进行编辑和确认删除。外部转账可新增和查看。使用本分支服务端时，编辑保留已有标签、图片和位置，编辑与删除都会拒绝过期的流水详情。
- 账户列表、余额及账户明细，支持按自然月浏览收支、分类和流水。报告按原币种计算并排除服务端标为隐藏的流水。
- 原生深浅主题、系统字号、VoiceOver 标签、进入后台时隐私遮罩。

新增需要提供持久写入回执的服务端。单版本部署的 Finexy 1.9.4 正式版也支持新增。编辑需要本分支新增的条件编辑接口。账户和分类管理、预算、AI/OCR、附件管理、账单导入、账本成员管理与离线同步仍可按对应平台的支持情况在 Web 或 Android 使用。会话过期后需重新登录，退出会移除此设备保存的会话。

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

GitHub Actions 的 iOS 工作流运行核心测试和模拟器构建。真机签名、完整 VoiceOver 和系统适配验收、归档及商店发布需单独完成。

2026-11-10 本机验证通过 19 项 Swift XCTest、启用 CGO 的 Go 全量测试、iOS Simulator 构建与无签名的 iOS Release 编译。iPhone 18 Pro（iOS 27.2）连接一次性本机 Go/SQLite 服务，完成登录、账本切换、月报与账户报告、金额隐私、只读权限，以及丢回包后跨进程恢复新增、编辑与删除。数据库核对确认重试仅入账一次，删除后余额恢复。服务端专项也验证了过期详情不能覆盖或删除其他客户端更新过的流水。

## 数据合同

- 服务器、会话和账本变化时立即清除旧财务内容；迟到响应不能更新新的上下文。
- ID 与游标保留字符串，金额使用 Int64；所有币种固定按 100 倍定点存储，不按 ISO 币种小数位改变缩放。
- 默认账本 `0` 由客户端补入，服务端账本列表不包含它。每次读取明确传 `ledgerId`。
- 只有成功 envelope 且存在完整 `result` 才能展示数据；失败不当成空账本或零余额。
- 密码和 2FA challenge 仅用于当前登录流程。最终令牌与已提交、待核对的新增请求分别保存在 `WhenUnlockedThisDeviceOnly` 钥匙串项。
- 新增先保存不可变请求与稳定编号，再发送给服务器。结果未知时，返回同一服务器、账号和账本，使用“重试原请求”核对。保存成功后独立刷新流水和余额。

接口依据：`pkg/models/auth_response.go`、`pkg/models/transaction.go`、`pkg/models/account.go`、`pkg/utils/api.go` 与现有 Android API 实现。界面遵循 `docs/UI_UX_DESIGN.md` 的品牌、状态、账本隔离及金额语义。
