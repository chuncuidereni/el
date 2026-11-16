package com.el2ck.util;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;

/** 配置中的资源 ID：兼容数值、前后空格和前导零；空值、非整数、溢出及非正数都不构成引用。 */
public final class ReferenceId {
    private ReferenceId() { }

    public static long parse(Object value) {
        if (value instanceof JsonNode) {
            JsonNode node = (JsonNode) value;
            value = node.isNumber() ? node.numberValue() : node.isTextual() ? node.textValue() : null;
        }
        try {
            long id = value instanceof Number ? new BigDecimal(value.toString()).longValueExact()
                    : value instanceof String ? Long.parseLong(((String) value).trim()) : 0;
            return Math.max(0, id);
        } catch (NumberFormatException | ArithmeticException ignored) { return 0; }
    }
}
