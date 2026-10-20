package com.example.shopcore.item;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.CustomModelData;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Optional;

/**
 * 商店自身 UI 物品。
 *
 * <p>每个按钮同时带两个组件：</p>
 * <ul>
 *   <li>{@code minecraft:custom_data} 中的 {@code shopcore:ui_id}：服务端识别按钮，
 *       也方便玩家端/资源包读取；</li>
 *   <li>{@code minecraft:custom_model_data} 的第 0 个字符串：资源包物品模型定义可直接
 *       用 {@code minecraft:select} 的 {@code custom_model_data} 属性按这个字符串切换模型。</li>
 * </ul>
 */
public final class UiItems {

    public static final String FILLER = "shopcore:ui/filler";
    public static final String MAIN_OFFICIAL = "shopcore:ui/main/official";
    public static final String MAIN_PLAYER = "shopcore:ui/main/player";
    public static final String MAIN_LISTINGS = "shopcore:ui/main/listings";
    public static final String MAIN_MAILBOX = "shopcore:ui/main/mailbox";
    public static final String MAIN_BALANCE = "shopcore:ui/main/balance";
    public static final String MAIN_EXCHANGE = "shopcore:ui/main/exchange";
    public static final String MAINMENU_ENTRY = "shopcore:ui/mainmenu/entry";
    public static final String MAIN_CLOSE = "shopcore:ui/main/close";
    public static final String EXCHANGE_PAIR = "shopcore:ui/exchange/pair";
    public static final String WALLET_HISTORY = "shopcore:ui/wallet/history";
    public static final String CURRENCY_ENTRY = "shopcore:ui/currency/entry";
    public static final String NAV_BACK = "shopcore:ui/nav/back";
    public static final String NAV_PREV = "shopcore:ui/nav/prev";
    public static final String NAV_NEXT = "shopcore:ui/nav/next";
    public static final String NAV_PAGE = "shopcore:ui/nav/page";
    public static final String NAV_CLOSE = "shopcore:ui/nav/close";
    public static final String PLAYER_LIST = "shopcore:ui/player/list";
    public static final String PLAYER_DELIST = "shopcore:ui/player/delist";
    public static final String PLAYER_MAILBOX = "shopcore:ui/player/mailbox";
    public static final String MAIL_CLAIM = "shopcore:ui/mail/claim";
    public static final String ADMIN_ENTRY = "shopcore:ui/admin/entry";
    public static final String ADMIN_ADD = "shopcore:ui/admin/add";
    public static final String ADMIN_MANAGE = "shopcore:ui/admin/manage";
    public static final String ADMIN_PRICE = "shopcore:ui/admin/price";
    public static final String ADMIN_STOCK = "shopcore:ui/admin/stock";
    public static final String ADMIN_LIMIT = "shopcore:ui/admin/limit";
    public static final String ADMIN_GROWTH = "shopcore:ui/admin/growth";
    public static final String ADMIN_MAX_PRICE = "shopcore:ui/admin/max-price";
    public static final String ADMIN_CURRENCY = "shopcore:ui/admin/currency";
    public static final String ADMIN_RESET = "shopcore:ui/admin/reset";
    public static final String ADMIN_ENABLED = "shopcore:ui/admin/enabled";
    public static final String ADMIN_DELETE = "shopcore:ui/admin/delete";

    private final NamespacedKey uiIdKey;

    public UiItems(Plugin plugin) {
        this.uiIdKey = new NamespacedKey(plugin, "ui_id");
    }

    public NamespacedKey uiIdKey() {
        return uiIdKey;
    }

    public ItemStack create(Material material, String id, Component name, List<Component> lore, boolean glow) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(noItalic(name));
        if (lore != null && !lore.isEmpty()) {
            meta.lore(lore.stream().map(UiItems::noItalic).toList());
        }
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
        if (glow) {
            meta.setEnchantmentGlintOverride(true);
        }
        item.setItemMeta(meta);

        item.setData(DataComponentTypes.CUSTOM_MODEL_DATA,
                CustomModelData.customModelData().addString(id).build());
        item.editPersistentDataContainer(pdc -> pdc.set(uiIdKey, PersistentDataType.STRING, id));
        return item;
    }

    public boolean isUiItem(ItemStack item) {
        return item != null
                && item.getPersistentDataContainer().has(uiIdKey, PersistentDataType.STRING);
    }

    public Optional<String> uiId(ItemStack item) {
        if (item == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(item.getPersistentDataContainer().get(uiIdKey, PersistentDataType.STRING));
    }

    /** 商店所有文字统一不用斜体；物品名/描述在客户端默认会斜体，必须显式关闭。 */
    public static Component noItalic(Component component) {
        return component == null
                ? Component.empty()
                : component.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }
}
