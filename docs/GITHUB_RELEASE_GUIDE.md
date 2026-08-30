# GitHub 代码更新与 Release 发布指南

本文是 Finexy 后续更新 GitHub 代码和发布版本的标准流程。仓库地址：
`https://github.com/SMTM-PH/finexy-ai-bookkeeping`。

## 1. 发布原则

- `main` 始终保持可发布；禁止直接推送和强制推送。
- 所有代码和版本文件变更都通过短期分支、Pull Request、CI 和 Squash Merge 进入 `main`。
- 版本使用语义化版本 `主版本.次版本.修订版本`；Git 标签使用 `v` 前缀，例如 `v1.7.3`。
- 标签一经发布不得移动或覆盖。发布出错时修复代码并发布新版本。
- GitHub Release 必须对应 `main` 上已通过 CI 的提交，并附带 `SHA256SUMS.txt`。
- Windows 安装包未使用商业代码签名时，发行说明必须提示 SmartScreen 风险。

## 2. 开始更新代码

先同步 `main`，再创建短期分支：

```powershell
git switch main
git pull --ff-only origin main
git switch -c feat/short-description
```

分支前缀按改动类型选择：`feat/`、`fix/`、`docs/`、`chore/`、`refactor/`、`test/` 或 `ci/`。

完成修改后检查改动，确认没有提交密钥、个人数据、日志或临时构建文件：

```powershell
git status --short
git diff --check
git diff
```

## 3. 本地验证并上传代码

常规更新至少运行：

```powershell
npm ci
npm run check
go vet ./...
go test ./...
```

涉及 Docker 或 NAS 时另外运行：

```powershell
docker compose config
docker compose build
```

提交并推送分支：

```powershell
git add <本次修改的文件>
git commit -m "type: concise description"
git push -u origin HEAD
gh pr create --fill --base main
```

等待 GitHub Actions 的 `Quality gate` 通过并完成审核，然后使用 Squash Merge：

```powershell
gh pr checks --watch
gh pr merge --squash --delete-branch
```

## 4. 准备版本

版本发布本身也通过 `chore/release-X.Y.Z` 分支和 PR 完成。至少同步以下内容：

- 根目录 `package.json` 与 `package-lock.json` 的版本。
- `desktop/package.json` 与 `desktop/package-lock.json` 的版本。
- `CHANGELOG.md`：发布日期、用户可见变更和必要的升级说明。
- `README.md`：Release 附件名、Docker 拉取命令。
- `deploy/nas-amd64/compose.yaml` 与其 `README.md` 中的镜像和附件版本。

检查是否还有旧版本残留：

```powershell
rg -n "上一版本号" README.md CHANGELOG.md package.json package-lock.json desktop deploy docs
```

版本 PR 合并后重新同步，并记录待发布提交：

```powershell
git switch main
git pull --ff-only origin main
git status --short --branch
git rev-parse HEAD
```

工作区必须干净，且本地 `main` 必须与 `origin/main` 一致。

## 5. 构建发布文件

Windows x64 安装版与便携版：

```powershell
npm ci
npm run check
npm run desktop:build
```

输出位于 `release/windows/`。仅上传以下两个文件：

- `Finexy-Windows-X.Y.Z-x64-Setup.exe`
- `Finexy-Windows-X.Y.Z-x64-Portable.exe`

构建 NAS AMD64 主服务镜像：

```powershell
docker build --platform linux/amd64 `
  --build-arg RELEASE_BUILD=true `
  -t ph97/finexy-bookkeeping:X.Y.Z-amd64 `
  -t ph97/finexy-bookkeeping:latest-amd64 .
```

确认 OCR 镜像 `ph97/finexy-bookkeeping:ocr-1.0-amd64` 已存在，然后导出两个离线镜像：

```powershell
docker image inspect ph97/finexy-bookkeeping:X.Y.Z-amd64
docker image inspect ph97/finexy-bookkeeping:ocr-1.0-amd64
docker save -o release/github/Finexy-NAS-X.Y.Z-linux-amd64.tar `
  ph97/finexy-bookkeeping:X.Y.Z-amd64 `
  ph97/finexy-bookkeeping:ocr-1.0-amd64
