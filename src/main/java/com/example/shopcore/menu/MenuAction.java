package com.example.shopcore.menu;

import org.bukkit.entity.Player;

@FunctionalInterface
public interface MenuAction {
    void click(Player player, ShopMenuHolder holder, int slot);
}
