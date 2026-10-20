package com.example.shopcore.listener;

import com.example.shopcore.ShopCorePlugin;
import com.example.shopcore.util.Scheduler;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public final class PlayerListener implements Listener {

    private final ShopCorePlugin plugin;

    public PlayerListener(ShopCorePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.sessions().clearPlayer(event.getPlayer().getUniqueId());
        plugin.walletEconomy().invalidateBalances(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Scheduler.runSyncLater(plugin,
                () -> plugin.shopService().notifyMailboxOnJoin(event.getPlayer()), 20L);
    }
}
