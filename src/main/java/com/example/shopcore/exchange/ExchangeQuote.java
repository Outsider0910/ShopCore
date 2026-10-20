package com.example.shopcore.exchange;

import java.math.BigDecimal;

public record ExchangeQuote(ExchangePair pair, BigDecimal sourceAmount, BigDecimal targetAmount) {
}
