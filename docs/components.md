# 物品组件与资源包契约

本文是 ShopCore 与资源包之间的稳定契约。

## 1. 商店自身 UI 物品

每个按钮/装饰物品由 `UiItems` 创建，带两个组件：

| 组件 | 值 | 用途 |
| --- | --- | --- |
| `minecraft:custom_data`（PDC key） | `shopcore:ui_id = "<UI ID>"` | 服务端识别、允许资源包读取；玩家无法通过改名伪造 |
| `minecraft:custom_model_data` | `strings[0] = "<UI ID>"` | 资源包物品模型定义按字符串切换模型 |

- UI ID 使用与 `custom_model_data` 相同的字符串，方便两边对照。
- PDC key 的命名空间是插件名 `shopcore`，key 是 `ui_id`。
- UI 物品的 `custom_model_data` 只有一个字符串（索引 0），不写 float/flag/color。
- 所有 UI 物品的显示名与 lore 都显式设置 `ITALIC=false`，避免客户端默认斜体。
- 这些都是普通物品组件，不是自定义注册组件；删掉资源包时仍会回退到基础材质。

### 1.1 UI ID 与基础材质

| UI ID | 基础材质 | 位置 |
| --- | --- | --- |
| `shopcore:ui/filler` | `GRAY_STAINED_GLASS_PANE` | 空位/加载中 |
| `shopcore:ui/main/official` | `EMERALD` | 主菜单-官方商店 |
| `shopcore:ui/main/player` | `CHEST` | 主菜单-玩家商店 |
| `shopcore:ui/main/listings` | `WRITABLE_BOOK` | 主菜单-我的上架 |
| `shopcore:ui/main/mailbox` | `HOPPER` | 主菜单-待领取 |
| `shopcore:ui/main/balance` | `EMERALD` | 主菜单-钱包余额（点击打开流水） |
| `shopcore:ui/main/exchange` | `GOLD_NUGGET` | 主菜单-货币兑换 |
| `shopcore:ui/mainmenu/entry` | `EMERALD` | 时钟主菜单-商店入口 |
| `shopcore:ui/main/close` | `BARRIER` | 主菜单-关闭 |
| `shopcore:ui/exchange/pair` | `GOLD_INGOT` | 兑换方向条目 |
| `shopcore:ui/wallet/history` | `PAPER` | 钱包流水条目 |
| `shopcore:ui/currency/entry` | `GOLD_INGOT` / `EMERALD` | 选择货币-货币条目（money 资产用金锭，其余绿宝石） |
| `shopcore:ui/nav/back` | `ARROW` | 返回 |
| `shopcore:ui/nav/prev` | `ARROW` / `GRAY_STAINED_GLASS_PANE` | 上一页（不可用时灰色） |
| `shopcore:ui/nav/next` | `ARROW` / `GRAY_STAINED_GLASS_PANE` | 下一页（不可用时灰色） |
| `shopcore:ui/nav/page` | `PAPER` | 页码/加载中 |
| `shopcore:ui/nav/close` | `BARRIER` | 关闭 |
| `shopcore:ui/player/list` | `ANVIL` | 玩家商店-上架物品 |
| `shopcore:ui/player/delist` | `WRITABLE_BOOK` | 玩家商店-下架物品 |
| `shopcore:ui/player/mailbox` | `HOPPER` | 玩家商店-待领取 |
| `shopcore:ui/mail/claim` | `HOPPER` | 待领取-领取全部 |
| `shopcore:ui/admin/entry` | `COMMAND_BLOCK` | 主菜单-商店管理 |
| `shopcore:ui/admin/add` | `ANVIL` | 管理-上架官方商品 |
| `shopcore:ui/admin/manage` | `BOOK` | 管理-管理官方商品 |
| `shopcore:ui/admin/price` | `GOLD_INGOT` | 编辑-单价 |
| `shopcore:ui/admin/stock` | `CHEST` | 编辑-库存 |
| `shopcore:ui/admin/limit` | `BOOK` | 编辑-限购 |
| `shopcore:ui/admin/growth` | `EXPERIENCE_BOTTLE` | 编辑-涨价百分比 |
| `shopcore:ui/admin/max-price` | `EMERALD` | 编辑-单件最高价 |
| `shopcore:ui/admin/currency` | `EMERALD` | 编辑-修改货币 |
| `shopcore:ui/admin/reset` | `CLOCK` | 编辑-重置周期 |
| `shopcore:ui/admin/enabled` | `LIME_DYE` / `RED_DYE` | 编辑-启用/停用 |
| `shopcore:ui/admin/delete` | `BARRIER` | 编辑-删除 |

