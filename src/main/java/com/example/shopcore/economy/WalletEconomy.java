package com.example.shopcore.economy;

import com.example.shopcore.config.PluginSettings;
import com.example.shopcore.util.Amounts;
import com.example.wallet.api.WalletApi;
import com.example.wallet.api.WalletAsset;
import com.example.wallet.api.WalletBalance;
import com.example.wallet.api.WalletException;
import com.example.wallet.api.WalletService;
import com.example.wallet.api.WalletTransaction;
import com.example.wallet.api.WalletTransactionPage;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * 唯一经济入口：直接使用 mcPlugin/Wallet 的 {@link WalletService}。
 *
 * <ul>
 *   <li>货币类型、名称、精度、启用状态全部来自 Wallet 官网资产表；</li>
 *   <li>余额来自官网，本地只做短时缓存用于菜单展示；</li>
 *   <li>商店所有扣款/入账都调用 Wallet 的 {@code debit}/{@code credit}，由 Wallet 写回官网；</li>
 *   <li>本插件不再保存任何本地余额，也没有 internal-test / service 等替代货币模式。</li>
 * </ul>
 */
public final class WalletEconomy implements CurrencyLookup {

    private static final int UNKNOWN_SCALE_FALLBACK = 2;

    private final Plugin plugin;
    private final Supplier<WalletService> walletSupplier;
    private volatile long assetsCacheMillis;
    private volatile long balanceCacheMillis;
    private final Object assetsLock = new Object();
    private final Map<UUID, CachedBalances> balanceCache = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<UUID, Long> balanceRefreshAt = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<UUID, Boolean> unbound = new java.util.concurrent.ConcurrentHashMap<>();

    private volatile Map<String, CurrencyAsset> assetsByCode = Map.of();
    private volatile List<CurrencyAsset> enabledAssets = List.of();
    private volatile Set<String> localEnabled;
    private volatile long assetsFetchedAt;
    private volatile long localSwitchReadAt;

    public WalletEconomy(Plugin plugin, PluginSettings settings) {
        this.plugin = plugin;
        this.walletSupplier = () -> WalletApi.walletOrNull();
        applySettings(settings);
    }

    /** /shopadmin reload 时更新缓存时长，不需要重建经济层。 */
    public void applySettings(PluginSettings settings) {
        this.assetsCacheMillis = Math.max(1, settings.assetsCacheSeconds()) * 1000L;
        this.balanceCacheMillis = Math.max(1, settings.balanceCacheSeconds()) * 1000L;
    }

    /** 包内可见构造器：测试时注入假的 WalletService，不访问官网。 */
    WalletEconomy(Plugin plugin, long assetsCacheSeconds, long balanceCacheSeconds,
                  Supplier<WalletService> walletSupplier) {
        this.plugin = plugin;
        this.walletSupplier = walletSupplier;
        this.assetsCacheMillis = Math.max(1, assetsCacheSeconds) * 1000L;
        this.balanceCacheMillis = Math.max(1, balanceCacheSeconds) * 1000L;
    }

    /** 每次调用现取 Wallet 服务，避免 Wallet 重载后仍持有旧实例。 */
    private WalletService service() {
        WalletService current = walletSupplier.get();
        if (current == null) {
            throw new IllegalStateException("Wallet 尚未加载");
        }
        return current;
    }

    /** Wallet 是否已由服务端加载；ShopCore 把 Wallet 声明为必需依赖，这里只做兜底判断。 */
    public static boolean walletPresent() {
        return WalletApi.walletOrNull() != null;
    }

    // ------------------------------------------------------------------
    // 货币资产
    // ------------------------------------------------------------------

