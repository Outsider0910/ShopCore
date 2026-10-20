package com.example.shopcore;

import com.example.mainmenu.api.MainMenuApi;
import com.example.mainmenu.api.MenuFeatureRegistry;
import com.example.shopcore.command.ShopAdminCommand;
import com.example.shopcore.command.ShopCommand;
import com.example.shopcore.config.Messages;
import com.example.shopcore.config.PluginSettings;
import com.example.shopcore.economy.WalletEconomy;
import com.example.shopcore.exchange.ExchangeService;
import com.example.shopcore.item.UiItems;
import com.example.shopcore.listener.ChatListener;
import com.example.shopcore.listener.MenuListener;
import com.example.shopcore.listener.PlayerListener;
import com.example.shopcore.menu.MenuService;
import com.example.shopcore.menu.ShopMainMenuFeature;
import com.example.shopcore.service.DeliveryService;
import com.example.shopcore.service.ShopService;
import com.example.shopcore.session.SessionManager;
import com.example.shopcore.storage.SqliteShopStorage;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.sql.SQLException;
import java.util.List;

public final class ShopCorePlugin extends JavaPlugin {

    private PluginSettings settings;
    private Messages messages;
    private SqliteShopStorage storage;
    private WalletEconomy walletEconomy;
    private DeliveryService delivery;
    private SessionManager sessions;
    private UiItems uiItems;
    private ExchangeService exchangeService;
    private ShopService shopService;
    private MenuService menuService;
    private BukkitTask cleanupTask;
    private BukkitTask balanceTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        try {
            settings = PluginSettings.load(getConfig());
            messages = new Messages(this);
            if (!WalletEconomy.walletPresent()) {
                getLogger().severe("Wallet 未加载。ShopCore 的货币、余额与支付全部依赖 Wallet，"
                        + "请确认 paper-plugin.yml 的 Wallet 依赖和 Wallet 插件配置。");
                getServer().getPluginManager().disablePlugin(this);
                return;
            }
            walletEconomy = new WalletEconomy(this, settings);
            File database = new File(getDataFolder(), settings.databaseFile());
            storage = new SqliteShopStorage(database.toPath(), walletEconomy, settings.resetZone());
            storage.warmUp().join();
        } catch (Exception ex) {
            getLogger().severe("ShopCore 启动失败：" + ex.getMessage());
            ex.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        sessions = new SessionManager();
        uiItems = new UiItems(this);
        delivery = new DeliveryService(this, storage);
        exchangeService = new ExchangeService(
                this, settings, messages, storage, walletEconomy, sessions);
        shopService = new ShopService(
                this, settings, messages, storage, walletEconomy, delivery, sessions, uiItems, exchangeService);
        menuService = new MenuService(this, shopService, uiItems);
        registerMainMenuFeature();
        walletEconomy.preload();

        getServer().getPluginManager().registerEvents(new MenuListener(this), this);
        getServer().getPluginManager().registerEvents(new ChatListener(this), this);
        getServer().getPluginManager().registerEvents(new PlayerListener(this), this);

        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            Commands commands = event.registrar();
            commands.register("shop", "打开商店", List.of("scshop"), new ShopCommand(this));
            commands.register("shopadmin", "商店管理", List.of("scadmin"), new ShopAdminCommand(this));
        });

        cleanupTask = getServer().getScheduler().runTaskTimerAsynchronously(
                this, () -> {
                    sessions.cleanupExpired(System.currentTimeMillis());
                    shopService.cleanupPendingSelections();
                }, 600L, 600L);

        // 主菜单余额每秒重绘一次；真正的官网请求由 WalletEconomy 按 5 秒节流。
        balanceTask = getServer().getScheduler().runTaskTimer(this, () -> {
            if (menuService != null) {
                menuService.tickOpenMainBalances();
            }
        }, 20L, 20L);

        getLogger().info("ShopCore 已启用：货币/余额/支付全部走 Wallet 官网，"
                + "兑换=" + (settings.exchange().enabled()
                        ? (settings.exchange().bidirectional() ? "双向" : "单向")
                        + "/" + settings.exchange().rates().size() + " 组"
                        : "关闭")
                + "，数据库=" + storage.dbPath());
    }

    @Override
    public void onDisable() {
        try {
            MenuFeatureRegistry registry = MainMenuApi.registryOrNull();
            if (registry != null) {
                registry.unregister(ShopMainMenuFeature.FEATURE_ID);
            }
        } catch (RuntimeException ignored) {
            // MainMenu 可能已经在关闭
        }
        if (cleanupTask != null) {
            cleanupTask.cancel();
        }
        if (balanceTask != null) {
            balanceTask.cancel();
        }
        if (storage != null) {
            storage.close();
        }
    }

    private void registerMainMenuFeature() {
        if (!MainMenuApi.isAvailable()) {
            getLogger().warning("MainMenu 未就绪，商店未注册到时钟主菜单。");
            return;
        }
        try {
            MainMenuApi.registry().register(new ShopMainMenuFeature(this));
            getLogger().info("已注册时钟主菜单功能：" + ShopMainMenuFeature.FEATURE_ID);
        } catch (RuntimeException ex) {
            getLogger().log(java.util.logging.Level.SEVERE, "注册时钟主菜单商店入口失败", ex);
        }
    }

    /**
     * /shopadmin reload：保留数据库连接、Wallet 适配和会话，重建配置、菜单服务。
     * 已打开的旧菜单仍指向旧服务，关闭重开即可。
     */
    public void reloadRuntime() {
        String oldDb = settings == null ? null : settings.databaseFile();
        reloadConfig();
        PluginSettings next = PluginSettings.load(getConfig());
        if (oldDb != null && !oldDb.equals(next.databaseFile())) {
            getLogger().warning("database.file 已修改，但数据库连接不会热切换；请重启服务器生效。");
        }
        settings = next;
        messages = new Messages(this);
        walletEconomy.applySettings(next);
        delivery = new DeliveryService(this, storage);
        exchangeService = new ExchangeService(
                this, settings, messages, storage, walletEconomy, sessions);
        shopService = new ShopService(
                this, settings, messages, storage, walletEconomy, delivery, sessions, uiItems, exchangeService);
        menuService = new MenuService(this, shopService, uiItems);
        walletEconomy.preload();
    }

    public PluginSettings settings() {
        return settings;
    }

    public Messages messages() {
        return messages;
    }

    public SqliteShopStorage storage() {
        return storage;
    }

    public WalletEconomy walletEconomy() {
        return walletEconomy;
    }

    public SessionManager sessions() {
        return sessions;
    }

    public UiItems uiItems() {
        return uiItems;
    }

    public ExchangeService exchangeService() {
        return exchangeService;
    }

    public ShopService shopService() {
        return shopService;
    }

    public MenuService menuService() {
        return menuService;
    }
}
