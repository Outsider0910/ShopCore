# ShopCore 插件规范

> 适用范围：`ShopCore` 独立工程的源码、配置、文档、测试与构建产物。
> 目标：货币只认 Wallet、数据可追溯、UI 行为一致、后续改动能按同一套规则验收。

本文是工程约束，回答"必须怎么做 / 不得怎么做"。功能说明见
[`README.md`](../README.md)，货币细节见
[`economy-integration.md`](./economy-integration.md)，兑换细节见
[`exchange.md`](./exchange.md)，UI 组件契约见 [`components.md`](./components.md)。
一条规则只在一处定义，其他文档引用本文即可，不要复制。

## 1. 技术栈与依赖边界

| 项 | 约定 |
| --- | --- |
| 平台 | Paper 26.3，`api-version: '26.3'` |
| 语言 | Java 25，Gradle 工具链 `release = 25` |
| 构建 | Gradle Wrapper 9.8.1 + `run-paper` 3.1.0 |
| 文本 | Adventure MiniMessage |
| 数据库 | SQLite（驱动由服务端提供，不打包进 jar） |

允许依赖：

- JDK 标准库、Paper API（`compileOnly`）。
- MainMenu API（`compileOnly`）。
- Wallet API（`compileOnly`）。
- 测试：JUnit 5、Paper API、Wallet API、SQLite JDBC。

禁止依赖：

- MainMenu / Wallet 的内部实现类（只能使用 `*.api` 包）。
- 自行打包 MainMenu / Wallet 的类（不得使用 `implementation`）。
- 直接访问 YuDream 官网 HTTP 接口。
- 任何本地货币/余额系统（`internal-test`、`service`、`disabled` 等模式已删除）。

### 1.1 运行期依赖

```yaml
# paper-plugin.yml
dependencies:
  server:
    MainMenu:
      load: BEFORE
      required: true
    Wallet:
      load: BEFORE
      required: true
```

- MainMenu 缺失：商店不启用，因为没有时钟主菜单入口。
- Wallet 缺失：商店不启用，因为货币、余额、支付全部依赖 Wallet。
- 编译期 jar 路径固定在 `build.gradle`，升级 MainMenu/Wallet 时必须同步更新并重新验证。

### 1.2 对外契约

ShopCore 不对外导出货币 API，也不注册 `ShopEconomy` 一类服务：

- 其他插件需要货币能力时，依赖 Wallet API，不要把 ShopCore 当货币提供方。
- 其他插件需要主菜单入口时，注册 MainMenu 功能，不要往商店菜单里塞按钮。
- ShopCore 只消费 MainMenu / Wallet，不要求任何插件反向依赖 ShopCore。

## 2. 命名与坐标

| 项 | 规则 | 示例 |
| --- | --- | --- |
| 插件名 | 固定 `ShopCore` | `name: ShopCore` |
| Java 包 | `com.example.shopcore` | `com.example.shopcore.economy` |
| 权限 | `shopcore.<action>` | `shopcore.use`、`shopcore.admin` |
| UI ID | `shopcore:ui/<path>` | `shopcore:ui/main/balance` |
| 配置键 | 小写短横线 | `economy.balance-cache-seconds` |
| businessNo | `mc:<server.id>:shop:<orderId>` 或 `mc:<server.id>:exchange:<orderId>` | 不得含空格 |
| 产物 | `ShopCore-mc26_3-plugin-<版本>.jar` | `...-Beta_0_3_2.jar` |

- `server.id` 不得包含冒号。
- 新增权限必须同时写入 `paper-plugin.yml` 和代码检查，不得只写一边。
- 新增 UI ID 必须先更新 [`components.md`](./components.md)，再改代码。

## 3. 插件入口与生命周期

`ShopCorePlugin` 只负责组装，不写业务流程。

`onEnable` 顺序必须保持：

1. `saveDefaultConfig()`，读取 `PluginSettings`。
2. 创建 `Messages`。
3. 检查 `WalletEconomy.walletPresent()`；Wallet 不存在则禁用插件。
4. 创建 `WalletEconomy`、`SqliteShopStorage` 并完成 `warmUp()`。
5. 创建 `SessionManager`、`UiItems`、`DeliveryService`、`ExchangeService`、`ShopService`、`MenuService`。
6. 注册 MainMenu 功能 `shopcore:shop`。
7. `walletEconomy.preload()` 异步读取官网资产。
8. 注册 `MenuListener`、`ChatListener`、`PlayerListener`。
9. 通过 `LifecycleEvents.COMMANDS` 注册 `/shop`、`/shopadmin`。
10. 启动清理任务和主菜单余额刷新任务。

