# ShopCore

> Minecraft **26.3**（Paper）商店插件 · Java **25** · 独立 Gradle 工程
>
> 版本：`Beta_0_3_2`（2026-10-20）
>
> 官方商店 + 玩家商店；物品严格按**完整组件**识别；商店自身 UI 按钮带
> `custom_data` / `custom_model_data` 组件，供资源包按组件值切换模型。

## 当前状态

- 已完成：SQLite 持久化、官方商品管理、玩家寄售、上架/下架/购买、聊天输入 +
  点击确认、邮箱发货、官方商品规则（库存/限购/涨价/重置周期）、Wallet 经济、
  多币种商品/寄售、货币兑换、时钟主菜单入口、42 个单元测试、Paper 26.3 测试服验证。
- 经济系统：**只使用 `mcPlugin/Wallet`**。货币类型、名称、精度、余额全部由 Wallet
  从 Wallet 官网读取；商店的所有扣款/入账都调用 Wallet 的 `debit`/`credit` 写回官网。
  本插件不再保存余额，也没有 `internal-test` / `service` / `disabled` 模式。
- 货币种类严格取自 Wallet：上架商品/寄售时从官网 `enabled` 资产中选择一种货币；
  商品价格精度、余额展示、购买扣款都按该资产 code 处理。官网停用的货币不能选择，
  已使用它的商品购买时会被拒绝，不会自动换币。
- 已验证：插件在 Paper 26.3 测试服正常启用；SQLite 驱动由服务端提供、无需额外打包；
  控制台命令、`/shopadmin reload`、Wallet 余额读取/加减币、货币兑换落库正确；
  `/shopadmin debug` 确认 UI 物品组件写入与相似性判断正确。
- 未验证：GUI 点击全流程需要真实客户端人工验收（本机没有连接客户端）。

## 功能

### 时钟主菜单入口

- 商店以 `shopcore:shop` 功能项注册到 **MainMenu**（手持时钟右键 / `/cd` 的 6×9 菜单）。
- 点击图标直接打开商店主菜单。
- `paper-plugin.yml` 已声明 `MainMenu: load=BEFORE, required=true`；没有 MainMenu 时商店不会启用。
- 编译期依赖 `../mcPlugin/release/MainMenu/MainMenu-mc26_3-plugin-Beta_1_2_0.jar`
  （compileOnly，运行期由 MainMenu 插件提供）。
- 入口物品 UI ID：`shopcore:ui/mainmenu/entry`，基础材质 `EMERALD`。

### 官方商店

- 管理员在游戏内打开管理菜单，选择背包物品，再选择一种 Wallet 官网启用的货币，
  输入单价即可上架。
  官方商店页底部也直接提供“上架官方商品 / 管理官方商品”按钮（OP 或
  `shopcore.admin` 可见）。
  官方商品保存的是该物品的模板，**不会扣除管理员背包里的物品**；库存由规则单独控制。
- 商品记录 `asset_code`，价格精度按该 Wallet 资产 `scale`；管理员可在编辑页修改货币。
- 默认：库存无限、每位玩家不限购、不涨价、不重置。
- 管理员可在游戏内编辑：
  - 单价；
  - 库存（`-1` = 无限）；
  - 每位玩家限购件数（`0` = 不限）；
  - 每件涨价百分比（按该玩家当前周期已购件数逐件递增）；
  - 单件最高价（`0` = 不设上限）；
  - 重置周期（永不 / 每天 / 每周 / 每月）；
  - 启用/停用；
  - 删除。
- 一次购买多件时，涨价逐件计算；确认消息展示本次总价。

### 玩家商店

- 玩家自由上架、下架、购买；支持部分购买。
- 上架流程：
  1. 点击“上架物品” → 打开背包物品选择界面；
  2. 点击要上架的物品 → **选择货币**（Wallet 官网 enabled 资产）；
  3. 聊天框输入 `数量 单价`；
  4. 公屏出现数量/单价/总价确认消息，点击 `[确认]`；
  5. 物品从背包扣除并写入寄售。
- 下架流程：
  1. 点击玩家商店里的“下架物品”（或主菜单的“我的上架”，也可以直接点自己的寄售）；
  2. 点击要下架的寄售；
  3. 点击公屏确认的 `[确认]`；
  4. 物品进入“待领取”，背包有空位时自动放入。
