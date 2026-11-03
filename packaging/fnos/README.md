# Finexy fnOS（飞牛 NAS）FPK 打包

本目录保存 FPK 的源结构，产物输出到仓库根目录的 `release/fnos/`。

## 产物

| 项目 | 值 |
| --- | --- |
| 文件 | `release/fnos/finexy-1.9.3-x86_64.fpk` |
| 版本 | 1.9.3（无构建号后缀） |
| 架构 | linux/amd64（manifest `platform=x86`） |
| 大小 | 27,041,082 bytes |
| SHA-256 | `6798786aedd94bf6a7338cca54e8579ff30002ea9b7915c865d77fb2efaf7d4c` |

历史包已移入 `release/fnos/archive/`，不随本版本发布：

- `finexy-1.9.3-original-install-init-failed-x86_64.fpk`：初版 1.9.3，`install_init` 权限失败，真机安装即回滚，请勿使用。SHA-256 `5bd67e0928f66e76dbea1e9f432a2f9c501e2d320b3a23e94bab1edc467fc8ac`。
- `finexy-1.9.3-2-iframe-x86_64.fpk`：安装/启动已修复，桌面入口仍为 `iframe`（内嵌窗口）。SHA-256 `e191bee5efb4608b2c42df59eb282e7cf59190b92d19fa44d6f6a35c91772924`。
- `finexy-1.9.3-3-url-x86_64.fpk`：入口已改 `url`，但版本带 `-3` 构建号；当前包是其内容等价、版本号改回 `1.9.3` 的重打包。SHA-256 `4922f63b15aaba69b5bc6c00dfd822a4762f5440206962d2d59b6abdaf221786`。

以上三个包均不发布，仓库 `release/fnos/SHA256SUMS.txt` 只列实际发布的 Release 资产。

## 规范依据（官方文档）

- 应用框架与生命周期脚本、退出码：<https://developer.fnnas.com/docs/core-concepts/framework/>
- manifest 字段：<https://developer.fnnas.com/docs/core-concepts/manifest/>
- 权限 `config/privilege`：<https://developer.fnnas.com/docs/core-concepts/privilege/>
- 资源 `config/resource`：<https://developer.fnnas.com/docs/core-concepts/resource/>
- 环境变量 `TRIM_*`：<https://developer.fnnas.com/docs/core-concepts/environment-variables/>
- 安装/升级/卸载/配置向导：<https://developer.fnnas.com/docs/core-concepts/wizard/>
- 图标要求：<https://developer.fnnas.com/docs/core-concepts/icon/>
- 桌面入口 `app/ui/config`：<https://developer.fnnas.com/docs/core-concepts/app-entry/>
- `fnpack` 下载与打包校验规则：<https://developer.fnnas.com/docs/cli/fnpack/>

`manifest` 中的 `checksum` 字段未出现在官方 manifest 文档中；该字段由 `fnpack` 1.2.3 打包时自动写入，实测为 `app.tgz` 的 MD5，本包已校验一致。

## 构建与产物来源

- 后端二进制取自仓库已有的发布产物 `release/v1.9.3/Finexy-NAS-1.9.3-linux-amd64.tar`（`docker save` 格式），载入后从镜像 `ph97/finexy-bookkeeping:1.9.3-amd64` 中取出。
- 二进制为 `ELF 64-bit LSB executable, x86-64, statically linked, stripped`，`--version` 输出 `Finexy version 1.9.3 (commit bb4308b)`。
- 前端静态文件、`templates/`、`conf/ezbookkeeping.ini`、`LICENSE` 同样取自该镜像。
- 打包工具为官方 `fnpack` 1.2.3 linux-amd64，命令：`cd packaging/fnos/finexy && fnpack build`（在应用源目录内执行）。
- 未在 Windows 上交叉编译 CGO SQLite；二进制来自 Linux/amd64 容器构建，保持 CGO `mattn/go-sqlite3` 能力，SQLite 功能未降级。
- 说明：当前分支 HEAD 比该镜像的二进制提交 `bb4308b` 多出 2 个后端提交（AI 设置与私有模型计量、F03-F08 相关实现）。本包未包含这些改动，仍为 1.9.3 发布镜像内容。

## 真机安装失败复盘（初版 1.9.3，已修复）

现象：在真实 fnOS 应用中心安装**初版 1.9.3** 时失败并回滚。当前发布的 `finexy-1.9.3-x86_64.fpk` 已是修复后的重打包，版本号按用户要求仍为 `1.9.3`；两者版本字符串相同，但内容不同，需以本文记录的 sha256 区分。

