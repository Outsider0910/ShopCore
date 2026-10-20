package com.example.shopcore.economy;

import com.example.wallet.api.WalletAsset;
import com.example.wallet.api.WalletBalance;
import com.example.wallet.api.WalletException;
import com.example.wallet.api.WalletService;
import com.example.wallet.api.WalletTransaction;
import com.example.wallet.api.WalletTransactionPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wallet 经济层的支付/兑换语义。使用假 WalletService，不访问官网。
 */
class WalletEconomyTest {

    private static final WalletAsset POINT = new WalletAsset(
            "POINT", "积分", "积分", 0, false, true, true);
    private static final WalletAsset CNY = new WalletAsset(
            "CNY", "人民币", "￥", 2, true, true, false);

    private FakeWallet wallet;
    private WalletEconomy economy;

    @BeforeEach
    void setUp() {
        wallet = new FakeWallet();
        economy = new WalletEconomy(null, 60, 30, () -> wallet);
        economy.assets(true).join();
    }

    @Test
    void transferDebitsBuyerAndCreditsSellerInSameAsset() {
        UUID buyer = UUID.randomUUID();
        UUID seller = UUID.randomUUID();
        wallet.set(buyer, "POINT", new BigDecimal("1000"));
        wallet.set(seller, "POINT", BigDecimal.ZERO);

        WalletPaymentResult result = economy.transfer(
                buyer, "Buyer", seller, "Seller", "POINT", new BigDecimal("250"),
                "mc:test:shop:1", "玩家商店购买").join();

        assertTrue(result.successful());
        assertEquals(new BigDecimal("750"), wallet.amount(buyer, "POINT"));
        assertEquals(new BigDecimal("250"), wallet.amount(seller, "POINT"));
    }

    @Test
    void exchangeDebitsSourceAndCreditsDifferentTargetAsset() {
        UUID player = UUID.randomUUID();
        wallet.set(player, "POINT", new BigDecimal("1000"));
        wallet.set(player, "CNY", BigDecimal.ZERO);

        WalletPaymentResult result = economy.exchange(
                player, "Player", "POINT", new BigDecimal("100"),
                "CNY", new BigDecimal("1.00"), "mc:test:exchange:1", "商店货币兑换").join();

        assertTrue(result.successful());
        assertEquals(new BigDecimal("900"), wallet.amount(player, "POINT"));
        assertEquals(new BigDecimal("1.00"), wallet.amount(player, "CNY"));
    }

    @Test
    void definiteTargetFailureRefundsSource() {
        UUID player = UUID.randomUUID();
        wallet.set(player, "POINT", new BigDecimal("1000"));
        wallet.set(player, "CNY", BigDecimal.ZERO);
        wallet.failCreditStatus = 400;
        wallet.failingCreditAssets.add("CNY");

        WalletPaymentResult result = economy.exchange(
                player, "Player", "POINT", new BigDecimal("100"),
                "CNY", new BigDecimal("1.00"), "mc:test:exchange:2", "商店货币兑换").join();

        assertEquals(WalletPaymentResult.Status.FAILED, result.status());
        assertEquals(new BigDecimal("1000"), wallet.amount(player, "POINT"));
        assertEquals(BigDecimal.ZERO, wallet.amount(player, "CNY"));
    }

    @Test
    void unknownTargetFailureDoesNotRefundOrClaimSuccess() {
        UUID player = UUID.randomUUID();
        wallet.set(player, "POINT", new BigDecimal("1000"));
        wallet.set(player, "CNY", BigDecimal.ZERO);
        wallet.failCreditStatus = 500;
        wallet.failingCreditAssets.add("CNY");

        WalletPaymentResult result = economy.exchange(
                player, "Player", "POINT", new BigDecimal("100"),
                "CNY", new BigDecimal("1.00"), "mc:test:exchange:3", "商店货币兑换").join();

        assertEquals(WalletPaymentResult.Status.UNKNOWN, result.status());
        assertEquals(new BigDecimal("900"), wallet.amount(player, "POINT"));
        assertEquals(BigDecimal.ZERO, wallet.amount(player, "CNY"));
    }

    @Test
    void insufficientSourceFundsStopsBeforeTargetCredit() {
        UUID player = UUID.randomUUID();
        wallet.set(player, "POINT", new BigDecimal("10"));
        wallet.set(player, "CNY", BigDecimal.ZERO);

        WalletPaymentResult result = economy.exchange(
                player, "Player", "POINT", new BigDecimal("100"),
                "CNY", new BigDecimal("1.00"), "mc:test:exchange:4", "商店货币兑换").join();

        assertEquals(WalletPaymentResult.Status.INSUFFICIENT_FUNDS, result.status());
        assertEquals(new BigDecimal("10"), wallet.amount(player, "POINT"));
        assertEquals(BigDecimal.ZERO, wallet.amount(player, "CNY"));
        assertTrue(wallet.credited.isEmpty());
    }

    @Test
    void assetScaleAndFormattingComeFromWallet() {
        assertEquals(0, economy.scaleOf("POINT"));
        assertEquals(2, economy.scaleOf("CNY"));
        assertEquals("1.00", economy.format("CNY", BigDecimal.ONE));
        assertEquals("85 积分", economy.formatLikeWallet("POINT", new BigDecimal("85")));
        assertEquals("1.00 ￥", economy.formatLikeWallet("CNY", BigDecimal.ONE));
    }

