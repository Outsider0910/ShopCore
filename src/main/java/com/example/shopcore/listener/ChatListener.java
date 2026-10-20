package com.example.shopcore.listener;

import com.example.shopcore.ShopCorePlugin;
import com.example.shopcore.session.InputSession;
import com.example.shopcore.util.Scheduler;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.Optional;

/**
 * 截获“下一次聊天输入”。
 *
 * <p>会话存在时取消聊天广播，把纯文本交给主线程处理；格式错误会把会话放回，
 * 玩家可以继续输入，直到超时或成功。</p>
 */
public final class ChatListener implements Listener {

    private final ShopCorePlugin plugin;

    public ChatListener(ShopCorePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        long now = System.currentTimeMillis();
        Optional<InputSession> taken = plugin.sessions().takeInput(player.getUniqueId(), now);
        if (taken.isEmpty()) {
            return;
        }
        event.setCancelled(true);
        String text = PlainTextComponentSerializer.plainText().serialize(event.message());
        Scheduler.runSync(plugin, () -> {
            if (player.isOnline()) {
                plugin.shopService().handleInput(player, taken.get(), text);
            }
        });
    }
}
