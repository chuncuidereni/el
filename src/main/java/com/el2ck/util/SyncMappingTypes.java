package com.el2ck.util;

/**
 * 【文件职责】判断源字段与目标字段类型兼容性，并集中定义同步映射允许的类型转换边界。
 */

import com.el2ck.entity.common.Column;
import com.el2ck.entity.sync.SyncFieldMapping;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 同步保存与执行共用的结构校验；依赖实际值的普通转换仍由写入阶段校验。
 *
 * <p>这里只做不依赖真实数据「值」的结构性判断：字段名合法性、重复映射、增量键是否被映射、
 * 源/目标类型的静态兼容性。真正需要看每条记录才能判断的转换(如把 {@code "abc"} 解析成整数)
 * 留到写入阶段，以免在没有数据时误报。保存接口与执行入口都调用本类，保证两处结论一致。
 */
public final class SyncMappingTypes {
    private SyncMappingTypes() { }

    /**
     * 校验映射列表的基本结构：至少一条；每条源/目标列名非空、目标列名符合同一标识符规则、
     * 源列与目标列各自不重复；并对目标类型表达式做语法校验。
     */
    public static void validateMappings(List<SyncFieldMapping> mappings) {
        if (mappings == null || mappings.isEmpty()) throw new IllegalArgumentException("至少配置一个字段映射");
        Set<String> sources = new HashSet<>(), targets = new HashSet<>();
        for (SyncFieldMapping mapping : mappings) {
            if (mapping == null) throw new IllegalArgumentException("字段映射不能为空");
            String source = text(mapping.getSourceColumn()), target = text(mapping.getTargetColumn());
            if (source.isEmpty() || target.isEmpty()) throw new IllegalArgumentException("映射的源列与目标列不能为空");
            if (!target.matches("[A-Za-z_][A-Za-z0-9_]*")) throw new IllegalArgumentException("目标列名非法: " + target);
            if (!sources.add(source)) throw new IllegalArgumentException("源字段重复映射: " + source);
            if (!targets.add(target)) throw new IllegalArgumentException("目标列重复映射: " + target);
            if (Boolean.TRUE.equals(mapping.getAutoMdnMd5())
                    && !"String".equalsIgnoreCase(mappingType(mapping)))
                throw new IllegalArgumentException("字段「" + target + "」开启手机号转 MD5 后，映射类型必须为 String");
            String error = syntaxError(mappingType(mapping));
            if (error != null) throw new IllegalArgumentException("字段「" + target + "」: " + error);
        }
    }

    /**
     * 在结构校验之上对照源结构校验：增量键必须是已映射的源字段；每条映射的源字段都要存在于
     * 源结构且类型可识别，并与映射类型兼容(增量键按增量规则从严)。
     */
    public static void validateSource(List<SyncFieldMapping> mappings, List<Column> columns, String incrementalColumn) {
        validateMappings(mappings);
        if (incrementalColumn != null && (incrementalColumn.isEmpty() || mappings.stream()
                .noneMatch(m -> text(m.getSourceColumn()).equals(incrementalColumn))))
            throw new IllegalArgumentException("增量模式必须选择已映射的源字段作为增量键");
        Map<String, String> types = columnTypes(columns);
        for (SyncFieldMapping mapping : mappings) {
            String source = text(mapping.getSourceColumn());
            if (!types.containsKey(source)) throw new IllegalArgumentException("映射源字段不存在: " + source);
            String type = types.get(source);
            if (type.isEmpty()) throw new IllegalArgumentException("源字段「" + source + "」缺少类型，请重新读取源结构");
            requireCompatible(source, type, mappingType(mapping), source.equals(incrementalColumn));
        }
    }

