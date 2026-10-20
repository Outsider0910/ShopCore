package com.example.shopcore.model;

import java.math.BigDecimal;

public record PlayerQuoteData(PlayerListing listing, int quantity, BigDecimal totalPrice) {
}
