# 版本与产物规范

> 适用范围：ShopCore 独立工程的版本号、构建产物、归档、ChangeLog 与发布流程。
> 目标：版本一眼看懂、产物命名可对照、每一版都有档可查、改动有 ChangeLog 可追。

本文中的版本号规则与产物命名沿用 `mcPlugin/docs/dev/版本与产物规范.md`；
ShopCore 是独立工程，因此归档目录和测试服路径按本工程实际情况固定。
货币/UI/存储等工程约束见 [`specification.md`](./specification.md)，此处不重复。

## 1. 产物命名

| 类型 | 命名格式 | 示例 |
| --- | --- | --- |
| ShopCore 插件 | `ShopCore-mc<游戏版本>-plugin-<项目版本>.jar` | `ShopCore-mc26_3-plugin-Beta_0_3_2.jar` |

- 游戏版本来自 `gradle.properties` 的 `minecraftVersion`，把点换成下划线：
  `26.3` → `mc26_3`。
- 项目版本来自 `build.gradle` 的 `version`。
- 文件名由 `base.archivesName` 自动生成，**不得手工改名**。
- `paper-plugin.yml` 中的版本是 `${version}`，构建时由 `processResources` 展开；
  不得在资源文件里写死版本号。

## 2. 版本号规则

当前版本：`Beta_0_3_2`。版本号唯一来源是 `build.gradle` 的 `version`。

| 改动类型 | 规则 | 示例 |
| --- | --- | --- |
| 测试期修 bug | 第 3 位 +1 | `Beta_0_3_1` → `Beta_0_3_2` |
| 测试期新增功能 | 第 2 位 +1，第 3 位归 0 | `Beta_0_3_2` → `Beta_0_4_0` |
| 首次出正式版 | 第 1 位 +1，后两位归 0，去掉 `Beta` | `Beta_0_4_0` → `1_0_0` |
| 正式版后修 bug | 回到同第 1 位的测试版，第 3 位 +1 | `1_0_0` → `Beta_1_0_1` |
| 正式版后新增功能 | 回到同第 1 位的测试版，第 2 位 +1，第 3 位归 0 | `1_0_0` → `Beta_1_1_0` |
| 测试版转正 | 去掉 `Beta`，第 3 位归 0；第 2 位取“上一正式版第 2 位 +1”与“测试版第 2 位”的较大值，第 1 位不变 | `Beta_1_0_1` → `1_1_0`；`Beta_1_1_0` → `1_1_0`；`Beta_1_2_0` → `1_2_0` |

补充规则：

- 正式版不是终点：正式版之后有任何改动，先回到 `Beta_<第 1 位>_x_x`，验证后再转正。
- 大版本（第 1 位）只在明确决定升大版本时 +1，日常转正不动它。
- 不得用 SemVer（`1.2.3`）或其他编号方式替代。
- 版本号变化必须同时更新 `README.md` 的版本行、追加 ChangeLog、重新构建并归档。
- 只改文档、不改行为时，可以保持版本号不变；但必须在 ChangeLog 对应版本下补“文档”条目。

## 3. 构建与归档

### 3.1 环境

- JDK 25（Paper 26.3 要求）。
- 仓库自带 Gradle Wrapper 9.8.1，不要求本机安装 Gradle。
- 编译期依赖：
  - `../mcPlugin/release/MainMenu/MainMenu-mc26_3-plugin-Beta_1_2_0.jar`
  - `../mcPlugin/release/Wallet/Wallet-mc26_3-plugin-Beta_0_2_1.jar`

依赖 jar 缺失时构建直接失败；升级 MainMenu/Wallet 时必须同步修改 `build.gradle`
中的路径，并重新跑完整测试与服务端验证。

### 3.2 命令

```bash
./gradlew test        # 单元测试
./gradlew build       # 编译 + 测试 + 打包 + 归档
./gradlew clean build # 发布前推荐，排除增量缓存影响
```

- `build` 成功后执行 `archiveArtifact`，把 jar 复制到 `./release/ShopCore/`。
- 归档文件名自带版本；历史产物保留，不手工覆盖。
- 同一版本重复构建会覆盖同名文件，这是允许的；不同行为必须使用新版本号。
- `build/` 是临时产物，不提交；`release/ShopCore/*.jar` 是留档产物，必须保留。

### 3.3 版本校验

发布前确认：

```bash
unzip -p ./release/ShopCore/ShopCore-mc26_3-plugin-<版本>.jar paper-plugin.yml
```

输出中的 `version` 必须与 `build.gradle` 一致，不能是 `${version}`。

## 4. ChangeLog

文件：

- Beta 版本：`ShopCore-mc26_3-plugin-ChangeLog-Beta.md`
- 正式版：`ShopCore-mc26_3-plugin-ChangeLog.md`（首次出正式版时创建）

