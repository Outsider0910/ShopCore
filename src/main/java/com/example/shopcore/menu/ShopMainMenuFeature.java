package com.example.shopcore.menu;

import com.example.mainmenu.api.MenuFeature;
import com.example.shopcore.ShopCorePlugin;
import com.example.shopcore.item.UiItems;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.OptionalInt;

/**
 * 注册到 MainMenu（时钟主菜单）里的商店入口。
 *
 * <p>直接实现 MenuFeature 而不是用 builder：icon() 每次返回带 custom_data /
 * custom_model_data 的新物品，避免 builder 再写 displayName 时改动组件。</p>
 */
public final class ShopMainMenuFeature implements MenuFeature {

    public static final String FEATURE_ID = "shopcore:shop";

    private final ShopCorePlugin plugin;

    public ShopMainMenuFeature(ShopCorePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return FEATURE_ID;
    }

    @Override
    public String owner() {
        return plugin.getPluginMeta().getName();
    }

    @Override
    public ItemStack icon() {
        return plugin.uiItems().create(Material.EMERALD, UiItems.MAINMENU_ENTRY,
                name(), List.of(lore()), true);
    }

    @Override
    public List<Component> description() {
        return List.of(lore());
    }

    @Override
    public boolean clickable() {
        return true;
    }

    @Override
    public void onClick(Player player, ClickType clickType) {
        plugin.menuService().openMain(player);
    }

    @Override
    public OptionalInt preferredSlot() {
        return OptionalInt.empty();
    }

    @Override
    public int order() {
        return 10;
    }

    private Component name() {
        return plugin.messages().get("ui.mainmenu.entry-name",
                "<green><bold>商店</bold></green>");
    }

    private Component lore() {
        return plugin.messages().get("ui.mainmenu.entry-lore",
                "<gray>官方商店 / 玩家商店 / 货币兑换</gray>");
    }
}