`onDisable`：

- 取消 MainMenu 功能注册。
- 取消所有 `BukkitTask`。
- 关闭 SQLite 连接。

`/shopadmin reload`：

- 重建配置、文案、业务服务与菜单服务。
- 不热切换数据库连接；`database.file` 改动需要重启。
- `WalletEconomy` 保持同一实例，但每次调用都重新获取 Wallet 服务，允许 Wallet 重载。
- 重载后已打开的旧菜单指向旧服务，关闭重开即可，不在规范内做热替换。

## 4. 包结构与分层

```text
command/      /shop、/shopadmin
config/       配置快照、MiniMessage 文案
economy/      Wallet 直连：资产、余额、扣款/入账、补偿
exchange/     兑换配置、比例、报价、订单、补偿
item/         完整组件编解码、UI 物品
listener/     菜单点击、聊天输入、进服/退服
menu/         GUI 渲染、翻页、选择货币、钱包流水
model/        商品、寄售、订单、邮箱、报价
service/      上架/购买/发货/价格计算
session/      聊天输入、点击确认、超时
storage/      SQLite 持久化
util/         金额、周期、调度、输入解析
```

分层约束：

- `menu` 只做渲染和点击收集，不得写经济/库存规则。
- `service` 编排用例，不得直接操作 GUI。
- `storage` 不得依赖 Bukkit 实体/背包；只接收模型对象和回调。
- `economy` 只能通过 Wallet API 读写货币，不得保存余额。
- `listener` 只做事件转交，不写业务流程。

## 5. 经济与货币契约

### 5.1 唯一数据源

- 货币类型、名称、符号、精度、官网启用状态：`WalletApi.wallet().assets()`。
- 玩家余额：`WalletApi.wallet().balance(...)` / `cachedBalance(...)`。
- 加币/扣币：只能调用 `WalletApi.wallet().credit(...)` / `debit(...)`。
- 流水：`WalletApi.wallet().transactions(...)`。

不得：

- 直接请求官网 `/api/plugins/yudream-wallet/**`。
- 在 ShopCore 内维护任何余额、测试钱包或本地流水账本。
- 用反射读取 Wallet 内部类；必须直接使用 Wallet API。

### 5.2 本服启用货币

商店展示、上架、兑换、余额行只使用"本服启用货币"，口径与 Wallet 的
`AssetSwitchStore` 一致：

1. 官网 `enabled=false` 的货币永远不启用。
2. `plugins/Wallet/assets.yml` 存在：以其中 `enabled` 列表为准（空列表 = 全部停用）。
3. 否则读取 `plugins/Wallet/config.yml` 的 `assets` 列表。
4. 两者都没有：官网 `enabled=true` 的货币全部启用。

切换本服货币后，ShopCore 最多 5 秒同步；不得要求重启插件。

### 5.3 余额显示与刷新

主菜单余额按钮必须与 Wallet 主菜单同款：

- 材质 `EMERALD`，名称"钱包"，白色标题。
- 每行 `<货币名>：<金额> <符号>`，例如 `绿宝石：85 绿宝石`。
- 无数值时：未绑定显示"未绑定网站账号"，加载中显示"…"。
- 底部提示"点击查看流水"。
- 点击打开钱包流水页。

刷新规范：

- 余额按钮每秒重绘一次；余额请求按玩家 5 秒节流。
- 渲染路径只读 `WalletService.cachedBalance(...)`，不得发 HTTP。
- 加/扣币成功后失效该玩家缓存。
- 玩家退出时清理余额刷新节流、未绑定标记与本地缓存。

### 5.4 支付

官方商店：

```text
debit(买家, 商品 assetCode, 总价)   businessNo = ...:debit
```

玩家商店：

```text
debit(买家, assetCode, 总价)        businessNo = ...:debit
credit(卖家, assetCode, 总价)       businessNo = ...:credit
目标入账确定失败时：
credit(买家, assetCode, 总价)       businessNo = ...:refund
```