    /**
     * 读取官网资产表。返回值只包含官网 {@code enabled=true} 的货币；顺序与官网一致。
     *
     * @param force 忽略本地短时缓存，强制重新请求 Wallet/官网
     */
    public CompletableFuture<List<CurrencyAsset>> assets(boolean force) {
        List<CurrencyAsset> current = enabledAssets;
        if (!force && !current.isEmpty()
                && System.currentTimeMillis() - assetsFetchedAt < assetsCacheMillis) {
            return CompletableFuture.completedFuture(current);
        }
        synchronized (assetsLock) {
            current = enabledAssets;
            long age = System.currentTimeMillis() - assetsFetchedAt;
            if (!force && !current.isEmpty() && age < assetsCacheMillis) {
                return CompletableFuture.completedFuture(current);
            }
            try {
                return service().assets().thenApply(this::acceptAssets);
            } catch (Throwable error) {
                return CompletableFuture.failedFuture(error);
            }
        }
    }

    /** 官网启用的货币；没有缓存时立即请求一次。 */
    public CompletableFuture<List<CurrencyAsset>> enabledAssets(boolean force) {
        return assets(force);
    }

    /** 只读缓存：官网启用的货币。可能为空（尚未加载）。 */
    public List<CurrencyAsset> cachedEnabledAssets() {
        return enabledAssets;
    }

    /** 只读缓存：全部官网资产（含停用），用于判断某个 code 是否存在。 */
    public Map<String, CurrencyAsset> cachedAssets() {
        return assetsByCode;
    }

    public Optional<CurrencyAsset> asset(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(assetsByCode.get(code.trim().toUpperCase(Locale.ROOT)));
    }

    /** 资产可购买：官网启用且本服启用（与 Wallet 的 assets.yml / config.yml 口径一致）。 */
    public Optional<CurrencyAsset> enabledAsset(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        String wanted = code.trim().toUpperCase(Locale.ROOT);
        for (CurrencyAsset asset : enabledAssets) {
            if (asset.code().equals(wanted)) {
                return Optional.of(asset);
            }
        }
        return Optional.empty();
    }

    /** 本服是否启用该货币；没有本服开关时等于官网 enabled。 */
    public boolean locallyEnabled(String code) {
        Set<String> local = this.localEnabled;
        return code != null && (local == null || local.contains(code.trim().toLowerCase(Locale.ROOT)));
    }

    /**
     * 只重读 Wallet 的本服货币开关（assets.yml / config.yml），不访问官网。
     * 用于主菜单余额刷新时同步 Wallet 管理员刚切换的币种，按 5 秒节流。
     */
    public void refreshLocalSwitches() {
        long now = System.currentTimeMillis();
        if (now - localSwitchReadAt < 5_000L) {
            return;
        }
        localSwitchReadAt = now;
        Map<String, CurrencyAsset> all = assetsByCode;
        if (all.isEmpty()) {
            return;
        }
        Set<String> local = readLocalEnabledCodes();
        this.localEnabled = local;
        this.enabledAssets = all.values().stream()
                .filter(asset -> asset.enabled()
                        && (local == null || local.contains(asset.code().toLowerCase(Locale.ROOT))))
                .toList();
    }

    public Optional<CurrencyAsset> defaultAsset() {
        return enabledAssets.stream().findFirst();
    }

    @Override
    public String resolveCode(String assetCode) {
        CurrencyAsset asset = effectiveAsset(assetCode);
        if (asset != null) {
            return asset.code();
        }
        return assetCode == null ? "" : assetCode.trim().toUpperCase(Locale.ROOT);
    }

    @Override
    public int scaleOf(String assetCode) {
        CurrencyAsset asset = effectiveAsset(assetCode);
        return asset == null ? UNKNOWN_SCALE_FALLBACK : asset.scale();
    }

    public String format(String assetCode, BigDecimal amount) {
        return Amounts.format(amount == null ? BigDecimal.ZERO : amount, scaleOf(assetCode));
    }

