# 配置规范

> 适用范围：`plugins/ShopCore/config.yml`。
> 目标：配置键有唯一解释、默认值明确、reload 行为可预期。

## 1. 文件与加载

- 运行期路径：`plugins/ShopCore/config.yml`（相对服务端根目录）。
- 首次启动从 jar 内 `config.yml` 释放默认值。
- 启动时读取一次，生成不可变 `PluginSettings` 快照。
- `/shopadmin reload` 重新读取文件并重建业务服务；缺键使用代码兜底值。
- 配置格式错误、数值越界、汇率非法会抛 `IllegalArgumentException`，
  插件启动/重载失败；看控制台日志定位具体键。

## 2. 服务端与数据库

| 键 | 类型 | 默认 | reload | 说明 |
| --- | --- | --- | --- | --- |
| `server.id` | string | `server` | 是 | 写入 businessNo；不能包含冒号 |
| `database.file` | string | `shop.db` | **否** | 相对 `plugins/ShopCore/`；改动需重启 |

## 3. 经济（Wallet）

| 键 | 类型 | 默认 | reload | 说明 |
| --- | --- | --- | --- | --- |
| `economy.payment-timeout-seconds` | int > 0 | `60` | 是 | 支付超时按 `UNKNOWN`，不发货 |
| `economy.assets-cache-seconds` | int > 0 | `60` | 是 | Wallet 官网资产表缓存 |
| `economy.balance-cache-seconds` | int > 0 | `30` | 是 | 管理命令全量余额缓存；主菜单余额另有 5 秒刷新节流 |
| `economy.timezone` | string | `Asia/Shanghai` | 是 | 钱包流水时间显示时区；非法值时回退 `Asia/Shanghai` |

注意：

- 货币种类、名称、符号、精度、官网启用状态**不在配置里**，全部来自 Wallet。
- 本服启用货币来自 Wallet 的 `plugins/Wallet/assets.yml` / `config.yml`，见
  [`economy-integration.md`](./economy-integration.md)。
- 不存在 `economy.mode`、`economy.currency`、`economy.display-name`、
  `economy.scale`、`economy.internal-test.*`；旧文件里的这些键会被忽略，建议删除。

## 4. 兑换

| 键 | 类型 | 默认 | reload | 说明 |
| --- | --- | --- | --- | --- |
| `exchange.enabled` | boolean | `true` | 是 | 关闭后兑换入口不显示可用方向 |
| `exchange.bidirectional` | boolean | `true` | 是 | 未显式配置的反向按 `1/rate` 生成 |
| `exchange.rates.<源币>.<目标币>` | decimal string > 0 | 见默认文件 | 是 | `1 源币 = rate 目标币` |

规则：

- 币种 code 必须与 Wallet 官网资产 code 一致（大小写不敏感）。
- 源币=目标币的条目忽略。
- 源/目标必须同时是本服启用货币，否则该方向不显示。
- 显式反向优先于自动倒数。
- 目标金额按目标币 `scale` 向下取整；为 0 拒绝。
- 非法数字或非正数会导致配置加载失败。

示例：

```yaml
exchange:
  enabled: true
  bidirectional: true
  rates:
    POINT:
      CNY: "0.01"
    # 显式反向优先于自动倒数：
    # CNY:
    #   POINT: "100"
```

## 5. 玩家商店与限制

| 键 | 类型 | 默认 | reload | 说明 |
| --- | --- | --- | --- | --- |
| `player-shop.max-listings-per-player` | int ≥ 1 | `28` | 是 | 每位玩家同时寄售笔数上限 |
| `limits.min-price` | decimal string > 0 | `1` | 是 | 单价下限（按商品/寄售货币的数值比较） |
| `limits.max-price` | decimal string > 0 | `1000000` | 是 | 单价上限；`0` 表示不设上限 |
| `limits.max-quantity` | int > 0 | `2304` | 是 | 单次上架/购买最大件数 |
| `limits.input-timeout-seconds` | int > 0 | `60` | 是 | 聊天输入等待时间 |
| `limits.confirm-timeout-seconds` | int > 0 | `120` | 是 | 点击确认有效期 |
| `rules.reset-zone` | string | 空 | **否** | 限购/涨价重置时区；空=服务器默认；改动需重启 |

