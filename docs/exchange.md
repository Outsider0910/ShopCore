# 货币兑换规范

货币兑换使用 `mcPlugin/Wallet` 的 `WalletService`。商店只使用 Wallet 经济，
不再有 `economy.mode`；源币和目标币都来自 Wallet 从官网读取的
`enabled=true` 资产表。

## 1. 配置

```yaml
exchange:
  enabled: true
  # true：配置 A -> B 后，未显式配置 B -> A 时自动按 1/rate 允许反向
  # false：只允许 rates 里写出来的方向
  bidirectional: true
  rates:
    POINT:
      CNY: "0.01"
    # 显式反向优先于自动倒数
    # CNY:
    #   POINT: "100"
```

规则：

1. `rates.<源币>.<目标币> = rate` 表示 **1 源币 = rate 目标币**。
2. `bidirectional: true` 时：
   - 若配置了 `POINT -> CNY: 0.01`，且没有显式 `CNY -> POINT`，自动生成反向方向，
     费率为 `1 / 0.01 = 100`。
   - 若显式配置了 `CNY -> POINT: 120`，反向使用 120，不自动倒数。
3. `bidirectional: false` 时，只有 `rates` 里明确写出的方向可用；显式反向仍然可用。
4. 源币、目标币必须同时存在于 Wallet `/assets` 返回的资产表中，且 `enabled=true`；
   否则该方向不显示。商店商品的计价货币也按同样规则校验：商品使用的资产在官网停用时，
   购买直接不可用，不会自动换成其他货币。
5. 精度使用 Wallet 资产的 `scale`。
   - `POINT` 通常 `scale=0`，`CNY` 通常 `scale=2`。
6. 目标金额计算：

   ```text
   target = source × rate
   ```

   然后按目标币 `scale` **向下取整**（`RoundingMode.DOWN`），避免换出超过配置比例的价值。
   取整后为 0 的请求会被拒绝（`error.exchange-too-small`）。
7. 不收取手续费。

## 2. 界面流程

1. 主菜单点击“货币兑换”。
2. 兑换页列出所有可用方向，例如 `POINT → CNY`。
3. 点击方向 → 关闭菜单 → 聊天输入源币数量。
4. 报价会读取源币余额；余额不足时提示并允许重新输入。
5. 公屏出现确认消息：

   ```text
   确认用 100 POINT 兑换 1.00 CNY？比例：1 POINT = 0.01 CNY。
   [确认] [取消]
   ```

6. 点击确认 → 执行兑换。

对应 UI ID：

| ID | 位置 |
| --- | --- |
| `shopcore:ui/main/exchange` | 主菜单“货币兑换” |
| `shopcore:ui/exchange/pair` | 兑换方向条目 |

## 3. 执行与失败语义

Wallet 只有 `credit` / `debit`，没有跨币种原子兑换端点，因此采用：

```text
1. debit 源币   businessNo = mc:<server.id>:exchange:<orderId>:debit
2. credit 目标币 businessNo = mc:<server.id>:exchange:<orderId>:credit
3. 若目标币“确定失败”：
   credit 源币退回 businessNo = ...:refund
```

- 源币扣款失败/余额不足：不执行目标入账，订单 `FAILED`。
- 源币扣款结果未知：不执行目标入账，订单 `UNKNOWN`。
- 目标币入账成功：订单 `COMPLETED`。
- 目标币入账确定失败：
  - 自动退回源币成功 → 订单 `FAILED`，玩家看到“源货币已退回”；
  - 自动退款结果未知 → 订单 `UNKNOWN`。
- 目标币入账结果未知：不自动退款（可能已入账），订单 `UNKNOWN`。

所有阶段都使用稳定 `businessNo`，Wallet/官网按业务号幂等。管理员可在 Wallet 后台按
`businessNo` 对账。

## 4. 订单与管理员工具

兑换订单写入 SQLite `exchange_orders`：

```text
id, business_no, player_uuid, from_code, to_code,
source_amount, target_amount, status, message, created_at, updated_at
```

状态：`PENDING` / `COMPLETED` / `FAILED` / `UNKNOWN`。

管理员命令：

| 命令 | 作用 |
| --- | --- |
| `/shopadmin exchanges` | 查看最近 30 条兑换记录（含业务号、状态、金额） |
| `/shopadmin exchange <玩家> <源币> <目标币> <数量>` | 跳过点击确认，直接按当前配置兑换（客服/测试用） |
| `/shopadmin wallet <玩家> [币种]` | 查询 Wallet 余额；不带币种时列出全部启用货币 |

`/shopadmin exchange` 同样走报价、订单、扣款/入账/补偿流程，不会绕过 Wallet。

## 5. 测试（Wallet + mock）

测试服已安装 `Wallet` 并指向 `mock-wallet`：

```yaml
# test-server/plugins/Wallet/config.yml
base-url: "http://127.0.0.1:18080"
api-key: "yda_test_local"
assets: ["POINT", "CNY"]
```

```yaml
# test-server/plugins/ShopCore/config.yml
exchange:
  enabled: true
  bidirectional: true
  rates:
    POINT:
      CNY: "0.01"
```

> 共享 mock 原本只实现 旧货币插件 的 `/admin/assets`；Wallet 读 `/assets`。
> 测试服的 `mock-wallet/server.js` 已同时支持两个路径。

验证命令（控制台或 OP）：

```text
shopadmin wallet Outsider0910 POINT
shopadmin wallet Outsider0910 CNY
shopadmin exchange Outsider0910 POINT CNY 100
shopadmin wallet Outsider0910 POINT
shopadmin wallet Outsider0910 CNY
shopadmin exchanges
```

预期：

```text
POINT 1000 / CNY 100.00
→ 100 POINT 兑换 1.00 CNY
POINT 900 / CNY 101.00
```

反向：

```text
shopadmin exchange Outsider0910 CNY POINT 1
→ POINT 1000 / CNY 100.00
```

不足额：

```text
shopadmin exchange Outsider0910 POINT CNY 999999
→ 提示余额不足，不产生 debit/credit，不新增订单
```

## 6. 测试货币名称

本地 mock（`test-server/mock-wallet/server.js`）的资产：

| code | 名称 | 符号 | scale |
| --- | --- | --- | --- |
| `POINT` | 积分 | 积分 | 0 |
| `CNY` | 人民币 | ￥ | 2 |

这是**测试数据**，不是生产站点的资产名称。生产环境以 Wallet 从官网
Wallet 官网资产接口返回的 `name`/`symbol`/`scale` 为准。
游戏内可用 `/shopadmin currency` 查看 Wallet 官网资产的实际名称、精度和启用状态。

## 7. 已知限制

- Wallet 没有原子跨币种兑换，扣款与入账之间不是事务；失败时靠补偿退款，极端情况下
  （两步都结果未知）需要管理员按 businessNo 对账。
- 兑换只使用 Wallet 的**官网 enabled** 资产；本服 `plugins/Wallet/assets.yml` 的局部
  启停不参与汇率判断。若要禁止某方向，删除对应 `exchange.rates` 配置即可。
- 没有手续费、最小/最大兑换额、每日限额；如需这些规则可后续扩展。
- 兑换目标账户固定为发起兑换的同一名玩家；不支持“替别人兑换”。
