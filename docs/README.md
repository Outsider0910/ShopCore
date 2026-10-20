# 文档索引

ShopCore 的文档按"入口 → 规范 → 契约 → 设计"分层。规范类文档写"必须/禁止"，
其余文档解释"是什么、怎么做"。一条规则只在一处定义，其他文档引用。

## 建议阅读顺序

1. [`../README.md`](../README.md)：项目定位、功能、命令、配置示例、已知限制。
2. [`../AGENTS.md`](../AGENTS.md)：AI/开发者入口、硬规则、关键文件。
3. [`specification.md`](./specification.md)：工程规范总纲。
4. 按任务读下面的专项规范。

## 规范类

| 文档 | 内容 | 什么时候读 |
| --- | --- | --- |
| [`specification.md`](./specification.md) | 依赖边界、分层、Wallet 契约、UI/文案、存储、线程、命令权限、测试、禁止事项 | 任何改动前 |
| [`versioning-and-artifacts.md`](./versioning-and-artifacts.md) | 版本号、产物命名、归档、ChangeLog、测试服发布 | 发版/构建 |
| [`configuration.md`](./configuration.md) | 全部配置键、默认值、reload 范围、MiniMessage | 改配置/新增键 |
| [`ui.md`](./ui.md) | 菜单布局、槽位、余额/流水、返回主菜单、刷新、无斜体 | 改 GUI |
| [`storage.md`](./storage.md) | SQLite schema v2、事务、迁移、备份、并发 | 改数据/订单/库存 |
| [`testing.md`](./testing.md) | 单测、钱包 mock、手工验收清单、发布门禁 | 提交/发版前 |
| [`operations.md`](./operations.md) | 部署、升级、回滚、备份、故障处置 | 上线/运维 |

## 契约类

| 文档 | 内容 |
| --- | --- |
| [`economy-integration.md`](./economy-integration.md) | Wallet 资产/余额/支付/失败语义、schema 迁移 |
| [`exchange.md`](./exchange.md) | 兑换比例、双向、执行顺序、补偿、mock 验证 |
| [`components.md`](./components.md) | UI 物品组件、完整 UI ID 表、资源包契约、严格组件匹配 |

## 设计类

| 文档 | 内容 |
| --- | --- |
| [`design-notes.md`](./design-notes.md) | 需求映射、关键流程、表结构、默认值、边界 |

## 变更记录

- [`../ShopCore-mc26_3-plugin-ChangeLog-Beta.md`](../ShopCore-mc26_3-plugin-ChangeLog-Beta.md)：Beta 版本变更。
- `ShopCore-mc26_3-plugin-ChangeLog.md`：正式版变更（首次出正式版时创建）。

## 维护规则

- 行为变更：改代码 + 更新对应契约/规范 + 追加 ChangeLog。
- 只改文档：在对应版本 ChangeLog 的"文档"分类下记录。
- 新增配置键：更新 `config.yml` 注释、[`configuration.md`](./configuration.md)、README 示例。
- 新增 UI ID：先更新 [`components.md`](./components.md)，再改 `UiItems`。
- 新增数据库列/表：更新 [`storage.md`](./storage.md)、[`design-notes.md`](./design-notes.md)，
  并提供迁移。
- 新增命令/权限：更新 [`specification.md`](./specification.md) §9、README 命令表、
  `paper-plugin.yml`。
