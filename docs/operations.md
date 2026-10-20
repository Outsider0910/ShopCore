# 部署与运维规范

> 适用范围：ShopCore 在生产/测试服的安装、升级、回滚、备份与故障处置。
> 目标：升级可回退、故障有手册、经济账目可对账。

## 1. 部署前提

| 项 | 要求 |
| --- | --- |
| 服务端 | Paper 26.3 |
| Java | 25 |
| MainMenu | 已安装且版本兼容；ShopCore 必需 |
| Wallet | 已安装且已配置 `base-url` / `api-key`；ShopCore 必需 |
| Wallet API Key | 具备 `plugin:yudream-wallet:manage` |
| 数据库 | 单服本地 SQLite；不要把同一个库挂给多个服务端 |

安装顺序：MainMenu → Wallet → ShopCore。`paper-plugin.yml` 已声明
`load: BEFORE, required: true`，缺失任一依赖时 ShopCore 不会启用。

## 2. 安装 / 升级 / 回滚

### 2.1 安装

1. 把 `ShopCore-mc26_3-plugin-<版本>.jar` 放进 `plugins/`。
2. 启动服务端，确认 `plugins/ShopCore/config.yml` 已释放。
3. 按 [`configuration.md`](./configuration.md) 填写 `server.id`。
4. 确认 Wallet 能读取官网资产。
5. 启动日志出现 `ShopCore 已启用`。

### 2.2 升级

1. 阅读 ChangeLog，确认版本号与迁移说明。
2. 停止服务端，备份 `shop.db` + `shop.db-wal` + `shop.db-shm`。
3. 移除旧的 `ShopCore-mc26_3-plugin-*.jar`，放入新 jar。
4. 对比新 `config.yml`，补新增键（旧配置缺键有兜底，但新增键不会自动写入）。
5. 启动服务端，检查日志、`/shopadmin currency`、`/shopadmin wallet`。
6. 执行一笔小额购买/兑换验证。

### 2.3 回滚

1. 停止服务端。
2. 备份当前数据库与配置。
3. 放回旧 jar。
4. 如果新版本做过 schema 迁移，优先恢复升级前的数据库备份；
   schema v2 的 `asset_code` 是加法式迁移，旧版本通常可忽略，但不作为保证。
5. 启动并验证余额、订单、兑换记录。

## 3. 启动检查

正常日志：

```text
[ShopCore] Loading server plugin ShopCore v<版本>
[ShopCore] Enabling ShopCore v<版本>
[ShopCore] 已注册时钟主菜单功能：shopcore:shop
[ShopCore] 已从 Wallet 读取官网货币：[...]
[ShopCore] ShopCore 已启用：...
```

必须排查：

- `Wallet 未加载` / `Wallet 尚未就绪`。
- `Ambiguous plugin name 'ShopCore'`（`plugins/` 有多个同名 jar）。
- `ERROR` / `Exception` / `NoClassDefFoundError` / `NoSuchMethodError`。
- 货币列表为空（官网无启用资产或 Wallet 配置错误）。

## 4. 重载范围

`/shopadmin reload` 会重建配置、文案、业务服务、菜单服务，并更新 Wallet
缓存时长。

| 配置 | reload | 备注 |
| --- | --- | --- |
| `server.id` | 是 | 只影响新订单 businessNo |
| `economy.*` | 是 | 支付超时、资产/余额缓存、流水时区 |
| `exchange.*` | 是 | 汇率/双向 |
| `player-shop.*` / `limits.*` | 是 | 上限、价格、超时 |
| `menu.*` / `messages.*` | 是 | 标题/文案 |
| `database.file` | **否** | 需重启 |
| `rules.reset-zone` | **否** | 存储层时区在启动时固定，需重启 |

注意：

- 已打开的旧菜单仍指向旧服务，关闭重开。
- Wallet/MainMenu 自身的配置变更按其插件方式 reload/restart。
- reload 不重新注册 MainMenu 功能；功能入口保持。

## 5. 备份与恢复

- 停机备份：复制 `plugins/ShopCore/shop.db`、`shop.db-wal`、`shop.db-shm`。
- 运行中备份：使用 SQLite 备份 API 或 `VACUUM INTO`，不要只复制主库文件。
- 恢复：停止服务端 → 备份当前文件 → 放回备份 → 启动 → 检查日志/订单/余额。
- 备份频率：至少每日；大版本升级前必须备份。
- 备份保留：按运营要求保留多个时间点；`orders` / `exchange_orders` 是审计数据。
- 不要用文件同步工具在写入时直接覆盖 `shop.db`。

## 6. 监控

建议监控：

- 日志中的 `ERROR` / `Exception` / `WARN`（Wallet 读取失败、订单收口失败）。
- `UNKNOWN` 订单数量（`/shopadmin orders`）。
- Wallet 余额接口可用性/延迟。
- SQLite 文件大小与磁盘空间。
- `plugins/` 是否存在多个 ShopCore jar。
- 主菜单余额是否持续显示"…"（Wallet 不通/未绑定）。

## 7. 故障处置

### 7.1 Wallet 未加载 / API Key 无效

