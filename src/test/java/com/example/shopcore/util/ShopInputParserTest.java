package com.example.shopcore.util;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ShopInputParserTest {

    private static final BigDecimal MIN = new BigDecimal("0.01");
    private static final BigDecimal MAX = new BigDecimal("1000000");

    @Test
    void parsesListingFormats() {
        assertListing("10 100", 10, "100.00");
        assertListing("10,99.5", 10, "99.50");
        assertListing("10x5", 10, "5.00");
        assertListing("10 × 5", 10, "5.00");
        assertListing("  3   12.34  ", 3, "12.34");
    }

    @Test
    void rejectsBadListing() {
        assertThrows(IllegalArgumentException.class,
                () -> ShopInputParser.parseListing("10", 2, MIN, MAX, 100));
        assertThrows(IllegalArgumentException.class,
                () -> ShopInputParser.parseListing("0 100", 2, MIN, MAX, 100));
        assertThrows(IllegalArgumentException.class,
                () -> ShopInputParser.parseListing("10 0", 2, MIN, MAX, 100));
        assertThrows(IllegalArgumentException.class,
                () -> ShopInputParser.parseListing("10 1.234", 2, MIN, MAX, 100));
        assertThrows(IllegalArgumentException.class,
                () -> ShopInputParser.parseListing("101 1", 2, MIN, MAX, 100));
        assertThrows(IllegalArgumentException.class,
                () -> ShopInputParser.parseListing("10 0.001", 2, MIN, MAX, 100));
    }

    @Test
    void parsesQuantityWithSuffix() {
        assertEquals(3, ShopInputParser.parseQuantity("3", 100));
        assertEquals(3, ShopInputParser.parseQuantity("3个", 100));
        assertEquals(3, ShopInputParser.parseQuantity(" 3 件 ", 100));
        assertThrows(IllegalArgumentException.class,
                () -> ShopInputParser.parseQuantity("0", 100));
    }

    @Test
    void parsesAdminNumbers() {
        assertEquals(-1, ShopInputParser.parseStock("-1"));
        assertEquals(0, ShopInputParser.parseStock("0"));
        assertEquals(10, ShopInputParser.parseStock("10"));
        assertThrows(IllegalArgumentException.class, () -> ShopInputParser.parseStock("-2"));
        assertEquals(0, ShopInputParser.parseLimit("0"));
        assertEquals(5, ShopInputParser.parseLimit("5"));
        assertThrows(IllegalArgumentException.class, () -> ShopInputParser.parseLimit("-1"));
    }

    private void assertListing(String text, int quantity, String price) {
        ShopInputParser.ListingInput input =
                ShopInputParser.parseListing(text, 2, MIN, MAX, 100);
        assertEquals(quantity, input.quantity());
        assertEquals(new BigDecimal(price), input.unitPrice());
    }
}
