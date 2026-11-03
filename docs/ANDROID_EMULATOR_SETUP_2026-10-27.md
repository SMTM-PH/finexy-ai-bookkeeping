# Android 模拟器参数与设备安排

2026-10-27，用户明确允许使用当前模拟器，并要求正确设置参数；2026-11-03 再次配置与核验。此前的设备暂缓安排解除。本次参数配置不安装 APK、不执行业务测试或部署服务；其他专项结果分别记录。

## 当前基线

| 项目 | 已设置及核验的值 |
| --- | --- |
| AVD / serial | 现有 `Finexy_API_35_Clean` / `emulator-5554` |
| 系统镜像 | Android 15 / API 35 / Google APIs / x86_64 |
| 手机硬件 | Pixel 8，触屏、导航栏、竖屏；1080×2400、420dpi，无尺寸或密度覆盖 |
| CPU / RAM | 4 核 / 4096 MiB；实际 CPU `0-3`，本次内核可见 RAM `4013928 KiB`（不同开机有少量保留内存差异） |
| 图形 | 配置 `auto`，实际解析为 `host`；启动日志识别 RTX 5080 Laptop GPU |
| 字号 / 旋转 | `font_scale=1.0`，关闭自动旋转，`user_rotation=0` |
| 键盘 / 相机 | 手机软键盘可显示；后置 `emulated`，前置 `none` |
| 时区 | `Asia/Shanghai`，关闭自动时区避免恢复为 GMT；未手工改系统时间 |
| 动画 / 主题 / 系统语言 | 保持现有系统设置；另按 F10 矩阵切换，不将本次配置等同适配通过 |
| 启动方式 | 冷启动，固定端口 5554，`-no-snapshot`，不加载或保存快照 |
| 网络仿真 | `netdelay none`、`netspeed full`；未配置 HTTP 代理 |
| QA 包 | `com.finexy.mobile.qa` 与 `com.finexy.mobile.qa.test` 已启用 |
| Instrumentation | `com.finexy.mobile.qa.test/androidx.test.runner.AndroidJUnitRunner`，目标为 `.qa` |
| 主应用 | 此专用 AVD 未安装 `com.finexy.mobile`，未访问其数据；历史 Pixel AVD 未启动或改动 |

首次检查发现 AVD 已被外部改成 `tv_1080p / no-touch / 1920×1080 / 320dpi`。本次以已有 Pixel 手机配置恢复硬件，保留原镜像、AVD 身份、全部磁盘和应用数据。初次尝试的 `hw.ramSize=4G` 被当前模拟器回退为 2048 MB；最终改用整数 `4096` 及启动参数 `-memory 4096`，并核验实际内存。之后补固定时区及宿主回环连接核验，无需再次重启。各轮记录保留，完整结果以最终读回为准。

## 复用与验收参数

在仓库根目录执行：

```powershell
python .github/scripts/configure-android-emulator.py --apply
```

[配置脚本](../.github/scripts/configure-android-emulator.py) 只操作现有专用 AVD：修改前备份 `config.ini`；遇到其他 AVD 或运行中的硬件不匹配时退出，不强制终止。未启动时才启动；运行中参数已匹配则复用。所有 ADB 命令固定 serial。脚本不安装、不清数据、不运行测试，只定向启用已有的两个 QA 包。

硬件需要重新配置时，先正常关闭专用 AVD，再执行脚本。回退同样须先正常关闭，使用本轮保存的原始配置恢复 `config.ini`；不要删除或替换 `userdata`、SD 卡或快照。备份原始配置是电视状态，仅用于回溯，本项目手机验收应保持本表基线。

| 后续专项 | 宿主地址 | 模拟器参数 |
| --- | --- | --- |
| AI 拍照/中断恢复 | 专用隔离服务端口 `18141` | `finexy.ai.source.url=http://10.0.2.2:18141` |
| 报告 HTTP | 专用隔离服务 `127.0.0.1:18155`、UUID 245 | `finexy.e2e.url=http://10.0.2.2:18155`、`finexy.e2e.disposable=true` |

