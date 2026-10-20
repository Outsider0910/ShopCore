package com.example.shopcore.menu;

import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.HashMap;
import java.util.Map;

/** 菜单身份 + 槽位动作表。动作表不依赖物品 PDC，避免玩家伪造按钮。 */
public final class ShopMenuHolder implements InventoryHolder {

    private final Player player;
    private final MenuView view;
    private final Map<Integer, MenuAction> actions = new HashMap<>();
    private Inventory inventory;

    public ShopMenuHolder(Player player, MenuView view) {
        this.player = player;
        this.view = view;
    }

    public Player player() {
        return player;
    }

    public MenuView view() {
        return view;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    public void clearActions() {
        actions.clear();
    }

    public void action(int slot, MenuAction action) {
        actions.put(slot, action);
    }

    public MenuAction action(int slot) {
        return actions.get(slot);
    }

    public boolean isOpen() {
        return inventory != null
                && player.isOnline()
                && player.getOpenInventory().getTopInventory().getHolder() == this;
    }
}
