package com.el2ck.util;

/**
 * 【文件职责】提供 SQL 标识符引用、字符串转义、语句拆分和安全校验等公共能力。
 */

import com.el2ck.exception.ApiException;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * SQL 语句处理的纯工具函数，对应 Go internal/service/util.go + query_service.go 的公共部分。
 * 职责：
 *  1. 标识符校验/引用(quoteIdent)——只允许字母/下划线开头、仅字母数字下划线，杜绝 SQL 注入；
 *  2. 字符串字面量转义(quoteString)——ClickHouse 中反斜杠是转义符，须先转义再双写单引号；
 *  3. 只读语句判定(isReadStatement / firstKeyword)——按首关键字白名单判定；
 *  4. 分号拆句(splitStatements)——手写词法器，跳过字符串/注释/$$ 美元引号内的分号。
 * 所有校验失败抛 {@link ApiException}(400)，错误文案与 Go 版逐字一致。
 */
public final class Sql {

    /** 工具类私有构造器：禁止实例化。 */
    private Sql() {
    }

    /** 合法标识符正则：字母或下划线开头、仅字母/数字/下划线(对应 Go identRe)。 */
    private static final Pattern IDENT_RE = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");

    /** 只读语句首关键字白名单(对应 Go readKeywords)。 */
    private static final Set<String> READ_KEYWORDS = new HashSet<>(Arrays.asList(
            "SELECT", "SHOW", "DESCRIBE", "DESC", "EXPLAIN", "EXISTS", "WITH"));

    /** 语句体里出现即视为写操作的关键字(用于 WITH 开头的语句)。对应 Go sqlWriteKeywords。 */
    private static final String[] SQL_WRITE_KEYWORDS = {
            "INSERT", "ALTER", "DELETE", "UPDATE", "CREATE", "DROP", "TRUNCATE",
            "RENAME", "OPTIMIZE", "SYSTEM", "GRANT", "REVOKE", "EXCHANGE", "MOVE",
            "ATTACH", "DETACH", "KILL"};

    /**
     * 只读语句里仍能【写服务器文件】的子句关键字。对应 Go fileWriteKeywords。
     *
     * <p>必须单独挡:{@code SELECT ... INTO OUTFILE '/任意路径' FORMAT CSV} 首关键字是 SELECT,
     * 却让 ClickHouse 在服务器上写文件(路径任意、内容由查询结果决定)。判成只读会让
     * 「只读连接」与「未勾选允许写操作」两道护栏同时失效,构成任意文件写原语。
     * 只收 OUTFILE(SELECT 的写文件子句只有 INTO OUTFILE;INFILE 属于 LOAD DATA,本就非只读)。
     */
    private static final String[] FILE_WRITE_KEYWORDS = {"OUTFILE"};

    /**
     * 校验标识符是否合法；合法则原样返回名字(可直接安全拼入 SQL，无需引号)。
     * 非法时抛 400 {@link ApiException}，错误文案与 Go quoteIdent 一致。
     */
    public static String quoteIdent(String name) {
        // 标识符为空或不符合正则时抛参数校验异常(HTTP 400)
        if (name == null || !IDENT_RE.matcher(name).matches()) {
            throw ApiException.validation(String.format(
                    "非法标识符 \"%s\"：仅允许字母、数字、下划线且不能以数字开头", name == null ? "" : name));
        }
        // 校验通过，原样返回标识符供 SQL 拼接
        return name;
    }

    /** 判断字符串是否为合法标识符；不抛异常，仅返回判定结果。 */
    public static boolean validIdent(String name) {
        // 要求非空且整体匹配标识符正则
        return name != null && IDENT_RE.matcher(name).matches();
    }

    /**
     * 生成 ClickHouse 字符串字面量。
     * 注意：ClickHouse 字符串里 '\' 是转义符，须先把 '\' 转义成 '\\'，再双写单引号(对应 Go quoteString)。
     */
    public static String quoteString(String s) {
        // null 视为空串；先把反斜杠自身转义为双反斜杠
        s = s == null ? "" : s.replace("\\", "\\\\");
        // 单引号双写，最后整体包上一对单引号
        return "'" + s.replace("'", "''") + "'";
    }

    /** 用反引号包裹列名(列名来自表结构，已保证安全)。对应 Go quoteCol。 */
    public static String quoteCol(String c) {
        // 反引号包裹后可直接拼入 SQL 的列引用
        return "`" + c + "`";
    }

