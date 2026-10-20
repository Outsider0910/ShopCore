package com.example.shopcore.config;

import com.example.shopcore.exchange.ExchangeSettings;
import org.bukkit.configuration.file.FileConfiguration;

import java.math.BigDecimal;
import java.time.ZoneId;

/** 启动时读取一次的配置快照。货币种类/精度不在配置里定义，全部来自 Wallet 官网资产表。 */
public record PluginSettings(
        String serverId,
        String databaseFile,
        BigDecimal minPrice,
        BigDecimal maxPrice,
        int maxQuantity,
        int inputTimeoutSeconds,
        int confirmTimeoutSeconds,
        int paymentTimeoutSeconds,
        int assetsCacheSeconds,
        int balanceCacheSeconds,
        int maxListingsPerPlayer,
        ZoneId resetZone,
        ZoneId timeZone,
        String titleMain,
        String titleOfficial,
        String titlePlayer,
        String titleListings,
        String titleMailbox,
        String titleAdmin,
        String titleAdminManage,
        String titleAdminEdit,
        String titleSelectItem,
        String titleSelectCurrency,
        String titleExchange,
        String titleWalletHistory,
        ExchangeSettings exchange
) {

    public static PluginSettings load(FileConfiguration config) {
        String serverId = config.getString("server.id", "server").trim();
        if (serverId.isEmpty()) {
            serverId = "server";
        }
        if (serverId.contains(":")) {
            throw new IllegalArgumentException("server.id 不能包含冒号");
        }

        BigDecimal minPrice = new BigDecimal(config.getString("limits.min-price", "0.01"));
        BigDecimal maxPrice = new BigDecimal(config.getString("limits.max-price", "1000000"));
        if (minPrice.signum() <= 0) {
            throw new IllegalArgumentException("limits.min-price 必须大于 0");
        }
        if (maxPrice.signum() > 0 && maxPrice.compareTo(minPrice) < 0) {
            throw new IllegalArgumentException("limits.max-price 不能小于 limits.min-price");
        }

        int maxQuantity = config.getInt("limits.max-quantity", 2304);
        if (maxQuantity <= 0) {
            throw new IllegalArgumentException("limits.max-quantity 必须大于 0");
        }

        int inputTimeout = config.getInt("limits.input-timeout-seconds", 60);
        int confirmTimeout = config.getInt("limits.confirm-timeout-seconds", 120);
        int paymentTimeout = config.getInt("economy.payment-timeout-seconds", 60);
        int assetsCache = config.getInt("economy.assets-cache-seconds", 60);
        int balanceCache = config.getInt("economy.balance-cache-seconds", 30);
        if (inputTimeout <= 0 || confirmTimeout <= 0 || paymentTimeout <= 0
                || assetsCache <= 0 || balanceCache <= 0) {
            throw new IllegalArgumentException("输入/确认/支付/缓存时间必须大于 0");
        }

        String zoneRaw = config.getString("rules.reset-zone", "").trim();
        ZoneId zone = zoneRaw.isEmpty() ? ZoneId.systemDefault() : ZoneId.of(zoneRaw);

        ZoneId timeZone;
        try {
            timeZone = ZoneId.of(config.getString("economy.timezone", "Asia/Shanghai"));
        } catch (RuntimeException ex) {
            timeZone = ZoneId.of("Asia/Shanghai");
        }

        return new PluginSettings(
                serverId,
                config.getString("database.file", "shop.db"),
                minPrice,
                maxPrice,
                maxQuantity,
                inputTimeout,
                confirmTimeout,
                paymentTimeout,
                assetsCache,
                balanceCache,
                Math.max(1, config.getInt("player-shop.max-listings-per-player", 28)),
                zone,
                timeZone,
                config.getString("menu.title-main", "<dark_gray>商店</dark_gray>"),
                config.getString("menu.title-official", "<dark_gray>官方商店</dark_gray>"),
                config.getString("menu.title-player", "<dark_gray>玩家商店</dark_gray>"),
                config.getString("menu.title-listings", "<dark_gray>我的上架</dark_gray>"),
                config.getString("menu.title-mailbox", "<dark_gray>待领取物品</dark_gray>"),
                config.getString("menu.title-admin", "<dark_gray>商店管理</dark_gray>"),
                config.getString("menu.title-admin-manage", "<dark_gray>官方商品管理</dark_gray>"),
                config.getString("menu.title-admin-edit", "<dark_gray>编辑官方商品</dark_gray>"),
                config.getString("menu.title-select-item", "<dark_gray>选择物品</dark_gray>"),
                config.getString("menu.title-select-currency", "<dark_gray>选择货币</dark_gray>"),
                config.getString("menu.title-exchange", "<dark_gray>货币兑换</dark_gray>"),
                config.getString("menu.title-wallet-history", "<dark_gray>钱包流水</dark_gray>"),
                ExchangeSettings.load(config)
        );
    }
}