> 这些是最初的基础材质。后续如果资源包完全接管，可以保持 ID 不变，只改模型；
> 不建议改 ID，因为 ID 是服务端与资源包之间的契约。

### 1.2 物品模型定义示例

假设资源包模型文件为
`assets/shopcore/models/item/ui/main_official.json`，可以在对应基础材质
（例如 `assets/minecraft/items/emerald.json`）的物品模型定义里写：

```json
{
  "model": {
    "type": "minecraft:select",
    "property": "minecraft:custom_model_data",
    "index": 0,
    "cases": [
      {
        "when": "shopcore:ui/main/official",
        "model": {
          "type": "minecraft:model",
          "model": "shopcore:item/ui/main_official"
        }
      },
      {
        "when": "shopcore:ui/main/balance",
        "model": {
          "type": "minecraft:model",
          "model": "shopcore:item/ui/main_balance"
        }
      }
    ],
    "fallback": {
      "type": "minecraft:model",
      "model": "minecraft:item/emerald"
    }
  }
}
```

- `property` / `index` 的写法以目标客户端版本的物品模型定义格式为准；关键是
  `custom_model_data` 第 0 个字符串就是表中 ID。
- 同一个基础材质被多个 UI ID 复用时（例如多个按钮都用 `ARROW`），把多个
  `cases` 放进同一个模型定义即可。
- 只给实际用到的材质写定义；未覆盖的 ID 会走 `fallback`，不会出现紫黑模型。
- 如果资源包还需要读 `custom_data`，服务端写入的是
  `shopcore:ui_id` 字符串，可直接在数据包里生成 predicate 或供其他模组使用。

## 2. 玩家寄售 / 官方商品的组件

商店对“物品”的识别是**完整组件**，不是材质或显示名：

1. 保存时把数量为 1 的 `ItemStack` 用 `ItemStack.serializeAsBytes()` 完整写入 SQLite。
2. 上架确认时扫描玩家主背包（槽位 0-35），把数据库/会话中的物品反序列化为数量 1
   的模板，再用 Paper 的 `ItemStack.isSimilar`（底层 `isSameItemSameComponents`）
   与背包物品比较。它比较材质和全部组件、忽略数量。
3. 只有所有组件都一致的物品才会被扣除。
4. 展示时反序列化一份克隆；点击购买/下架用的是数据库里的原始字节。
5. 官方商品是管理员选择物品的克隆，不会从管理员背包扣除；玩家寄售会从背包扣除。

> 为什么不用“序列化字节相等”直接比较：`serializeAsBytes` 在反序列化后可能补全默认
> 组件或改变编码顺序，字节不一定逐位相同；`isSimilar` 才是服务端语义上的
> “同一种物品”。商店仍然保存完整字节，所以展示/发货不会丢组件。

因此：

- 两把只有耐久不同的剑是两种商品；
- 同名但 `custom_data` / `custom_model_data` 不同的物品是两种商品；
- 容器内容、附魔、属性修饰符、`item_name`、`lore` 等组件都会参与匹配；
- 商店不会给寄售/官方商品追加 `shopcore:ui_id`，避免改变商品本身；
- 带 `shopcore:ui_id` 的商店按钮物品禁止被上架（防止把 UI 物品当商品流通）。

> 数量（`amount`）不参与“物品类型”比较：商店把商品存为数量 1 的模板，数量单独
> 记在寄售/订单里；扣除和发货时再按模板生成对应数量。

## 3. 兼容性提示

- `custom_model_data` 在 1.21.4+ 支持字符串；Paper 26.3 的 API 已确认可用。
- 如果目标客户端使用不同的组件选择方式，只需要保证 `custom_model_data` 字符串
  和 `custom_data` 的 `shopcore:ui_id` 不被改写。
- 不要给商店 UI 物品再加 `item_model` 组件指向不存在的模型；当前实现只用
  `custom_model_data`，没有资源包时也能正常显示基础材质。
- 资源包更新后，按钮的模型切换只影响显示；服务端识别始终以 `shopcore:ui_id`
  和槽位动作表为准，不信任客户端发来的物品。
