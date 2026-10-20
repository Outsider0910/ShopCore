# 经济系统对接（Wallet）

ShopCore **只使用 `mcPlugin/Wallet` 作为经济系统**。本插件不再保存任何余额，也没有
`internal-test` / `service` / `disabled` 等替代货币模式。

- 货币类型、名称、符号、精度、官网启用状态：`WalletApi.wallet().assets()`（Wallet 从
  Wallet 官网资产接口读取）。
- 玩家余额：`WalletApi.wallet().balance(...)`，来自官网。
- 所有加币/扣币：`WalletApi.wallet().credit(...)` / `debit(...)`，由 Wallet 写回官网，
  官网流水就是上报结果。
- ShopCore 只做短时缓存（主菜单余额、货币列表），不落库余额。

## 1. 依赖

```yaml
# paper-plugin.yml
dependencies:
  server:
    Wallet:
      load: BEFORE
      required: true
```

```groovy
// build.gradle：以现有 Wallet 产物编译，运行期由 Wallet 插件提供
compileOnly files('../mcPlugin/release/Wallet/Wallet-mc26_3-plugin-Beta_0_2_1.jar')
```

运行期请安装同一版本或更新的 Wallet。Wallet 未加载时 ShopCore 不会启用。

## 2. 货币类型来自官网

商店不使用 `config.yml` 里的“主货币 code/名称/精度”。每种商品/寄售记录自己的
Wallet 资产 code：`official_items.asset_code`、`player_listings.asset_code`。

- 上架官方商品：选择背包物品 → **选择货币**（列出官网 `enabled=true` 的资产）→ 输入单价。
- 玩家上架：选择物品 → **选择货币** → 输入 `数量 单价`。
- 管理员编辑官方商品时可点击“修改货币”；价格按新资产精度重新取整（向下）。
- 商品显示和输入校验使用该资产的 `scale`。例如 `POINT(scale=0)` 只能输入整数，
  `CNY(scale=2)` 允许两位小数。
- 官网停用（`enabled=false`）的货币不会出现在选择列表；已使用该货币的商品/寄售在
  购买时会被拒绝，不会自动换成别的货币。
- 历史数据（Beta_0_2_0 及更早）没有 `asset_code`，运行期解析为官网当前启用资产表的
  第一个；新订单会写入解析后的 code。

## 3. 余额

- 主菜单“钱包”按钮采用 Wallet 同款样式：`货币名：余额 符号`，逐行列出本服启用货币。
- 只读 Wallet 的 `cachedBalance`，每秒重绘、5 秒节流异步刷新；未绑定网站账号显示
  “未绑定网站账号”，首次加载显示“…”。加/扣币后主动失效该玩家缓存。
- 本服启用货币与 Wallet 的 `plugins/Wallet/assets.yml` / `config.yml` 同口径：
  `assets.yml` 优先，其次 `config.yml` 的 `assets`，两者都没有时使用官网 `enabled` 全部。
- 管理员命令：
  - `/shopadmin wallet <玩家>`：列出该玩家全部启用货币余额；
  - `/shopadmin wallet <玩家> <币种>`：查询单个币种；
  - `/shopadmin currency`：列出 Wallet 官网资产表（code/名称/精度/官网启用状态）。

## 4. 支付

商店仍然采用“下单占位 → 支付 → 成功发货 / 失败释放 / 未知不发货”的状态机。

官方商店：

```text
debit(买家, 商品 assetCode, 总价)   businessNo = mc:<server.id>:shop:<orderId>:debit
```

玩家商店：

```text
debit(买家, assetCode, 总价)        businessNo = ...:debit
credit(卖家, assetCode, 总价)       businessNo = ...:credit
若卖家入账确定失败：
credit(买家, assetCode, 总价)       businessNo = ...:refund
```

- `businessNo` 是 Wallet/官网的幂等键，重试沿用同一个值不会重复记账。
- 余额不足、确定失败：商店恢复库存/寄售，订单 `FAILED`。
- 超时、网络错误、Wallet 返回无法确认：订单 `UNKNOWN`，**不发货、不继续入账**；
  管理员先在官网按 `businessNo` 对账，再用 `/shopadmin resolve` 收口。
- 只有确定 `SUCCESS` 才会把物品放进买家邮箱。

## 5. 货币兑换

兑换仍按 `exchange.rates` 配置方向，但源/目标资产必须来自 Wallet 官网 `enabled` 表。
执行顺序与失败语义见 [`exchange.md`](./exchange.md)。

## 6. 数据表变化（Schema v2）

`official_items`、`player_listings`、`orders` 增加 `asset_code TEXT NOT NULL DEFAULT ''`。
旧库启动时自动 `ALTER TABLE` 补列；`test_wallet` / `test_economy_ledger` 不再创建，
也不再读写（旧库里的空表可以手工删除）。

## 7. 测试

单元测试使用一个只读的 `CurrencyLookup` 假实现（`CNY scale=2`、`POINT scale=0`），
不访问官网。Wallet 接口联调请使用测试服 + `mock-wallet`：

```yaml
# test-server/plugins/Wallet/config.yml
base-url: "http://127.0.0.1:18080"
api-key: "yda_test_local"
assets: ["POINT", "CNY"]
```

验证：

```text
/shopadmin currency
/shopadmin wallet Outsider0910
/shopadmin wallet Outsider0910 POINT
/shopadmin exchange Outsider0910 POINT CNY 100
/shopadmin exchanges
```

## 8. 已知边界

- Wallet 没有原子跨币种转账/兑换，`debit` 与 `credit` 是两次官网请求；结果未知时只能
  按 `businessNo` 人工对账。
- Wallet API 的 `assets()` 返回官网全部资产及官网启用状态；ShopCore 只使用
  `enabled=true` 的资产，不读取 Wallet 本地 `assets.yml` 的启停开关（该开关只影响
  Wallet 自己的菜单）。
- 商店价格上限 `limits.min-price/max-price` 是数值门槛，不随币种换算；配置时应按
  最小/最大面值货币设置合理范围。
- 单服 SQLite；多服共享商店需要换 MySQL 并重新设计并发控制。