    /**
     * 系统字段可在自动建表开启时补齐，普通字段缺失始终拒绝。
     *
     * <p>目标列必须存在，或属于允许自动补齐的 {@code addableColumns}；存在时其实际类型
     * 须与映射类型兼容(增量键同样按增量规则)。
     */
    public static void validateTarget(List<SyncFieldMapping> mappings, List<Column> columns,
                                      String incrementalColumn, Set<String> addableColumns) {
        Map<String, String> types = columnTypes(columns);
        if (types.isEmpty()) throw new IllegalArgumentException("目标表没有可识别字段，请重新读取目标结构");
        for (SyncFieldMapping mapping : mappings) {
            String target = text(mapping.getTargetColumn());
            if (!types.containsKey(target)) {
                if (addableColumns.contains(target)) continue;
                throw new IllegalArgumentException("目标表缺少映射字段: " + target);
            }
            String type = types.get(target);
            if (Boolean.TRUE.equals(mapping.getAutoMdnMd5())
                    && !"String".equalsIgnoreCase(type.replaceAll("(?i)Nullable\\(|LowCardinality\\(|\\)|\\s", "")))
                throw new IllegalArgumentException("目标字段「" + target + "」必须为 String 才能保存手机号 MD5");
            if (type.isEmpty()) throw new IllegalArgumentException("目标字段「" + target + "」缺少类型，请重新读取目标结构");
            requireCompatible(target + "（实际目标列）", mappingType(mapping), type,
                    text(mapping.getSourceColumn()).equals(incrementalColumn));
        }
    }

    /** 把列清单转成「列名 → 类型文本」的有序映射，跳过空列与空列名。 */
    public static Map<String, String> columnTypes(List<Column> columns) {
        Map<String, String> types = new LinkedHashMap<>();
        if (columns != null) for (Column column : columns)
            if (column != null && !text(column.getName()).isEmpty()) types.put(column.getName(), text(column.getType()));
        return types;
    }

    /** 取映射的目标类型文本；未指定时按 String 处理(与自动建表的默认类型一致)。 */
    private static String mappingType(SyncFieldMapping mapping) {
        String type = text(mapping.getType());
        return type.isEmpty() ? "String" : type;
    }

    /** 调 {@link #error} 判断源→目标是否兼容；不兼容则抛中文异常，指明字段名与两个类型。 */
    private static void requireCompatible(String field, String source, String target, boolean incremental) {
        String reason = error(source, target, incremental);
        if (reason != null) throw new IllegalArgumentException("字段「" + field + "」类型不兼容: "
                + source + " → " + target + "，" + reason);
    }

