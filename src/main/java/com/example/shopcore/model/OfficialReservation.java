package com.example.shopcore.model;

import com.example.shopcore.service.PriceCalculator;

/** 官方商品下单前成功占住库存的结果。 */
public record OfficialReservation(
        Order order,
        OfficialItem item,
        PriceCalculator.Quote quote
) {
}
