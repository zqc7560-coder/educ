# 校园失物招领系统（Campus Lost & Found）

软件工程毕业设计项目 · 评审稿仓库

## 项目简介

面向高校校园的失物招领平台：学生发布失物/拾物信息，系统提供分类检索、
智能相似匹配、带核验问答的认领流程状态机、学生会/管理员审核治理与统计看板，
解决校园失物信息分散在群聊公告、检索难、冒领难核实的问题。

## 技术栈

Java 17 · Spring Boot 3 · MyBatis-Plus · MySQL 8 · Redis 7 ·
Vue 3 · Vite · Element Plus · Docker Compose · JUnit 5 / Vitest · GitHub Actions CI

## 文档索引

| 文档 | 内容 | 状态 |
|---|---|---|
| [docs/开发计划.md](docs/开发计划.md) | 背景目标、技术选型、16 周阶段计划、里程碑、WBS、风险、验收标准 | ✅ 定稿 |
| [docs/开发方案.md](docs/开发方案.md) | 总体架构、工程结构、认领状态机等核心设计、安全/质量方案、表与接口概览 | ✅ 定稿 |
| [docs/系统功能说明.md](docs/系统功能说明.md) | 角色划分、功能清单（含优先级 P0/P1/P2）、用例要点 | ✅ 定稿 |
| [docs/进度.md](docs/进度.md) | M0–M12 模块台账：每模块交付物、提交号、验证记录、迭代日志 | ✅ 持续更新 |
| [docs/测试报告.md](docs/测试报告.md) | 测试策略、用例清单（后端 126 例/前端 19 例/UI 30 断言）、覆盖率、缺陷台账 | ✅ 定稿 |
| [docs/部署文档.md](docs/部署文档.md) | 架构总览、一键部署、环境变量、备份恢复、验收脚本 | ✅ 定稿 |

## 当前状态

🎉 M0–M12 全部模块开发完成：后端 126 用例全绿（行覆盖 0.86，验收门禁 0.60）、
前端构建通过 + 19 vitest、GitHub Actions 双绿、Docker Compose 全链路冒烟 10/10。
快速上手见 [docs/部署文档.md](docs/部署文档.md)。

## 目录结构

```
├── backend/    # Spring Boot 3.2 应用（Java 17）
├── frontend/   # Vue 3 + Element Plus 前端
├── docs/       # 开发计划/方案/功能说明/测试报告/部署文档/进度台账
└── deploy/     # Docker Compose 部署资产 + 备份/验收脚本
```
