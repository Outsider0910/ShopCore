package com.example.shopcore.model;

import java.util.List;

public record PageResult<T>(List<T> items, int total, int page, int pageSize) {

    public PageResult {
        items = items == null ? List.of() : List.copyOf(items);
        page = Math.max(0, page);
        pageSize = Math.max(1, pageSize);
    }

    public int pages() {
        return Math.max(1, (int) Math.ceil(total / (double) pageSize));
    }

    public boolean hasPrevious() {
        return page > 0;
    }

    public boolean hasNext() {
        return page + 1 < pages();
    }
}