- 购买流程：
  1. 点击要购买的商品；
  2. 输入购买数量；
  3. 点击确认消息里的 `[确认]`；
  4. 支付成功后物品进入“待领取”，背包有空位时自动放入。
- 玩家不能购买自己的寄售；同一商品有未完成订单时会阻止重复下单。
- 每位玩家的同时寄售笔数默认上限 28，可在配置中调整。

### 货币兑换

- 主菜单新增“货币兑换”入口，列出配置允许的兑换方向。
- 点击方向 → 关闭菜单 → 聊天输入源币数量 → 公屏确认 → 点击确认执行。
- 兑换比例和是否允许双向都从 `config.yml` 读取：
  ```yaml
  exchange:
    enabled: true
    bidirectional: true
    rates:
      POINT:
        CNY: "0.01"     # 1 POINT = 0.01 CNY
  ```

- `bidirectional: true` 时，未显式配置的反向方向自动按 `1/rate` 生成；显式反向费率优先。
- `bidirectional: false` 时只允许 `rates` 里写出的方向。
- 目标金额按目标货币精度**向下取整**；两个币种都必须启用且存在于 Wallet 官网资产表。
- 详细规范见 [`docs/exchange.md`](./docs/exchange.md)。

### 余额与流水

- 主菜单的“钱包”绿宝石按钮采用 Wallet 主菜单同款样式：
  `货币名：余额 符号`，逐行显示本服启用货币；标题白色、数值白色、提示深灰色。
- 余额检测完全按 Wallet 的口径：只读 Wallet 的余额缓存，每 5 秒异步刷新；
  未绑定网站账号显示“未绑定网站账号”，首次加载显示“…”。
- 点击“钱包”按钮打开钱包流水页（纸图标，按时间倒序），支持翻页；点击“返回主菜单”
  使用 Wallet 的 `MainMenuApi.open`，旧版 MainMenu 自动退回 `/cd`。
- 本服启用货币与 Wallet 的 `assets.yml` / `config.yml` 口径一致；管理员在 Wallet
  菜单里切换币种后，商店最多 5 秒同步。

### 文字样式

- 商店所有菜单标题、按钮名称、说明文字、余额/流水文字均显式关闭斜体
  （`ITALIC=false`），包括资源包 UI 物品的 name/lore。
- 所有“关闭”按钮改为 Wallet 风格的“返回主菜单”。

### 物品名称与聊天

- 公屏确认/成功消息里的物品名使用 Adventure `Component`（原版物品是
  translatable component），由客户端按自己的语言显示；中文客户端会显示“钻石”等中文，
  不再把物品名转成英文/translation key。
- 自定义名称的物品仍然显示玩家设置的自定义名称。

### 物品组件

- **不按材质 + 名字判断物品**。商店把数量为 1 的 `ItemStack.serializeAsBytes()` 落库，
  匹配时用 Paper 的 `ItemStack.isSimilar`（底层 `isSameItemSameComponents`，比较材质 +
  全部组件、忽略数量）。附魔、耐久、容器内容、`custom_data`、`custom_model_data`
  等全部参与比较。
- 上架时只扣除与所选物品**所有组件完全一致**的物品；组件变了就视为不同物品。
- 展示物品是克隆，不会改动寄售/官方商品本身。
- 商店自身按钮物品同时带：
  - `minecraft:custom_data` 中的 `shopcore:ui_id` 字符串；
  - `minecraft:custom_model_data` 的第 0 个字符串（值为同一个 UI ID）。
- 资源包可按 `custom_model_data` 字符串切换模型。完整 ID 列表与示例见
  [`docs/components.md`](./docs/components.md)。

### 发货与邮箱

- 购买/下架后先写 SQLite 邮箱，再尝试放入背包。
- 背包满时物品留在“待领取”，不会掉落、不会丢失。
- 玩家进服时会提示并尝试自动领取。

## 命令与权限