    /**
     * 用反引号包裹<b>来自查询结果</b>的列名(用户 SQL 的别名、表达式名,内容任意)。
     *
     * <p>与 {@link #quoteCol} 的唯一区别:这里把列名里的反引号翻倍转义。表结构来的列名先过
     * quoteIdent 白名单、不含反引号,所以那条路不需要;而结果列名可以是
     * {@code SELECT 1 AS "a`b"} 这种,不转义会把外层投影拼成坏 SQL(甚至夹带出第二个列引用)。
     */
    public static String quoteColRaw(String c) {
        // 反引号翻倍是 ClickHouse 里反引号标识符的标准转义写法
        return "`" + (c == null ? "" : c.replace("`", "``")) + "`";
    }

    /*
     * ⚠️ 这里原来还有一个字符集版类型校验 validType(^[A-Za-z0-9_(),.\- ]+$),已随建表/改列
     * 改用 {@link DdlGuard#validateColumnType(String)} 而删除。原因与 Go 侧一致:它更窄(禁掉单引号,
     * 于是 DateTime64(3, 'Asia/Shanghai')、Enum8('a' = 1) 这类合法类型被误拒),又更宽(放行顶层逗号,
     * {@code Int32, DROP COLUMN y} 这种夹带第二个动作的注入片段直接过)。两套并存只会有「哪个先拦」的坑。
     */

    /**
     * 判断语句是否只读。对应 Go isReadStatement。
     *
     * <p>⚠️ 不能只看首关键字:ClickHouse 允许 {@code WITH <cte> INSERT INTO t SELECT …} ——
     * 首关键字是 WITH,却是一条写入语句(实测在只读连接 + 未勾选允许写时仍成功写入 1000 行)。
     * 所以 WITH 开头时再扫一遍语句体;而文件写子句(INTO OUTFILE)对所有只读语句都要扫。
     */
    public static boolean isReadStatement(String sql) {
        String kw = firstKeyword(sql);
        if (!READ_KEYWORDS.contains(kw)) {
            return false;
        }
        // WITH 开头:语句体里若藏写关键字则不是只读
        if ("WITH".equals(kw) && containsSqlWriteKeyword(sql)) {
            return false;
        }
        // 文件写子句对所有只读语句都要挡(见 FILE_WRITE_KEYWORDS)
        if (containsFileWriteClause(sql)) {
            return false;
        }
        return true;
    }

    /** 语句骨架里是否出现写关键字(整词匹配)。对应 Go containsSQLWriteKeyword。 */
    private static boolean containsSqlWriteKeyword(String sql) {
        String up = bareSQL(sql).toUpperCase();
        for (String k : SQL_WRITE_KEYWORDS) {
            if (hasBareWord(up, k)) {
                return true;
            }
        }
        return false;
    }

    /** 语句骨架里是否出现文件读写子句(整词匹配)。对应 Go containsFileWriteClause。 */
    private static boolean containsFileWriteClause(String sql) {
        String up = bareSQL(sql).toUpperCase();
        for (String k : FILE_WRITE_KEYWORDS) {
            if (hasBareWord(up, k)) {
                return true;
            }
        }
        return false;
    }

    /**
     * s 里是否存在独立的词 w(词字符 = 字母/数字/下划线/$)。对应 Go hasBareWord。
     * 整词匹配是为了避开 {@code delete_count} 这类列名被误判成 DELETE。
     */
    static boolean hasBareWord(String s, String w) {
        for (int i = 0; ; ) {
            int j = s.indexOf(w, i);
            if (j < 0) {
                return false;
            }
            int end = j + w.length();
            boolean leftOk = j == 0 || !isWordByte(s.charAt(j - 1));
            boolean rightOk = end == s.length() || !isWordByte(s.charAt(end));
            if (leftOk && rightOk) {
                return true;
            }
            i = j + 1;
        }
    }

    /** 词字符判定(字母/数字/下划线/$)。对应 Go isWordByte。 */
    private static boolean isWordByte(char b) {
        return b == '_' || b == '$'
                || (b >= 'a' && b <= 'z') || (b >= 'A' && b <= 'Z') || (b >= '0' && b <= '9');
    }

