# 测试与验收规范

> 适用范围：ShopCore 的单元测试、测试服联调、手工验收与发布门禁。
> 目标：经济链路可证明、GUI 可验收、每次发布有可复现的检查记录。

## 1. 测试分层

| 层 | 工具 | 覆盖 |
| --- | --- | --- |
| 单元测试 | JUnit 5 + Gradle | 金额、价格、兑换、输入解析、SQLite、Wallet 支付/兑换语义 |
| 集成联调 | Paper 26.3 测试服 + Wallet mock | 插件加载、命令、余额、购买/兑换、订单落库 |
| 手工验收 | 真实 Minecraft 客户端 | GUI 点击、悬停文本、无斜体、资源包 UI ID |

单元测试不启动服务端；集成联调不连接生产官网。

## 2. 单元测试

命令：

```bash
./gradlew test
./gradlew clean test
```

当前基线：**42 个用例**。

| 测试类 | 用例数 | 覆盖 |
| --- | ---: | --- |
| `AmountsTest` | 4 | 金额解析、精度、负数/科学计数法拒绝 |
| `PriceCalculatorTest` | 5 | 无涨价、逐件涨价、已购件数、最高价、精度取整 |
| `ResetPeriodTest` | 5 | 永不/每天/每周/每月窗口 |
| `ShopInputParserTest` | 4 | 上架/购买/管理员输入 |
| `ExchangeSettingsTest` | 3 | 比例、双向、非法费率 |
| `ExchangePairFactoryTest` | 4 | 显式方向、自动反向、显式反向优先、停用资产过滤 |
| `ExchangeRateCalculatorTest` | 3 | POINT↔CNY、目标精度向下取整、0 金额拒绝 |
| `WalletEconomyTest` | 7 | 同币种付款、跨币种兑换、失败退款、未知不退款、余额不足、精度格式化、缓存/未绑定 |
| `SqliteShopStorageTest` | 7 | 官方/寄售报价、占位、完成、取消、限购、库存恢复、邮箱、兑换订单 |

规范：

- 新增经济/库存/兑换行为必须补测试；不能只靠手工验证。
- SQLite 测试使用临时目录与独立数据库，不写真实 `plugins/ShopCore/shop.db`。
- Wallet 测试使用假的 `WalletService`，不访问网络。
- 测试不得依赖执行顺序、系统时区或真实玩家。

## 3. 测试服联调

测试服：`../test-server`（Paper 26.3，端口 25566，RCON 25575）。

启动顺序：

```text
mock-wallet/start-mock.bat
start-server.bat
```

依赖：

- `MainMenu` 已安装（ShopCore 必需）。
- `Wallet` 已安装并指向 mock 或官网。
- `ShopCore` 使用 `./release/ShopCore/` 最新 jar。

Wallet mock 配置示例：

```yaml
# ../test-server/plugins/Wallet/config.yml
base-url: "http://127.0.0.1:18080"
api-key: "yda_test_local"
assets: ["POINT", "CNY"]
```

mock 预置：

| 玩家 | POINT | CNY |
| --- | ---: | ---: |
| `Outsider0910` | 1000 | 100.00 |
| `Steve` | 250 | 10.00 |

mock 余额只在内存，重启 mock 恢复初始值。

### 3.1 启动检查

日志必须出现：

```text
[ShopCore] Loading server plugin ShopCore v<版本>
[ShopCore] Enabling ShopCore v<版本>
[ShopCore] 已注册时钟主菜单功能：shopcore:shop
[ShopCore] 已从 Wallet 读取官网货币：[...]
```

不得出现：

- `Ambiguous plugin name 'ShopCore'`（测试服有多个同名 jar）。
- `ERROR` / `Exception` / `NoClassDefFoundError` / `NoSuchMethodError`。

### 3.2 命令检查

```text
/shopadmin currency
/shopadmin wallet Outsider0910
/shopadmin wallet Outsider0910 POINT
/shopadmin exchange Outsider0910 POINT CNY 100
/shopadmin exchanges
/shopadmin orders
```

预期：

- `currency` 列出官网资产、本服启用状态。
- `wallet` 不带币种列出全部启用货币余额；带币种查单个。
- `exchange` 后 POINT 减少、CNY 增加，`exchange_orders` 为 `COMPLETED`。
- 余额不足时提示且不产生 debit/credit。
- `orders` 能列出 `PENDING/UNKNOWN`。

### 3.3 失败链路

| 场景 | 操作 | 预期 |
| --- | --- | --- |
| 未绑定账号 | mock 玩家名 `unbound` | 余额行显示"未绑定网站账号"；支付失败 |
| 货币停用 | Wallet `assets.yml` 停用某币 | 该币不出现在选择/兑换/余额行 |
| 余额不足 | 兑换/购买超过余额 | `INSUFFICIENT_FUNDS`，库存/寄售恢复 |
| 目标入账失败 | mock 返回 4xx | 源币退回，订单 `FAILED` |
| 结果未知 | 支付中断网/超时 | 订单 `UNKNOWN`，不发货、不退款 |
| 重复 businessNo | 重放同一请求 | Wallet 幂等，不重复记账 |

## 4. 手工 GUI 验收

必须用真实客户端连接测试服，逐项确认：

- [ ] 主菜单余额按钮：绿宝石、白色"钱包"、`货币名：余额 符号`、无斜体。
- [ ] 未绑定/加载中显示正确；余额变化后 5 秒内刷新。
- [ ] 点击余额打开钱包流水，翻页、返回、返回主菜单正常。
- [ ] 官方商品：上架、改价、库存、限购、涨价、重置周期、启停、删除。
- [ ] 玩家寄售：上架、选择货币、部分购买、下架、待领取。
- [ ] 货币选择页：只显示本服启用货币，点击后输入价格。
- [ ] 兑换：方向、输入、确认、成功提示。
- [ ] 所有"关闭"按钮回到服务器主菜单（旧版 MainMenu 走 `/cd`）。
- [ ] 所有菜单文字无斜体；资源包 UI ID 与 `components.md` 一致。
- [ ] 背包满时物品进邮箱，不丢失。

GUI 未验收不得标记"全部完成"；服务端日志与数据库只能证明后端链路。

## 5. 发布门禁

发布前必须：

1. `./gradlew clean build` 通过，42 个测试全绿。
2. 版本号、ChangeLog、README 版本行一致。
3. 测试服只保留一个 ShopCore jar。
4. 启动无 `ERROR` / `Exception`。
5. `/shopadmin currency`、`/shopadmin wallet`、兑换/购买链路通过。
6. GUI 手工验收完成，或明确记录"未验收"。
7. 归档 jar 在 `./release/ShopCore/`，文件名与版本一致。

## 6. 测试数据管理

- 重置 mock 余额：重启 mock。
- 重置商店数据：停止服务端，备份后删除 `plugins/ShopCore/shop.db*`，再启动。
- 不要在生产服做会写 `shop.db` 的测试。
- 测试订单的 `businessNo` 保留在日志/数据库中，便于对账。
- 手工改库只允许在停机备份后进行，并记录原因。

## 7. 禁止事项

- 禁止跳过单元测试直接发布。
- 禁止用生产官网/生产 Wallet API Key 做破坏性测试。
- 禁止在测试服保留多个同名 ShopCore jar。
- 禁止只验证"插件能启动"就认为购买/兑换可用。
- 禁止在未备份的情况下删除/修改 `shop.db`。