格式采用按版本分节的结构化 Markdown（与 `mcPlugin` 的编号式条目信息等价，
便于分类阅读），最新版本在最上面：

```markdown
## Beta_x_y_z（YYYY-MM-DD）

### 新增

- ...

### 修复

- ...

### 调整 / 移除 / 测试 / 文档

- ...
```

要求：

- 每次版本号变化必须追加一节，不能只改代码不写 ChangeLog。
- 一节内只写本版本的变化；不要重复历史版本内容。
- 条目写“做了什么、影响哪里”，涉及经济/UI/存储/配置时写明：
  - Wallet 资产/余额/支付语义是否变化；
  - UI ID、菜单布局、文案是否变化；
  - SQLite schema 是否迁移；
  - 配置键是否新增/删除/改名；
  - 测试数量与验证方式。
- 只改文档时，可以保留版本号，在对应版本下补“文档”条目。
- ChangeLog 与 `README.md`、`docs/specification.md` 的行为描述必须一致。

## 5. 发布与测试服部署

1. 确认 `build.gradle` 版本号已按 §2 涨号。
2. 确认 ChangeLog 已追加，README 版本行已更新。
3. 执行 `./gradlew clean build`，确认测试全绿、归档成功。
4. 验证 jar 内 `paper-plugin.yml` 的版本已展开。
5. 把 `./release/ShopCore/ShopCore-mc26_3-plugin-<版本>.jar` 复制到
   `../test-server/plugins/`。
6. **移除测试服里同插件的旧 jar**（`ShopCore-mc26_3-plugin-*.jar`），
   否则 Paper 会报 `Ambiguous plugin name 'ShopCore'` 并可能加载旧版本。
7. 同步测试服 `plugins/ShopCore/config.yml`（如默认配置有新增键）。
8. 启动测试服，确认日志出现：
   - `ShopCore v<版本>` 加载/启用；
   - 从 Wallet 读取到预期货币；
   - 无 `ERROR` / `Exception`。
9. 执行验收：
   - `/shopadmin currency` 能看到官网货币与本服启用状态；
   - `/shopadmin wallet <玩家>` 能读取余额；
   - 购买/兑换后官网余额与 `orders` / `exchange_orders` 一致；
   - 人为超时得到 `UNKNOWN`，不发货。
10. GUI 全流程用真实客户端验收；服务端日志与数据库只能证明后端链路。

如果纳入 Git 管理：

- 提交源码、`release/ShopCore/` 下的归档 jar、ChangeLog 与文档。
- 不提交 `build/`、`run/`、测试服目录、IDE 文件。
- 发布 tag 建议使用 `v<版本>`（例如 `v0.3.1`），tag 前确认版本、ChangeLog、归档三者一致。

## 6. 依赖版本与可复现性

- Paper API 版本在 `gradle.properties`：`paperApiVersion`。
- Java 编译目标在 `gradle.properties`：`targetJavaVersion=25`。
- MainMenu / Wallet 编译期 jar 在 `build.gradle` 写死路径；升级时同步更新并验证。
- 构建必须可复现：同一源码、同一 JDK、同一依赖 jar，重新 `clean build` 应生成同名同版本产物。
- 不得手工修改 jar 内容；需要改行为就改源码并涨版本。
- 不得在文档、脚本、配置里写盘符绝对路径；仓库内一律使用相对路径。

## 7. 发布前检查清单

- [ ] `build.gradle` 版本号已按规则涨号。
- [ ] `README.md` 版本行已更新。
- [ ] ChangeLog 已追加本节，分类清晰。
- [ ] `./gradlew clean build` 通过，测试全绿（当前基线 42 个）。
- [ ] `./release/ShopCore/` 已生成对应版本 jar。
- [ ] jar 内 `paper-plugin.yml` 版本已展开。
- [ ] 测试服只保留一个 ShopCore jar，且为本次版本。
- [ ] 测试服启动无 `ERROR` / `Exception`。
- [ ] `/shopadmin currency`、`/shopadmin wallet` 正常。
- [ ] 购买/兑换失败语义符合 `docs/specification.md` §5。
- [ ] 相关文档（README/specification/economy/exchange/components）已同步。
- [ ] 没有调试日志、注释掉的死代码、无主 TODO。

## 8. 禁止事项

- 禁止手工改名 jar 或跳过 `archiveArtifact` 归档。
- 禁止同一版本号对应不同行为。
- 禁止只改代码不写 ChangeLog。
- 禁止把 `build/`、测试服目录、IDE 文件提交进仓库。
- 禁止在测试服保留多个同名 ShopCore jar。
- 禁止在文档或脚本中写盘符绝对路径。
- 禁止用旧版本 jar 冒充新版本发布。
