# 数据与存储规范

> 适用范围：`plugins/ShopCore/shop.db` 及 `SqliteShopStorage`。
> 目标：数据可追溯、事务边界清晰、迁移可回退、并发不超卖。

## 1. 文件与连接

- 路径：`plugins/ShopCore/shop.db`（相对服务端根目录）。
- SQLite WAL；启动时设置 `journal_mode=WAL`、`synchronous=NORMAL`、
  `foreign_keys=ON`。
- 所有访问走单线程 `ShopCore-DB` executor；SQLite 连接不跨线程使用。
- 关闭时等待连接关闭与 executor 终止（最多 5 秒）。
- 单服单库；不支持多服共享同一个 `shop.db`。跨服需要改 MySQL 并重新设计锁。

## 2. Schema v2

`meta.schema_version = 2`。DDL 以 `SqliteShopStorage` 为准，文档只描述字段含义。

### 2.1 `official_items`

| 列 | 类型 | 说明 |
| --- | --- | --- |
| `id` | TEXT PK | 商品 ID |
| `asset_code` | TEXT | Wallet 资产 code；大写；旧数据空值运行期解析 |
| `item_data` | BLOB | 数量 1 的完整 `ItemStack` 序列化 |
| `base_price` | TEXT | 基础单价，BigDecimal 文本 |
| `stock` | INTEGER | `-1` = 无限 |
| `per_player_limit` | INTEGER | `0` = 不限 |
| `growth_percent` | TEXT | 每件涨价百分比 |
| `max_price` | TEXT | 单件最高价；`0` = 不设上限 |
| `reset_period` | TEXT | `NEVER/DAILY/WEEKLY/MONTHLY` |
| `enabled` | INTEGER | 1/0 |
| `created_at` | INTEGER | epoch millis |
| `updated_at` | INTEGER | epoch millis |

### 2.2 `player_listings`

| 列 | 类型 | 说明 |
| --- | --- | --- |
| `id` | TEXT PK | 寄售 ID |
| `asset_code` | TEXT | Wallet 资产 code |
| `seller_uuid` | TEXT | 卖家 UUID |
| `seller_name` | TEXT | 上架时名称（展示用） |
| `item_data` | BLOB | 数量 1 的完整组件模板 |
| `unit_price` | TEXT | 单价 |
| `quantity` | INTEGER | 剩余数量；0 = 售罄/不显示 |
| `created_at` | INTEGER | epoch millis |
| `updated_at` | INTEGER | epoch millis |

索引：`idx_listings_seller(seller_uuid)`、`idx_listings_created(created_at)`。

### 2.3 `official_purchases`

| 列 | 类型 | 说明 |
| --- | --- | --- |
| `player_uuid` | TEXT | 玩家 UUID |
| `item_id` | TEXT | 官方商品 ID |
| `purchase_count` | INTEGER | 当前周期已购件数 |
| `window_start` | INTEGER | 当前周期起点 |

主键：`(player_uuid, item_id)`。

### 2.4 `orders`

| 列 | 类型 | 说明 |
| --- | --- | --- |
| `id` | TEXT PK | 订单 UUID |
| `business_no` | TEXT UNIQUE | Wallet 幂等键 |
| `asset_code` | TEXT | 支付货币 |
| `buyer_uuid` | TEXT | 买家 |
| `seller_uuid` | TEXT NULL | 玩家商店卖家；官方订单为空 |
| `listing_id` | TEXT NULL | 玩家寄售 ID |
| `official_item_id` | TEXT NULL | 官方商品 ID |
| `item_data` | BLOB | 下单时的商品模板 |
| `quantity` | INTEGER | 数量 |
| `unit_price` | TEXT | 单价 |
| `total_price` | TEXT | 总价 |
| `status` | TEXT | `PENDING/COMPLETED/FAILED/UNKNOWN` |
| `created_at` | INTEGER | epoch millis |
| `updated_at` | INTEGER | epoch millis |

索引：`idx_orders_status(status)`、`idx_orders_buyer(buyer_uuid)`。

### 2.5 `exchange_orders`

| 列 | 类型 | 说明 |
| --- | --- | --- |
| `id` | TEXT PK | 兑换订单 UUID |
| `business_no` | TEXT UNIQUE | Wallet 幂等键 |
| `player_uuid` | TEXT | 玩家 |
| `from_code` | TEXT | 源币 code |
| `to_code` | TEXT | 目标币 code |
| `source_amount` | TEXT | 源币金额 |
| `target_amount` | TEXT | 目标币金额 |
| `status` | TEXT | `PENDING/COMPLETED/FAILED/UNKNOWN` |
| `message` | TEXT | 结果/错误说明 |
| `created_at` | INTEGER | epoch millis |
| `updated_at` | INTEGER | epoch millis |

