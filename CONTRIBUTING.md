# Contributing to Finexy

感谢参与 Finexy。仓库采用受保护的 `main` 分支和 Pull Request（PR）协作流程。

## 分支策略

仓库采用 GitHub Flow，只保留一个长期分支 `main`。`main` 始终代表通过 CI、可发布和可部署的代码；日常开发使用短期分支，不维护容易与生产状态长期偏离的 `develop` 分支。

| 分支 | 用途 | 生命周期 |
| --- | --- | --- |
| `main` | 稳定、可发布代码 | 长期保留并受保护 |
| `feat/*` | 新功能 | PR 合并后自动删除 |
| `fix/*` | 缺陷和线上热修复 | PR 合并后自动删除 |
| `docs/*` | 文档 | PR 合并后自动删除 |
| `chore/*`、`refactor/*`、`test/*`、`ci/*` | 维护、重构、测试和 CI | PR 合并后自动删除 |
| `dependabot/*` | 自动依赖更新 | 由 Dependabot 创建，PR 关闭或合并后删除 |

版本发布使用不可变标签 `v主版本.次版本.修订版本`，例如 `v1.7.3`，不创建长期 `release/*` 分支。紧急修复直接从最新 `main` 创建 `fix/*` 分支，验证后通过 PR 合并。

`main` 禁止直接推送、强制推送和删除。所有修改必须经过 PR、Quality Gate、讨论解决和维护者审核，并使用 Squash Merge 保持线性历史。

## 开始开发

1. 从最新 `main` 创建分支，推荐命名：`feat/主题`、`fix/主题`、`docs/主题` 或 `chore/主题`。
2. 不要在源码、测试、日志或截图中提交真实密钥、令牌、邮箱、账本和个人财务数据。
3. 每个 PR 聚焦一个目标，避免混入无关格式化或生成文件。

```bash
git switch main
git pull --ff-only
git switch -c feat/short-description
npm ci
```

开发期间如 `main` 已更新，请先同步远端并将分支变基到最新 `main`，解决冲突后再更新 PR：

```bash
git fetch origin
git rebase origin/main
git push --force-with-lease
```

只允许对个人短期分支使用 `--force-with-lease`；不得对 `main` 使用任何强制推送。

## 本地验证

提交前至少运行与改动相关的检查：

```bash
npm run build
npm test
go test ./...
```

Docker 或 NAS 改动还应运行：

```bash
docker compose config
docker compose build
```

## 提交与 PR

- 建议使用 Conventional Commits，例如 `feat: add budget rollover`、`fix: correct OCR timeout`。
- PR 描述应包含变更目的、验证结果、兼容性影响和界面截图（如适用）。
- CI 通过且至少一名维护者批准后才能合并。
- 使用 Squash Merge，保持 `main` 历史简洁；合并后删除功能分支。

## 发布

只有维护者可以创建版本标签和 GitHub Release。发布文件必须附带 SHA-256 校验值；未签名的 Windows 文件必须在发行说明中明确标注。

完整的代码更新、版本准备、构建、上传与发布后验证流程见 [GitHub 发布指南](docs/GITHUB_RELEASE_GUIDE.md)。

## 安全问题

安全漏洞不要提交公开 Issue。请按照 [SECURITY.md](SECURITY.md) 使用 GitHub Private Vulnerability Reporting。
