import FinexyCore
import SwiftUI

enum FinexyStyle {
    static let brand = Color(red: 240 / 255, green: 85 / 255, blue: 55 / 255)
    static let action = Color(red: 203 / 255, green: 64 / 255, blue: 39 / 255)
}

@main
struct FinexyApp: App {
    @StateObject private var session = AppSession()
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(session)
                .tint(FinexyStyle.action)
                .modifier(PrivacyShield())
                .task { session.start() }
                .onChange(of: scenePhase) { _, value in
                    if value == .background { session.protectInBackground() }
                    if value == .active { session.resumeIfNeeded() }
                }
        }
    }
}

struct PrivacyShield: ViewModifier {
    @Environment(\.scenePhase) private var scenePhase

    func body(content: Content) -> some View {
        content
            .accessibilityHidden(scenePhase != .active)
            .overlay {
                if scenePhase != .active {
                    ZStack {
                        Color(.systemBackground).ignoresSafeArea()
                        Label("Finexy", systemImage: "lock.fill").font(.title2.weight(.semibold))
                    }
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel("Finexy 隐私保护")
                }
            }
    }
}

private struct RootView: View {
    @EnvironmentObject private var session: AppSession

    var body: some View {
        switch session.phase {
        case .starting, .restoring:
            VStack(spacing: 16) {
                BrandMark()
                ProgressView("正在核验登录与账本…")
            }
        case .restoreFailed:
            NavigationStack {
                ContentUnavailableView {
                    Label("暂时无法恢复登录", systemImage: "wifi.exclamationmark")
                } description: {
                    Text(session.authError ?? "请检查网络与设备解锁状态后重试。")
                } actions: {
                    Button("重新核验登录") { session.restore() }.buttonStyle(.borderedProminent)
                    Button("退出此设备的登录", role: .destructive) { session.signOut() }
                }
            }
        case .signedOut:
            LoginView()
        case .signedIn:
            TabView {
                OverviewView()
                    .tabItem { Label("总览", systemImage: "chart.bar.xaxis") }
                TransactionListView()
                    .tabItem { Label("流水", systemImage: "list.bullet.rectangle") }
                AccountsView()
                    .tabItem { Label("账户", systemImage: "wallet.bifold") }
                SettingsView()
                    .tabItem { Label("设置", systemImage: "gearshape") }
            }
        }
    }
}

private struct BrandMark: View {
    var body: some View {
        HStack(spacing: 12) {
            Text("F").font(.system(.title2, design: .rounded, weight: .bold))
                .foregroundStyle(.white)
                .frame(width: 44, height: 44)
                .background(FinexyStyle.brand, in: RoundedRectangle(cornerRadius: 12))
                .accessibilityHidden(true)
            Text("Finexy").font(.largeTitle.weight(.semibold))
        }
        .accessibilityElement(children: .combine)
    }
}

private struct LoginView: View {
    @EnvironmentObject private var session: AppSession
    @Environment(\.scenePhase) private var scenePhase
    @State private var server = ""
    @State private var username = ""
    @State private var password = ""
    @State private var code = ""
    @State private var recovery = false
    @State private var allowHTTP = false
    @FocusState private var focus: Field?
    private enum Field { case server, username, password, code }