必须：

- 先扣买家，再给卖家入账；不得用两次调用伪装原子转账。
- `businessNo` 稳定、可重试、官网幂等；重试沿用同一个值。
- 目标入账 `UNKNOWN` 时不继续、不退款、不发货，订单保持 `UNKNOWN`。
- 目标入账确定失败时才退款；退款结果未知同样标 `UNKNOWN`。

### 5.5 支付结果语义

| 状态 | 商店行为 |
| --- | --- |
| `SUCCESS` | 订单完成，物品进买家邮箱 |
| `INSUFFICIENT_FUNDS` | 恢复库存/寄售，订单 `FAILED` |
| `FAILED` | 恢复库存/寄售，订单 `FAILED` |
| `UNKNOWN` | 保留库存/寄售占位，订单 `UNKNOWN`，不发货 |

超时按 `UNKNOWN` 处理。只有确定成功才能发货。

### 5.6 货币兑换

- 源币、目标币都必须是本服启用货币。
- 比例来自 `exchange.rates`：`1 源币 = rate 目标币`。
- `bidirectional: true` 时未显式配置的反向按 `1/rate` 生成；显式反向优先。
- 目标金额按目标币 `scale` **向下取整**；为 0 拒绝。
- 执行顺序：`debit` 源币 → `credit` 目标币；目标确定失败时 `credit` 退回源币。
- 兑换必须使用 `WalletEconomy.exchange(...)`，不得复用同币种 `transfer(...)`。

## 6. 物品与 UI 规范

### 6.1 物品身份

- 商品/寄售保存数量 1 的 `ItemStack.serializeAsBytes()`。
- 匹配使用 `ItemCodec.sameComponents`（Paper `isSameItemSameComponents`：
  材质 + 全部组件，忽略数量）。
- 禁止退化成 `Material`、显示名、Lore 或 NBT 局部匹配。
- 展示物品必须是克隆；不得修改商品/寄售本身。

### 6.2 UI 物品

- 所有商店按钮必须通过 `UiItems.create(...)` 创建。
- 每个 UI 物品必须同时带：
  - `custom_data.shopcore:ui_id` 字符串；
  - `custom_model_data` 第 0 个字符串（同一个 UI ID）。
- UI ID 变更视为破坏资源包契约，必须先更新 [`components.md`](./components.md)。
- 带 `shopcore:ui_id` 的物品禁止上架/寄售。
- 点击动作以 `ShopMenuHolder` 的槽位动作表为准，不信任物品 PDC 或客户端数据。

### 6.3 文字与样式

- 所有插件文字**不得斜体**。
- 物品名称、Lore、菜单标题、余额行、流水行必须显式
  `ITALIC=false`（`UiItems.noItalic` 或 `Messages`）。
- 新增 UI 物品必须走 `UiItems.create`，不得直接 `meta.displayName(Component.text(...))`。
- 聊天文案统一 MiniMessage；配置缺键时使用代码内兜底文案。
- 禁止在配置默认文案中使用 `<italic>`。

### 6.4 返回主菜单

所有页面中的"关闭"按钮统一为"返回主菜单"：

- 优先 `MainMenuApi.openOrNull(player)`。
- 返回 `false` 或旧版 MainMenu 没有该 API 时，执行 `/cd`。
- 必须下一 tick 打开，避免与当前 GUI 关闭事件冲突。
- 不得用 `player.closeInventory()` 代替返回主菜单。

### 6.5 菜单交互

- 菜单点击/拖拽一律取消，只响应动作表登记的槽位。
- 翻页、返回、关闭由 `MenuService` 统一渲染。
- 点击回调在主线程执行；不得在回调里做阻塞 IO。
- 菜单渲染失败时展示错误物品并提供返回主菜单。
- 主菜单余额按钮每秒重绘，其他页面按打开/翻页时加载。

### 6.6 钱包流水页

- 使用 Wallet `transactions(...)`，只查询本服启用货币。
- 纸图标，按时间倒序；第 1~5 行内容，底部翻页。
- 每条显示：入账/出账、金额、币种、时间、余额、备注、业务号。
- 时间时区取 `economy.timezone`，默认 `Asia/Shanghai`。

