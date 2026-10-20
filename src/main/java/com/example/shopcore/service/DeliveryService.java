package com.example.shopcore.service;

import com.example.shopcore.item.ItemCodec;
import com.example.shopcore.model.MailboxEntry;
import com.example.shopcore.storage.SqliteShopStorage;
import com.example.shopcore.util.Scheduler;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * 物品投递。
 *
 * <p>购买/下架先写进 mailbox（数据库），再尝试放进背包；背包满时物品继续留在邮箱。
 * 这样即使服务端在“交易完成”和“发物品”之间崩溃，也不会直接吞物品。</p>
 */
public final class DeliveryService {

    private final Plugin plugin;
    private final SqliteShopStorage storage;
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();

    public DeliveryService(Plugin plugin, SqliteShopStorage storage) {
        this.plugin = plugin;
        this.storage = storage;
    }

    public CompletableFuture<DeliveryResult> deliverAll(Player player) {
        UUID uuid = player.getUniqueId();
        if (!inFlight.add(uuid)) {
            return CompletableFuture.completedFuture(new DeliveryResult(0, 0, true));
        }
        return storage.listAllMailbox(uuid)
                .handle((entries, error) -> {
                    if (error != null) {
                        plugin.getLogger().log(Level.SEVERE, "读取待领取物品失败", error);
                        inFlight.remove(uuid);
                        return CompletableFuture.completedFuture(new DeliveryResult(0, 0, false));
                    }
                    return deliverOnMainThread(player, entries);
                })
                .thenCompose(future -> future);
    }

    private CompletableFuture<DeliveryResult> deliverOnMainThread(Player player, List<MailboxEntry> entries) {
        CompletableFuture<DeliveryResult> result = new CompletableFuture<>();
        UUID uuid = player.getUniqueId();
        Scheduler.runSync(plugin, () -> {
            try {
                int delivered = 0;
                int remaining = 0;
                for (MailboxEntry entry : entries) {
                    if (!player.isOnline()) {
                        remaining += entry.quantity();
                        continue;
                    }
                    Attempt attempt = addToInventory(player, entry);
                    delivered += attempt.delivered();
                    remaining += attempt.remaining();
                    if (attempt.delivered() > 0) {
                        storage.consumeMailbox(entry.id(), uuid, attempt.delivered())
                                .exceptionally(error -> {
                                    plugin.getLogger().log(Level.SEVERE,
                                            "更新待领取数量失败：" + entry.id(), error);
                                    return null;
                                });
                    }
                }
                result.complete(new DeliveryResult(delivered, remaining, true));
            } finally {
                inFlight.remove(uuid);
            }
        });
        return result;
    }

    private Attempt addToInventory(Player player, MailboxEntry entry) {
        ItemStack unit;
        try {
            unit = ItemCodec.unitItem(entry.itemData());
        } catch (RuntimeException ex) {
            plugin.getLogger().log(Level.SEVERE, "待领取物品反序列化失败：" + entry.id(), ex);
            return new Attempt(0, entry.quantity());
        }
        int remaining = entry.quantity();
        int delivered = 0;
        int maxStack = Math.max(1, unit.getMaxStackSize());
        while (remaining > 0) {
            int batch = Math.min(maxStack, remaining);
            ItemStack stack = unit.asQuantity(batch);
            HashMap<Integer, ItemStack> leftover = player.getInventory().addItem(stack);
            int leftoverAmount = leftover.values().stream().mapToInt(ItemStack::getAmount).sum();
            int added = batch - leftoverAmount;
            delivered += added;
            remaining -= added;
            if (added <= 0) {
                break;
            }
        }
        return new Attempt(delivered, remaining);
    }

    /** 把物品安全地放进邮箱；在线玩家随后会尝试自动放进背包。 */
    public CompletableFuture<Void> toMailbox(Player player, ItemStack unit, int quantity) {
        return storage.addMailbox(player.getUniqueId(), ItemCodec.unitBytes(unit), quantity);
    }

    public record DeliveryResult(int delivered, int remaining, boolean playersOnline) {
        public boolean inventoryFull() {
            return remaining > 0;
        }
    }

    private record Attempt(int delivered, int remaining) {
    }
}