    private var isHTTP: Bool { (try? ServerAddress(rawValue: server))?.isSecure == false }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    BrandMark().padding(.vertical, 8)
                    Text("连接自己的服务器，查看账本并记录日常收支。")
                        .foregroundStyle(.secondary)
                }
                .listRowBackground(Color.clear)

                if session.hasChallenge {
                    Section {
                        LabeledContent("服务器") { Text(session.serverText).textSelection(.enabled) }
                        VStack(alignment: .leading, spacing: 8) {
                            Text(recovery ? "恢复码" : "动态验证码").font(.subheadline)
                            SecureField(recovery ? "输入一个未使用的恢复码" : "输入验证器中的 6 位验证码", text: $code)
                                .textContentType(.oneTimeCode)
                                .keyboardType(recovery ? .asciiCapable : .numberPad)
                                .textInputAutocapitalization(.never)
                                .autocorrectionDisabled()
                                .focused($focus, equals: .code)
                                .accessibilityLabel(recovery ? "恢复码" : "动态验证码")
                                .frame(minHeight: 44)
                        }
                        Button(recovery ? "使用动态验证码" : "使用恢复码") { recovery.toggle(); code = "" }
                            .frame(minHeight: 44)
                    } header: { Text("二步验证") } footer: {
                        Text("验证成功前不会保存登录。恢复码每个只能使用一次。")
                    }
                } else {
                    Section {
                        field("服务器地址", hint: "https://finexy.example.com", text: $server, field: .server)
                            .keyboardType(.URL)
                        if isHTTP {
                            Toggle(isOn: $allowHTTP) {
                                Label("我同意向此 HTTP 地址明文发送账号、密码和账本数据", systemImage: "exclamationmark.shield")
                                    .font(.subheadline)
                            }
                        }
                    } header: { Text("自托管服务器") } footer: {
                        Text("填写完整地址，可包含反向代理子路径。推荐使用 HTTPS。")
                    }
                    Section("账号") {
                        field("用户名或邮箱", hint: "输入用户名或邮箱", text: $username, field: .username)
                            .textContentType(.username)
                        VStack(alignment: .leading, spacing: 8) {
                            Text("密码").font(.subheadline)
                            SecureField("输入密码", text: $password)
                                .textContentType(.password)
                                .focused($focus, equals: .password)
                                .accessibilityLabel("密码")
                                .frame(minHeight: 44)
                        }
                    }
                }

                if let error = session.authError {
                    Section { InlineError(message: error) }
                }
                Section {
                    Button {
                        focus = nil
                        if session.hasChallenge { session.completeTwoFactor(code: code, recovery: recovery) }
                        else { session.login(server: server, loginName: username, password: password, allowHTTP: allowHTTP) }
                    } label: {
                        HStack {
                            Spacer()
                            if session.authBusy { ProgressView().tint(.white) }
                            Text(session.authBusy ? "正在核验…" : (session.hasChallenge ? "验证并登录" : "登录服务器"))
                            Spacer()
                        }
                        .frame(minHeight: 44)
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(session.authBusy || (session.hasChallenge ? code.isEmpty : username.isEmpty || password.isEmpty || server.isEmpty || (isHTTP && !allowHTTP)))
                    .listRowBackground(Color.clear)
                    if session.hasChallenge {
                        Button("取消二步验证") { session.cancelChallenge(); code = ""; recovery = false }
                            .frame(minHeight: 44)
                            .disabled(session.authBusy)
                    }
                } footer: {
                    Text("登录凭据保存在此设备的钥匙串。密码仅用于本次登录。")
                }
            }
            .disabled(session.authBusy)
            .navigationTitle(session.hasChallenge ? "二步验证" : "连接账本")
            .navigationBarTitleDisplayMode(.inline)
            .onAppear { server = session.serverText }
            .onChange(of: server) { _, _ in allowHTTP = false }
            .onChange(of: session.hasChallenge) { _, hasChallenge in
                if hasChallenge { password = ""; focus = .code }
                code = ""
            }
            .onChange(of: scenePhase) { _, phase in
                if phase == .background { password = ""; code = "" }
            }
            .scrollDismissesKeyboard(.interactively)
            .toolbar {
                ToolbarItemGroup(placement: .keyboard) {
                    Spacer()
                    Button("完成") { focus = nil }.frame(minHeight: 44)
                }
            }
        }
    }

    private func field(_ title: String, hint: String, text: Binding<String>, field: Field) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title).font(.subheadline)
            TextField(hint, text: text)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .focused($focus, equals: field)
                .accessibilityLabel(title)
                .frame(minHeight: 44)
        }
    }
}

private struct SettingsView: View {
    @EnvironmentObject private var session: AppSession
    @State private var confirmSignOut = false

    var body: some View {
        NavigationStack {
            Form {
                Section("服务器与账号") {
                    LabeledContent("昵称", value: session.displayName)
                    LabeledContent("用户名", value: session.user?.username ?? "")
                    LabeledContent("服务器") { Text(session.serverText).textSelection(.enabled) }
                    LabeledContent("当前账本", value: session.currentLedgerName)
                    Text("更换服务器或账号前，请先退出当前登录。")
                        .font(.footnote).foregroundStyle(.secondary)
                }
                Section("隐私") {
                    Toggle("显示金额与备注", isOn: $session.showAmounts)
                    Text("退出后台会隐藏金额并清除当前财务展示，返回时重新核验服务器身份与访问权限。密码不保存，登录凭据仅存入此设备的钥匙串。")
                        .font(.footnote).foregroundStyle(.secondary)
                }
                Section("读取状态") {
                    if session.dataBusy { ProgressView("正在读取当前账本…") }
                    else if let lastReadAt = session.lastReadAt {
                        LabeledContent("上次读取") { Text(lastReadAt, format: .dateTime.month().day().hour().minute()) }
                    } else { Text("尚未完成读取").foregroundStyle(.secondary) }
                    if let error = session.dataError { InlineError(message: error) }
                    if let error = session.authError { InlineError(message: error) }
                    Button("重新读取当前账本") { session.refresh() }
                        .frame(minHeight: 44).disabled(session.dataBusy)
                }
                Section("iOS 开发版") {
                    Text("支持流水记账、账户查询和月度报告。写入时需要连接服务器。")
                    Text("账户设置、AI/OCR、账单导入和预算可在 Web 或 Android 使用。")
                        .foregroundStyle(.secondary)
                }
                Section {
                    Button("退出登录", role: .destructive) { confirmSignOut = true }
                        .frame(minHeight: 44).disabled(session.writeBusy)
                }
            }
            .navigationTitle("设置")
            .confirmationDialog("退出当前登录？", isPresented: $confirmSignOut, titleVisibility: .visible) {
                Button("退出登录", role: .destructive) { session.signOut() }
            } message: {
                Text("移除此设备保存的登录凭据与当前财务展示，服务器账目保留。")
            }
        }
    }
}

struct InlineError: View {
    let message: String
    var body: some View {
        Label(message, systemImage: "exclamationmark.circle")
            .font(.subheadline)
            .foregroundStyle(.red)
            .fixedSize(horizontal: false, vertical: true)
            .accessibilityElement(children: .combine)
    }
}