日志原文（用户 2026-11-03 15:58 导出的 Debug Log）：

```
# system/apps/finexy.log（全文仅 3 行）
mkdir: cannot create directory '/vol1/@appdata/finexy': Permission denied
mkdir: cannot create directory '/vol1/@appdata/finexy': Permission denied
mkdir: cannot create directory '/vol1/@appdata/finexy': Permission denied

# system/trim_app_center/info.log
msg="install app" appName=finexy class=install reqid=1793692637454965209 version=1.9.3
msg="app package contents" appName=finexy dirs="[cmd config wizard]" files="[ICON.PNG ICON_256.PNG LICENSE app.tgz manifest]" flag=install_start module=security version=1.9.3
msg=rollback appName=finexy class=install reqid=1793692637454965209 version=1.9.3

# system/trim_app_center/error.log
msg="install error" appName=finexy class=install error="10237:" reqid=1793692637454965209 text= version=1.9.3
msg="response error: &vo.Result{Code:10237, Msg:\"\", Data:(*vo.Status)(0xc0011b9cc0)}, err=<nil>"

# system/syslog
TRIMEVENT:{"data":{"APP_NAME":"finexy",...,"VOLUME":0},"eventId":"APP_INSTALL_FAILED_INSTALL_INIT_EXCEPTION",...}

# 事件日志 CSV
错误,全部,2026-11-03 15:57:23,system,应用 Finexy 记账 安装失败。原因：执行应用初始化脚本异常。
```

根因：`cmd/install_init` 在 fnOS 创建 `/vol1/@appdata/finexy` **之前**执行，且以 package 用户身份运行。
脚本里的 `mkdir -p "$TRIM_PKGVAR/data" "$TRIM_PKGVAR/log" "$TRIM_PKGVAR/storage"` 需要先创建父目录
`/vol1/@appdata/finexy`，而 `/vol1/@appdata` 归 root 所有、package 用户不可写；`set -e` 使脚本以非 0 退出，
应用中心判定 `APP_INSTALL_FAILED_INSTALL_INIT_EXCEPTION`（10237）并回滚。

已排除的项（均有证据）：

- CRLF：对包内 `cmd/*` 实测 `grep -c $'\r'` 全部为 0，`od -c` 显示首行以 `#!/bin/bash\n` 结束，为纯 LF。
- shebang 与可执行位：`#!/bin/bash` 存在（脚本确实被执行并产生 mkdir 报错），解包后 `cmd/*` 为 0755。
- 包格式/manifest/图标/checksum：应用中心已接受包内容并进入 `install_start`，无签名、架构、图标、端口失败记录；
  `checksum` 字段与 `app.tgz` 的 MD5 一致；图标实测 64×64 与 256×256。
- 端口与版本兼容：日志中没有 `service_port` 冲突或版本拒绝记录（`INSTALL_VOLUME_ID=0` 的含义日志未给出，未能确认）。

修复点：

- `cmd/install_init` 改为完全无写操作（仅注释 + `exit 0`），不再在安装阶段触碰 `@appdata`/`@appconf`。
- `cmd/main start` 新增 `ensure_runtime`：首次启动时创建 `var/{data,log,storage}` 与 `etc/finexy.ini`
  （随机 64 位 hex `secret_key`、绝对路径、日志轮转、权限 0600）；启动失败信息写入 `TRIM_TEMP_LOGFILE`。
- `cmd/upgrade_init` 同样改为无副作用，避免升级因同类权限问题失败。
- `manifest` 版本号：修复过程中曾用 `1.9.3-2`、`1.9.3-3` 这类“数值段-构建号”格式（本机日志中官方应用也有
  `0.9.4-5`、`0.9.7-1` 的写法），用于区分不同的修复构建。最终发布按用户要求改回纯 `1.9.3`，不带构建号后缀。
  **注意**：纯 `1.9.3` 与已安装在设备上的 `1.9.3-2` 在版本比较上存在被判定为“降级”的风险，见下文
  「版本比较与降级风险」。

## 包结构