    /**
     * 判断「源类型 → 目标类型」是否被允许，返回中文原因，兼容则返回 {@code null}。
     *
     * <p>⚠️ 本方法是一串<b>有序</b>规则，靠前命中即返回，<b>不能重排</b>：先做与增量无关的
     * 大类互斥判断(数值↔时间、布尔↔时间、复合类型)，再放行「目标是 String」的万能兜底，
     * 最后才做只有增量键才需要的范围/精度检查。若把增量规则前移，会误伤全量任务；
     * 若把 String 兜底提前，会让本该报错的组合被放过。
     */
    public static String error(String sourceType, String targetType, boolean incremental) {
        // 目标类型本身语法非法时无从比较，直接返回语法错误。
        String syntax = syntaxError(targetType);
        if (syntax != null) return syntax;
        Type source = describe(sourceType), target = describe(targetType);
        // 增量键必须两端都可比较(数值或日期时间)；float16 虽为数值但精度不足，被排除在可比之外。
        if (incremental && (!source.comparable() || !target.comparable())) return "增量键的源和目标必须同为数值或同为日期时间类型";
        // 两大类互斥：数值不能直接当时间、时间也不能当数值，否则水位语义完全不同。
        if ((source.numeric() && target.temporal()) || (source.temporal() && target.numeric())) return "数值与日期时间不能直接转换";
        if ((source.kind.equals("boolean") && target.temporal()) || (source.temporal() && target.kind.equals("boolean"))) return "布尔与日期时间不能直接转换";
        // 增量键即使同为时间类，日期与时间戳也不能互换：两者表示的范围与语义不同，换过去水位会错。
        if (incremental && source.temporal() && target.temporal() && !source.kind.equals(target.kind)) return "增量键不能在日期与时间戳之间转换";
        // 对象/数组只能落到 String 或另一个复合类型，不能压进标量列。
        if (source.kind.equals("complex") && !target.kind.equals("complex") && !target.kind.equals("string")) return "对象或数组请映射为 String 或相容的复合类型";
        if (target.kind.equals("complex") && !source.kind.equals("complex")) return "普通值不能直接转换成数组或对象";
        // String 是万能目标：任何值都能以文本形式存下，到这里直接放行。
        if (target.kind.equals("string")) return null;
        // 布尔源只接受 Bool、UInt8(1 字节即可表示 0/1)或 String。
        if (source.kind.equals("boolean") && !target.kind.equals("boolean")
                && !(target.kind.equals("integer") && target.unsigned && target.bits == 8)) return "布尔源值请映射为 UInt8、Bool 或 String";
        // 以下均为增量键专属的范围/精度规则；非增量任务到此放行。
        if (!incremental) return null;
        // 整数→整数：目标位宽不得小于源；且除了「无符号源落到更大的有符号目标」外，符号变化会改变可达范围，需拒绝。
        if (source.kind.equals("integer") && target.kind.equals("integer")
                && ((source.unsigned && !target.unsigned && target.bits <= source.bits)
                || (!source.unsigned && target.unsigned) || target.bits < source.bits)) return "目标整数范围无法覆盖源增量类型";
        // 整数→浮点：有效位数(float 24 位、double 53 位)须不小于源整数的有效位，否则大整数水位会被舍入。
        if (source.kind.equals("integer") && target.kind.equals("float")
                && source.bits - (source.unsigned ? 0 : 1) > (target.bits == 32 ? 24 : 53)) return "浮点类型无法保证保留大整数水位精度";
        // 整数→Decimal：整数位按 log10(2) 换算源整数所需十进制位，目标 Decimal 的整数位(precision-scale)必须够。
        if (source.kind.equals("integer") && target.kind.equals("decimal")
                && target.precision - target.scale < Math.ceil((source.bits - (source.unsigned ? 0 : 1)) * Math.log10(2))) return "目标 Decimal 的整数位不足";
        // Decimal→Decimal：小数位与整数位都不能缩水，否则水位被四舍五入/截断。
        if (source.kind.equals("decimal") && target.kind.equals("decimal") && source.precision > 0
                && (target.scale < source.scale || target.precision - target.scale < source.precision - source.scale)) return "目标 Decimal 的小数位或整数位不足";
        if ((source.kind.equals("decimal") || source.kind.equals("float")) && target.kind.equals("integer")) return "小数增量键不能转换为整数";
        // 浮点→浮点：精度不得下降。
        if (source.kind.equals("float") && target.kind.equals("float") && target.bits < source.bits) return "目标浮点精度不足";
        if ((source.kind.equals("decimal") && target.kind.equals("float")) || (source.kind.equals("float") && target.kind.equals("decimal"))) return "浮点与 Decimal 转换无法保证水位精度";
        // 时间戳→时间戳：小数秒精度不得下降。
        if (source.kind.equals("datetime") && target.kind.equals("datetime") && target.scale < source.scale) return "目标时间戳小数秒精度不足";
        if (source.kind.equals("date") && target.kind.equals("date") && target.bits < source.bits) return "目标日期范围小于源增量类型";
        return null;
    }