| 命令 | 说明 | 权限 | 默认 |
| --- | --- | --- | --- |
| `/shop` / `/scshop` | 打开主菜单 | `shopcore.use` | true |
| `/shop mail` | 打开待领取 | `shopcore.use` | true |
| `/shop help` | 命令帮助 | `shopcore.use` | true |
| `/shop confirm <token>` | 点击确认按钮时由聊天组件执行 | `shopcore.use` | true |
| `/shop cancel <token>` | 点击取消按钮时由聊天组件执行 | `shopcore.use` | true |
| `/shopadmin` / `/scadmin` | 打开管理菜单 | `shopcore.admin` | op |
| `/shopadmin reload` | 重载配置、文案和经济适配 | `shopcore.admin` | op |
| `/shopadmin orders` | 查看 PENDING/UNKNOWN 订单 | `shopcore.admin` | op |
| `/shopadmin resolve <订单UUID\|businessNo> complete\|cancel` | 人工完成/取消订单 | `shopcore.admin` | op |
| `/shopadmin exchanges` | 查看最近兑换记录 | `shopcore.admin` | op |
| `/shopadmin exchange <玩家> <源币> <目标币> <数量>` | 管理员直接兑换（仍走报价/订单/补偿） | `shopcore.admin` | op |
| `/shopadmin wallet <玩家> [币种]` | 查询 Wallet 余额；不带币种时列出全部启用货币 | `shopcore.admin` | op |
| `/shopadmin currency` | 列出 Wallet 官网资产表（code/名称/精度/启用状态） | `shopcore.admin` | op |
| `/shopadmin debug` | 输出 UI 物品的 `ui_id`、`custom_model_data`、组件相似性/变更检测结果，供资源包自检 | `shopcore.admin` | op |

- 所有权限都登记在 `paper-plugin.yml`，代码里的检查与登记保持一致。
- 命令用 Paper 的 `LifecycleEvents.COMMANDS` 注册，不写 yml `commands:`。
- 点击确认按钮实际执行 `/shop confirm <token>`，token 与玩家 UUID 绑定，默认 120 秒过期。

## 配置

完整默认值见 [`src/main/resources/config.yml`](./src/main/resources/config.yml)。关键项：

```yaml
server:
  id: "server"                 # 写进经济系统 businessNo，不能含冒号

economy:
  payment-timeout-seconds: 60  # 超时按“结果未知”处理，不发货
  assets-cache-seconds: 60     # Wallet 官网资产表缓存
  balance-cache-seconds: 30    # 玩家余额缓存

exchange:
  enabled: true
  bidirectional: true          # true 时自动允许反向（1/rate）
  rates:
    POINT:
      CNY: "0.01"              # 1 POINT = 0.01 CNY

player-shop:
  max-listings-per-player: 28

limits:
  min-price: "1"
  max-price: "1000000"
  max-quantity: 2304
  input-timeout-seconds: 60
  confirm-timeout-seconds: 120

rules:
  reset-zone: ""               # 留空使用服务器默认时区
```

- **货币种类/名称/精度不在 config.yml 里**：全部来自 Wallet 读取的官网资产表。
  上架商品/寄售时在 GUI 里选择一种官网启用货币；`asset_code` 随商品/寄售落库。
- **价格精度按资产**：`POINT(scale=0)` 只能输入整数，`CNY(scale=2)` 允许两位小数。
- **兑换**：`exchange.rates` 只配置方向与比例，源/目标资产必须同时存在于 Wallet
  官网 enabled 资产表。
- 聊天输入格式：
  - 上架：`数量 单价`，例如 `10 100`；也支持 `10,100`、`10x100`。
  - 购买：数量，例如 `3`，允许 `3个`。
  - 管理员修改数值：直接输入数字；库存 `-1` 表示无限，限购/涨价/上限 `0` 表示关闭。
- 文案用 MiniMessage；改完执行 `/shopadmin reload`。数据库路径变更需重启。

## 构建与运行

要求 JDK 25（Paper 26.3 的编译目标），并且工作区里要有：

- `../mcPlugin/release/MainMenu/MainMenu-mc26_3-plugin-Beta_1_2_0.jar`（compileOnly）
- `../mcPlugin/release/Wallet/Wallet-mc26_3-plugin-Beta_0_2_1.jar`（compileOnly，运行期由 Wallet 提供）

