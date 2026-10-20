package com.example.shopcore.command;

import com.example.shopcore.ShopCorePlugin;
import com.example.shopcore.economy.CurrencyAsset;
import com.example.shopcore.economy.CurrencyBalance;
import com.example.shopcore.exchange.ExchangeOrder;
import com.example.shopcore.item.UiItems;
import com.example.shopcore.model.Order;
import com.example.shopcore.util.Scheduler;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.CustomModelData;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;

public final class ShopAdminCommand implements BasicCommand {

    private final ShopCorePlugin plugin;

    public ShopAdminCommand(ShopCorePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        if (!sender.isOp() && !sender.hasPermission("shopcore.admin")) {
            send(sender, "no-permission", "你没有权限执行此操作。");
            return;
        }
        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                send(sender, "player-only", "该命令只能由玩家执行。");
                return;
            }
            plugin.menuService().openAdminMain(player);
            return;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> {
                try {
                    plugin.reloadRuntime();
                    send(sender, "reloaded", "配置已重载。");
                } catch (RuntimeException ex) {
                    plugin.getLogger().log(Level.SEVERE, "重载配置失败", ex);
                    send(sender, "error.database", "重载失败，请查看控制台。");
                }
            }
            case "orders" -> orders(sender);
            case "resolve" -> resolve(sender, args);
            case "exchanges" -> exchanges(sender);
            case "exchange" -> exchange(sender, args);
            case "wallet" -> wallet(sender, args);
            case "currency" -> currency(sender);
            case "debug" -> debugItem(sender);
            case "help" -> help(sender);
            default -> send(sender, "unknown-subcommand",
                    "未知的子命令，使用 /shopadmin help 查看帮助。");
        }
    }

    private void orders(CommandSender sender) {
        plugin.shopService().unsettledOrders().whenComplete((orders, error) ->
                Scheduler.runSync(plugin, () -> {
                    if (error != null) {
                        plugin.getLogger().log(Level.SEVERE, "读取订单失败", error);
                        send(sender, "error.database", "读取订单失败。");
                        return;
                    }
                    if (orders.isEmpty()) {
                        send(sender, "info.admin-orders-empty",
                                "没有需要处理的订单。");
                        return;
                    }
                    send(sender, "info.admin-orders-header", "待处理订单：");
                    for (Order order : orders) {
                        String code = plugin.walletEconomy().resolveCode(order.assetCode());
                        plugin.messages().sendRaw(sender, "info.admin-order-line", Map.of(
                                "order", order.businessNo(),
                                "status", order.status().name(),
                                "buyer", order.buyerUuid().toString(),
                                "quantity", Integer.toString(order.quantity()),
                                "currency", plugin.walletEconomy().displayName(code),
                                "code", code,
                                "total", plugin.walletEconomy().format(code, order.totalPrice())),
                                "- " + order.businessNo() + " " + order.status()
                                        + " 数量=" + order.quantity()
                                        + " 总额=" + plugin.walletEconomy().format(code, order.totalPrice())
                                        + " " + code);
                    }
                }));
    }

    /** 运行时验证 UI 物品的组件契约，方便资源包作者确认 custom_data / custom_model_data。 */
    private void debugItem(CommandSender sender) {
        ItemStack item = plugin.uiItems().create(Material.EMERALD, UiItems.MAIN_OFFICIAL,
                Component.text("debug"), java.util.List.of(), false);
        byte[] bytes = item.serializeAsBytes();
        ItemStack roundTrip = ItemStack.deserializeBytes(bytes);
        String uiId = roundTrip.getPersistentDataContainer()
                .get(plugin.uiItems().uiIdKey(), PersistentDataType.STRING);
        CustomModelData model = roundTrip.getData(DataComponentTypes.CUSTOM_MODEL_DATA);
        String modelValue = model == null || model.strings().isEmpty()
                ? "null" : model.strings().get(0);
        boolean similar = item.isSimilar(roundTrip);
        ItemStack changed = roundTrip.clone();
        org.bukkit.inventory.meta.ItemMeta meta = changed.getItemMeta();
        meta.displayName(Component.text("changed"));
        changed.setItemMeta(meta);
        boolean detectsComponentChange = !item.isSimilar(changed);
        sender.sendMessage(Component.text("ShopCore UI: ui_id=" + uiId
                + ", custom_model_data=" + modelValue
                + ", bytes=" + bytes.length
                + ", componentSimilar=" + similar
                + ", detectsChange=" + detectsComponentChange));
    }

    private void exchanges(CommandSender sender) {
        plugin.exchangeService().recentOrders().whenComplete((orders, error) ->
                Scheduler.runSync(plugin, () -> {
                    if (error != null) {
                        plugin.getLogger().log(Level.SEVERE, "读取兑换记录失败", error);
                        send(sender, "error.database", "读取兑换记录失败。");
                        return;
                    }
                    if (orders.isEmpty()) {
                        send(sender, "info.admin-exchanges-empty", "没有兑换记录。");
                        return;
                    }
                    send(sender, "info.admin-exchanges-header", "最近兑换记录：");
                    for (ExchangeOrder order : orders) {
                        plugin.messages().sendRaw(sender, "info.admin-exchange-line", Map.of(
                                "order", order.businessNo(),
                                "status", order.status().name(),
                                "from", order.fromCode(),
                                "to", order.toCode(),
                                "source", order.sourceAmount().toPlainString(),
                                "target", order.targetAmount().toPlainString(),
                                "player", order.playerUuid().toString()),
                                "- " + order.businessNo() + " " + order.status()
                                        + " " + order.fromCode() + "→" + order.toCode()
                                        + " " + order.sourceAmount().toPlainString()
                                        + "→" + order.targetAmount().toPlainString());
                    }
                }));
    }

    private void exchange(CommandSender sender, String[] args) {
        if (args.length < 5) {
            send(sender, "help.admin-exchange",
                    "<yellow>/shopadmin exchange <玩家> <源货币> <目标货币> <数量></yellow>");
            return;
        }
        OfflinePlayer target = resolvePlayer(args[1]);
        if (target == null) {
            send(sender, "error.player-not-found", "找不到该玩家。");
            return;
        }
        String pairKey = args[2].trim().toUpperCase(Locale.ROOT)
                + ">" + args[3].trim().toUpperCase(Locale.ROOT);
        plugin.exchangeService().directExchange(target, pairKey, args[4])
                .whenComplete((result, error) -> Scheduler.runSync(plugin, () -> {
                    if (error != null) {
                        plugin.getLogger().log(Level.SEVERE, "管理员兑换失败", error);
                        send(sender, "error.database", "兑换失败。");
                        return;
                    }
                    if (result.messageKey() != null) {
                        send(sender, result.messageKey(),
                                result.placeholders(), result.fallback());
                    }
                }));
    }

    /** /shopadmin currency：列出 Wallet 从官网读取到的全部货币及本服可用状态。 */
    private void currency(CommandSender sender) {
        plugin.walletEconomy().assets(true).whenComplete((enabled, error) -> {
            Scheduler.runSync(plugin, () -> {
                    if (error != null) {
                        plugin.getLogger().log(Level.WARNING, "读取 Wallet 货币列表失败", error);
                        send(sender, "error.exchange-unavailable", "读取 Wallet 货币列表失败。");
                        return;
                    }
                    List<CurrencyAsset> all = plugin.walletEconomy().cachedAssets().values().stream()
                            .sorted(java.util.Comparator.comparing(CurrencyAsset::code))
                            .toList();
                    if (all.isEmpty()) {
                        send(sender, "info.admin-currency-empty",
                                "Wallet 官网资产表为空。");
                        return;
                    }
                    send(sender, "info.admin-currency-header",
                            "Wallet 官网货币（enabled = 官网启用，商店只使用 enabled 的货币）：");
                    for (CurrencyAsset asset : all) {
                        boolean local = plugin.walletEconomy().locallyEnabled(asset.code());
                        plugin.messages().sendRaw(sender, "info.admin-currency-line", Map.of(
                                "code", asset.code(),
                                "name", asset.displayName(),
                                "symbol", asset.symbolOrCode(),
                                "scale", Integer.toString(asset.scale()),
                                "state", asset.enabled() ? "启用" : "停用",
                                "local", local ? "启用" : "停用"),
                                "- " + asset.code() + " " + asset.displayName()
                                        + " scale=" + asset.scale()
                                        + (asset.enabled() ? " 官网启用" : " 官网停用")
                                        + (local ? " 本服启用" : " 本服停用"));
                    }
                    send(sender, "info.admin-currency-footer", Map.of(
                            "count", Integer.toString(enabled.size())),
                            "商店当前可用货币数：" + enabled.size());
                });
        });
    }

    /**
     * /shopadmin wallet <玩家> [币种]：不带币种时列出该玩家在所有官网启用货币上的余额。
     */
    private void wallet(CommandSender sender, String[] args) {
        if (args.length < 2) {
            send(sender, "help.admin-wallet",
                    "<yellow>/shopadmin wallet <玩家> [币种]</yellow>");
            return;
        }
        OfflinePlayer target = resolvePlayer(args[1]);
        if (target == null) {
            send(sender, "error.player-not-found", "找不到该玩家。");
            return;
        }
        String name = target.getName() == null ? "" : target.getName();
        if (args.length >= 3) {
            String currency = args[2].trim().toUpperCase(Locale.ROOT);
            if (plugin.walletEconomy().enabledAsset(currency).isEmpty()) {
                send(sender, "error.currency-not-found", Map.of("currency", currency),
                        "Wallet 中没有该货币或该货币未启用。");
                return;
            }
            plugin.walletEconomy().balance(target.getUniqueId(), name, currency)
                    .whenComplete((balance, error) -> Scheduler.runSync(plugin, () -> {
                        if (error != null) {
                            send(sender, "error.exchange-unavailable",
                                    "读取 Wallet 余额失败：" + error.getMessage());
                            return;
                        }
                        String code = plugin.walletEconomy().resolveCode(currency);
                        send(sender, "info.wallet-balance", Map.of(
                                "player", name,
                                "currency", plugin.walletEconomy().displayName(code),
                                "code", code,
                                "balance", plugin.walletEconomy().format(code, balance)),
                                name + " 的 " + code + " 余额："
                                        + plugin.walletEconomy().format(code, balance));
                    }));
            return;
        }
        plugin.walletEconomy().balances(target.getUniqueId(), name)
                .whenComplete((balances, error) -> Scheduler.runSync(plugin, () -> {
                    if (error != null) {
                        send(sender, "error.exchange-unavailable",
                                "读取 Wallet 余额失败：" + error.getMessage());
                        return;
                    }
                    if (balances.isEmpty()) {
                        send(sender, "info.admin-currency-empty",
                                "Wallet 官网没有启用货币。");
                        return;
                    }
                    send(sender, "info.wallet-balances-header", Map.of("player", name),
                            name + " 的 Wallet 余额：");
                    for (CurrencyBalance balance : balances) {
                        String code = balance.asset().code();
                        String text = balance.ok()
                                ? plugin.walletEconomy().format(code, balance.amountOrZero())
                                : "查询失败：" + balance.error();
                        plugin.messages().sendRaw(sender, "info.wallet-balance-line", Map.of(
                                "currency", balance.asset().displayName(),
                                "code", code,
                                "balance", text),
                                "- " + balance.asset().displayName() + "（" + code + "）：" + text);
                    }
                }));
    }

    private void resolve(CommandSender sender, String[] args) {
        if (args.length < 3) {
            send(sender, "help.admin-resolve",
                    "<yellow>/shopadmin resolve <订单UUID|businessNo> complete|cancel</yellow>");
            return;
        }
        String mode = args[2].toLowerCase(Locale.ROOT);
        if (!mode.equals("complete") && !mode.equals("cancel")) {
            send(sender, "help.admin-resolve",
                    "<yellow>/shopadmin resolve <订单UUID|businessNo> complete|cancel</yellow>");
            return;
        }
        plugin.shopService().resolveOrder(args[1], mode.equals("complete"))
                .whenComplete((result, error) -> Scheduler.runSync(plugin, () -> {
                    if (error != null) {
                        plugin.getLogger().log(Level.SEVERE, "处理订单失败", error);
                        send(sender, "error.database", "处理订单失败。");
                        return;
                    }
                    if (!result.success()) {
                        send(sender, result.messageKey(), result.placeholders(),
                                result.fallback());
                        return;
                    }
                    send(sender,
                            mode.equals("complete") ? "success.order-completed" : "success.order-cancelled",
                            mode.equals("complete") ? "订单已完成，物品已放入买家待领取。"
                                    : "订单已取消，库存/寄售数量已释放。");
                }));
    }

    private OfflinePlayer resolvePlayer(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return online;
        }
        OfflinePlayer offline = Bukkit.getOfflinePlayer(name);
        if (offline.hasPlayedBefore() || offline.isOnline()) {
            return offline;
        }
        return null;
    }

    private void help(CommandSender sender) {
        send(sender, "help.admin",
                "<yellow>/shopadmin</yellow> 打开管理；<yellow>/shopadmin reload</yellow> 重载；"
                        + "<yellow>/shopadmin orders</yellow> 查看待处理订单；"
                        + "<yellow>/shopadmin resolve ...</yellow> 完成/取消订单；"
                        + "<yellow>/shopadmin exchanges</yellow> 兑换记录；"
                        + "<yellow>/shopadmin exchange ...</yellow> 管理员直接兑换；"
                        + "<yellow>/shopadmin wallet ...</yellow> 查看 Wallet 余额；"
                        + "<yellow>/shopadmin currency</yellow> 查看 Wallet 官网货币。");
    }

    private void send(CommandSender sender, String key, String fallback) {
        Scheduler.runSync(plugin, () -> plugin.messages().send(sender, key, fallback));
    }

    private void send(CommandSender sender, String key, Map<String, String> placeholders,
                      String fallback) {
        Scheduler.runSync(plugin, () -> plugin.messages().send(sender, key, placeholders, fallback));
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        if (args.length <= 1) {
            return List.of("reload", "orders", "resolve", "exchanges", "exchange",
                    "wallet", "currency", "debug", "help");
        }
        if (args[0].equalsIgnoreCase("resolve") && args.length == 3) {
            return List.of("complete", "cancel");
        }
        return List.of();
    }
}