现象：ShopCore 不启用，或货币列表为空，购买/兑换不可用。

处理：

1. 确认 Wallet 插件存在且启用。
2. 检查 Wallet `base-url` / `api-key`。
3. Key 需要 `plugin:yudream-wallet:manage`。
4. `/shopadmin currency` 看是否能读到资产。
5. ShopCore 不提供本地货币回退，修复 Wallet 前不要绕过。

### 7.2 玩家未绑定网站账号

现象：余额行显示"未绑定网站账号"，支付失败。

处理：

1. 玩家在官网绑定 Minecraft UUID/名称。
2. Wallet `match-mode` 与站点映射一致。
3. 修复后最多 5 秒余额刷新；仍不显示可重开菜单。

### 7.3 订单 UNKNOWN

现象：支付超时/网络错误，订单 `UNKNOWN`，不发货。

处理：

1. `/shopadmin orders` 找到 `businessNo`。
2. 在 Wallet/官网按 `businessNo` 查流水：
   - 已扣款：`/shopadmin resolve <businessNo> complete`，物品进买家邮箱。
   - 确定未扣款：`/shopadmin resolve <businessNo> cancel`，释放库存/寄售。
3. `cancel` 不会自动退款；如已扣款先按官网流程退款。
4. 不要手工改订单状态。

### 7.4 货币被停用

现象：使用该货币的商品/寄售购买被拒绝；兑换方向消失。

处理：

1. 官网 `enabled=false`：只能官网重新启用，或让管理员改商品货币。
2. Wallet 本服 `assets.yml` 关闭：在 Wallet 菜单重新启用，最多 5 秒同步。
3. 玩家寄售不支持改币种；需要下架后按新币种重新上架。

### 7.5 多个同名 jar

现象：`Ambiguous plugin name 'ShopCore'`，可能加载旧版本。

处理：删除 `plugins/ShopCore-mc26_3-plugin-*.jar` 中除当前版本外的所有 jar，重启。

### 7.6 数据库锁 / 损坏

现象：订单/库存操作报数据库错误，日志出现 SQLite 异常。

处理：

1. 确认没有多个服务端进程共用同一个 `shop.db`。
2. 检查磁盘空间、杀毒软件/同步盘占用。
3. 停止服务端，备份 `shop.db*`。
4. 用 SQLite 完整性检查；损坏时恢复最近备份。
5. 不要手工删除 WAL 文件后继续写入。

### 7.7 主菜单没有商店入口

处理：

1. 确认 MainMenu 已安装且版本兼容。
2. 看日志是否 `已注册时钟主菜单功能：shopcore:shop`。
3. 执行 `/cd debug` 查看功能位置/冲突。
4. 确认 `paper-plugin.yml` 的 MainMenu 依赖为 `required: true`。

### 7.8 权限问题

- 普通玩家：`shopcore.use`。
- 管理员：`shopcore.admin`（默认 OP）。
- 权限节点以 `paper-plugin.yml` 为准；不要只给命令别名授权。

### 7.9 余额显示不更新

- 主菜单余额每秒重绘、5 秒节流刷新；等待 5 秒。
- 检查 Wallet 余额接口是否正常。
- 加/扣币后 ShopCore 会失效缓存；若仍不更新，重开菜单。
- 未绑定/无缓存显示"…"或"未绑定网站账号"是预期状态。

### 7.10 资源包按钮模型不生效

- 用 `/shopadmin debug` 检查 `custom_data` / `custom_model_data`。
- 对照 [`components.md`](./components.md) 的 UI ID。
- 确认资源包模型定义按 `custom_model_data` 字符串匹配。

## 8. 安全

- ShopCore 不保存 Wallet API Key；Key 只存在 Wallet 配置中。
- 限制 `plugins/ShopCore/` 与 `plugins/Wallet/` 的文件权限。
- 不要公开数据库、备份、日志中的敏感信息。
- 管理命令默认 OP；给 `shopcore.admin` 时按最小权限原则。
- 不信任客户端物品/槽位；服务端只认完整组件与动作表。
- `businessNo` 可用于对账，不要随意修改/重放；重放依赖官网幂等。
- 生产环境不要使用 mock Wallet。

## 9. 变更管理

- 每次升级按 [`versioning-and-artifacts.md`](./versioning-and-artifacts.md) 涨号、
  归档、写 ChangeLog。
- 影响经济/UI/存储/配置的变更，必须同步更新对应文档。
- 大版本/数据库迁移前必须备份并准备回滚。
- 测试服验证通过后再上生产；GUI 需真实客户端验收。
- 生产故障处理记录：时间、现象、businessNo、操作、结果。

## 10. 禁止事项

- 禁止在 Wallet 不可用时启用本地货币回退。
- 禁止手工改 `UNKNOWN` 订单状态代替官网对账。
- 禁止多服务端共用一个 SQLite 文件。
- 禁止只备份 `shop.db` 而忽略 WAL/SHM。
- 禁止在 `plugins/` 保留多个同名 ShopCore jar。
- 禁止把生产 API Key、数据库、备份提交到仓库。
- 禁止未备份就升级/回滚/手工修库。
