package com.example.shopcore.storage;

import com.example.shopcore.economy.CurrencyLookup;
import com.example.shopcore.exchange.ExchangeOrder;
import com.example.shopcore.exchange.ExchangeStatus;
import com.example.shopcore.model.MailboxEntry;
import com.example.shopcore.model.OfficialItem;
import com.example.shopcore.model.OfficialQuoteData;
import com.example.shopcore.model.OfficialReservation;
import com.example.shopcore.model.OperationResult;
import com.example.shopcore.model.Order;
import com.example.shopcore.model.OrderStatus;
import com.example.shopcore.model.PlayerListing;
import com.example.shopcore.model.PlayerReservation;
import com.example.shopcore.util.ResetPeriod;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteShopStorageTest {

    @TempDir
    Path tempDir;

    private SqliteShopStorage storage;

    private static final CurrencyLookup CURRENCIES = new CurrencyLookup() {
        @Override
        public String resolveCode(String assetCode) {
            return assetCode == null || assetCode.isBlank()
                    ? "CNY" : assetCode.trim().toUpperCase(java.util.Locale.ROOT);
        }

        @Override
        public int scaleOf(String assetCode) {
            return "POINT".equalsIgnoreCase(assetCode) ? 0 : 2;
        }
    };

    @BeforeEach
    void setUp() throws Exception {
        storage = new SqliteShopStorage(tempDir.resolve("shop.db"), CURRENCIES, ZoneOffset.UTC);
    }

    @AfterEach
    void tearDown() {
        storage.close();
    }

    @Test
    void officialPurchaseQuotesFinalizesAndEnforcesLimit() {
        storage.insertOfficialItem(official("diamond", 5, 5, "10", "0", ResetPeriod.NEVER)).join();
        UUID buyer = UUID.randomUUID();
        long now = System.currentTimeMillis();

        OperationResult<OfficialQuoteData> quote =
                storage.quoteOfficialPurchase("diamond", buyer, 2, now).join();
        assertTrue(quote.success());
        assertEquals(new BigDecimal("210.00"), quote.value().quote().totalPrice());

        OperationResult<OfficialReservation> reservation =
                storage.reserveOfficialPurchase("diamond", buyer, 2, "test", now).join();
        assertTrue(reservation.success());
        assertEquals(3, storage.getOfficialItem("diamond").join().orElseThrow().stock());

        storage.finalizeOfficialOrder(reservation.value().order().id()).join();
        Order order = storage.getOrder(reservation.value().order().id()).join().orElseThrow();
        assertEquals(OrderStatus.COMPLETED, order.status());

        List<MailboxEntry> mailbox = storage.listAllMailbox(buyer).join();
        assertEquals(1, mailbox.size());
        assertEquals(2, mailbox.get(0).quantity());

        OperationResult<OfficialQuoteData> next =
                storage.quoteOfficialPurchase("diamond", buyer, 1, now).join();
        assertTrue(next.success());
        assertEquals(new BigDecimal("120.00"), next.value().quote().firstUnitPrice());

        OperationResult<OfficialQuoteData> overLimit =
                storage.quoteOfficialPurchase("diamond", buyer, 4, now).join();
        assertFalse(overLimit.success());
        assertEquals("error.limit-reached", overLimit.messageKey());
    }

    @Test
    void cancelOfficialRestoresStock() {
        storage.insertOfficialItem(official("apple", 2, 0, "0", "0", ResetPeriod.NEVER)).join();
        UUID buyer = UUID.randomUUID();
        long now = System.currentTimeMillis();

        OperationResult<OfficialReservation> reservation =
                storage.reserveOfficialPurchase("apple", buyer, 1, "test", now).join();
        assertTrue(reservation.success());
        assertEquals(1, storage.getOfficialItem("apple").join().orElseThrow().stock());

        storage.cancelOfficialOrder(reservation.value().order().id()).join();
        assertEquals(2, storage.getOfficialItem("apple").join().orElseThrow().stock());
        assertEquals(OrderStatus.FAILED,
                storage.getOrder(reservation.value().order().id()).join().orElseThrow().status());
    }

    @Test
    void playerListingPurchaseAndDelist() {
        UUID seller = UUID.randomUUID();
        UUID buyer = UUID.randomUUID();
        storage.insertPlayerListing(listing("listing-1", seller, 10, "50.00"), 10).join();
        long now = System.currentTimeMillis();

        OperationResult<PlayerReservation> reservation =
                storage.reservePlayerPurchase("listing-1", buyer, 4, "test", now).join();
        assertTrue(reservation.success());
        assertEquals(6, storage.getPlayerListing("listing-1").join().orElseThrow().quantity());

        storage.finalizePlayerOrder(reservation.value().order().id()).join();
        List<MailboxEntry> buyerMailbox = storage.listAllMailbox(buyer).join();
        assertEquals(1, buyerMailbox.size());
        assertEquals(4, buyerMailbox.get(0).quantity());

        OperationResult<PlayerListing> removed =
                storage.removePlayerListing("listing-1", seller).join();
        assertTrue(removed.success());
        assertTrue(storage.getPlayerListing("listing-1").join().isEmpty());
        List<MailboxEntry> sellerMailbox = storage.listAllMailbox(seller).join();
        assertEquals(6, sellerMailbox.stream().mapToInt(MailboxEntry::quantity).sum());
    }

    @Test
    void cancelAfterDelistRestoresToSellerMailbox() {
        UUID seller = UUID.randomUUID();
        UUID buyer = UUID.randomUUID();
        storage.insertPlayerListing(listing("listing-2", seller, 5, "10.00"), 10).join();
        long now = System.currentTimeMillis();

        OperationResult<PlayerReservation> reservation =
                storage.reservePlayerPurchase("listing-2", buyer, 2, "test", now).join();
        assertTrue(reservation.success());
        assertTrue(storage.removePlayerListing("listing-2", seller).join().success());
        storage.cancelPlayerOrder(reservation.value().order().id()).join();

        int sellerItems = storage.listAllMailbox(seller).join().stream()
                .mapToInt(MailboxEntry::quantity).sum();
        assertEquals(5, sellerItems);
    }

    @Test
    void maxListingsEnforced() {
        UUID seller = UUID.randomUUID();
        assertTrue(storage.insertPlayerListing(listing("a", seller, 1, "1.00"), 1).join().success());
        OperationResult<PlayerListing> second =
                storage.insertPlayerListing(listing("b", seller, 1, "1.00"), 1).join();
        assertFalse(second.success());
        assertEquals("error.max-listings", second.messageKey());
    }

    @Test
    void cannotBuyOwnListing() {
        UUID seller = UUID.randomUUID();
        storage.insertPlayerListing(listing("own", seller, 1, "1.00"), 10).join();
        OperationResult<PlayerReservation> result =
                storage.reservePlayerPurchase("own", seller, 1, "test", System.currentTimeMillis()).join();
        assertFalse(result.success());
        assertEquals("error.own-listing", result.messageKey());
    }

    @Test
    void exchangeOrderLifecycle() {
        UUID player = UUID.randomUUID();
        long now = System.currentTimeMillis();
        ExchangeOrder order = new ExchangeOrder(
                UUID.randomUUID().toString(), "mc:test:exchange:1", player,
                "POINT", "CNY", new BigDecimal("100"), new BigDecimal("1.00"),
                ExchangeStatus.PENDING, "", now, now);
        storage.createExchangeOrder(order).join();
        assertEquals(1, storage.listExchangeOrders(10).join().size());

        storage.updateExchangeOrderStatus(order.id(), ExchangeStatus.COMPLETED, "ok").join();
        ExchangeOrder reloaded = storage.listExchangeOrders(10).join().get(0);
        assertEquals(ExchangeStatus.COMPLETED, reloaded.status());
        assertEquals("ok", reloaded.message());
        assertEquals(new BigDecimal("1.00"), reloaded.targetAmount());
    }

    private OfficialItem official(String id,
                                  int stock,
                                  int limit,
                                  String growth,
                                  String cap,
                                  ResetPeriod period) {
        long now = System.currentTimeMillis();
        return new OfficialItem(id, "CNY", new byte[]{1, 2, 3}, new BigDecimal("100.00"),
                stock, limit, new BigDecimal(growth), new BigDecimal(cap), period,
                true, now, now);
    }

    private PlayerListing listing(String id, UUID seller, int quantity, String price) {
        long now = System.currentTimeMillis();
        return new PlayerListing(id, "CNY", seller, "Seller", new byte[]{4, 5, 6},
                new BigDecimal(price), quantity, now, now);
    }
}
