# ShopCore ChangeLog

## Beta_0_3_2（2026-10-20）

### 修复

- `economy.assets-cache-seconds` / `economy.balance-cache-seconds` 现在
  `/shopadmin reload` 后立即生效，不再需要重启服务端。

### 文档

- 新增 [`docs/README.md`](docs/README.md)：文档索引与阅读顺序。
- 新增 [`docs/configuration.md`](docs/configuration.md)：全部配置键、默认值、reload 范围。
- 新增 [`docs/ui.md`](docs/ui.md)：菜单槽位、余额/流水、返回主菜单、刷新、无斜体。
- 新增 [`docs/storage.md`](docs/storage.md)：Schema v2、事务、迁移、备份、并发。
- 新增 [`docs/testing.md`](docs/testing.md)：单测、Wallet mock、GUI 验收、发布门禁。
- 新增 [`docs/operations.md`](docs/operations.md)：部署、升级、回滚、故障处置。

## Beta_0_3_1（2026-10-20）

### UI

- 主菜单余额按钮改为 Wallet 同款“钱包”样式：绿宝石图标、白色标题、按
  `货币名：余额 符号` 逐行显示本服启用货币；未绑定网站账号显示“未绑定网站账号”。
- 余额每秒重绘、5 秒节流异步刷新，只读 Wallet 余额缓存；本服货币开关与
  Wallet `assets.yml` / `config.yml` 同口径。
- 点击“钱包”打开钱包流水页（翻页、入账/出账、时间、余额、备注、业务号）。
- 所有“关闭”改为 Wallet 风格“返回主菜单”（`MainMenuApi.openOrNull`，旧版退回 `/cd`）。
- 菜单标题、UI 物品 name/lore、聊天文案统一 `ITALIC=false`，不再出现斜体。

### 文档

- 新增 [`docs/specification.md`](docs/specification.md)：依赖边界、Wallet 货币契约、
  UI/文案、存储、线程、命令权限、测试与版本产物规范。
- 新增 [`docs/versioning-and-artifacts.md`](docs/versioning-and-artifacts.md)：
  涨号规则、产物命名、归档、ChangeLog 与测试服发布流程。
- 新增 [`docs/README.md`](docs/README.md)、[`docs/configuration.md`](docs/configuration.md)、
  [`docs/ui.md`](docs/ui.md)、[`docs/storage.md`](docs/storage.md)、
  [`docs/testing.md`](docs/testing.md)、[`docs/operations.md`](docs/operations.md)：
  文档索引、配置、菜单、存储、测试与运维规范。

### 测试

- 新增余额缓存/未绑定检测用例，单元测试共 42 个。
- `./gradlew test` 全部通过。

## Beta_0_3_0（2026-10-20）

### 命名

- 项目目录、插件名、Java 包名、UI 命名空间、权限节点和测试服数据目录统一为
  `ShopCore` / `com.example.shopcore` / `shopcore:*` / `shopcore.*`；
  测试服数据目录迁移到 `plugins/ShopCore/`。

### 重构

- **清除 ShopCore 原有货币系统**：删除 `ShopEconomy` / `PaymentResult` 契约、
  `internal-test` 测试钱包（`test_wallet`、`test_economy_ledger`）、`service` /
  `disabled` 模式，以及 `economy.mode` / `economy.currency` / `display-name` /
  `scale` / `starting-balance` 配置。
- **按 Wallet 规范重新对接**：
  - `paper-plugin.yml` 将 Wallet 改为 `load: BEFORE, required: true`；
  - 删除反射桥 `WalletBridge` / `WalletAccess` / `WalletShopEconomy`，新增
    `WalletEconomy` 直接调用 `WalletApi.wallet()` 的 `assets/balance/credit/debit`；
  - 编译期依赖 `../mcPlugin/release/Wallet/Wallet-mc26_3-plugin-Beta_0_2_1.jar`
    （compileOnly + 测试）。
- **货币类型与数量全部来自官网**：
  - 商品/寄售新增 `asset_code`，上架时在 GUI 里选择 Wallet 官网 `enabled` 资产；
  - 价格解析/显示/落库按该资产的 `scale`；
  - 主菜单“我的余额”逐行显示玩家在所有官网启用货币上的余额；
  - `/shopadmin currency` 列出官网资产表，`/shopadmin wallet` 支持不带币种列全部余额。
- **商店货币改动写回官网**：购买/兑换只走 `WalletEconomy.debit/credit`，
  businessNo 语义保持 `mc:<server.id>:shop|exchange:<orderId>:debit|credit|refund`。
  兑换使用独立的 `WalletEconomy.exchange` 路径，扣源币与加目标币可以是不同资产/精度。
- SQLite schema 升级到 v2：`official_items` / `player_listings` / `orders` 自动补
  `asset_code` 列；旧库空值解析为官网第一个启用货币。

### 移除

- `/shopadmin test ...`、`/shopadmin economy ...` 测试货币命令与
  `shopcore.admin.economy` 权限。
- 本地余额表及所有本地记账代码。

### 测试

- 单元测试更新为 41 个；删除测试钱包幂等转账用例，SQLite 用例改用
  `CurrencyLookup` 假实现（CNY scale=2 / POINT scale=0）。
- 新增 `WalletEconomyTest`：同币种付款、跨币种兑换、目标入账失败退款、
  结果未知不退款、余额不足不继续加币、资产精度/格式化。
- `./gradlew test` 全部通过。

## Beta_0_2_0（2026-10-20）

### 新增

- 接入 `mcPlugin/Wallet`：`economy.mode: wallet`，通过反射读取 `WalletApi`，
  Wallet 作为商店主货币；`paper-plugin.yml` 声明 Wallet 为 BEFORE/非必需依赖。
