package com.el2ck.util;

/**
 * 【文件职责】生成和校验分区规则，处理时间/枚举分区表达式及其边界。
 */

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 分区键校验,对应 Go internal/sync/partition.go。
 *
 * <p>为什么要有这一层(真机踩过):把手机号(号码列)设成分区键 →
 * 一个号码一个分区 → 单次 INSERT 块超过 100 个分区时 ClickHouse 直接拒写:
 * {@code code: 252, Too many partitions for single INSERT block (more than 100)}。
 * 而且表已经建出来了 —— MergeTree 的分区键改不了,只能删表重建。
 *
 * <p>本类只在表体检里用到(报 warn)。Go 侧同一函数还用于同步任务保存与建表前的拦截,
 * 那两处尚未迁移,等相应工作包接入时直接复用本类即可。
 */
public final class PartitionRule {

    /** 工具类私有构造器:禁止实例化。 */
    private PartitionRule() {
    }

    /**
     * 分区建议长文案。与 Go 的 partitionAdvice <b>逐字一致</b> ——
     * 它会被拼进体检结论的 detail 直接展示给用户,漂了就是两边说法不同。
     */
    public static final String PARTITION_ADVICE =
            "分区要按日期切片(例如 toYYYYMMDD(ods_insert_at))。"
                    + "分区不是索引 —— 它是为 DROP PARTITION 这类数据操作准备的,单表分区建议不超过 1 万。"
                    + "按号码这种高基数列分区会得到几千上万个分区,单次写入超过 100 个分区时 ClickHouse 会直接拒写(code 252),"
                    + "还会拖慢启动、写入与查询;按号码查得快靠的是排序键/主键,不是分区。"
                    + "⚠️ 用 cityHash64(x)%N、取余、截字符串前几位来「分桶降基数」也不行(别这么试):"
                    + "① 分区剪枝只认「按分区键自己的表达式过滤」—— 实测 EXPLAIN indexes=1,按号码查时 Partitions 总是 9/9(全翻),"
                    + "换成分区键本身才有 1/9;哈希/取模对「按号码查」一点忙帮不上。"
                    + "② 但每个 INSERT 块都会同时碰到全部 N 个分区 → part 数与合并压力 ×N"
                    + "(实测同一批 500 行:hash 分区 64 个 part、磁盘是用日期分区的 9.4 倍),规模一大就撞上 Too many parts 的硬限。"
                    + "③ 分区的本职是 DROP/MOVE PARTITION,哈希之后 DROP 一个分区 = 随机扔掉 1/N 的行,和任何业务含义都不对应。";

    /** 取出表达式里的标识符(函数名也会被取出,但函数名不在列类型表里,自然被忽略)。对应 Go idWordRe。 */
    private static final Pattern ID_WORD_RE = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    /**
     * 分区表达式是否可用(<b>写路径</b>:保存任务 / 建表)。expr 为空表示不分区,合法。
     * 对应 Go ValidatePartitionExpr:字符(partitionExprSafe)+ 形状(partitionExprShape)+ 语义。
     *
     * <p>它会被<b>原样</b>拼进 {@code ) ENGINE = MergeTree PARTITION BY <expr> ORDER BY …},
     * 所以必须先做字符与形状校验,挡住"含日期列的任意片段"(如
     * {@code toYYYYMMDD(ts) SETTINGS index_granularity = 1})改写/破坏建表语句。
     *
     * @return 通过返回 null,否则返回与 Go 一致的错误文案(已附 PARTITION_ADVICE)
     */
    public static String validatePartitionExpr(String expr, String phoneCol, Map<String, String> targetTypes) {
        String e = expr == null ? "" : expr.trim();
        if (e.isEmpty()) {
            return null;
        }
        String safe = partitionExprSafe(e);
        if (safe != null) {
            // 报错一律带上改法(与语义错误一致,用户看到裸报错不知道怎么改)
            return safe + "。" + PARTITION_ADVICE;
        }
        String shape = partitionExprShape(e);
        if (shape != null) {
            return shape + "。" + PARTITION_ADVICE;
        }
        return validatePartitionSemantics(e, phoneCol, targetTypes);
    }

    /**
     * 分区表达式的<b>语义</b>检查:不含号码列、至少引用一个日期/时间列。expr 为空(或纯空白)合法。
     * 对应 Go ValidatePartitionSemantics。<b>体检</b>路径(对已存在的、可能非本平台建的表)用它,
     * 不继承写路径的字符/形状严格度 —— 那些表的 PARTITION BY 是 ClickHouse 原文,合法形态远多于本平台支持的写法。
     */
    public static String validatePartitionSemantics(String expr, String phoneCol, Map<String, String> targetTypes) {
        String e = expr == null ? "" : expr.trim();
        if (e.isEmpty()) {
            return null;
        }
        List<String> toks = identifiersOf(e);
        String pc = phoneCol == null ? "" : phoneCol.trim();
        if (!pc.isEmpty()) {
            for (String t : toks) {
                if (t.equals(pc)) {
                    return "分区键不能是号码列 \"" + pc + "\"。" + PARTITION_ADVICE;
                }
            }
        }
        for (String t : toks) {
            if (isDateTimeType(targetTypes == null ? null : targetTypes.get(t))) {
                return null;
            }
        }
        return "分区键里没有任何日期/时间列。" + PARTITION_ADVICE;
    }