    /**
     * 把注释与引号内的内容抹成空格,只留可做词法扫描的骨架。对应 Go bareSQL。
     *
     * <p>为什么必须抹:字符串字面量/标识符里可能出现写关键字(如 {@code SELECT 'delete' AS note}),
     * 不抹就会把只读语句误判成写。状态机与 {@link #splitStatements} 一致(支持 ' " ` 引号、
     * -- 与 # 行注释、块注释、$$ 与 $tag$ 美元引号)。heredoc 用独立布尔量表示,避免空标签 $$ 被当哨兵。
     */
    static String bareSQL(String sql) {
        if (sql == null) {
            return "";
        }
        StringBuilder b = new StringBuilder(sql.length());
        int n = sql.length();
        char quote = 0;
        String dollarTag = "";
        boolean inDollar = false;
        boolean inLine = false;
        boolean inBlock = false;
        for (int i = 0; i < n; ) {
            char ch = sql.charAt(i);
            if (inLine) {
                if (ch == '\n') {
                    inLine = false;
                    b.append('\n');
                } else {
                    b.append(' ');
                }
                i++;
            } else if (inBlock) {
                if (ch == '*' && i + 1 < n && sql.charAt(i + 1) == '/') {
                    inBlock = false;
                    b.append("  ");
                    i += 2;
                } else {
                    b.append(' ');
                    i++;
                }
            } else if (inDollar) {
                String closing = dollarTag.isEmpty() ? "$$" : "$" + dollarTag + "$";
                if (sql.startsWith(closing, i)) {
                    for (int k = 0; k < closing.length(); k++) {
                        b.append(' ');
                    }
                    i += closing.length();
                    inDollar = false;
                    dollarTag = "";
                } else {
                    b.append(' ');
                    i++;
                }
            } else if (quote != 0) {
                if (ch == '\\' && i + 1 < n) {
                    b.append("  ");
                    i += 2;
                } else if (ch == quote && i + 1 < n && sql.charAt(i + 1) == quote) {
                    b.append("  ");
                    i += 2;
                } else if (ch == quote) {
                    quote = 0;
                    b.append(' ');
                    i++;
                } else {
                    b.append(' ');
                    i++;
                }
            } else {
                if (ch == '$' && i + 1 < n && sql.charAt(i + 1) == '$') {
                    inDollar = true;
                    dollarTag = "";
                    b.append("  ");
                    i += 2;
                } else if (ch == '$' && i + 1 < n && isIdentStart(sql.charAt(i + 1))) {
                    int k = i + 1;
                    while (k < n && isIdentPart(sql.charAt(k))) {
                        k++;
                    }
                    if (k < n && sql.charAt(k) == '$') {
                        inDollar = true;
                        dollarTag = sql.substring(i + 1, k);
                        for (int t = 0; t < k - i + 1; t++) {
                            b.append(' ');
                        }
                        i = k + 1;
                    } else {
                        b.append(ch);
                        i++;
                    }
                } else if (ch == '\'' || ch == '"' || ch == '`') {
                    quote = ch;
                    b.append(' ');
                    i++;
                } else if (ch == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                    inLine = true;
                    b.append("  ");
                    i += 2;
                } else if (ch == '#') {
                    // ClickHouse 行注释含 # 与 #!(实测 SELECT 1 # c 可执行)
                    inLine = true;
                    b.append(' ');
                    i++;
                } else if (ch == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                    inBlock = true;
                    b.append("  ");
                    i += 2;
                } else {
                    b.append(ch);
                    i++;
                }
            }
        }
        return b.toString();
    }

    /**
     * 语句能否被安全地包成子查询({@code (SELECT ...) AS x})。对应 Go wrappableSelect。
     *
     * <p>⚠️ 只能用它判断,【不能】用 {@link #isReadStatement} —— 后者的白名单还含
     * SHOW / DESCRIBE / DESC / EXPLAIN / EXISTS,而 {@code (DESCRIBE TABLE t) AS x}
     * 是语法错误,报错位置还很难联系到是这里包的。
     */
    public static boolean isWrappableSelect(String sql) {
        // 只有 SELECT / WITH 开头的语句能当子查询。
        String kw = firstKeyword(sql);
        return "SELECT".equals(kw) || "WITH".equals(kw);
    }

    /**
     * 把语句包成带别名的子查询。对应 Go wrapSubquery。
     *
     * <p>⚠️ 语句与 {@code )} 之间必须留一个换行:用户 SQL 末尾常写成一行 {@code -- 注释},
     * 紧随其后的 {@code )} 与别名会被这行注释一起吃掉,ClickHouse 报「缺右括号」。
     */
    public static String wrapSubquery(String stmt, String alias) {
        // 换行不能省,理由见上。
        return "(" + stmt + "\n) AS " + alias;
    }

