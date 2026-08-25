# Contributing to Finexy

感谢参与 Finexy。仓库采用受保护的 `main` 分支和 Pull Request（PR）协作流程。

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

## 安全问题

安全漏洞不要提交公开 Issue。请按照 [SECURITY.md](SECURITY.md) 使用 GitHub Private Vulnerability Reporting。