## 7. 数据与存储

数据库：`plugins/ShopCore/shop.db`，SQLite WAL。

Schema v2 表：

| 表 | 作用 |
| --- | --- |
| `official_items` | 官方商品模板、`asset_code`、价格规则、库存、限购、重置周期 |
| `player_listings` | 玩家寄售、`asset_code`、卖家、单价、剩余数量 |
| `official_purchases` | 每位玩家在每个官方商品上的周期已购件数 |
| `orders` | 订单审计：`business_no`、`asset_code`、买卖家、金额、状态 |
| `exchange_orders` | 兑换审计：源/目标币、金额、状态 |
| `mailbox` | 待领取物品 |
| `meta` | schema 版本 |

必须：

- 所有读写走 `SqliteShopStorage` 的单线程 DB executor。
- 组合操作必须事务：扣库存/寄售 + 建订单；下架 + 进邮箱；
  完成订单 + 进邮箱 + 累计购买数；兑换订单状态落库。
- 旧库自动补 `asset_code` 列；空值运行期解析为第一个本服启用货币。
- 不得绕过存储层直接写 `shop.db` 或 sidecar 文件。
- 不得在异步回调里直接操作背包；背包操作回主线程。

## 8. 线程模型

- SQLite 只在 DB executor 上访问。
- GUI、背包、玩家消息、MainMenu 操作只在主线程执行。
- Wallet/HTTP 回调在工作线程；需要 Bukkit API 时通过 `Scheduler.runSync` 回主线程。
- 渲染路径禁止阻塞 IO、数据库查询和 HTTP。
- 支付超时按 `UNKNOWN`，不得在超时后继续发货或继续加币。

## 9. 命令与权限

| 命令 | 权限 | 说明 |
| --- | --- | --- |
| `/shop` / `/scshop` | `shopcore.use` | 打开商店 |
| `/shop mail` | `shopcore.use` | 打开待领取 |
| `/shop confirm <token>` | `shopcore.use` | 点击确认 |
| `/shop cancel <token>` | `shopcore.use` | 点击取消 |
| `/shopadmin` | `shopcore.admin` | 管理菜单 |
| `/shopadmin reload` | `shopcore.admin` | 重载 |
| `/shopadmin orders` | `shopcore.admin` | 待处理订单 |
| `/shopadmin resolve ...` | `shopcore.admin` | 收口订单 |
| `/shopadmin exchanges` | `shopcore.admin` | 兑换记录 |
| `/shopadmin exchange ...` | `shopcore.admin` | 管理员兑换 |
| `/shopadmin wallet ...` | `shopcore.admin` | Wallet 余额 |
| `/shopadmin currency` | `shopcore.admin` | Wallet 官网货币 |
| `/shopadmin debug` | `shopcore.admin` | UI 组件自检 |

规范：

- 命令通过 `LifecycleEvents.COMMANDS` 注册，不写 `paper-plugin.yml` 的 `commands:`。
- 权限节点必须同时登记在 `paper-plugin.yml`。
- 命令输出统一走 `Messages`，不得直接拼接原始 MiniMessage 字符串。
- 管理命令不得绕过 `WalletEconomy` 直接改官网数据。

## 10. 配置与文案

`config.yml` 分组：

- `server.id`
- `database.file`
- `economy.payment-timeout-seconds`、`assets-cache-seconds`、
  `balance-cache-seconds`、`timezone`
- `exchange.enabled`、`bidirectional`、`rates`
- `player-shop.max-listings-per-player`
- `limits.*`
- `rules.reset-zone`
- `menu.*`
- `messages.*`

规范：

- 货币种类/名称/精度不得写入配置；全部来自 Wallet。
- 新增配置键必须有代码兜底值；旧配置缺键时插件仍可启动。
- 文案使用 MiniMessage；默认文案在 jar 内 `config.yml`，代码保留可行动兜底。
- `database.file`、`rules.reset-zone` 变更需重启；其余配置支持 `/shopadmin reload`。
- 配置中的文案不得依赖斜体标签。

## 11. 测试与验收

必须通过：

```bash
./gradlew test
./gradlew build
```

测试至少覆盖：

