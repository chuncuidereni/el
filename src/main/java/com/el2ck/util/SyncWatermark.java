package com.el2ck.util;

/**
 * 【文件职责】表示和比较增量水位值，处理精确格式、复合边界和稳定排序语义。
 */

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 增量水位写入后必须能原样读回，不能因类型收窄而移动边界。
 *
 * <p>增量同步依赖目标表的 {@code max(增量列)} 作为下一轮起点：写入时一旦发生截断、舍入或溢出，
 * 读回的边界就会偏离真实值——向下漂移会重复读行，向上漂移会永久漏掉数据。所以本类在提交水位前，
 * 用「按目标类型模拟存储、再比较能否还原出同一个数」的方式做无损校验：能无损保存才放行，
 * 否则抛异常让调用方拒绝提交，而不是截断后照写。
 *
 * <p>支持的类型：整数、Decimal(含 decimal32/64/128/256 存储形式)、float32/float64、
 * 以及 date/date32 与各类时间戳。
 */
public final class SyncWatermark {
    private SyncWatermark() { }
    private static final Pattern DECIMAL = Pattern.compile("decimal\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*\\)");
    private static final Pattern STORAGE_DECIMAL = Pattern.compile("decimal(32|64|128|256)\\(\\s*(\\d+)\\s*\\)");
    private static final Pattern INTEGER = Pattern.compile("(u?)int(8|16|32|64|128|256)");

    /** 空源或最大水位回退不能等价于“无新增”；不改写旧断点，交由用户核对源表重建/清空。 */
    public static void requireNotRegressed(String current, String confirmed, String type) {
        if (confirmed == null) return;
        boolean empty = current == null || current.trim().isEmpty() || "null".equalsIgnoreCase(current.trim());
        if (empty || compare(current, confirmed, type) < 0)
            throw new IllegalStateException("源表水位发生回退或源表已清空，可能已重建；断点已保留，请核对源数据后继续，不能按无新增完成");
    }

    private static int compare(String left, String right, String type) {
        if (SyncTime.isTimestamp(type) || SyncTime.baseType(type).matches("date|date32"))
            return SyncTime.parse(left).compareTo(SyncTime.parse(right));
        return new BigDecimal(left).compareTo(new BigDecimal(right));
    }

    /**
     * 校验增量水位值能被 {@code targetType} 无损保存；空值/空串/"null" 视为无水位直接放行。
     *
     * <p>分支约定：
     * <ul>
     *   <li>时间戳：委托 {@link SyncTime#requireExact} 检查小数秒精度；</li>
     *   <li>date/date32：必须恰为零点，否则日期类型装不下时刻；其中 16 位的 date 还要落在
     *       {@code 1970-01-01}~{@code 2149-06-06} 之间——这是 ClickHouse Date 用无符号 16 位
     *       存「自 1970-01-01 起的天数」所能表示的范围(约 6.5 万天);</li>
     *   <li>decimal：按声明的总精度/小数位用 {@link RoundingMode#UNNECESSARY} 设定位数——
     *       只要需要舍入就非法；{@code decimal32/64/128/256} 存储形式先按位宽换算成 9/18/38/76 位精度再判；</li>
     *   <li>整数：要求能精确转为大整数，并按位宽与有无符号取半开区间
     *       {@code [0, 1<<bits)} 或 {@code [-(1<<(bits-1)), 1<<(bits-1))};</li>
     *   <li>float32/float64：要求有限，再按 IEEE 往返比较——解析成浮点后转回十进制若与原文不等，
     *       说明该十进制小数在目标浮点里存不下，会把水位挪动。</li>
     * </ul>
     * 任一步失败，都统一以「无法无损保存」的异常抛出，由调用方拒绝提交。
     */
    public static void requireExact(String value, String targetType) {
        if (value == null || value.trim().isEmpty() || value.trim().equalsIgnoreCase("null")) return;
        if (SyncTime.isTimestamp(targetType)) { SyncTime.requireExact(value, targetType); return; }
        String type = SyncTime.baseType(targetType);
        try {
            if (type.equals("date") || type.equals("date32")) {
                java.time.LocalDateTime dateTime = SyncTime.parse(value).toLocalDateTime();
                if (!dateTime.toLocalTime().equals(java.time.LocalTime.MIDNIGHT))
                    throw new ArithmeticException("日期类型无法保留时刻");
                java.time.LocalDate date = dateTime.toLocalDate();
                if (type.equals("date") && (date.isBefore(java.time.LocalDate.of(1970, 1, 1))
                        || date.isAfter(java.time.LocalDate.of(2149, 6, 6))))
                    throw new ArithmeticException("超出 Date 范围");
                return;
            }
            BigDecimal number = new BigDecimal(value.trim());
            Matcher decimal = DECIMAL.matcher(type), storage = STORAGE_DECIMAL.matcher(type), integer = INTEGER.matcher(type);
            if (decimal.matches() || storage.matches()) {
                int precision, scale;
                if (decimal.matches()) { precision = Integer.parseInt(decimal.group(1)); scale = Integer.parseInt(decimal.group(2)); }
                else {
                    int bits = Integer.parseInt(storage.group(1));
                    precision = bits == 32 ? 9 : bits == 64 ? 18 : bits == 128 ? 38 : 76;
                    scale = Integer.parseInt(storage.group(2));
                }
                BigDecimal exact = number.setScale(scale, RoundingMode.UNNECESSARY);
                if (exact.precision() > precision) throw new ArithmeticException("超出 Decimal 精度");
            } else if (integer.matches()) {
                BigInteger exact = number.toBigIntegerExact();
                int bits = Integer.parseInt(integer.group(2));
                boolean unsigned = !integer.group(1).isEmpty();
                BigInteger upper = BigInteger.ONE.shiftLeft(unsigned ? bits : bits - 1);
                BigInteger lower = unsigned ? BigInteger.ZERO : upper.negate();
                if (exact.compareTo(lower) < 0 || exact.compareTo(upper) >= 0) throw new ArithmeticException("超出整数范围");
            } else if (type.equals("float32") || type.equals("float64")) {
                BigDecimal roundTrip;
                if (type.equals("float32")) {
                    float floating = Float.parseFloat(value.trim());
                    if (!Float.isFinite(floating)) throw new ArithmeticException("浮点溢出");
                    roundTrip = new BigDecimal(Float.toString(floating));
                } else {
                    double floating = Double.parseDouble(value.trim());
                    if (!Double.isFinite(floating)) throw new ArithmeticException("浮点溢出");
                    roundTrip = BigDecimal.valueOf(floating);
                }
                if (roundTrip.compareTo(number) != 0) throw new ArithmeticException("浮点精度不足");
            }
        } catch (RuntimeException failure) {
            throw new IllegalStateException("增量字段值无法由目标类型 " + targetType + " 无损保存，请调整目标类型或精度", failure);
        }
    }
}