    @Test
    void refreshBalancesUsesWalletCacheAndDetectsUnbound() {
        UUID player = UUID.randomUUID();
        wallet.set(player, "POINT", new BigDecimal("85"));
        wallet.set(player, "CNY", BigDecimal.ZERO);

        economy.refreshBalances(player, "Player");

        WalletBalance point = economy.cachedBalance(player, "POINT");
        assertNotNull(point);
        assertEquals("85 积分", economy.formatLikeWallet("POINT", point.balance()));

        UUID unbound = UUID.randomUUID();
        economy.refreshBalances(unbound, "unbound");
        assertTrue(economy.unbound(unbound));
    }

    private static final class FakeWallet implements WalletService {

        private final Map<String, BigDecimal> balances = new ConcurrentHashMap<>();
        private final Map<String, WalletBalance> cachedBalances = new ConcurrentHashMap<>();
        private final Set<String> failingCreditAssets = new HashSet<>();
        private final List<String> credited = new ArrayList<>();
        private int failCreditStatus = 400;

        void set(UUID player, String code, BigDecimal amount) {
            balances.put(key(player, code), amount);
        }

        BigDecimal amount(UUID player, String code) {
            return balances.getOrDefault(key(player, code), BigDecimal.ZERO);
        }

        private static String key(UUID player, String code) {
            return player + "|" + code;
        }

        @Override
        public CompletableFuture<List<WalletAsset>> assets() {
            return CompletableFuture.completedFuture(List.of(POINT, CNY));
        }

        @Override
        public CompletableFuture<WalletBalance> balance(UUID playerId, String playerName, String assetCode) {
            if ("unbound".equalsIgnoreCase(playerName)) {
                return CompletableFuture.failedFuture(
                        new WalletException(400, "玩家未绑定网站用户：unbound"));
            }
            WalletBalance balance = new WalletBalance(
                    "user-1", playerName, playerId.toString(), assetCode,
                    amount(playerId, assetCode), System.currentTimeMillis());
            cachedBalances.put(key(playerId, assetCode), balance);
            return CompletableFuture.completedFuture(balance);
        }

        @Override
        public CompletableFuture<WalletTransaction> credit(UUID playerId, String playerName,
                                                           String assetCode, BigDecimal amount,
                                                           String businessNo, String remark) {
            if (failingCreditAssets.contains(assetCode)) {
                return CompletableFuture.failedFuture(
                        new WalletException(failCreditStatus, "模拟入账失败"));
            }
            BigDecimal next = amount(playerId, assetCode).add(amount);
            balances.put(key(playerId, assetCode), next);
            remember(playerId, playerName, assetCode, next);
            credited.add(assetCode);
            return CompletableFuture.completedFuture(transaction(
                    "CREDIT", assetCode, amount, next, businessNo));
        }

        @Override
        public CompletableFuture<WalletTransaction> debit(UUID playerId, String playerName,
                                                          String assetCode, BigDecimal amount,
                                                          String businessNo, String remark) {
            BigDecimal current = amount(playerId, assetCode);
            if (current.compareTo(amount) < 0) {
                return CompletableFuture.failedFuture(
                        new WalletException(400, "余额不足（当前 " + current.toPlainString() + "）"));
            }
            BigDecimal next = current.subtract(amount);
            balances.put(key(playerId, assetCode), next);
            remember(playerId, playerName, assetCode, next);
            return CompletableFuture.completedFuture(transaction(
                    "DEBIT", assetCode, amount, next, businessNo));
        }

        private static WalletTransaction transaction(String type, String assetCode, BigDecimal amount,
                                                     BigDecimal after, String businessNo) {
            return new WalletTransaction(
                    UUID.randomUUID().toString(), businessNo, type, "TEST", assetCode,
                    "DEBIT".equals(type) ? "OUT" : "IN", amount, after, "", System.currentTimeMillis());
        }

        @Override
        public CompletableFuture<WalletTransactionPage> transactions(UUID playerId, String playerName,
                                                                     int page, int size) {
            return CompletableFuture.completedFuture(new WalletTransactionPage(List.of(), 0));
        }

        @Override
        public CompletableFuture<WalletTransactionPage> transactions(UUID playerId, String playerName,
                                                                     List<String> assetCodes,
                                                                     int page, int size) {
            return CompletableFuture.completedFuture(new WalletTransactionPage(List.of(), 0));
        }

        @Override
        public List<WalletAsset> cachedAssets() {
            return List.of(POINT, CNY);
        }

        private void remember(UUID playerId, String playerName, String assetCode, BigDecimal amount) {
            cachedBalances.put(key(playerId, assetCode), new WalletBalance(
                    "user-1", playerName, playerId.toString(), assetCode,
                    amount, System.currentTimeMillis()));
        }

        @Override
        public WalletBalance cachedBalance(UUID playerId, String assetCode) {
            return cachedBalances.get(key(playerId, assetCode));
        }
    }
}