    /** 与 Wallet 菜单完全一致的显示：按 scale 向下取整，并在需要时追加资产 symbol。 */
    public String formatLikeWallet(String assetCode, BigDecimal amount) {
        CurrencyAsset asset = effectiveAsset(assetCode);
        int scale = asset == null ? 0 : asset.scale();
        BigDecimal safe = amount == null ? BigDecimal.ZERO : amount;
        String text = safe.setScale(scale, RoundingMode.DOWN).toPlainString();
        String symbol = asset == null ? "" : asset.symbol();
        return symbol == null || symbol.isBlank() ? text : text + " " + symbol;
    }

    public String displayName(String assetCode) {
        CurrencyAsset asset = effectiveAsset(assetCode);
        if (asset != null) {
            return asset.displayName();
        }
        return assetCode == null || assetCode.isBlank() ? "货币" : assetCode;
    }

    public boolean available() {
        if (walletSupplier.get() == null) {
            return false;
        }
        if (!enabledAssets.isEmpty()) {
            return true;
        }
        assets(false).exceptionally(error -> {
            if (plugin != null) {
                plugin.getLogger().log(Level.WARNING, "读取 Wallet 货币列表失败", error);
            }
            return List.of();
        });
        return false;
    }

    public CompletableFuture<Void> preload() {
        return assets(true).thenAccept(list -> {
                    if (plugin != null) {
                        plugin.getLogger().info(
                                "已从 Wallet 读取官网货币：" + (list.isEmpty() ? "暂无启用货币"
                                        : list.stream().map(asset -> asset.code()
                                                + "(" + asset.displayName() + ",scale=" + asset.scale() + ")")
                                        .toList()));
                    }
                })
                .exceptionally(error -> {
                    if (plugin != null) {
                        plugin.getLogger().log(Level.WARNING, "读取 Wallet 货币列表失败，"
                                + "购买/兑换会暂时不可用", error);
                    }
                    return null;
                });
    }

