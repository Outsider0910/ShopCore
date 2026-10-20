package com.example.shopcore.listener;

import com.example.shopcore.ShopCorePlugin;
import com.example.shopcore.menu.MenuAction;
import com.example.shopcore.menu.ShopMenuHolder;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

import java.util.logging.Level;

public final class MenuListener implements Listener {

    private final ShopCorePlugin plugin;

    public MenuListener(ShopCorePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof ShopMenuHolder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        int raw = event.getRawSlot();
        if (raw < 0 || raw >= holder.getInventory().getSize()) {
            return;
        }
        MenuAction action = holder.action(raw);
        if (action == null) {
            return;
        }
        plugin.menuService().playClick(player);
        try {
            action.click(player, holder, raw);
        } catch (Throwable ex) {
            plugin.getLogger().log(Level.SEVERE, "商店按钮执行失败：" + raw, ex);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof ShopMenuHolder) {
            event.setCancelled(true);
        }
    }
}