```bash
./gradlew test       # 42 个单测：金额、价格、兑换比例/双向、输入解析、SQLite 交易
./gradlew build      # 编译 + 单测 + 打包，产物归档到 ./release/ShopCore/
./gradlew runServer  # 本地测试服（首次需在 ./run/eula.txt 改 eula=true）
```

产物：`./release/ShopCore/ShopCore-mc26_3-plugin-Beta_0_3_2.jar`。

共享测试服（`../test-server`，Paper 26.3 + JDK 25，已安装 Wallet + mock 钱包）：

```text
把 jar 放进 ../test-server/plugins/
先启动 mock：mock-wallet/start-mock.bat
再启动服务端：start-server.bat
连接 127.0.0.1:25566（离线模式，OP：Outsider0910）
```

测试服 Wallet 指向 `http://127.0.0.1:18080`，mock 预置 `Outsider0910`
1000 POINT / 100.00 CNY。可以直接验证：

```text
/shopadmin currency
/shopadmin wallet Outsider0910
/shopadmin wallet Outsider0910 POINT
/shopadmin exchange Outsider0910 POINT CNY 100
/shopadmin exchanges
```

## 设计决策（需要你确认时改这里）

以下是为了不阻塞开发而采用的默认解释，均可在后续调整：

1. **“点击想要上架的物品”** 实现为：关闭当前商店界面，打开一个镜像玩家主背包
   （槽位 0-35）的选择界面，点击其中物品进行选择。不包含盔甲/副手槽。
2. **“数量和价格”** 解释为 `数量 单价`；价格是**单价**，总价 = 单价 × 数量
   （官方商店按涨价规则逐件累加）。确认消息同时展示单价与总价。
3. **“点击上架物品按钮再点击想要下架的物品”** 中的“上架物品按钮”按笔误处理：
   玩家商店提供“下架物品”按钮，主菜单提供“我的上架”，两处都打开自己的寄售列表；
   点击自己的寄售后弹出确认。
4. **官方商店“默认购买数量无限”** 解释为库存无限（`stock = -1`），同时默认
   每位玩家不限购、不涨价。单次输入数量仍受 `limits.max-quantity` 限制，防止误输入。
5. **官方涨价** 按“每位玩家在该商品上的已购件数”计算，且一次购买多件时逐件递增；
   重置周期按 `rules.reset-zone`（留空=服务器时区）计算。
6. **玩家商店价格不可编辑**：上架后单价固定；如需改价请下架后重新上架。
7. **邮箱优先**：发货先写 SQLite 邮箱，再进背包；背包满不会掉落。
8. **没有交易税**；没有搜索/分类；没有玩家寄售过期时间。
9. **正式经济只使用 Wallet**：货币类型、精度、余额来自 Wallet 官网资产表；
   商品/寄售上架时选择货币。商店不再提供 `internal-test` 或外部 `ShopEconomy` 模式。
   详见 [`docs/economy-integration.md`](./docs/economy-integration.md)。
10. **支付结果未知时不发货**：订单保持 `UNKNOWN`，管理员用 `/shopadmin orders` 查看，
    在货币侧确认后可用 `/shopadmin resolve ... complete|cancel` 收口。
11. **兑换比例与双向**：`rates` 表示 `1 源币 = rate 目标币`；`bidirectional: true`
    时自动生成未显式配置的反向方向（1/rate），显式反向优先；目标金额按目标币精度
    向下取整。详见 [`docs/exchange.md`](./docs/exchange.md)。

## 已知风险

- **上架崩溃窗口**：确认上架时先从背包扣除物品，再写入 SQLite；这两步无法组成
  跨系统事务。若服务端恰好在两步之间崩溃，物品可能丢失。窗口很小，但如果正式运营
  对物品安全要求极高，建议后续改成“先写待确认记录 → 扣物品 → 激活寄售”的两阶段流程。
- **邮箱重复领取窗口**：发货先写邮箱、再放入背包、最后删除/扣减邮箱行；若进程在
  “已放入背包、尚未扣减邮箱”之间崩溃，玩家可能重复领取一次。窗口很小，但正式运营前
  建议结合日志与人工复核。
- **支付结果未知**：货币系统超时/无法确认时，商店不发货，订单保持 `UNKNOWN`；需要
  管理员在货币侧确认后用 `/shopadmin resolve` 收口。