```
finexy/
├── manifest
├── ICON.PNG                 # 64x64 PNG
├── ICON_256.PNG             # 256x256 PNG
├── LICENSE
├── app/
│   ├── finexy/
│   │   ├── ezbookkeeping        # 静态链接 linux/amd64 二进制
│   │   ├── public/              # Web/PWA 静态资源
│   │   ├── templates/           # 邮件/LLM 模板
│   │   ├── conf/finexy.ini.template
│   │   └── LICENSE
│   └── ui/
│       ├── config               # 桌面入口
│       └── images/icon_64.png、icon_256.png
├── cmd/
│   ├── main                     # start / stop / status
│   ├── install_init / install_callback
│   ├── upgrade_init / upgrade_callback
│   ├── uninstall_init / uninstall_callback
│   └── config_init / config_callback
├── config/
│   ├── privilege                # run-as=package，用户/组 finexy
│   └── resource                 # {}，不声明额外系统资源
└── wizard/
    └── install                  # 安装说明（tips）
```

`fnpack build` 会把 `app/` 打成 `app.tgz` 放入 FPK，最终 `.fpk` 为 gzip 压缩的 tar 包。

## 生命周期脚本

| 脚本 | 职责 | 返回值 |
| --- | --- | --- |
| `main start` | 首次启动时创建 `var/{data,log,storage}` 与 `etc/finexy.ini`（随机 64 位 hex `secret_key`、绝对路径），再后台启动服务并写 PID 文件 | 0 成功，1 失败 |
| `main stop` | 向 PID 发送 TERM，最多等待 10 秒后 KILL | 0 |
| `main status` | PID 存活返回 0，否则 3 | 0 / 3 |
| `install_init` | 无写操作（仅 `exit 0`）；安装阶段系统尚未创建 `@appdata`/`@appconf`，不能在此建目录 | 0 |
| `upgrade_init` | 无写操作，不覆盖配置与数据 | 0 |
| `uninstall_init` | 调用 `main stop`，避免 SQLite 写入中断 | 0 |
| `uninstall_callback` | 不删除应用数据目录 | 0 |
| `install_callback` / `upgrade_callback` / `config_init` / `config_callback` | 预留钩子，当前无额外动作 | 0 |

官方文档仅明确 `cmd/main` 接收 `start|stop|status` 参数；安装/升级/卸载/配置脚本未定义参数，本包按无参处理。

## 运行时路径、端口与配置

- 安装后由 fnOS 生成：`/var/apps/finexy/{target,etc,var,tmp,home}`，分别软链到 `/volN/@appcenter`、`@appconf`、`@appdata`、`@apptemp`、`@apphome`。
- 数据库：`$TRIM_PKGVAR/data/ezbookkeeping.db`（SQLite，CGO）。
- 附件：`$TRIM_PKGVAR/storage/`。
- 日志：`$TRIM_PKGVAR/log/ezbookkeeping.log`（应用日志，开启轮转）与 `$TRIM_PKGVAR/log/app.log`（启动/停止记录）。
- 配置：`$TRIM_PKGETC/finexy.ini`（首次 `main start` 时生成，权限 0600）。
- 端口：`8080`（manifest `service_port`、`app/ui/config` 入口、配置 `http_port` 一致）。
- 时区：`TZ` 环境变量优先，未设置时默认 `Asia/Shanghai`。
- 不声明 `data-share`，因此不会额外创建用户可见共享目录；账本数据位于应用数据卷。

## 首次安装后的初始化

1. 在 fnOS 应用中心安装 FPK，选择存储空间。
2. 点击“Finexy 记账”桌面图标：从 1.9.3（入口 `type=url`）起在浏览器新标签页打开 `http://<NAS地址>:8080/`；也可手动输入该地址。
3. 在登录页注册账号。Finexy 没有预置管理员账号，首个注册用户即日常使用者。
4. 如需关闭后续自助注册，编辑 `/volN/@appconf/finexy/finexy.ini`（应用配置目录）将 `enable_register` 改为 `false`，然后在应用中心停止/启动应用。
5. 使用反向代理或非默认域名时，修改 `finexy.ini` 中的 `domain` / `root_url`。

## 桌面入口：内嵌窗口与浏览器标签页

官方依据：<https://developer.fnnas.com/docs/core-concepts/app-entry/>。该文档明确 `type` 字段：

- `iframe`：在飞牛 fnOS 桌面窗口内打开（内嵌）。
- `url`：在浏览器标签页或外部 Web 视图中打开。

`port` 为服务端口（可用 `${wizard_port}` 引用向导收集的端口），`url` 为入口路径（可用 `${wizard_path}`）。
本包未在 `wizard/install` 中收集端口/路径，因此直接写 `"port": "8080"`、`"url": "/"`；
host 由 fnOS 按当前访问地址拼接，配置里不出现 IP 或 localhost。

