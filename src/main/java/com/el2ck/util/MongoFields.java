package com.el2ck.util;

/**
 * 【文件职责】解析 MongoDB 字段路径和投影规则，为文档扁平化及映射提供支持。
 */

import com.el2ck.entity.common.Column;
import org.bson.types.Decimal128;
import org.bson.types.ObjectId;
import java.math.BigDecimal;
import java.util.*;

/**
 * 字段探测与同步读取共用的 MongoDB 字段路径、类型及 JSON 值规则。
 *
 * <p>MongoDB 无固定模式，这里负责把 BSON 文档映射到强类型的 ClickHouse 列：探测阶段扫描小样本，
 * 按点分路径推断每个字段的 BSON 类型；读取阶段用同样的路径取值并按规则转成文本或 JSON。
 * 类型推断只依据样本，因此同一路径出现多种类型时按 {@link #merge} 取「能容纳所有已见值」的结果。
 */
public final class MongoFields {
    private MongoFields() { }

    /** 取库名：优先用配置项 {@code database}，为空时回退到连接串里的库名；两者都没有返回空串。 */
    public static String database(Map<String, Object> config) {
        Object configured = config.get("database");
        String database = configured == null ? "" : configured.toString().trim();
        if (!database.isEmpty()) return database;
        String fromUri = new com.mongodb.ConnectionString(RemoteSourceFile.mongoUri(config)).getDatabase();
        return fromUri == null ? "" : fromUri;
    }

    /**
     * 从一批样本文档推断字段清单：递归展开嵌套对象为点分路径，同名字段按 {@link #merge} 合并类型。
     * 样本里从未出现过的字段不会出现在结果中——无模式库只能靠样本探测。
     */
    public static List<Column> columns(Iterable<? extends Map<String, Object>> documents) {
        Map<String, String> types = new LinkedHashMap<>();
        for (Map<String, Object> document : documents) collect(document, "", types);
        List<Column> columns = new ArrayList<>();
        for (Map.Entry<String, String> entry : types.entrySet()) {
            Column column = new Column(); column.setName(entry.getKey());
            column.setType(entry.getValue().isEmpty() ? "String" : entry.getValue());
            columns.add(column);
        }
        return columns;
    }

    /**
     * 递归收集单个文档的字段路径与类型，{@code prefix} 是外层嵌套已累积的点分前缀。
     *
     * <p>跳过不能作为点分路径的字段名(含点、以 {@code $} 开头、含 NUL 字符)：这些字面键在本系统的
     * {@code a.b.c} 查询语法里会被误解成层级分隔，无法安全表达。嵌套 Map 继续下钻，
     * 数组等其它值只记录本层类型，不展开内部结构。
     */
    private static void collect(Map<String, Object> document, String prefix, Map<String, String> types) {
        for (Map.Entry<String, Object> entry : document.entrySet()) {
            String key = entry.getKey();
            // BSON 字面字段名含点/美元符号时不能作为本系统的点分查询路径。
            if (key.isEmpty() || key.contains(".") || key.startsWith("$") || key.indexOf('\0') >= 0) continue;
            String path = prefix + key;
            String detected = entry.getValue() == null ? "" : type(entry.getValue());
            types.put(path, merge(types.get(path), detected));
            if (entry.getValue() instanceof Map)
                collect((Map<String, Object>) entry.getValue(), path + ".", types);
        }
    }

    /**
     * 由单个 BSON 值的运行时类型推断目标列类型。
     *
     * <p>映射：Date→DateTime64(3)(MongoDB 的 Date 只到毫秒)；Decimal128/BigDecimal→Decimal；
     * 浮点→Float64；其它数值→Int64；布尔→BOOLEAN；其余(字符串、ObjectId、数组、对象等)→String。
     * ObjectId 这类没有原生列的类型统一落到 String，读取时再序列化成文本。
     */
    public static String type(Object value) {
        if (value instanceof Date) return "DateTime64(3)";
        if (value instanceof Decimal128) {
            Decimal128 decimal = (Decimal128) value;
            return decimal.isFinite() ? decimalType(decimal.bigDecimalValue()) : "String";
        }
        if (value instanceof BigDecimal) return decimalType((BigDecimal) value);
        if (value instanceof Float || value instanceof Double) return "Float64";
        if (value instanceof Number) return "Int64";
        if (value instanceof Boolean) return "BOOLEAN";
        return "String";
    }

    /**
     * 由 BigDecimal 的实际精度/小数位选择 Decimal 存储宽度。
     *
     * <p>ClickHouse 的宽 Decimal 只有 128 位(38 位有效数字)和 256 位(76 位)两档，
     * 因此所需位数(整数位+小数位)不超过 38 就用 38，否则用 76；超过 76 位仍装不下时降级为
     * String，宁可当文本存下也不冒写入溢出丢精度的风险。
     */
    private static String decimalType(BigDecimal value) {
        int scale = Math.max(0, value.scale());
        int digits = Math.max(1, value.precision() - value.scale()) + scale;
        if (digits > 76) return "String";
        return "Decimal(" + (digits <= 38 ? 38 : 76) + ", " + scale + ")";
    }