- 金额解析/精度/非法输入。
- 官方商品价格、涨价、限购、库存恢复。
- 玩家寄售购买、部分购买、下架、退款回邮箱。
- SQLite 事务、订单状态、schema 迁移。
- 兑换比例、双向、目标精度向下取整。
- Wallet 同币种付款、跨币种兑换、失败退款、结果未知不退款、
  余额不足不继续加币、余额缓存与未绑定检测。
- UI 组件 ID 与序列化相似性。

服务端验收：

- Paper 26.3 测试服加载成功。
- Wallet 正常读取官网资产。
- `/shopadmin currency`、`/shopadmin wallet` 正常。
- 购买/兑换后官网余额与 `orders`/`exchange_orders` 一致。
- 人为超时：订单 `UNKNOWN`，不发货。
- GUI 点击全流程需真实客户端人工验收。

当前基线：42 个单元测试。

## 12. 版本与产物

版本号、产物命名、归档、ChangeLog 与发布流程统一遵循
[`versioning-and-artifacts.md`](./versioning-and-artifacts.md)：

- 测试期修 bug：第 3 位 +1；新增功能：第 2 位 +1、第 3 位归 0。
- 版本号唯一来源：`build.gradle`。
- 产物：`ShopCore-mc26_3-plugin-<版本>.jar`，归档到 `./release/ShopCore/`。
- 每次版本变化必须追加 ChangeLog 并更新 README 版本行。
- 测试服部署前移除同插件旧 jar，避免 `Ambiguous plugin name`。

## 13. 文档纪律

- `README.md`：这是什么、怎么用、命令/配置/已知限制。
- `docs/README.md`：文档索引与阅读顺序。
- `docs/specification.md`：必须/禁止级工程约束（本文）。
- `docs/versioning-and-artifacts.md`：版本号、产物命名、归档、ChangeLog、发布流程。
- `docs/configuration.md`：配置键、默认值、reload 范围。
- `docs/ui.md`：菜单槽位、余额/流水、返回主菜单、刷新、无斜体。
- `docs/storage.md`：Schema、事务、迁移、备份。
- `docs/testing.md`：单测、mock、手工验收、发布门禁。
- `docs/operations.md`：部署、升级、回滚、故障处置。
- `docs/economy-integration.md`：Wallet 契约、支付与 schema。
- `docs/exchange.md`：兑换比例、执行顺序、补偿。
- `docs/components.md`：UI ID、资源包契约。
- `docs/design-notes.md`：需求映射、流程、表结构、已知边界。
- `AGENTS.md`：AI 协作入口、硬规则、关键文件。

行为变更必须同步更新对应文档与 ChangeLog；一条规则只在一处定义。

## 14. 禁止事项

- 禁止本地余额、测试钱包、本地账本或任何绕过 Wallet 的货币写路径。
- 禁止直接请求 YuDream 官网接口。
- 禁止反射/复制 Wallet 内部实现。
- 禁止把 Wallet/MainMenu 打进 ShopCore jar。
- 禁止绕过 `ItemCodec` 用材质/显示名匹配物品。
- 禁止 UI 物品不带 `custom_data` / `custom_model_data`。
- 禁止在渲染路径做数据库/HTTP/阻塞 IO。
- 禁止在异步回调直接修改背包。
- 禁止菜单文字斜体。
- 禁止用 `player.closeInventory()` 代替"返回主菜单"。
- 禁止在 `UNKNOWN` 支付结果下发货或继续入账。
- 禁止提交调试日志、注释掉的死代码、无主 TODO。

## 15. 提交前自查

- [ ] `./gradlew clean build` 通过，测试全绿。
- [ ] 版本号、ChangeLog、`release/ShopCore/` 产物一致。
- [ ] 货币读写全部经过 `WalletEconomy`。
- [ ] 新 UI 物品通过 `UiItems.create`，ID 已写入 `components.md`。
- [ ] 菜单文字无斜体；关闭按钮返回主菜单。
- [ ] 余额显示使用 Wallet 缓存，刷新节流，未绑定显示正确。
- [ ] 数据库访问只在 DB executor，组合操作在事务内。
- [ ] 异步回调中的 Bukkit 操作已回主线程。
- [ ] 文档、配置默认值、权限声明同步更新。
- [ ] 测试服加载成功，无报错；旧同名 jar 已移除。