当前入口（`app/ui/config`）：

```json
{
  ".url": {
    "finexy.main": {
      "title": "Finexy 记账",
      "icon": "images/icon_{0}.png",
      "type": "url",
      "protocol": "http",
      "port": "8080",
      "url": "/",
      "allUsers": true
    }
  }
}
```

两种方式的差异：

| 方式 | 用户看到的效果 | 适用场景 |
| --- | --- | --- |
| `iframe`（历史包 1.9.3-2） | 在 fnOS 桌面弹出的窗口内嵌页面，地址栏不可见，受桌面容器尺寸/权限限制 | 希望留在 NAS 桌面内、入口轻量 |
| `url`（当前 1.9.3） | 默认浏览器新标签页打开 `http://<当前 NAS 地址>:8080/`，有完整地址栏、书签、下载、PWA 安装能力 | 需要完整浏览器能力、可加书签、移动端/远程访问更自然 |

说明与未确认项：

- 一个应用可以定义多个入口（不同 ID 可分别用 `iframe`/`url`）。本包只保留一个 `finexy.main`，所以桌面上只会有一个图标，点击即走浏览器。
- `url` 模式打开的主机名由 fnOS 系统决定（按当前访问地址拼接），不是包内写死；远程/FN Connect 场景下究竟拼成局域网 IP 还是当前访问域名，尚未在真机验证。
- 若桌面仍显示内嵌窗口，先确认安装的确实是本包（`manifest` version 为 `1.9.3`，且 sha256 与本文一致），
  再重启桌面/重新登录；入口配置在安装时注册，升级后一般立即生效。

## 升级与卸载

- 升级：`upgrade_init` 不触碰 `etc/` 与 `var/`，程序文件替换在 `target/`，数据库与附件保留；启动时由应用自身执行数据库迁移。
- 从历史测试包（`1.9.3-2` / `1.9.3-3`）覆盖安装本包：入口配置只是 `app/ui/config`，升级不会改 `etc/finexy.ini` 与 `@appdata` 数据。
  应用中心升级流程通常会先停止应用再替换文件（`manifest` 为 `ctl_stop=true`）；为稳妥可手动停止后再装，数据不会丢失。
  若升级后桌面图标仍是内嵌窗口，重新登录桌面或重启 NAS 桌面进程刷新入口缓存。
- 卸载：脚本不删除 `var/`，但 fnOS 应用中心是否移除 `@appdata` 目录未在官方文档中明确，卸载前请备份 `ezbookkeeping.db` 与 `storage/`。

## 版本比较与降级风险

**官方依据**：manifest 文档 <https://developer.fnnas.com/docs/core-concepts/manifest/> 对 `version` 只给出示例
`1.0.0`、`2.1.3-beta`，**没有给出任何版本比较、升级或降级判定规则**；公开帮助文档中也未检索到相关说明。
因此以下是**推断，未经真机验证**：

- 若 fnOS 按 SemVer 语义比较，`1.9.3-2` 是 `1.9.3` 的预发布版本，则 `1.9.3-2 < 1.9.3`，覆盖安装不会被拒。
- 若 fnOS 把“数值段相同、带 `-N` 后缀”视为更高构建号，则 `1.9.3-2 > 1.9.3`，覆盖安装可能被拒为降级。

当前设备上装的是 `1.9.3-2`，所以第二种情况是真实风险。

**建议安装路径**：

1. **优先直接覆盖安装**：在应用中心上传 `finexy-1.9.3-x86_64.fpk`。若接受并安装/升级成功，即完成；此路径只替换
   `target/` 程序文件，不改 `@appconf/finexy/finexy.ini` 与 `@appdata` 数据。
2. **若被拒为降级**（提示版本过低/不允许降级/安装失败）：先备份，再卸载重装。
   - 卸载前必须备份：`/volN/@appdata/finexy/data/ezbookkeeping.db`、整个 `/volN/@appdata/finexy/storage/`，
     以及 `/volN/@appconf/finexy/finexy.ini`（含 `secret_key`，丢失会导致已有登录态/加密数据不可用）。
   - 在应用中心卸载。卸载脚本只调用 `main stop`，不主动删数据；但**应用中心是否会清除 `@appdata` 尚未确认**，
     所以备份是必须的，不能假设数据一定保留。
   - 重新安装本包 → 把备份的 `ezbookkeeping.db`、`storage/` 放回 `@appdata/finexy/`，`finexy.ini` 放回 `@appconf/finexy/`，
     属主保持 `finexy:finexy`，再在应用中心启动。