    /**
     * 提取语句首关键字(自动跳过前导注释)，用于语句类型判断。对应 Go firstKeyword。
     * 处理顺序：去掉行注释与块注释 → 跳过前导括号 → 截取首个空格/括号前的单词并转大写。
     */
    public static String firstKeyword(String sql) {
        // null 入参按空串处理
        if (sql == null) {
            return "";
        }
        // 去除首尾空白后开始解析
        String s = sql.trim();
        // 循环清掉开头的 -- 行注释与 /* 块注释，直到首字符不再是注释
        for (; ; ) {
            // 以 -- 开头：跳到该行行尾(换行之后)继续
            if (s.startsWith("--")) {
                int i = s.indexOf('\n');
                if (i >= 0) {
                    s = s.substring(i + 1).trim();
                } else {
                    // 整句只剩注释，视为空串
                    s = "";
                }
                // 以 /* 开头：跳到块注释结束符 */ 之后继续
            } else if (s.startsWith("/*")) {
                int i = s.indexOf("*/");
                if (i >= 0) {
                    s = s.substring(i + 2).trim();
                } else {
                    // 块注释未闭合，视为空串
                    s = "";
                }
            } else {
                // 开头已不是注释，结束清理
                break;
            }
        }
        // 跳过前导括号，(SELECT 1) 仍识别为 SELECT
        s = s.replaceFirst("^\\(", "");
        int end = s.length();
        // 定位首个空白或左括号的位置，其前的子串即首关键字
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == ' ' || ch == '\t' || ch == '\r' || ch == '\n' || ch == '(') {
                end = i;
                break;
            }
        }
        // 截取关键字并统一转大写返回
        return s.substring(0, end).toUpperCase();
    }

    /** 去掉语句尾部多余的分号与空白。对应 Go trimTrailingSemicolons。 */
    public static String trimTrailingSemicolons(String s) {
        // null 入参按空串处理
        if (s == null) {
            return "";
        }
        int end = s.length();
        // 从尾部逐个左移，只要末尾是分号或各类空白就继续
        while (end > 0) {
            char ch = s.charAt(end - 1);
            if (ch == ';' || ch == ' ' || ch == '\t' || ch == '\r' || ch == '\n') {
                end--;
            } else {
                break;
            }
        }
        // 截取去掉尾部无效字符后的子串
        return s.substring(0, end);
    }

    /**
     * 按分号把 SQL 拆成多条语句，自动跳过字符串、标识符引号及注释中的分号。对应 Go splitStatements。
     * 支持：单引号/双引号/反引号、-- 行注释、块注释、$$ 美元引号、$tag$ 自定义美元引号。
     * 注意：拆出的语句按原样保留，不做 trim——行注释不进缓冲会被丢掉，块注释则原样留在语句里；
     * 仅当缓冲内容全为空白(例如该片段只有行注释)时才丢弃。
     */
    public static List<String> splitStatements(String sql) {
        // 收集拆分后各条语句的列表
        java.util.List<String> stmts = new java.util.ArrayList<>();
        // null 入参返回空列表
        if (sql == null) {
            return stmts;
        }
        // 正在累积的单条语句缓冲
        StringBuilder cur = new StringBuilder();
        char quote = 0;            // 0=不在引号内；'\''、'"'、'`' 表示当前引号类型
        String dollarTag = null;   // 非空表示处于 $tag$...$tag$ 内；空串表示 $$...$$ 内
        boolean inLine = false;    // 是否处于 -- 行注释内
        boolean inBlock = false;   // 是否处于 /* */ 块注释内
        int n = sql.length();
        // 逐字符扫描，用状态机区分注释、引号与真正分号
        for (int i = 0; i < n; i++) {
            char ch = sql.charAt(i);
            // 行注释内：只等待换行结束，其余字符一律跳过(不入缓冲)
            if (inLine) {
                if (ch == '\n') {
                    inLine = false;
                }
                continue;
            }
            // 块注释内：内容照常入缓冲，遇 */ 结束块注释
            if (inBlock) {
                cur.append(ch);
                if (ch == '*' && i + 1 < n && sql.charAt(i + 1) == '/') {
                    cur.append('/');
                    i++;
                    inBlock = false;
                }
                continue;
            }
            // 美元引号内：内容入缓冲，并在当前位置匹配到结束 tag 时退出该状态
            if (dollarTag != null) {
                cur.append(ch);
                // 匹配 $tag$ 结束标记
                if (ch == '$' && sql.startsWith("$" + dollarTag + "$", i)) {
                    cur.append(dollarTag).append('$');
                    i += 1 + dollarTag.length();
                    dollarTag = null;
                }
                continue;
            }
            // 处于普通引号(单/双/反引号)内
            if (quote != 0) {
                cur.append(ch);
                if (ch == '\\' && i + 1 < n) {
                    // 反斜杠转义：把被转义的下一个字符也追加进缓冲
                    cur.append(sql.charAt(i + 1));
                    i++;
                } else if (ch == quote) {
                    if (i + 1 < n && sql.charAt(i + 1) == quote) {
                        // 双写引号转义：'' 或 ""，第二个引号也应原样保留
                        cur.append(sql.charAt(i + 1));
                        i++;
                    } else {
                        // 普通闭合引号，退出引号状态
                        quote = 0;
                    }
                }
                continue;
            }
            // 不在任何引号/注释中：按当前字符分发处理
            switch (ch) {
                case '$':
                    if (i + 1 < n && sql.charAt(i + 1) == '$') {
                        // $$ 美元引号开始，空串 tag 标记
                        dollarTag = "";
                        cur.append("$$");
                        i++;
                        continue;
                    }
                    // $tag$ 形式(tag 必须以字母或下划线开头)
                    if (i + 1 < n && isIdentStart(sql.charAt(i + 1))) {
                        int k = i + 1;
                        // 向后收集 tag 名(字母/数字/下划线)
                        while (k < n && isIdentPart(sql.charAt(k))) {
                            k++;
                        }
                        if (k < n && sql.charAt(k) == '$') {
                            // 找到闭合的 $tag$，记录 tag 并连同定界符写入缓冲
                            dollarTag = sql.substring(i + 1, k);
                            cur.append('$').append(dollarTag).append('$');
                            i = k;
                            continue;
                        }
                        // 未找到闭合的 $tag$：不当作美元引号起点，退回按普通字符处理
                    }
                    cur.append(ch);
                    break;
                case '\'':
                case '"':
                case '`':
                    // 记录引号类型并把引号本身写入缓冲
                    quote = ch;
                    cur.append(ch);
                    break;
                case '-':
                    // -- 进入行注释状态
                    if (i + 1 < n && sql.charAt(i + 1) == '-') {
                        inLine = true;
                        cur.append("--");
                        i++;
                    } else {
                        cur.append(ch);
                    }
                    break;
                case '/':
                    // /* 进入块注释状态
                    if (i + 1 < n && sql.charAt(i + 1) == '*') {
                        inBlock = true;
                        cur.append("/*");
                        i++;
                    } else {
                        cur.append(ch);
                    }
                    break;
                case ';':
                    // 真正的语句分隔分号：当前缓冲非空则收为一条语句
                    if (cur.toString().trim().length() > 0) {
                        stmts.add(cur.toString());
                    }
                    // 清空缓冲，开始下一条语句
                    cur.setLength(0);
                    break;
                default:
                    // 普通字符直接追加
                    cur.append(ch);
            }
        }
        // 收尾：缓冲中还剩有非空语句则追加为最后一条
        if (cur.toString().trim().length() > 0) {
            stmts.add(cur.toString());
        }
        return stmts;
    }

    /** 判断字符是否为标识符起始字符(字母/下划线)。 */
    private static boolean isIdentStart(char b) {
        // 大小写字母或下划线可作为标识符开头
        return (b >= 'a' && b <= 'z') || (b >= 'A' && b <= 'Z') || b == '_';
    }

    /** 判断字符是否为标识符组成字符(起始字符或数字)。 */
    private static boolean isIdentPart(char b) {
        // 起始字符之外再允许数字
        return isIdentStart(b) || (b >= '0' && b <= '9');
    }

    /** 提取异常链中最内层可用的错误消息，对齐 Go err.Error() 的语义。 */
    public static String messageOf(Throwable e) {
        // 从最外层异常沿 cause 链定位实际错误
        Throwable t = e;
        // 只要当前层没有消息就继续向 cause 链下层查找
        while (t.getCause() != null && (t.getMessage() == null || t.getMessage().isEmpty())) {
            t = t.getCause();
        }
        // 取出最终定位异常的消息
        String m = t.getMessage();
        // 消息仍为空时用异常的 toString 兜底
        return (m == null || m.isEmpty()) ? String.valueOf(e) : m;
    }
}