- 接入 `mcPlugin/MainMenu`：`shopcore:shop` 注册为时钟主菜单功能项；
  `paper-plugin.yml` 声明 MainMenu 为 BEFORE/必需依赖；compileOnly 依赖
  `../mcPlugin/release/MainMenu/MainMenu-mc26_3-plugin-Beta_1_2_0.jar`。
  入口 UI ID `shopcore:ui/mainmenu/entry`。
- 货币兑换：主菜单“货币兑换”入口、方向列表、聊天输入源币数量、可点击确认。
  - `exchange.rates`：`1 源币 = rate 目标币`。
  - `exchange.bidirectional`：true 时未显式配置的反向方向自动按 1/rate 生成；
    false 时只允许显式方向；显式反向费率优先。
  - 目标金额按目标货币 `scale` 向下取整；源/目标必须存在于 Wallet enabled 资产表。
  - 订单写入 `exchange_orders`；businessNo 为
    `mc:<server.id>:exchange:<orderId>`，Wallet 腿为 `:debit` / `:credit` / `:refund`。
  - Wallet 无原子兑换：扣源币 → 加目标币 → 目标确定失败时退回源币；结果未知标 UNKNOWN。
- 管理员命令：`/shopadmin exchanges`、`/shopadmin exchange <玩家> <源币> <目标币> <数量>`、
  `/shopadmin wallet <玩家> [币种]`。
- UI：`shopcore:ui/main/exchange`、`shopcore:ui/exchange/pair`。
- 测试：新增兑换配置/方向/精度/订单测试，总计 36 个；Wallet + mock 实测双向兑换、
  余额与订单状态、单向模式拒绝反向。
- 测试服 mock 增加 Wallet 的 `/assets` 路径（与 旧货币插件 的 `/admin/assets` 并存）。

### 修复

- 公屏物品名改用 Adventure `Component` 占位符，中文客户端按原版翻译显示中文，
  不再显示英文/translation key。
- 官方商店页底部增加管理员“上架官方商品 / 管理官方商品”按钮；管理员判断兼容 OP。
- 主货币名称/精度严格以 Wallet `WalletAsset` 为准；`config.yml` 的
  `display-name/scale` 只作加载前回退。主货币缺失/停用时禁用购买。
- 新增 `/shopadmin currency` 查看当前经济模式和 Wallet 实际主货币信息。
- 新增管理员测试命令 `/shopadmin test balance|give|take|set <玩家> [数量] [币种]`：
  wallet 模式直接调用 Wallet credit/debit，internal-test 走测试钱包；用法已写入
  `/shopadmin help`。

### 调整

- 商店购买在 `wallet` 模式下改走 Wallet；`internal-test` / `service` 仍保留。
- 商店价格解析与 SQLite 计算精度跟随经济提供者的实际 scale。
- README/组件/经济对接/兑换规范/设计说明同步更新。

## Beta_0_1_0（2026-10-20）

首个可运行版本。

### 新增

- 独立 Gradle 工程：Paper 26.3 / Java 25；产物
  `ShopCore-mc26_3-plugin-Beta_0_1_0.jar`。
- 官方商店：管理员游戏内选择背包物品上架，编辑单价、库存、每位玩家限购、
  每件涨价百分比、单件最高价、重置周期（永不/每天/每周/每月）、启停、删除。
- 玩家商店：上架/下架/购买/部分购买；聊天输入数量与单价；可点击确认；
  每位玩家寄售笔数上限；不能购买自己的寄售。
- 物品组件：数量 1 的完整 `ItemStack.serializeAsBytes()` 落库；匹配用
  `ItemStack.isSimilar`（`isSameItemSameComponents`，材质 + 全部组件、忽略数量）；
  展示克隆不修改商品；UI 物品带 `custom_data.shopcore:ui_id` 和
  `custom_model_data` 第 0 个字符串。
- SQLite 持久化：官方商品、玩家寄售、订单、官方购买计数、邮箱、测试钱包；
  WAL；单线程 DB executor；关键组合操作事务化。
- 发货邮箱：背包满时寄存，进服提示/自动领取；下架与购买统一走邮箱。
- 经济解耦：`ShopEconomy` 接口 + `PaymentResult`；`internal-test` /
  `service` / `disabled` 三种模式；支付结果未知时订单 UNKNOWN、不发货。
- 管理员订单工具：`/shopadmin orders`、`/shopadmin resolve <订单> complete|cancel`。
- 命令：`/shop`、`/shop mail`、`/shopadmin`、`/shopadmin reload`、测试钱包命令；
  `/shopadmin debug` 可输出 UI 组件与序列化往返自检。
- 25 个单元测试：金额/价格/重置周期/输入/SQLite 交易。
- 文档：README、组件契约、经济对接、设计说明、Agent 入口。

### 验证

- `./gradlew test` 25/25 通过。
- `./gradlew build` 通过并归档产物。
- Paper 26.3 测试服加载成功；SQLite 驱动由服务端提供；控制台命令、重载、
  测试钱包加扣币落库验证通过；`/shopadmin debug` 验证 UI 组件写入正确、
  组件相似性判断正确且能识别组件变更。
- GUI 点击全流程未连接客户端，需人工验收。

### 已知限制

- 正式货币系统尚未接入；`internal-test` 不是正式账本。
- 单服 SQLite；未实现跨服、多币种、交易税、搜索/分类、玩家寄售过期。
- 极端崩溃窗口可能造成邮箱重复领取；见 `docs/economy-integration.md` §7。