    /**
     * 目标列 → 类型。ODS 贴源表还会自动带 ods_insert_at(DateTime)。对应 Go PartitionTypesOfMapping。
     */
    public static Map<String, String> partitionTypesOfMapping(
            List<com.el2ck.entity.sync.SyncFieldMapping> mapping, boolean ods) {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        if (mapping != null) {
            for (com.el2ck.entity.sync.SyncFieldMapping m : mapping) {
                if (m == null) {
                    continue;
                }
                String name = m.getTargetColumn() == null ? "" : m.getTargetColumn().trim();
                if (name.isEmpty()) {
                    continue;
                }
                out.put(name, m.getType());
            }
        }
        if (ods) {
            out.put("ods_insert_at", "DateTime");
        }
        return out;
    }

    /**
     * 把 ClickHouse 的「分区太多」写入报错(code 252)翻译成人话并附改法;其它报错原样返回。
     * 对应 Go ExplainPartitionInsertError —— 只在命中时改写,不动别的链路。
     *
     * @param msg 底层报错文案(可为 null)
     * @return 命中分区报错则返回带改法的文案,否则原样返回
     */
    public static String explainPartitionInsertError(String msg) {
        if (msg == null) {
            return null;
        }
        if (!msg.contains("Too many partitions") && !msg.contains("Code: 252") && !msg.contains("code: 252")) {
            return msg;
        }
        return msg + "\n【怎么改】" + PARTITION_ADVICE
                + "\n⚠️ 已经建出来的表改不了分区键,要删掉目标表重建(历史数据需要重新同步一次)。";
    }