3. 若希望规避降级比较，可改用更高构建号（如 `1.9.3-4`）；本包按用户要求固定为纯 `1.9.3`，不提供该变体。

## 真机安装注意事项

- 升级到 1.9.3 前，若装过失败的初版 1.9.3（`5bd67e…`，已归档），无需手动清理：该版本已回滚，不会残留应用目录。
  两者版本串相同，安装前请核对 sha256，不要误装归档里的初版。
- 安装前确认 `8080` 未被占用（`manifest` 中 `checkport=true`），否则应用中心会以端口冲突拒绝。
- 首次安装只落地程序文件；`var/{data,log,storage}` 与 `etc/finexy.ini` 在**第一次启动时**创建。
  因此安装完成后需在应用中心启动一次，看到状态变为“运行中”再访问 `http://<NAS地址>:8080/`。
- 诊断顺序：安装失败先看 `/var/log/apps/finexy.log`（脚本输出，本次失败的证据就在这里），
  再看应用中心日志与 `TRIM_TEMP_LOGFILE`；启动失败看 `@appdata/finexy/log/app.log`。
- 不要手工在 `@appdata` 或 `@appconf` 下预建目录并改属主，目录由系统在安装时创建、由应用以 package 身份写入。
- 卸载前备份 `@appdata/finexy/data/ezbookkeeping.db` 与 `storage/`；卸载脚本本身不删除这些数据。
- 版本号采用纯 `1.9.3`；`-2`、`-3` 只是修复过程中的本地临时构建号，归档在 `release/fnos/archive/`，不作为 Release 资产发布。
  若未来升级到新二进制版本，直接用 `1.9.4` 等更高数值段即可。

## 已验证 / 未验证

已验证：

- `fnpack build` 通过其内置检查并生成 `.fpk`。
- `tar -tzf` 可正常解开；`cmd/*` 权限为 0755，其余 0644；manifest 含 fnpack 自动添加的 `checksum`。
- `checksum` 与 `app.tgz` 的 MD5 一致（`1f514d5fb8a73617d64f1619e7db1849`）。
- 包内 `cmd/*` 实测无 CRLF（`grep -c $'\r'` 全为 0），首行为 `#!/bin/bash\n`；`app/ui/config` 无 CRLF；图标 64×64 与 256×256。
- 解包确认 `app/ui/config` 为 `"type": "url"`、`"protocol": "http"`、`"port": "8080"`、`"url": "/"`，无写死 IP。
- 在 `alpine:3.24.1` 容器中以非 root 用户模拟 fnOS 环境回归：
  1）`/vol1/@appdata` 归 root、`finexy` 目录不存在时执行 `install_init`，返回 0 且未创建任何目录（复现原失败条件）；
  2）系统创建 `@appdata/finexy` 与 `etc` 后执行 `main start`，自动生成 `finexy.ini`（0600）与数据目录，
  `GET /healthz.json` 返回 `{"status":"ok","version":"1.9.3"}`，`GET /` 返回 200；`status` 返回 0，`stop` 后返回 3，SQLite 数据库文件已创建。

未验证 / 需以官方文档或真机为准：

- 本包（纯 `1.9.3`，入口 `url`）尚未在真实 fnOS 设备上验证“点击图标是否确实打开浏览器新标签页”，也未验证远程访问时 `url` 模式拼接的主机名。
- 从已安装的 `1.9.3-2` 覆盖安装纯 `1.9.3` 是否会被应用中心判为降级，未在真机验证（版本比较规则官方未公开，见上节）。
- 错误码 `10237` 的具体语义、`INSTALL_VOLUME_ID=0` / `VOLUME=0` 的含义，日志中未直接说明。
- 容器测试假设 fnOS 会把 `@appdata/finexy`、`@appconf/finexy` 创建为 package 用户可写；该点未在真机确认，但 `install_init` 不再依赖它。
- 卸载时应用中心是否保留 `@appdata` 数据。
- 本包未声明 `os_min_version`，未在具体 fnOS 版本上验证兼容范围。
- 图标为仓库现有 512px 图标缩放生成，符合尺寸/格式要求，但视觉效果未在 fnOS 桌面实际预览。
- 当前分支较镜像提交更新的后端功能未包含在本包内。
