# ShopCore Agent Entry

- 项目：**ShopCore**
- 平台：Minecraft **26.3**（Paper）· Java **25** · 独立 Gradle 工程
- 定位：官方商店 + 玩家商店 + 货币兑换；物品严格按完整组件识别；
  经济**只对接** `mcPlugin/Wallet`（Wallet 是必需依赖），货币类型、余额、扣款/入账
  全部由 Wallet 从官网读取/写回；商店不再有本地货币模式。
  商店作为功能项注册到 **MainMenu**（时钟主菜单），MainMenu 为必需依赖。

## 开始工作前

1. 先读 [`README.md`](./README.md)、[`docs/README.md`](./docs/README.md)、
   [`docs/specification.md`](./docs/specification.md)。
2. 涉及 UI/菜单/资源包时读 [`docs/ui.md`](./docs/ui.md)、[`docs/components.md`](./docs/components.md)。
3. 涉及货币、支付、订单状态时读 [`docs/economy-integration.md`](./docs/economy-integration.md)。
4. 涉及兑换时读 [`docs/exchange.md`](./docs/exchange.md)。
5. 涉及配置时读 [`docs/configuration.md`](./docs/configuration.md)。
6. 涉及数据库/订单/库存时读 [`docs/storage.md`](./docs/storage.md)。
7. 涉及测试/发布/运维时读 [`docs/testing.md`](./docs/testing.md)、
   [`docs/versioning-and-artifacts.md`](./docs/versioning-and-artifacts.md)、
   [`docs/operations.md`](./docs/operations.md)。
8. 不得绕过 SQLite 存储层直接写文件；不得把货币逻辑硬编码进 GUI。

## 硬规则

1. **物品身份以完整组件为准**：数量 1 的 `ItemStack.serializeAsBytes()` 落库；匹配用
   Paper `ItemStack.isSimilar`（`isSameItemSameComponents`，材质 + 全部组件、忽略数量）。
   禁止退化成 `Material` / 显示名匹配。展示必须是克隆，不能改动商品本身。
2. **UI 物品必须带组件**：`custom_data.shopcore:ui_id` + `custom_model_data` 第 0 个
   字符串；ID 变更视为破坏资源包契约，先改 `docs/components.md`。
3. **UI 物品禁止上架**：`ItemCodec`/`UiItems.isUiItem` 的检查不能删。
4. **经济入口只有 Wallet**：商品/寄售记录 `asset_code`；货币类型、scale、余额来自
   `WalletApi.wallet()`；购买/兑换必须走 `WalletEconomy` 的 `debit`/`credit`。
   不要新增 internal-test / service / 本地余额表，也不要把 Wallet 内部实现类复制进商店。
5. **Wallet 没有原子转账/兑换**：所有“扣 A 加 B”必须先扣、后加，目标入账确定失败
   时退回源；结果未知标 UNKNOWN，不继续加币/不发货。
6. **支付失败语义**：只有 `SUCCESS` 才发货；`UNKNOWN` 保持库存占用、订单 UNKNOWN、
   不发货；`FAILED`/余额不足才恢复库存/寄售。
7. **货币精度按资产**：价格解析/显示/落库使用商品/寄售 `asset_code` 对应的
   Wallet scale，不再有全局 `economy.scale`。汇率：`1 源币 = rate 目标币`；双向开启
   时自动 1/rate 反向，显式反向优先；目标金额按目标币 scale **向下取整**，为 0 拒绝。
8. **线程纪律**：SQLite 只在单线程 DB executor 上访问；GUI/背包/消息回主线程；
   不要在异步回调里直接改背包。
9. **组合操作必须事务**：扣库存/扣寄售 + 建订单；下架 + 进邮箱；完成订单 + 进邮箱
   + 累计购买数；兑换订单状态落库。
10. **文案用 MiniMessage**，默认文案在 `config.yml`，代码里保留可行动兜底。
11. **路径相对**：仓库内文档/脚本不写盘符绝对路径。
12. **版本与产物**：按 [`docs/versioning-and-artifacts.md`](./docs/versioning-and-artifacts.md)
    涨号、命名、归档并追加 ChangeLog；产物归档到 `./release/ShopCore/`。
13. **文字无斜体**：菜单标题、UI 物品 name/lore、聊天文案都必须显式
    `ITALIC=false`（`UiItems.noItalic` / `Messages`）；新增 UI 物品仍走 `UiItems.create`。
14. **关闭 = 返回主菜单**：所有“关闭”按钮使用 `MainMenuApi.openOrNull(player)`，
    旧版 MainMenu 退回 `/cd`；不要直接 `player.closeInventory()`。
15. **余额显示按 Wallet 口径**：只读 `WalletService.cachedBalance`，5 秒节流异步刷新；
    未绑定网站账号、加载中、本服启用货币的判断与 Wallet 菜单一致。

## 构建与测试

```bash
./gradlew test      # 单元测试
./gradlew build     # 编译 + 测试 + 打包 + 归档
./gradlew runServer # 本地测试服（run/eula.txt 需为 true）
```

本机 JDK 25 路径示例（脚本/文档中不要写死）：
`~/.gradle/jdks/temurin-25/jdk-25.0.4.1+1`。

共享测试服：`../test-server`（Paper 26.3 / 端口 25566 / RCON 25575；已装 Wallet 与
mock 钱包）。测试时把 `release/ShopCore/*.jar` 复制到 `../test-server/plugins/`，
并确保 mock 已启动（`mock-wallet/start-mock.bat`）。

当前自动化测试 42 个。Wallet mock 需同时支持 `/admin/assets` 和 `/assets`
（测试服 mock 已补）。

## 关键文件

| 文件 | 作用 |
| --- | --- |
| `src/main/java/.../economy/WalletEconomy.java` | Wallet 直连：资产/余额/扣款入账/补偿 |
| `src/main/java/.../service/ShopService.java` | 上架/购买/确认/发货编排、货币选择 |
| `src/main/java/.../storage/SqliteShopStorage.java` | 事务、订单、库存、寄售、邮箱、asset_code 迁移 |
| `src/main/java/.../menu/MenuService.java` | 全部 GUI 渲染与动作、选择货币页 |
| `src/main/java/.../menu/ShopMainMenuFeature.java` | 注册到 MainMenu 的时钟菜单入口 |
| `src/main/java/.../session/SessionManager.java` | 聊天输入、确认 token、超时 |
| `src/main/java/.../exchange/ExchangeService.java` | 兑换报价、确认、订单与失败处理 |
| `src/main/java/.../exchange/ExchangePairFactory.java` | 比例/双向方向构建 |
| `src/main/java/.../item/ItemCodec.java` | 完整组件序列化/匹配 |
| `src/main/java/.../item/UiItems.java` | UI ID 与组件写入 |
| `src/main/resources/config.yml` | 配置 + MiniMessage 文案 |

## 已知未验证项

- GUI 点击全流程需要连接真实客户端做人工验收（本机只验证了服务端加载、命令、
  SQLite、Wallet API、兑换执行）。
- Wallet 兑换是“扣款 + 入账 + 补偿”，不是原子操作；结果未知需按 businessNo 对账。
- 极端崩溃窗口：物品进背包后、删除邮箱行前可能重复领取；正式运营前建议观察/加
  幂等领取记录。
- 跨服、MySQL、交易税、搜索/分类均未实现；多币种商品定价已按 Wallet 资产实现。