    /**
     * 校验类型表达式语法，合法返回 {@code null}，否则返回中文错误。
     *
     * <p>先做整体形态与括号/引号/注释扫描：类型文本必须是「名称(可选参数)」的形式；扫描时跟踪括号
     * 深度与引号状态，遇到 {@code ;}、{@code --}、{@code /*} 视为试图注入 SQL 而拒绝。再剥掉
     * {@code Nullable(...)} 等包装递归校验内层，最后按已知标量类型逐项核对参数：Int 位宽必须合法、
     * DateTime 只接受时区、DateTime64 精度 0–9、Decimal 精度 1–76 且小数位不超总精度、
     * FixedString 必须为正整数长度等。这样非法类型文本会在保存/执行前就被拦下。
     */
    public static String syntaxError(String value) {
        String raw = text(value);
        if (!raw.matches("[A-Za-z][A-Za-z0-9_]*(?:\\s*\\([\\s\\S]*\\))?")) return "字段类型格式不正确，请检查名称和括号";
        int depth = 0; char quote = 0;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i), next = i + 1 < raw.length() ? raw.charAt(i + 1) : 0;
            if (quote != 0) {
                if (c == '\\') i++;
                else if (c == quote && next == quote) i++;
                else if (c == quote) quote = 0;
            } else if (c == '\'' || c == '"' || c == '`') quote = c;
            else if (c == '(') depth++;
            else if (c == ')' && --depth < 0) return "字段类型括号不匹配";
            else if (c == ';' || (c == '-' && next == '-') || (c == '/' && next == '*')) return "字段类型不能包含 SQL 语句或注释";
        }
        if (depth != 0 || quote != 0) return "字段类型的括号或引号未闭合";
        String t = SyncTime.baseType(raw);
        if (!t.equals(raw.toLowerCase(Locale.ROOT))) return syntaxError(t);
        if (t.matches("(u?int[0-9]+|float[0-9]+|bfloat16|bool|boolean|date|date32|string)\\(.*")) return "该标量类型不接受参数";
        if (t.matches("u?int[0-9]+") && !t.matches("u?int(8|16|32|64|128|256)")) return "整数位宽必须为 8、16、32、64、128 或 256";
        if (t.startsWith("datetime(")) {
            if (!t.matches("datetime\\(\\s*'[^']*'\\s*\\)")) return "DateTime 只接受时区；小数秒精度请使用 DateTime64";
        }
        if (t.startsWith("datetime64")) {
            if (!t.matches("datetime64(?:\\(\\s*[0-9]+\\s*(?:,\\s*'[^']*'\\s*)?\\))?")) return "DateTime64 需填写精度，可选时区";
            Matcher scale = Pattern.compile("datetime64\\(\\s*([0-9]+)").matcher(t);
            if (scale.find() && number(scale.group(1)) > 9) return "DateTime64 精度必须在 0–9 之间";
        }
        if (t.startsWith("decimal")) {
            Matcher decimal = Pattern.compile("decimal\\(\\s*([0-9]+)\\s*,\\s*([0-9]+)\\s*\\)").matcher(t);
            Matcher storage = Pattern.compile("decimal(32|64|128|256)\\(\\s*([0-9]+)\\s*\\)").matcher(t);
            int precision, scale;
            if (decimal.matches()) { precision = number(decimal.group(1)); scale = number(decimal.group(2)); }
            else if (storage.matches()) { precision = decimalPrecision(number(storage.group(1))); scale = number(storage.group(2)); }
            else return "Decimal 需填写总精度和小数位，例如 Decimal(18, 6)";
            if (precision < 1 || precision > 76 || scale > precision) return "Decimal 精度必须在 1–76 之间，小数位不能超过总精度";
        }
        if (t.startsWith("fixedstring") && !t.matches("fixedstring\\(\\s*[1-9][0-9]*\\s*\\)")) return "FixedString 需填写正整数长度";
        return null;
    }

    /** 归一化后的类型描述：kind 为 integer/float/decimal/date/datetime/boolean/string/complex/other。 */
    private static final class Type {
        String kind; int bits, precision, scale; boolean unsigned;
        Type(String kind) { this.kind = kind; }
        /** 是否为数值类(整数/浮点/Decimal)。 */
        boolean numeric() { return kind.equals("integer") || kind.equals("float") || kind.equals("decimal"); }
        /** 是否为时间类(日期/时间戳)。 */
        boolean temporal() { return kind.equals("date") || kind.equals("datetime"); }
        /** 能否作为增量键比较：数值(除精度不足的 float16)或时间。 */
        boolean comparable() { return (numeric() && !(kind.equals("float") && bits == 16)) || temporal(); }
    }
    /**
     * 把各种跨库类型别名归并成内部统一的类型描述({@link Type})，供 {@link #error} 做规则比较。
     *
     * <p>源可能来自 MySQL/PG 等，类型名五花八门，这里逐一归类：
     * array/map/tuple/nested/object/json → complex；bool/boolean/bit(1) → boolean；
     * string/fixedstring/varchar/char/text/clob → string；
     * 标准 {@code (u?)intN} 直接取位宽与符号；
     * 含 int/serial/year 的方言名(如 bigint、hugeint、smallint、tinyint、serial、year)按关键字推位宽，
     * 命中不了的变体默认 32 位；float/double/real/bfloat16 → float(其中 real 视为 32 位)；
     * decimal/numeric 解析精度与小数位；date/date32 → date(位宽 16/32)；
     * 时间戳按类型名或显式精度定小数秒精度(见 {@link SyncTime})。
     */
    private static Type describe(String value) {
        String t = SyncTime.baseType(value);
        if (t.matches("(array|map|tuple|nested|object|json)(\\(.*|$)")) return new Type("complex");
        if (t.matches("bool|boolean|bit\\(1\\)")) return new Type("boolean");
        if (t.matches("(string|fixedstring|varchar|char|text|clob).*")) return new Type("string");
        Matcher integer = Pattern.compile("(u?)int(8|16|32|64|128|256)").matcher(t);
        Type result = new Type("other");
        if (integer.matches()) { result.kind = "integer"; result.bits = number(integer.group(2)); result.unsigned = !integer.group(1).isEmpty(); return result; }
        if (t.matches(".*(int|serial|year).*")) {
            result.kind = "integer"; result.unsigned = t.contains("unsigned") || t.startsWith("u");
            result.bits = t.contains("hugeint") ? 128 : t.contains("bigint") || t.contains("bigserial") ? 64 : t.contains("smallint") ? 16 : t.contains("tinyint") ? 8 : 32;
            return result;
        }
        if (t.matches("bfloat16|float.*|double.*|real")) {
            result.kind = "float"; result.bits = t.equals("bfloat16") ? 16 : t.equals("float32") || t.equals("real") ? 32 : 64; return result;
        }
        if (t.startsWith("decimal") || t.startsWith("numeric")) {
            result.kind = "decimal";
            Matcher m = Pattern.compile("(?:decimal|numeric)\\(\\s*([0-9]+)\\s*,\\s*([0-9]+)\\s*\\)").matcher(t);
            Matcher storage = Pattern.compile("decimal(32|64|128|256)\\(\\s*([0-9]+)\\s*\\)").matcher(t);
            if (m.matches()) { result.precision = number(m.group(1)); result.scale = number(m.group(2)); }
            else if (storage.matches()) { result.precision = decimalPrecision(number(storage.group(1))); result.scale = number(storage.group(2)); }
            return result;
        }
        if (t.equals("date") || t.equals("date32")) { result.kind = "date"; result.bits = t.equals("date32") ? 32 : 16; return result; }
        if (SyncTime.isTimestamp(t)) {
            result.kind = "datetime";
            Matcher scale = Pattern.compile("\\(\\s*([0-9]+)").matcher(t);
            result.scale = scale.find() ? number(scale.group(1)) : t.equals("timestamp_ns") ? 9 : t.equals("timestamp_ms") || t.equals("datetime64") ? 3 : t.equals("timestamp_s") || t.startsWith("datetime") ? 0 : 6;
        }
        return result;
    }
    /** 解析整数；上溢等异常时返回 {@link Integer#MAX_VALUE}，让随后的范围判断自然判为不兼容。 */
    private static int number(String value) { try { return Integer.parseInt(value); } catch (NumberFormatException e) { return Integer.MAX_VALUE; } }
    /** Decimal 存储位宽换算有效十进制位：32→9、64→18、128→38、256→76。 */
    private static int decimalPrecision(int bits) { return bits == 32 ? 9 : bits == 64 ? 18 : bits == 128 ? 38 : 76; }
    /** 空安全的 trim。 */
    private static String text(String value) { return value == null ? "" : value.trim(); }
}