索引：`idx_exchange_status(status)`、`idx_exchange_player(player_uuid)`。

### 2.6 `mailbox`

| 列 | 类型 | 说明 |
| --- | --- | --- |
| `id` | TEXT PK | 邮箱条目 ID |
| `player_uuid` | TEXT | 收件人 |
| `item_data` | BLOB | 数量 1 的模板 |
| `quantity` | INTEGER | 数量 |
| `created_at` | INTEGER | epoch millis |

索引：`idx_mailbox_player(player_uuid)`。

### 2.7 `meta`

| 列 | 类型 | 说明 |
| --- | --- | --- |
| `key` | TEXT PK | 元数据键 |
| `value` | TEXT | 值；`schema_version=2` |

## 3. 数据不变量

- `item_data` 永远是数量 1 的模板；数量单独存 `quantity`。
- 物品身份按完整组件匹配，不按材质/名称。
- `asset_code` 大写；空值只可能来自旧数据，运行期解析为本服第一个启用货币。
- 价格/金额用十进制文本保存，禁止用二进制浮点。
- `orders.business_no`、`exchange_orders.business_no` 唯一，作为 Wallet 幂等键。
- 卖家可以离线；入账只依赖 UUID，不要求在线。
- `mailbox` 条目仅属于一个玩家；领取后扣减/删除。
- `orders` / `exchange_orders` 是审计记录，不因完成而删除。

## 4. 事务边界

以下组合操作必须在同一事务内：

- 扣库存/扣寄售数量 + 创建 `PENDING` 订单。
- 下架寄售 + 写入卖家邮箱。
- 完成订单 + 写入买家邮箱 + 累计官方商品购买计数。
- 取消订单 + 恢复库存/寄售数量（必要时写入卖家邮箱）。
- 兑换订单状态落库。

事务失败必须回滚；不得出现"库存已扣、订单没建"或"订单完成、物品没进邮箱"。

## 5. 迁移

Schema v1 → v2：

- 启动时 `ensureColumn` 为 `official_items`、`player_listings`、`orders`
  增加 `asset_code TEXT NOT NULL DEFAULT ''`。
- `meta.schema_version` 更新为 2。
- 旧数据 `asset_code=''` 在读取/下单时解析为本服第一个启用货币；
  新写入的订单/寄售会写入解析后的 code。
- 迁移是加法式的，不删除旧表/旧列；旧 `test_wallet` / `test_economy_ledger`
  不再创建或读写，可手工删除。

迁移前必须备份；迁移后检查：
- `PRAGMA table_info` 能看到 `asset_code`；
- `meta.schema_version=2`；
- 旧商品/寄售可正常显示、购买时使用正确货币。

## 6. 备份与恢复

- **停机备份**：停止服务端后复制 `shop.db`、`shop.db-wal`、`shop.db-shm`
  三个文件；不要只复制 `shop.db`。
- **运行中备份**：使用 SQLite 备份 API 或 `VACUUM INTO` 生成一致快照；
  不要直接复制正在写入的 `shop.db`。
- 恢复：停止服务端 → 备份当前文件 → 放回备份文件 → 启动 →
  检查日志与 `/shopadmin orders`。
- 换机器/换目录：保持 `plugins/ShopCore/` 路径与文件权限。
- 不得把同一个 `shop.db` 挂给多个服务端同时写。

## 7. 并发与性能

- 单线程 DB executor 已串行化所有库存/寄售写入，避免超卖。
- 不要在 DB executor 上调用 Wallet HTTP 或 Bukkit API。
- 订单/审计表会持续增长；当前没有自动清理。需要清理时先归档，
  且不得删除 `PENDING/UNKNOWN` 订单。
- SQLite 适合单服；大量玩家/跨服请评估 MySQL。

## 8. 手工操作

- 禁止绕过存储层直接写 `shop.db`。
- 紧急修数必须先停机备份，使用 SQLite 工具操作，并记录原因/时间/操作人。
- 不允许手工修改 `PENDING/UNKNOWN` 订单状态来代替 Wallet 对账；
  先按 `businessNo` 在官网确认，再用 `/shopadmin resolve`。
- 不允许手工改 `mailbox` 数量来"补发"；使用管理流程或恢复备份。
