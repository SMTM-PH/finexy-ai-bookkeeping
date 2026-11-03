# 账户艺术卡片

> 交付日期：2026-10-03。适用于桌面 Web 的总览钱包与账户管理。最终横向卡面验收及 NAS 部署状态见末节；不包含原生 Android 改版。

## 展示与操作

- 卡面采用约 **1.586:1 的横向银行卡比例**，以原创插画、品牌象征图案、金属边缘和随视角变化的光泽展示账户。正反面均不添加可见文字。
- 名称、账户类型、币种、完整有符号余额与状态放在卡片外；管理页右侧保留实际账户详情和查看流水等操作。翻面后的完整备注也在卡片外显示。
- 账户管理提供钱包式叠放预览；选择缩略卡片会同步当前艺术卡、账户选择器及详情。上下键、Home/End 可切换，列表内部滚动保持选中项可见。
- 首页每次展示一个账户，沿用左右翻页、查看详情、金额隐藏、翻面及复位操作；不自动轮播。
- 管理页主卡宽约 380px，首页卡宽约 260px；两处保持横向比例，完整金融文字不挤入插画区域。
- 默认品牌图案仅用于视觉识别，不能用名称匹配来映射账户、分类或交易。未知品牌沿用实际账户图标。
- 已保存的自定义背景和主体优先，保留原位置、缩放和材质。换账户、换账本、失败重试及两设备冲突仍遵循原卡面持久化流程。
- 光泽为零时关闭镭射、高光与粒子；减少动态效果时固定视角并停用过渡。金额隐藏同时作用于外部说明和详情，无障碍名称不包含金额。

用户提供的收藏卡、钱包叠放和实体银行卡照片作为构图与比例参考。默认卡面使用本次生成的原创插画；品牌图案是应用中的艺术表达，不代表银行发行或认证。

## 三套原创插画

| 卡面 | 主体 | 环境与色彩 |
| --- | --- | --- |
| 月夜 / Moon Garden | 银发月夜守护者、白鹤羽翼、月兔、朱红缎带 | 靛蓝湖面、弯月、红叶与金色星屑 |
| 锦鲤 / Golden Koi | 朱金与象牙色双锦鲤、卷曲长鳍、水花 | 青绿水纹、金箔与月夜环境 |
| 青竹 / Jade Fox | 象牙色灵狐、青绿尾羽、竹叶 | 矿物青绿、古金与靛蓝背景 |

原始 PNG 为 1024×1536；主体拥有真实透明 Alpha。生产页面通过背景与主体分层排版适配横向卡面，不向图片添加账户文字。不同品牌和账户类型选择默认装饰，账户财务数据保持原合同。

## Holo Card Studio 制作文件

