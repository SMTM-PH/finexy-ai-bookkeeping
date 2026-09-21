# Finexy 官网（GitHub Pages）

静态单页官网，无构建步骤，部署在 GitHub Pages：
https://smtm-ph.github.io/finexy-ai-bookkeeping/

## 本地预览

```bash
cd website
python -m http.server 8090
# 或任意静态服务器，然后访问 http://localhost:8090/
```

## 发布

推送到 `main` 且改动 `website/**` 时，`.github/workflows/website.yml`
会自动把本目录发布到 GitHub Pages（仓库 Settings → Pages → Source 为
GitHub Actions）。也可在 Actions 页面手动触发 "Deploy Website"。

## 结构

- `index.html`：单页内容（功能 / Web 界面 / Android / 多端联动 / AI 接入 / 部署 / 安全与隐私 / 下载）
- `assets/css/style.css`：品牌样式（珊瑚橙 `#F05537`、墨黑 `#12141A`，与 `docs/UI_UX_DESIGN.md` 一致）
- `assets/js/main.js`：移动端菜单、代码复制、进场动画
- `assets/img/`：产品截图与图标（Web 截图来自 `docs/screenshots/`，Android 截图来自 `artifacts/android-ui/` 中无真实账本数据的空态/演示页，图标来自 `public/`）

## 注意事项

- **隐私红线**：不得使用包含真实账本数据的截图（如 `artifacts/android-ui/b4-final-main.png` 等
  含主账本金额与流水描述的图片）。新增截图前先人工确认内容为空态、全零或明确标注"演示数据"。
- AI 客户端相关文案（OpenClaw 龙虾 / Hermes Agent / Codex / 微信一句话记账）以 MCP 接口能力为准，
  不要写死"官方合作"等未经证实的表述。

文案与版本号需与根目录 `README.md` 保持同步（如版本号、Docker 镜像名、下载文件名）。