    /**
     * 合并同一路径上先后探测到的两个类型，得到能容纳两者的结果。
     *
     * <p>优先级：一方为空取另一方；两者相同直接返回；不同且都是数值时——有 Float64 则取 Float64
     * (浮点近似覆盖整数与 Decimal 的范围)；否则若含 Decimal，取两者最大的小数位与整数位重新拼成
     * Decimal，整数位+小数位超过 76 位仍不可表示则降为 String；其余任何跨大类(数值/字符串/布尔)
     * 的冲突一律降为 String——无法保证两种值都能无损放进同一列。
     */
    private static String merge(String left, String right) {
        if (left == null || left.isEmpty()) return right;
        if (right.isEmpty() || left.equals(right)) return left;
        if (numeric(left) && numeric(right)) {
            if (left.equals("Float64") || right.equals("Float64")) return "Float64";
            if (left.startsWith("Decimal") || right.startsWith("Decimal")) {
                int scale = Math.max(decimalScale(left), decimalScale(right));
                int integers = Math.max(integerDigits(left), integerDigits(right));
                return integers + scale > 76 ? "String" : "Decimal(" + (integers + scale <= 38 ? 38 : 76) + ", " + scale + ")";
            }
        }
        return "String";
    }

    /** 是否为数值类类型(整数 Int64 / 浮点 Float64 / Decimal)。 */
    private static boolean numeric(String type) { return type.equals("Int64") || type.equals("Float64") || type.startsWith("Decimal"); }
    /** 从 {@code Decimal(p, s)} 文本里取出小数位 s；非 Decimal 视为 0。 */
    private static int decimalScale(String type) {
        return type.startsWith("Decimal") ? Integer.parseInt(type.substring(type.indexOf(',') + 1, type.length() - 1).trim()) : 0;
    }
    /** 从 {@code Decimal(p, s)} 里取出整数位 p-s；非 Decimal(如 Int64)按 19 位十进制计。 */
    private static int integerDigits(String type) {
        return type.startsWith("Decimal") ? Integer.parseInt(type.substring(type.indexOf('(') + 1, type.indexOf(',')).trim()) - decimalScale(type) : 19;
    }

    /**
     * 按点分路径取值：逐段进入 Map；中途遇到非 Map(值为 null 或已是叶子)则返回 null。
     * 「路径访问不到」与「字段值本身为 null」都返回 null，调用方据此统一按空值处理。
     */
    public static Object field(Map<?, ?> document, String path) {
        Object value = document;
        for (String segment : path.split("\\.")) {
            if (!(value instanceof Map)) return null;
            value = ((Map<?, ?>) value).get(segment);
        }
        return value;
    }

    /**
     * 校验点分路径合法：非空、不含 NUL，且每一段都非空、不以 {@code $} 开头。
     * {@code split("\\.", -1)} 保留末尾空段，才能拦住 {@code "a."} 这类以点结尾的路径。
     */
    public static void validatePath(String path) {
        if (path == null || path.isEmpty() || path.indexOf('\0') >= 0) throw new IllegalArgumentException("MongoDB 字段路径不能为空");
        for (String segment : path.split("\\.", -1))
            if (segment.isEmpty() || segment.startsWith("$")) throw new IllegalArgumentException("MongoDB 字段路径非法: " + path);
    }

    /**
     * 同时选择 profile 和 profile.name 时只投影父字段，返回值仍可按两个映射取出。
     *
     * <p>规则是「若某路径的父路径也在列表中，则丢弃子路径、只保留父路径」：MongoDB 的投影里
     * 同时列出父字段与其子字段时，服务端也只按父字段返回整个子文档，重复列出子路径没有意义。
     * 投影父字段即可，读取时仍能按 {@code profile.name} 从返回的嵌套文档里取到值。
     * 用 LinkedHashSet 去重并保持首次出现顺序。
     */
    public static List<String> projectionPaths(List<String> paths) {
        List<String> result = new ArrayList<>();
        for (String path : new LinkedHashSet<>(paths)) {
            validatePath(path);
            if (paths.stream().noneMatch(parent -> !path.equals(parent) && path.startsWith(parent + "."))) result.add(path);
        }
        return result;
    }

    /**
     * 把 BSON 值递归转成可被 Jackson 序列化的 JSON 结构(对象/数组逐层展开)。
     *
     * <p>约定：ObjectId 转 24 位十六进制串；Decimal128 转其十进制文本(直接当 double 会丢精度)；
     * Date 转 ISO-8601 的 Instant 文本；Map/List 递归转换；字符串/数值/布尔/null 原样保留；
     * 其余无法直接表达的类型退化为 {@code toString()}。用于把对象/数组字段整体以 JSON 文本写入 String 列。
     */
    public static Object jsonValue(Object value) {
        if (value instanceof ObjectId) return ((ObjectId) value).toHexString();
        if (value instanceof Decimal128) return value.toString();
        if (value instanceof Date) return ((Date) value).toInstant().toString();
        if (value instanceof Map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) result.put(String.valueOf(entry.getKey()), jsonValue(entry.getValue()));
            return result;
        }
        if (value instanceof List) {
            List<Object> result = new ArrayList<>();
            for (Object item : (List<?>) value) result.add(jsonValue(item));
            return result;
        }
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) return value;
        return value.toString();
    }
}