- **Wallet 兑换不是原子操作**：Wallet 只有 credit/debit，兑换按“扣源币 → 加目标币”
  执行；目标入账确定失败会自动退回源币，但两步都结果未知时需要管理员按
  `businessNo` 在 Wallet 后台对账。详见 [`docs/exchange.md`](./docs/exchange.md) §3。
- **Wallet 必须可用**：货币列表、余额、支付都依赖 Wallet/官网；Wallet 未加载时
  ShopCore 不会启用，官网接口异常时只有缓存能支撑展示，购买/兑换会报错或进入
  UNKNOWN，不会退回本地货币。
- **单服 SQLite**：没有跨服库存锁；多服共享商店需要换 MySQL 并重新设计并发控制。
- **GUI 未做客户端验收**：服务端加载、命令、SQLite、组件写入/相似性已验证；菜单点击
  全流程仍需连接真实客户端人工走一遍。

## 目录

```text
./
├── build.gradle / settings.gradle / gradle.properties
├── gradlew / gradlew.bat / gradle/wrapper/
├── src/main/java/com/example/shopcore/
│   ├── command/      # /shop、/shopadmin
│   ├── config/       # 配置快照、MiniMessage 文案
│   ├── economy/      # Wallet 直连：资产、余额、扣款/入账、补偿
│   ├── exchange/     # 兑换配置、比例、报价、下单与补偿
│   ├── item/         # ItemCodec、UiItems（组件与 UI ID）
│   ├── listener/     # 菜单点击、聊天输入、进服/退服
│   ├── menu/         # GUI 渲染、翻页、选择货币
│   ├── model/        # 商品、寄售、订单、邮箱、报价
│   ├── service/      # 上架/购买/发货/价格计算
│   ├── session/      # 聊天输入与点击确认会话
│   ├── storage/      # SQLite 持久化（schema v2，含 asset_code）
│   └── util/
├── src/main/resources/paper-plugin.yml / config.yml
├── src/test/java/     # 42 个单测
├── docs/              # 文档索引、工程规范、版本产物、配置、UI、存储、测试、运维、契约与设计说明
└── release/ShopCore/
```

## 文档

| 文档 | 内容 |
| --- | --- |
| [`docs/README.md`](./docs/README.md) | 文档索引：先读什么、规范/契约/设计分层、维护规则 |
| [`docs/specification.md`](./docs/specification.md) | **插件工程规范**：依赖边界、Wallet 契约、UI/文案、存储、线程、命令权限、测试与版本产物 |
| [`docs/versioning-and-artifacts.md`](./docs/versioning-and-artifacts.md) | **版本与产物规范**：涨号规则、产物命名、归档、ChangeLog、测试服发布流程 |
| [`docs/configuration.md`](./docs/configuration.md) | **配置规范**：全部配置键、默认值、reload 范围、MiniMessage |
| [`docs/ui.md`](./docs/ui.md) | **菜单与交互规范**：槽位、余额/流水、返回主菜单、刷新、无斜体 |
| [`docs/storage.md`](./docs/storage.md) | **数据与存储规范**：Schema v2、事务、迁移、备份、并发 |
| [`docs/testing.md`](./docs/testing.md) | **测试与验收规范**：单测、Wallet mock、GUI 清单、发布门禁 |
| [`docs/operations.md`](./docs/operations.md) | **部署与运维规范**：安装/升级/回滚、备份、故障处置 |
| [`docs/components.md`](./docs/components.md) | UI 组件值、完整 ID 表、资源包物品模型示例、严格组件匹配口径 |
| [`docs/economy-integration.md`](./docs/economy-integration.md) | Wallet 依赖、官网资产/余额、支付与失败语义、schema 迁移 |
| [`docs/exchange.md`](./docs/exchange.md) | 兑换比例/双向规范、执行与补偿、Wallet mock 验证 |
| [`docs/design-notes.md`](./docs/design-notes.md) | 需求映射、流程状态、数据表、边界与后续可选项 |
| [`AGENTS.md`](./AGENTS.md) | 后续 AI/开发者入口与硬规则 |
| [`ShopCore-mc26_3-plugin-ChangeLog-Beta.md`](./ShopCore-mc26_3-plugin-ChangeLog-Beta.md) | 版本变更记录 |
