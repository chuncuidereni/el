package com.el2ck.util;

/**
 * 【文件职责】解析、格式化和截断同步时间值，使写入 ClickHouse DateTime/DateTime64 的精度与目标列一致。
 */

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;

/**
 * 同步链路的时间文本：保留小数秒；带偏移的输入转换为本机时区。
 *
 * <p>同步在「源读数 → 水位比较 → 写 ClickHouse」之间传递的都是字符串形式的时间文本，
 * 本类统一两侧的解析与格式化，保证同一个时刻不会被源和目标解析成不同的墙上时间。
 * 约定：不带时区的时间按本机时区理解，带偏移的按偏移换算到本机时区，小数秒保留到纳秒。
 */
public final class SyncTime {
    private SyncTime() { }
    private static final DateTimeFormatter FORMAT = new DateTimeFormatterBuilder()
            .appendPattern("yyyy-MM-dd HH:mm:ss")
            .appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true).toFormatter();

    /** 把本地日期时间格式化为 {@code yyyy-MM-dd HH:mm:ss[.fraction]} 文本，小数秒按需保留(最多 9 位)。 */
    public static String format(LocalDateTime value) { return FORMAT.format(value); }

    /**
     * 解析时间文本为 JDBC {@link Timestamp}(本机时区)。
     *
     * <p>兼容源端常见的几种写法。纯日期只有 10 个字符，补 {@code T00:00:00} 当作当天零点；
     * 空格分隔的 {@code 2024-01-01 12:00:00} 换成 ISO 的 {@code T}。
     * 先按带偏移的 {@link OffsetDateTime} 解析——这样 {@code Z}/{@code +08:00} 能被识别并
     * 换算到本机时区，不会因为丢掉偏移而让时刻漂移；解析失败再回退按无时区的
     * {@link LocalDateTime} 处理。
     */
    public static Timestamp parse(String value) {
        String text = value.trim();
        if (text.length() == 10) text += "T00:00:00";
        text = text.replace(' ', 'T');
        LocalDateTime local;
        try { local = OffsetDateTime.parse(text).atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime(); }
        catch (java.time.format.DateTimeParseException e) { local = LocalDateTime.parse(text); }
        return Timestamp.valueOf(local);
    }

    /**
     * 剥掉外层包装返回最内层的基础类型文本(小写)。
     *
     * <p>源结构里常见 {@code Nullable(String)}、{@code LowCardinality(Nullable(DateTime64(3)))} 这类
     * 层层包裹，必须循环拆掉才能比较真实类型；只处理能配对到最外层右括号的写法，避免误伤普通类型。
     */
    public static String baseType(String type) {
        String t = type == null ? "" : type.trim().toLowerCase(java.util.Locale.ROOT);
        while ((t.startsWith("nullable(") || t.startsWith("lowcardinality(")) && t.endsWith(")"))
            t = t.substring(t.indexOf('(') + 1, t.length() - 1).trim();
        return t;
    }

    /** 判断是否为时间戳类型(DateTime/DateTime64，或 timestamp_s/ms/ns 等各类 timestamp 别名)。 */
    public static boolean isTimestamp(String type) {
        return baseType(type).matches("datetime(64)?(\\(.*\\))?|timestamp.*");
    }

    /**
     * 取时间戳的小数秒精度(0–9)。带精度的 {@code DateTime64(n)} 返回 n；
     * 裸 {@code datetime64} 没有显式精度，按 ClickHouse 的默认精度 3 处理；其余(含普通 DateTime)为 0。
     */
    public static int scale(String type) {
        String t = baseType(type);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("datetime64\\(\\s*([0-9]+)").matcher(t);
        if (m.find()) {
            int scale = Integer.parseInt(m.group(1));
            if (scale > 9) throw new IllegalArgumentException("DateTime64 精度必须在 0 到 9 之间");
            return scale;
        }
        return "datetime64".equals(t) ? 3 : 0;
    }

    /** 按目标列精度生成写入值；增量水位须在调用前单独通过 requireExact 校验。 */
    public static Timestamp forTarget(Timestamp value, String targetType) {
        int factor = (int) Math.pow(10, 9 - scale(targetType));
        LocalDateTime local = value.toLocalDateTime();
        return Timestamp.valueOf(local.withNano(local.getNano() / factor * factor));
    }

    /**
     * 增量键不得在写入后变小，否则下一次的 max 水位会重复读边界行。
     *
     * <p>做法是把值的纳秒部分对 {@code 10^(9-scale)} 取模：目标精度每低一位，就多要求一位
     * 纳秒能被整除。不能整除，即意味着写入目标类型时小数秒会被截断、读回的边界偏离原值，
     * 因此这里直接拒绝(而非截断)。空值/空串/"null" 表示没有水位，直接放行。
     */
    public static void requireExact(String value, String targetType) {
        if (value == null || value.trim().isEmpty() || "null".equalsIgnoreCase(value.trim())) return;
        int nanos = parse(value).getNanos();
        int factor = (int) Math.pow(10, 9 - scale(targetType));
        if (nanos % factor != 0)
            throw new IllegalStateException("增量时间字段包含目标类型 " + targetType + " 无法保留的小数秒，请提高目标精度");
    }
}