    /**
     * 分区表达式的字符与形状校验(字符集部分)。对应 Go partitionExprSafe。通过返回 null,否则返回错误文案(不含 PARTITION_ADVICE)。
     *
     * <p>规则与 DdlGuard 的 safeFragment 是<b>同一字符集</b>:括号必须配平;顶层不允许逗号;
     * 单引号字符串整段跳过;禁 {@code ; " ` \ 换行} 以及 {@code --}/{@code /*} 注释符;
     * 允许 字母/数字/_/空格/./()/,/=/- 与括号内的 {@code + % * / : [ ]}(见 partitionExprByte)。
     */
    static String partitionExprSafe(String s) {
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\'') {
                // 字符串字面量整段跳过(里面的括号/逗号/等号是数据)
                i++;
                boolean closed = false;
                while (i < s.length()) {
                    if (s.charAt(i) == '\\') { // 反斜杠转义
                        i += 2;
                        continue;
                    }
                    if (s.charAt(i) == '\'') {
                        // '' 双写表示一个字面单引号
                        if (i + 1 < s.length() && s.charAt(i + 1) == '\'') {
                            i += 2;
                            continue;
                        }
                        closed = true;
                        break;
                    }
                    i++;
                }
                if (!closed) {
                    return "分区表达式里的字符串没有闭合:" + s;
                }
                continue;
            }
            if (ch == '(') {
                depth++;
            } else if (ch == ')') {
                depth--;
                if (depth < 0) {
                    return "分区表达式括号不匹配:" + s;
                }
            } else if (ch == ',') {
                if (depth == 0) {
                    return "分区表达式顶层不允许逗号:" + s;
                }
            } else if (ch == ';' || ch == '"' || ch == '`' || ch == '\\' || ch == '\n' || ch == '\r') {
                return "分区表达式含非法字符 '" + ch + "':" + s;
            } else if (ch == '-' && i + 1 < s.length() && s.charAt(i + 1) == '-') {
                return "分区表达式不允许注释符:" + s;
            } else if (ch == '/' && i + 1 < s.length() && s.charAt(i + 1) == '*') {
                return "分区表达式不允许注释符:" + s;
            } else if (!partitionExprByte(ch)) {
                return "分区表达式含非法字符 '" + ch + "':" + s;
            }
        }
        if (depth != 0) {
            return "分区表达式括号不匹配:" + s;
        }
        return null;
    }

    /** 分区表达式允许的字符。对应 Go partitionExprByte。 */
    static boolean partitionExprByte(char c) {
        if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')) {
            return true;
        }
        // '-' 放行(负数 / ts - INTERVAL 1 DAY);'--' 注释在上面单独挡
        if (c == '_' || c == ' ' || c == '.' || c == '(' || c == ')' || c == ',' || c == '=' || c == '-') {
            return true;
        }
        // 下列字符只在【括号内】的合法表达式里出现,顶层夹带子句由 partitionExprShape 挡:
        // + % * / : [ ] —— 见 Go 注释(toYYYYMMDD(ts + INTERVAL 8 HOUR)/formatDateTime/ts::Date 等)
        return c == '+' || c == '%' || c == '*' || c == '/' || c == ':' || c == '[' || c == ']';
    }

    /**
     * 形状校验:只允许"裸列名"或"函数调用链"。对应 Go partitionExprShape。通过返回 null,否则返回错误文案。
     *
     * <p>光有字符集不够:{@code toYYYYMMDD(ts) SETTINGS a = 1} 能过字符集且以 ) 结尾,
     * 所以必须看结构 —— 标识符之后只能跟若干个完整的括号组,之后再出现任何东西都算夹带子句。
     */
    static String partitionExprShape(String s) {
        int i = skipSpacesAt(s, 0);
        if (i >= s.length()) {
            return "分区表达式为空:" + s;
        }
        if (s.charAt(i) == '(') {
            int end = partitionMatchingParen(s, i);
            if (end < 0 || skipSpacesAt(s, end + 1) != s.length()) {
                return "分区表达式括号之后不应再有内容:" + s;
            }
            return null;
        }
        // 以标识符开头(可带点,如 db.col)
        int j = i;
        while (j < s.length() && (isIdentByte(s.charAt(j)) || s.charAt(j) == '.')) {
            j++;
        }
        if (j == i) {
            return "分区表达式应以列名或函数名开头:" + s;
        }
        // 其后只允许若干个完整的括号组(顶层算式/类型转换有意拒绝,见 Go 注释)
        for (int k = skipSpacesAt(s, j); k < s.length(); k = skipSpacesAt(s, k)) {
            if (s.charAt(k) != '(') {
                return "分区表达式只能是列名或函数调用,顶层不能再接运算符/类型转换;需要算式请套一层函数(如 toYYYYMMDD(ts + INTERVAL 8 HOUR)):" + s;
            }
            int end = partitionMatchingParen(s, k);
            if (end < 0) {
                return "分区表达式括号不匹配:" + s;
            }
            k = end + 1;
        }
        return null;
    }

    /** 跳过空白(空格/制表符),返回首个非空白下标。对应 Go skipSpacesAt。 */
    static int skipSpacesAt(String s, int i) {
        while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == '\t')) {
            i++;
        }
        return i;
    }

    /** 标识符字符。对应 Go isIdentByte。 */
    static boolean isIdentByte(char c) {
        return c == '_' || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
    }

    /**
     * 返回与 s[i]('(')配对的 ')' 下标;找不到返回 -1。对应 Go partitionMatchingParen。
     * 跳过单引号字符串(里面的括号是数据),单引号双写与 \ 转义与 partitionExprSafe 一致。
     */
    static int partitionMatchingParen(String s, int i) {
        int depth = 0;
        for (int j = i; j < s.length(); j++) {
            char c = s.charAt(j);
            if (c == '\'') {
                for (j++; j < s.length(); j++) {
                    if (s.charAt(j) == '\\') {
                        j++;
                        continue;
                    }
                    if (s.charAt(j) == '\'') {
                        if (j + 1 < s.length() && s.charAt(j + 1) == '\'') {
                            j++;
                            continue;
                        }
                        break;
                    }
                }
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return j;
                }
            }
        }
        return -1;
    }

    /** 表达式里出现的全部标识符(保持出现顺序)。对应 Go 的 {@code idWordRe.FindAllString}。 */
    static List<String> identifiersOf(String expr) {
        List<String> out = new ArrayList<>();
        Matcher m = ID_WORD_RE.matcher(expr);
        while (m.find()) {
            out.add(m.group());
        }
        return out;
    }

    /** 这个(已剥掉 Nullable/LowCardinality 的)类型是不是日期/时间。对应 Go isDateTimeType。 */
    public static boolean isDateTimeType(String t) {
        if (t == null) {
            return false;
        }
        String base = unwrapType(t);
        return "Date".equals(base) || "Date32".equals(base) || "DateTime".equals(base)
                || base.startsWith("DateTime(") || base.startsWith("DateTime64(");
    }

    /**
     * 剥掉 Nullable/LowCardinality 外壳(可嵌套,循环剥)。对应 Go ArbUnwrapType。
     *
     * <p>与 Go 一样是<b>大小写敏感</b>的前缀比对:类型文本来自 DESCRIBE,是 ClickHouse 的规范写法。
     */
    public static String unwrapType(String t) {
        String s = t == null ? "" : t.trim();
        for (; ; ) {
            String inner = stripTypeWrapper(s, "Nullable");
            if (inner == null) {
                inner = stripTypeWrapper(s, "LowCardinality");
            }
            if (inner == null) {
                return s;
            }
            s = inner.trim();
        }
    }

    /** 若 t 形如 {@code Name(...)} 则返回括号内内容,否则返回 null。对应 Go stripTypeWrapper。 */
    private static String stripTypeWrapper(String t, String name) {
        String prefix = name + "(";
        if (!t.startsWith(prefix) || !t.endsWith(")")) {
            return null;
        }
        String inner = t.substring(prefix.length(), t.length() - 1);
        // 括号必须配平,否则可能是 FixedString(10) 这种被误判(名字对不上自然返回 false)
        int depth = 0;
        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth < 0) {
                    return null;
                }
            }
        }
        return depth != 0 ? null : inner;
    }
}
