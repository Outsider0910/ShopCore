package com.example.shopcore.command;

import com.example.shopcore.ShopCorePlugin;
import com.example.shopcore.session.ConfirmationSession;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

public final class ShopCommand implements BasicCommand {

    private final ShopCorePlugin plugin;

    public ShopCommand(ShopCorePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "player-only", "该命令只能由玩家执行。");
            return;
        }
        if (!player.hasPermission("shopcore.use")) {
            plugin.messages().send(player, "no-permission", "你没有权限执行此操作。");
            return;
        }
        if (args.length == 0) {
            plugin.menuService().openMain(player);
            return;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "mail" -> plugin.menuService().openMailbox(player, 0);
            case "help" -> plugin.messages().send(player, "help.shop",
                    "<yellow>/shop</yellow> 打开商店；<yellow>/shop mail</yellow> 打开待领取。");
            case "confirm" -> confirm(player, args);
            case "cancel" -> cancel(player, args);
            default -> plugin.messages().send(player, "unknown-subcommand",
                    "未知的子命令，使用 /shop 查看帮助。");
        }
    }

    private void confirm(Player player, String[] args) {
        UUID token = parseToken(player, args);
        if (token == null) {
            return;
        }
        Optional<ConfirmationSession> session = plugin.sessions()
                .takeConfirmation(token, player.getUniqueId(), System.currentTimeMillis());
        if (session.isEmpty()) {
            plugin.messages().send(player, "info.confirm-expired",
                    "确认已过期或不存在，请重新操作。");
            return;
        }
        plugin.shopService().confirm(player, session.get());
    }

    private void cancel(Player player, String[] args) {
        UUID token = parseToken(player, args);
        if (token == null) {
            return;
        }
        Optional<ConfirmationSession> session = plugin.sessions()
                .takeConfirmation(token, player.getUniqueId(), System.currentTimeMillis());
        if (session.isEmpty()) {
            plugin.messages().send(player, "info.confirm-expired",
                    "确认已过期或不存在，请重新操作。");
            return;
        }
        plugin.messages().send(player, "info.cancelled", "已取消。");
    }

    private UUID parseToken(Player player, String[] args) {
        if (args.length < 2) {
            plugin.messages().send(player, "info.confirm-expired",
                    "确认已过期或不存在，请重新操作。");
            return null;
        }
        try {
            return UUID.fromString(args[1]);
        } catch (IllegalArgumentException ex) {
            plugin.messages().send(player, "info.confirm-expired",
                    "确认已过期或不存在，请重新操作。");
            return null;
        }
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        if (args.length <= 1) {
            return List.of("mail", "help");
        }
        return List.of();
    }
}
