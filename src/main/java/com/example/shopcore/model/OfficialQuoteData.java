package com.example.shopcore.model;

import com.example.shopcore.service.PriceCalculator;

public record OfficialQuoteData(OfficialItem item, PriceCalculator.Quote quote) {
}