```

生成 NAS 在线部署 ZIP，只包含部署所需文件，不包含真实 `.env`：

```powershell
Compress-Archive -Force `
  -Path deploy/nas-amd64/compose.yaml,deploy/nas-amd64/.env.example,deploy/nas-amd64/README.md `
  -DestinationPath release/github/Finexy-NAS-X.Y.Z-amd64-deploy.zip
```

把 Windows 文件复制到 `release/github/`，然后生成校验文件：

```powershell
Copy-Item release/windows/Finexy-Windows-X.Y.Z-x64-Setup.exe release/github/
Copy-Item release/windows/Finexy-Windows-X.Y.Z-x64-Portable.exe release/github/

Get-ChildItem release/github/Finexy-* | ForEach-Object {
    $hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $_.FullName).Hash.ToLowerInvariant()
    "$hash  $($_.Name)"
} | Set-Content -Encoding ascii release/github/SHA256SUMS.txt
```

如果需要同步 Docker Hub，在发布 GitHub Release 前执行并确认推送成功：

```powershell
docker push ph97/finexy-bookkeeping:X.Y.Z-amd64
docker push ph97/finexy-bookkeeping:latest-amd64
```

## 6. 创建标签与 GitHub Release

再次确认 CI、提交、版本和附件：

```powershell
gh run list --branch main --limit 5
git status --short --branch
git log -1 --oneline
Get-FileHash -Algorithm SHA256 release/github/Finexy-* 
```

创建注释标签并推送：

```powershell
git tag -a vX.Y.Z -m "Finexy X.Y.Z"
git push origin vX.Y.Z
```

先准备 `release-notes-X.Y.Z.md`，内容至少包括主要变化、下载说明、Docker 镜像、升级注意事项和校验提示。然后发布：

```powershell
gh release create vX.Y.Z `
  release/github/Finexy-Windows-X.Y.Z-x64-Setup.exe `
  release/github/Finexy-Windows-X.Y.Z-x64-Portable.exe `
  release/github/Finexy-NAS-X.Y.Z-amd64-deploy.zip `
  release/github/Finexy-NAS-X.Y.Z-linux-amd64.tar `
  release/github/SHA256SUMS.txt `
  --repo SMTM-PH/finexy-ai-bookkeeping `
  --title "Finexy X.Y.Z" `
  --notes-file release-notes-X.Y.Z.md `
  --verify-tag `
  --latest
```

## 7. 发布后验证

```powershell
gh release view vX.Y.Z --repo SMTM-PH/finexy-ai-bookkeeping
gh release view vX.Y.Z --repo SMTM-PH/finexy-ai-bookkeeping --json assets,url
git ls-remote --tags origin vX.Y.Z
```

逐项确认：

- Release 页面显示为 Latest，且不是 Draft/Prerelease。
- 标签指向发布时记录的 `main` 提交。
- 五个附件均存在，文件名和版本一致。
- 下载后的文件与 `SHA256SUMS.txt` 一致。
- Windows 安装版和便携版可以启动。
- NAS 部署 ZIP 中没有真实凭据，`docker compose config` 可以通过。
- Docker Hub 的版本标签和 `latest-amd64` 指向预期镜像。

## 8. 异常处理

- Release 说明或附件错误：使用 `gh release edit` 或 `gh release upload --clobber` 修正，不移动标签。
- 标签已推送但 Release 创建失败：修复附件或说明后，继续用同一标签执行 `gh release create`。
- 已发布版本存在代码缺陷：不要覆盖原标签；从最新 `main` 修复并发布下一个补丁版本。
- 任何凭据误传：立即撤销或轮换凭据，再按安全事件处理；仅删除 GitHub 文件不能使凭据恢复安全。