    private List<CurrencyAsset> acceptAssets(List<WalletAsset> list) {
        Map<String, CurrencyAsset> byCode = new LinkedHashMap<>();
        List<CurrencyAsset> enabled = new ArrayList<>();
        Set<String> local = readLocalEnabledCodes();
        if (list != null) {
            for (WalletAsset raw : list) {
                if (raw == null || raw.code() == null || raw.code().isBlank()) {
                    continue;
                }
                CurrencyAsset asset = CurrencyAsset.from(raw);
                byCode.put(asset.code(), asset);
                if (asset.enabled() && (local == null || local.contains(asset.code().toLowerCase(Locale.ROOT)))) {
                    enabled.add(asset);
                }
            }
        }
        this.assetsByCode = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(byCode));
        this.enabledAssets = List.copyOf(enabled);
        this.localEnabled = local;
        this.assetsFetchedAt = System.currentTimeMillis();
        return this.enabledAssets;
    }

    /**
     * 读取 Wallet 的本服货币开关，和 Wallet 的 AssetSwitchStore 保持同一口径：
     * plugins/Wallet/assets.yml 优先；没有时用 Wallet config.yml 的 assets；
     * 两者都没有 = 官网 enabled 的全部算本服启用。
     */
    private Set<String> readLocalEnabledCodes() {
        if (plugin == null) {
            return null;
        }
        try {
            Plugin walletPlugin = plugin.getServer().getPluginManager().getPlugin("Wallet");
            if (walletPlugin == null) {
                return null;
            }
            File dataFolder = walletPlugin.getDataFolder();
            File switches = new File(dataFolder, "assets.yml");
            if (switches.isFile()) {
                return lowerCodes(YamlConfiguration.loadConfiguration(switches).getStringList("enabled"));
            }
            File config = new File(dataFolder, "config.yml");
            if (config.isFile()) {
                List<String> initial = YamlConfiguration.loadConfiguration(config).getStringList("assets");
                if (!initial.isEmpty()) {
                    return lowerCodes(initial);
                }
            }
        } catch (RuntimeException ignored) {
            // 读不到就按官网 enabled 处理，不影响商店启动
        }
        return null;
    }

    private static Set<String> lowerCodes(List<String> raw) {
        Set<String> result = new HashSet<>();
        if (raw != null) {
            for (String code : raw) {
                if (code != null && !code.isBlank()) {
                    result.add(code.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        return result;
    }

    private CurrencyAsset effectiveAsset(String assetCode) {
        if (assetCode != null && !assetCode.isBlank()) {
            return assetsByCode.get(assetCode.trim().toUpperCase(Locale.ROOT));
        }
        return enabledAssets.isEmpty() ? null : enabledAssets.get(0);
    }

    // ------------------------------------------------------------------
    // 余额（只读官网）
    // ------------------------------------------------------------------

    public CompletableFuture<BigDecimal> balance(UUID playerId, String playerName, String assetCode) {
        String code = resolveCode(assetCode);
        if (code.isBlank()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("没有可用的 Wallet 货币"));
        }
        try {
            return service().balance(playerId, safeName(playerName), code)
                    .thenApply(balance -> balance == null ? BigDecimal.ZERO : balance.balance());
        } catch (Throwable error) {
            return CompletableFuture.failedFuture(error);
        }
    }

    /**
     * 查询玩家在官网全部启用货币上的余额。单个币种失败不会影响其他币种。
     * 结果按 30 秒（economy.balance-cache-seconds）缓存，避免菜单频繁刷新时重复请求。
     */
    public CompletableFuture<List<CurrencyBalance>> balances(UUID playerId, String playerName) {
        CachedBalances cached = balanceCache.get(playerId);
        if (cached != null && System.currentTimeMillis() - cached.at() < balanceCacheMillis) {
            return CompletableFuture.completedFuture(cached.values());
        }
        return assets(false).thenCompose(list -> {
            if (list.isEmpty()) {
                return CompletableFuture.completedFuture(List.<CurrencyBalance>of());
            }
            List<CompletableFuture<CurrencyBalance>> futures = new ArrayList<>();
            for (CurrencyAsset asset : list) {
                futures.add(service().balance(playerId, safeName(playerName), asset.code())
                        .handle((balance, error) -> new CurrencyBalance(
                                asset,
                                balance == null ? null : balance.balance(),
                                error == null ? null : errorMessage(error))));
            }
            return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                    .thenApply(ignored -> {
                        List<CurrencyBalance> values = futures.stream()
                                .map(CompletableFuture::join)
                                .toList();
                        balanceCache.put(playerId, new CachedBalances(values, System.currentTimeMillis()));
                        return values;
                    });
        });
    }

    public void invalidateBalances(UUID playerId) {
        if (playerId != null) {
            balanceCache.remove(playerId);
            balanceRefreshAt.remove(playerId);
            unbound.remove(playerId);
        }
    }

    /** 只读 Wallet 余额缓存；没有缓存时返回 null（渲染路径不发请求）。 */
    public WalletBalance cachedBalance(UUID playerId, String assetCode) {
        try {
            String code = resolveCode(assetCode);
            return code.isBlank() ? null : service().cachedBalance(playerId, code);
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    /** 玩家是否被 Wallet 判定为未绑定网站账号。 */
    public boolean unbound(UUID playerId) {
        return Boolean.TRUE.equals(unbound.get(playerId));
    }

    /**
     * 与 Wallet 主菜单相同的刷新策略：每个玩家 5 秒最多触发一次异步余额刷新，
     * 渲染路径只读 Wallet 缓存。调用方可以每秒重绘，但不会每秒发请求。
     */
    public void refreshBalances(UUID playerId, String playerName) {
        long now = System.currentTimeMillis();
        Long last = balanceRefreshAt.get(playerId);
        if (last != null && now - last < 5_000L) {
            return;
        }
        balanceRefreshAt.put(playerId, now);

        refreshLocalSwitches();
        assets(false).thenAccept(list -> {
            for (CurrencyAsset asset : list) {
                try {
                    service().balance(playerId, safeName(playerName), asset.code())
                            .whenComplete((balance, error) -> {
                                if (error == null) {
                                    unbound.remove(playerId);
                                } else if (unboundError(error)) {
                                    unbound.put(playerId, Boolean.TRUE);
                                }
                            });
                } catch (RuntimeException | LinkageError ignored) {
                    // Wallet 正在重载时跳过本轮，下一次重绘会重试
                }
            }
        });
    }

    /** 玩家流水（按时间倒序），一次性查询本服当前启用的全部币种。 */
    public CompletableFuture<WalletTransactionPage> transactions(UUID playerId, String playerName,
                                                                 int page, int size) {
        List<String> codes = enabledAssets.stream().map(CurrencyAsset::code).toList();
        try {
            return service().transactions(playerId, safeName(playerName), codes, page, size);
        } catch (RuntimeException | LinkageError error) {
            return CompletableFuture.failedFuture(error);
        }
    }

    private static boolean unboundError(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof WalletException exception
                    && exception.getMessage() != null
                    && exception.getMessage().contains("未绑定")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 加减货币（唯一写路径：Wallet -> 官网）
    // ------------------------------------------------------------------

    /**
     * 商店付款：先扣买家，买家需要收款方时再给收款方入账。
     *
     * <p>Wallet 没有原子转账端点，所以这不是事务：目标入账确定失败时自动退回源币；
     * 任一步结果未知都返回 UNKNOWN，商店不发货，由管理员按 businessNo 在官网对账。</p>
     */
    public CompletableFuture<WalletPaymentResult> transfer(UUID from,
                                                           String fromName,
                                                           UUID to,
                                                           String toName,
                                                           String assetCode,
                                                           BigDecimal amount,
                                                           String businessNo,
                                                           String remark) {
        return move(from, fromName, to, toName, assetCode, amount, assetCode, amount,
                businessNo, remark);
    }

    /** 商店货币兑换：扣源币、加目标币（可以是不同资产/精度）。 */
    public CompletableFuture<WalletPaymentResult> exchange(UUID playerId,
                                                           String playerName,
                                                           String fromCode,
                                                           BigDecimal fromAmount,
                                                           String toCode,
                                                           BigDecimal toAmount,
                                                           String businessNo,
                                                           String remark) {
        return move(playerId, playerName, playerId, playerName,
                fromCode, fromAmount, toCode, toAmount, businessNo, remark);
    }

    private CompletableFuture<WalletPaymentResult> move(UUID from,
                                                        String fromName,
                                                        UUID to,
                                                        String toName,
                                                        String fromCode,
                                                        BigDecimal fromAmount,
                                                        String toCode,
                                                        BigDecimal toAmount,
                                                        String businessNo,
                                                        String remark) {
        String debitCode = resolveCode(fromCode);
        if (from == null || debitCode.isBlank()
                || fromAmount == null || fromAmount.signum() <= 0
                || businessNo == null || businessNo.isBlank()) {
            return CompletableFuture.completedFuture(
                    WalletPaymentResult.failed("支付参数不完整或金额不合法"));
        }
        String creditCode = resolveCode(toCode);
        if (to != null && (creditCode.isBlank()
                || toAmount == null || toAmount.signum() <= 0)) {
            return CompletableFuture.completedFuture(
                    WalletPaymentResult.failed("目标货币参数不完整或金额不合法"));
        }

        return debit(from, safeName(fromName), debitCode, fromAmount, businessNo + ":debit", remark)
                .thenCompose(debitResult -> {
                    if (!debitResult.successful()) {
                        return CompletableFuture.completedFuture(debitResult);
                    }
                    if (to == null) {
                        return CompletableFuture.completedFuture(WalletPaymentResult.success());
                    }
                    return credit(to, safeName(toName), creditCode, toAmount,
                            businessNo + ":credit", remark)
                            .thenCompose(creditResult -> {
                                if (creditResult.successful()) {
                                    return CompletableFuture.completedFuture(
                                            WalletPaymentResult.success());
                                }
                                if (creditResult.status() == WalletPaymentResult.Status.UNKNOWN) {
                                    return CompletableFuture.completedFuture(
                                            WalletPaymentResult.unknown(
                                                    "目标货币入账结果未知，业务号 " + businessNo
                                                            + "：" + creditResult.message()));
                                }
                                return credit(from, safeName(fromName), debitCode, fromAmount,
                                        businessNo + ":refund", "退款：" + remark)
                                        .thenApply(refundResult -> {
                                            if (refundResult.successful()) {
                                                return WalletPaymentResult.failed(
                                                        "目标货币入账失败，源货币已退回："
                                                                + creditResult.message());
                                            }
                                            return WalletPaymentResult.unknown(
                                                    "目标货币入账失败，退款结果未知；业务号 "
                                                            + businessNo + "："
                                                            + refundResult.message());
                                        });
                            });
                });
    }

    /** 商店/管理员加币，直接写官网。 */
    public CompletableFuture<WalletPaymentResult> credit(UUID playerId, String playerName,
                                                         String assetCode, BigDecimal amount,
                                                         String businessNo, String remark) {
        return change(true, playerId, playerName, assetCode, amount, businessNo, remark);
    }

    /** 商店/管理员扣币，直接写官网。 */
    public CompletableFuture<WalletPaymentResult> debit(UUID playerId, String playerName,
                                                        String assetCode, BigDecimal amount,
                                                        String businessNo, String remark) {
        return change(false, playerId, playerName, assetCode, amount, businessNo, remark);
    }

    private CompletableFuture<WalletPaymentResult> change(boolean credit,
                                                          UUID playerId,
                                                          String playerName,
                                                          String assetCode,
                                                          BigDecimal amount,
                                                          String businessNo,
                                                          String remark) {
        String code = resolveCode(assetCode);
        if (playerId == null || code.isBlank()
                || amount == null || amount.signum() <= 0
                || businessNo == null || businessNo.isBlank()) {
            return CompletableFuture.completedFuture(
                    WalletPaymentResult.failed("Wallet 参数不完整或金额不合法"));
        }
        try {
            CompletableFuture<WalletTransaction> future = credit
                    ? service().credit(playerId, safeName(playerName), code, amount, businessNo, remark)
                    : service().debit(playerId, safeName(playerName), code, amount, businessNo, remark);
            return future.handle((transaction, error) -> {
                if (error != null) {
                    return mapError(error);
                }
                invalidateBalances(playerId);
                return WalletPaymentResult.success();
            });
        } catch (Throwable error) {
            return CompletableFuture.completedFuture(mapError(error));
        }
    }

    private WalletPaymentResult mapError(Throwable error) {
        Throwable cause = unwrap(error);
        String message = cause.getMessage() == null ? "Wallet 调用失败" : cause.getMessage();
        if (cause instanceof WalletException walletException) {
            if (message.contains("余额不足") || message.toLowerCase(Locale.ROOT).contains("insufficient")) {
                return WalletPaymentResult.insufficientFunds(message);
            }
            int status = walletException.statusCode();
            if (status >= 400 && status < 500) {
                return WalletPaymentResult.failed(message);
            }
            // WalletException(0, ...) 既有本地校验失败，也有 HTTP/网络失败。
            // “请求官网失败”无法确认是否已执行，按 UNKNOWN；其余本地校验按 FAILED。
            if (status == 0 && !message.contains("请求官网失败")) {
                return WalletPaymentResult.failed(message);
            }
        }
        return WalletPaymentResult.unknown(message);
    }

    private static String errorMessage(Throwable error) {
        Throwable cause = unwrap(error);
        String message = cause.getMessage();
        return message == null || message.isBlank() ? "查询失败" : message;
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while (current instanceof CompletionException || current instanceof ExecutionException) {
            Throwable cause = current.getCause();
            if (cause == null || cause == current) {
                break;
            }
            current = cause;
        }
        return current;
    }

    private static String safeName(String name) {
        return name == null ? "" : name;
    }

    private record CachedBalances(List<CurrencyBalance> values, long at) {
    }
}