`10.0.2.2` 用于模拟器访问宿主机；模拟器内部的 `127.0.0.1` 指向模拟器自身。启动参数及快照语义参见 [Android 官方文档](https://developer.android.com/studio/run/emulator-commandline)，网络地址参见 [官方网络说明](https://developer.android.com/studio/run/emulator-networking)。

后续先按 [当前计划](NEXT_DEVELOPMENT_PLAN_2026-11-01.md) 完成 F03/F04，再做报告 HTTP/生产页面/SAF。服务必须重新核验真实容器、标签、端口、无挂载与候选哈希；历史 `launch-parameters.json` 的设备暂缓标记属于旧批次，不能直接当作本轮运行证明。构建 QA 时保留 `-PfinexyIsolatedQa=true`；安装只用 `install -r`，禁止 `connectedDebugAndroidTest`、卸载或清空主应用。

## 本轮证据与边界

- [最终配置及读回](../artifacts/android-emulator-20261027/20261027-154042/summary.json)：物理显示、实际 RAM/CPU、GPU 模式、时区和 QA runner 核验通过。
- [原始电视配置](../artifacts/android-emulator-20261027/20261027-153351/config-before.ini)、[实际启动参数](../artifacts/android-emulator-20261027/20261027-153710/launch.json)、[内存格式修正](../artifacts/android-emulator-20261027/20261027-153710/configuration-changes.json)。完整的手机恢复差异在首次记录，最终轮复用已启动设备并补时区和网络核验。
- [首次内存不符记录](../artifacts/android-emulator-20261027/20261027-153351/summary.json)：已标记 `superseded`。
- [连接核验](../artifacts/android-emulator-20261027/20261027-154042/network.json)：模拟器经 `10.0.2.2` 实际请求本轮临时回环 HTTP 监听，返回预期标记；监听已关闭。此结果不代表报告或 AI 服务测试通过。
- [桌面/UI 树及崩溃缓冲核验](../artifacts/android-emulator-20261027/20261027-154042/smoke.json)：手机 Launcher、旋转值 0、QA 版本 `1.0.1-qa / 10902`，本轮崩溃缓冲未见崩溃。已保存 [手机桌面截图](../artifacts/android-emulator-20261027/20261027-154042/phone-home.png)。

以上是设备环境核验；业务恢复、真实 HTTP、系统图片、文件保存/分享、无障碍及正式升级仍按各专项验收。

## 2026-11-01 重新核验

本次检查时设备未启动，原配置已是上述 Pixel 手机参数。脚本保留原配置副本，以固定 serial 冷启动现有专用 AVD，没有新建、清空或替换磁盘。实际硬件文件逐项核验设备、显示、触控、导航、初始方向、相机、CPU、RAM 和 GPU；内核读回 CPU `0-3`、内存 `4013932 KiB`，实际 GPU 模式 `host`。

首次冷启动的临时 HTTP 请求返回 `Network is unreachable`，该轮失败证据保留在 [195812](../artifacts/android-emulator-20261101/20261101-195812/network-probe.json)，不标为通过。配置脚本已补 30 秒总预算的实际 HTTP 就绪重试，非空路由不作为通过条件，失败尝试及临时监听清理均留档。

- [本轮配置读回](../artifacts/android-emulator-20261101/20261101-200142/summary.json)：硬件、时区和实际宿主回环 HTTP 通过；字体为 1.0、固定竖屏、软键盘可用。
- [配置备份](../artifacts/android-emulator-20261101/20261101-200142/config-before.ini) 与 [差异](../artifacts/android-emulator-20261101/20261101-200142/configuration-changes.json)：本轮持久硬件配置无需变更。
- [桌面核验](../artifacts/android-emulator-20261101/20261101-200142/smoke.json)、[UI 树](../artifacts/android-emulator-20261101/20261101-200142/phone-home.xml) 与 [截图](../artifacts/android-emulator-20261101/20261101-200142/phone-home.png)：真实手机 Launcher，系统输入含 TOUCHSCREEN，本轮崩溃缓冲未发现 Finexy 进程崩溃。

仅两个独立 QA 包已安装且可用，runner 指向 `.qa`。此专用 AVD 未安装主包 `com.finexy.mobile`，本轮未访问应用数据、未安装 APK、未执行业务专项。参数配置与业务验收分别记录。

最终脚本补充区分“网络连接失败”与“已收到 HTTP 但正文不完整”：只有明确连接失败才尝试一次虚拟 Wi-Fi 恢复，收到 HTTP 的失败响应只重试，不改网络。随后在同一已启动设备上 [最终复验](../artifacts/android-emulator-20261101/20261101-200702/summary.json) 通过；[首个实际探测](../artifacts/android-emulator-20261101/20261101-200702/network-probes.json) 即返回 HTTP 200 和完整预期标记，无 Wi-Fi 恢复，临时监听已关闭。该轮配置脚本副本与结果绑定，前述 UI 树/截图来自同一次开机的 200142 轮。

## 2026-11-02 冷启动复核

重连后已确认模拟器及上轮图片执行进程均退出；原运行文件仍写“running”不能作为存活或通过证据。保存中断记录并清理旧 owned 夹具后，只启动同一专用 AVD。[本轮实际配置读回](../artifacts/android-emulator-20261102/20261102-101427/summary.json) 再次通过：1080×2400/420dpi、4 核/4096 MiB、多点触控、竖屏、host GPU、上海时区与实际宿主回环 HTTP。无清数据、无新 AVD，主包仍未安装。

未手工调整设备时间。此前约 1.6 秒跨设备时钟差属于报告客户端错误 TTL 上界的触发条件，已修客户端并用真实 HTTP 复验，不通过强制改系统时间掩盖问题。

## 2026-11-03 配置与复验

用户再次明确当前模拟器可用。本轮只使用现有 `Finexy_API_35_Clean / emulator-5554`：先在确认没有运行中的模拟器后冷启动；随后在同一设备上复验，未重新启动或改动磁盘。持久硬件已经符合手机基线，配置前后副本及空差异均保存。

- [冷启动配置结果](../artifacts/android-emulator-20261103/20261103-091546/summary.json) 与 [实际启动参数](../artifacts/android-emulator-20261103/20261103-091546/launch.json)：API 35 / x86_64、Pixel 8、多点触控、1080×2400 / 420dpi、4 核 / 4096 MiB、GPU `auto` 解析为 `host`；后置模拟相机、前置关闭，未加载或保存快照。
- [最终运行时读回](../artifacts/android-emulator-20261103/20261103-093524/summary.json)：无显示覆盖、字体 1.0、固定竖屏、软键盘可显示、上海时区且自动时区关闭；两个 QA 包均可用，runner 只指向 `.qa`。此专用 AVD 主包未安装。
- [网络探测](../artifacts/android-emulator-20261103/20261103-093524/network-probes.json) 与 [清理结果](../artifacts/android-emulator-20261103/20261103-093524/network.json)：最终复验首次即经 `10.0.2.2` 收到完整 HTTP 200 标记，耗时约 0.19 秒，无需恢复 Wi-Fi，临时监听已关闭。首次冷启动曾恢复虚拟 AndroidWifi 后通过，相关尝试原样保留在冷启动证据目录。
- [桌面与输入核验](../artifacts/android-emulator-20261103/20261103-093524/smoke.json)、[UI 树](../artifacts/android-emulator-20261103/20261103-093524/phone-home.xml) 和 [截图](../artifacts/android-emulator-20261103/20261103-093524/phone-home.png)：手机 Launcher 正常，输入系统包含触屏，截图尺寸为 1080×2400，保留的崩溃缓冲未发现 Finexy 崩溃；未清空日志。

本轮未安装、卸载或清除应用数据，未启动 Finexy 应用或运行 instrumentation，未操作历史 Pixel AVD。以上仅确认设备环境可用于后续 QA；相机拍照恢复、报告页面、系统文件等业务验收仍分别执行。