说明：

- `min-price/max-price` 是数值门槛，不随币种汇率换算；不同 `scale` 的货币
  按各自数值比较。
- 库存 `-1`、限购/涨价/最高价 `0` 是游戏内输入约定，不在配置里。

## 6. 菜单标题

| 键 | 默认 |
| --- | --- |
| `menu.title-main` | `<dark_gray>商店</dark_gray>` |
| `menu.title-official` | `<dark_gray>官方商店</dark_gray>` |
| `menu.title-player` | `<dark_gray>玩家商店</dark_gray>` |
| `menu.title-listings` | `<dark_gray>我的上架</dark_gray>` |
| `menu.title-mailbox` | `<dark_gray>待领取物品</dark_gray>` |
| `menu.title-admin` | `<dark_gray>商店管理</dark_gray>` |
| `menu.title-admin-manage` | `<dark_gray>官方商品管理</dark_gray>` |
| `menu.title-admin-edit` | `<dark_gray>编辑官方商品</dark_gray>` |
| `menu.title-select-item` | `<dark_gray>选择物品</dark_gray>` |
| `menu.title-select-currency` | `<dark_gray>选择货币</dark_gray>` |
| `menu.title-exchange` | `<dark_gray>货币兑换</dark_gray>` |
| `menu.title-wallet-history` | `<dark_gray>钱包流水</dark_gray>` |

标题使用 MiniMessage；代码会强制关闭斜体。改完执行 `/shopadmin reload`。

## 7. 文案

`messages.*` 全部是 MiniMessage。常用分组：

- `messages.prefix`
- `messages.error.*`
- `messages.prompt.*`
- `messages.confirm.*`
- `messages.success.*`
- `messages.info.*`
- `messages.help.*`
- `messages.period.*` / `messages.state.*`
- `messages.ui.*`

规则：

- 新增文案键必须在 jar 内 `config.yml` 提供默认值，代码里保留可行动兜底。
- 禁止使用 `<italic>`；所有插件文案由 `Messages` 强制 `ITALIC=false`。
- 物品名使用 Adventure `Component` 占位符，不要预先转成英文/translation key。
- 余额行格式：`messages.info.balance-line` = `<currency>：<value>`，
  其中 `<value>` 由 Wallet 同款格式生成（金额 + ` ` + symbol）。

## 8. 已移除/忽略的键

以下键在 `Beta_0_3_0` 已删除，不应再写入配置：

```yaml
economy:
  mode: ...
  currency: ...
  display-name: ...
  scale: ...
  internal-test:
    starting-balance: ...
```

插件会忽略它们；保留只会造成误解。升级时建议按新默认配置清理。

## 9. 配置示例（最小可用）

```yaml
server:
  id: "server"

database:
  file: "shop.db"

economy:
  payment-timeout-seconds: 60
  assets-cache-seconds: 60
  balance-cache-seconds: 30
  timezone: "Asia/Shanghai"

exchange:
  enabled: true
  bidirectional: true
  rates:
    POINT:
      CNY: "0.01"

player-shop:
  max-listings-per-player: 28

limits:
  min-price: "1"
  max-price: "1000000"
  max-quantity: 2304
  input-timeout-seconds: 60
  confirm-timeout-seconds: 120

rules:
  reset-zone: ""
```

## 10. 配置相关故障

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| 启动失败：`limits.min-price` 等 | 非数字或范围错误 | 修正数值；`min-price > 0`，`max-price ≥ min-price` |
| 启动失败：`exchange.rates...` | 费率非数字/≤0 | 修正费率或删除该方向 |
| 修改数据库路径不生效 | SQLite 连接不热切换 | 重启服务端 |
| 修改重置时区不生效 | 存储层时区在启动时固定 | 重启服务端 |
| 文案显示原始标签 | MiniMessage 写错，代码回退为纯文本 | 修正标签；看控制台 |
| 兑换方向不显示 | 资产未在本服启用/官网停用 | 检查 Wallet `assets.yml` 与官网资产 |
| 余额行不显示某货币 | Wallet 本服开关关闭 | 在 Wallet 菜单启用，最多 5 秒同步 |
