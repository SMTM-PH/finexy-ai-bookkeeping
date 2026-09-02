# Finexy Mobile

Android 原生客户端（Kotlin + Jetpack Compose），包名为 `com.finexy.mobile`，最低支持 Android 8.0。

## 本地构建

```bash
./gradlew assembleDebug
```

APK 输出于 `app/build/outputs/apk/debug/app-debug.apk`。

## 当前能力

- 首次启动配置自托管服务地址
- Compose 底部导航：总览、流水、记账、账户、设置
- 加密本地配置存储（Android Keystore + AES/GCM）
- 有序离线操作队列基础设施
- Finexy REST API 客户端基础设施

核心服务同步和登录页面将基于现有 API 逐步接入；Release 签名只从 CI Secret 或本机环境注入，不提交密钥。
