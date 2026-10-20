package com.example.shopcore.model;

import java.util.Collections;
import java.util.Map;

/** 服务层统一结果：成功时带返回值，失败时带可发送的文案键。 */
public record OperationResult<T>(
        boolean success,
        T value,
        String messageKey,
        String fallback,
        Map<String, String> placeholders
) {

    public OperationResult {
        placeholders = placeholders == null ? Collections.emptyMap() : Map.copyOf(placeholders);
    }

    public static <T> OperationResult<T> ok(T value) {
        return new OperationResult<>(true, value, null, null, Collections.emptyMap());
    }

    public static <T> OperationResult<T> ok(T value,
                                            String messageKey,
                                            Map<String, String> placeholders,
                                            String fallback) {
        return new OperationResult<>(true, value, messageKey, fallback, placeholders);
    }

    public static <T> OperationResult<T> fail(String messageKey, String fallback) {
        return fail(messageKey, Collections.emptyMap(), fallback);
    }

    public static <T> OperationResult<T> fail(String messageKey,
                                              Map<String, String> placeholders,
                                              String fallback) {
        return new OperationResult<>(false, null, messageKey, fallback, placeholders);
    }
}