已安装并实际执行 [Holo Card Studio](https://github.com/EverettFish/holo-card-studio) 的 **holographic** 路线。制作目录为 `artifacts/holo-art-studio/`，不把生成的二进制文件写入可复用技能目录。

| 文件 / 目录 | 内容 |
| --- | --- |
| `assets/` | 原始背景、三套透明主体、月夜描线和无文字图形徽章 |
| `card-config.json` | 月夜主场景参数；`text.png` 插槽使用透明图形徽章 |
| `card.blend`、`koi.blend`、`jade.blend` | Blender 分层主场景及两套主体变体 |
| `renders/` | 主场景正面、倾斜角度、锦鲤与青竹渲染、浏览器截图 |
| `web/assets/card.glb` | 导出的卡片实体几何 |
| `web/` | 本机 Three.js 查看器与图片导出；三套主体可选择 |
| `validation-report.md` | 制作文件、Alpha、场景、渲染和查看器验证及限制 |
| `prompts.md` | 图像生成方式、主体约束和可复用提示词 |
| `LICENSE-holo-card-studio.txt` | 上游 MIT 许可 |

制作场景保留收藏卡竖版比例，作为可编辑源文件。应用中的横向账户卡是这些原始分层插画的另一种排版；独立查看器不等同于 Finexy 财务页面。

本机查看器启动方式：

```powershell
$env:PORT = '18119'
node artifacts/holo-art-studio/web/server.mjs
```

打开 `http://127.0.0.1:18119`。这是需本机进程运行的制作预览，不是 NAS 上的另一项服务。

## 生产接入

| 源码 | 职责 |
| --- | --- |
| `src/assets/account-art/` | 四张随应用打包的 WebP 背景 / 主体，保留原图尺寸与透明度 |
| `src/lib/ui/account_art.ts` | 仅选择默认艺术风格与品牌图案，不参与财务关联 |
| `src/components/desktop/AccountBrandMark.vue` | 无文字品牌象征图案 |
| `src/components/desktop/AccountHoloCard.vue` | 艺术正背面、CSS 透视 / 光泽、外部财务说明 |
| `src/components/desktop/AccountWalletStack.vue` | 账户管理中的叠放预览与键盘选择 |
| `src/views/desktop/FinexyWorkspacePage.vue` | 真实账本 / 账户选择、首页与详情联动 |
| `vite.config.ts` | 将 WebP 发到现有 `/img/` 静态资源路径 |

Finexy 生产页面使用本地 WebP、Vue 与 CSS；不引入 Three.js、Blender 或远程图像生成服务作为运行时依赖。独立制作查看器使用其本地 Three.js 依赖。

## 验收与部署记录

### 已确认的制作证据

- `pipeline.txt` 记录了 Blender 4.5.14 LTS 制作、保存、渲染、GLB 导出和查看器依赖安装。
- `asset-validation.json` 确认主体真实透明度；`physical-verification.json` 确认 5 个实体网格与打包图片。
- `browser-validation.json` 和 `viewer-validation-final.txt` 确认 GLB、三套主体选择、快速切换丢弃旧响应、描线只用于对应主体，以及拖动、翻面 / 复位、滑杆、旋转、导出和 1440/768/375px 查看器；没有水平溢出和应用错误。
- `artifacts/agent-access/art-cards-final-check.txt` 记录最终源码的类型检查、Lint、18 个独立测试文件 / 38,508 个断言及生产构建通过；最后的窄屏宽度修正由 `art-cards-final-build.txt` 重建，并通过实际浏览器复核。
- `artifacts/agent-access/art-cards-artwork-final.json` 记录了最终艺术专项通过：10 类品牌、3 套插画、无卡面字体、本地图片成功解码、外部金融说明、约 1.586 的实际比例，说明文字对比度约 18.41:1。
- 同一艺术专项确认叠放钱包的 10 个字符串账户 ID、选择器与详情同步、上下键 / Home / End、内部滚动、至少 44px 可点击露出区域及筛选重置；账户余额未改变，浏览器应用错误为空。
- 最终候选通过钱包 9 组、账户工作区 10 组、卡面设计器 11 组，以及 10 类品牌与叠放导航专项。证据为 `art-cards-{wallet,workspace,designer,artwork}-landscape-gate.txt`。保存、共享隔离、409 冲突、失败草稿、重启保留、账本切换、金额隐藏与财务数据不变均有专项断言。
- `art-cards-local-cold-start.txt` 确认桌面和移动 Web 从新浏览器上下文冷启动成功，前后端均为 1.9.3，无浏览器错误；未用热更新代替生产验证。

### 最终状态

**最终 Web 候选与独立制作查看器已通过相关门禁。** 管理页大卡片最大 380px，首页最大 260px；375px 视口下按 `100vw - 48px` 限制宽度，艺术卡、翻面及编辑操作保持可达。

**NAS 已于 2026-10-03 部署并完成验证。**

| 项目 | 实际结果 |
| --- | --- |
| 地址 | http://192.168.31.184:8080/desktop#/account/list |
| 版本 | 1.9.3；沿用已验收后端，更新本轮 Web 静态资源 |
| 新容器 | `finexy-bookkeeping-art-cards-1-9-3` |
| 镜像 | `ai-bookkeeping-bookkeeping:art-cards-candidate` |
| 回退容器 | `finexy-bookkeeping-transaction-layout-1-9-3`，已停止并保留 |
| 备份 | `/vol2/1000/Docker/finexy-import-20261001/backup/art-cards-20261003-101225`，包含数据库、附件、日志及权限受限的容器配置 |
| 数据 | SQLite 完整性 `ok`，原财务行及附件哈希一致 |
| 配置 | 原环境、8080 端口及 data/storage/log 三处挂载完整沿用 |
| 静态资源 | 桌面/移动入口、对应 JS/CSS 和四张 WebP 的 HTTP、MIME 与 SHA256 已核对 |
| 浏览器 | NAS 桌面和移动 Web 在新浏览器上下文冷启动成功；无浏览器错误 |

证据：`art-cards-nas-deploy-final.json`、`art-cards-nas-image-final.json`、`art-cards-nas-online-final.json` 和 `art-cards-nas-cold-start.txt`。本机 Docker 的 OCI 清单摘要与 NAS 的配置摘要不同，使用归档配置 SHA256、镜像层和配置逐项核对，未跳过镜像一致性校验。部署临时传输文件及一次性验收容器已清理。

回退时先停止新容器并关闭其自动重启，再恢复旧容器原有重启策略并启动旧容器；两者不得同时占用 8080。静态资源回退继续使用当前挂载数据，不自动覆盖部署后新增账目；备份用于需要数据恢复时的另行核对。

浏览器如保留旧缓存，可使用 Ctrl+F5 强制刷新。

本轮没有 Go、原生 Android 或 Electron 专项运行结果；也没有实际触摸设备的验收证据。不能用桌面浏览器视口模拟结果替代这些验收。
