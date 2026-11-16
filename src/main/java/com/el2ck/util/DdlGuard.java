package com.el2ck.util;

/**
 * 【文件职责】在生成或执行 DDL 前校验标识符和危险语句边界，降低 SQL 注入及误删风险。
 */

import java.util.regex.Pattern;

/**
 * DDL 片段校验(列类型 / 默认值 / 注释),对应 Go internal/service/ddl_guard.go。
 *
 * <p>为什么必须有:这些字符串会原样拼进
 * {@code ALTER TABLE ... ADD|MODIFY COLUMN `x` <type> DEFAULT <def> COMMENT '<comment>'},
 * 前端那道校验(web/src/utils/ddlGuard.ts)是给用户看的,不是安全边界 —— 接口可以被直接调用,
 * 所以服务端必须独立拦。
 *
 * <p>本类的错误文案与 Go 版<b>逐字一致</b>:表体检的报告会把这些文案原样嵌进 detail 给用户看,
 * 两边漂移就会让「同一张表在 Go 与 Java 上体检结论不同」。
 *
 * <p>三条踩过的坑(与 Go 注释同一份):
 * <ol>
 *   <li>只挡字符集不够:ClickHouse 允许一条 ALTER 里用逗号分隔多个动作,
 *       所以 {@code Int32, DROP COLUMN y} 在宽松字符集下合法通过 → 必须按括号深度判定,顶层禁逗号。</li>
 *   <li>规则写太严同样是 bug:负值枚举 {@code Enum8('a' = -1)}、带时区的
 *       {@code DateTime64(3,'Asia/Shanghai')}、默认值 {@code now64(3)}/{@code toDate(now())}/{@code 1e5}
 *       都是合法的 —— 写窄了会让已经存在的列变得不可编辑(DESCRIBE 回读到的类型/默认值会被重新校验,
 *       连只改注释都会整体被拒)。</li>
 *   <li>前后端「镜像」必须真的是同一套规则,否则前端放行、服务端拒绝,用户看到的是莫名其妙的失败。</li>
 * </ol>
 * 所以核心只有这一个 safeFragment 扫描器,类型/默认值都走它。
 */
public final class DdlGuard {

    /** 工具类私有构造器:禁止实例化。 */
    private DdlGuard() {
    }

    /** 数字字面量(含正负号/小数/科学计数),对应 Go numLitRe。 */
    private static final Pattern NUM_LITERAL_RE =
            Pattern.compile("^[+-]?([0-9]+(\\.[0-9]*)?|\\.[0-9]+)([eE][+-]?[0-9]+)?$");

    /** 标识符正则:字母或下划线开头、仅字母数字下划线(对应 Go 复用的 identRe,不带点)。 */
    private static final Pattern IDENT_RE = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");

    /**
     * 通用片段校验(对应 Go safeFragment):
     * 括号必须配平;单引号字符串整段跳过(里面的括号/逗号/等号是数据,不参与判定);
     * 顶层(括号外)不允许逗号 —— 那是「同一条 ALTER 的第二个动作」;
     * 禁止 {@code ; " ` \} 换行 与 {@code --} {@code /*} 注释;
     * 允许字母数字 {@code _ . space ( ) , = -} 以及单引号字符串。
     *
     * @return 通过返回 null,否则返回与 Go 一致的中文错误文案
     */
    static String safeFragment(String s) {
        if (s == null || s.trim().isEmpty()) {
            return "内容不能为空";
        }
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            // 单引号字符串:整段跳过(含转义与 '' 双写)
            if (ch == '\'') {
                i++;
                boolean closed = false;
                for (; i < s.length(); i++) {
                    if (s.charAt(i) == '\\') {
                        // 反斜杠转义:连下一个字符一起跳过
                        i++;
                        continue;
                    }
                    if (s.charAt(i) == '\'') {
                        // '' 双写:继续留在字符串内
                        if (i + 1 < s.length() && s.charAt(i + 1) == '\'') {
                            i++;
                            continue;
                        }
                        closed = true;
                        break;
                    }
                }
                if (!closed) {
                    return "字符串没有闭合:" + s;
                }
                continue;
            }
            if (ch == '(') {
                depth++;
            } else if (ch == ')') {
                depth--;
                if (depth < 0) {
                    return "括号不匹配:" + s;
                }
            } else if (ch == ',') {
                if (depth == 0) {
                    return "顶层不允许逗号 —— 会被当成同一条 ALTER 的第二个动作:" + s;
                }
            } else if (ch == ';' || ch == '"' || ch == '`' || ch == '\\' || ch == '\n' || ch == '\r') {
                return "含非法字符 " + goQuote(ch) + ":" + s;
            } else if (ch == '-' && i + 1 < s.length() && s.charAt(i + 1) == '-') {
                return "不允许注释符:" + s;
            } else if (ch == '/' && i + 1 < s.length() && s.charAt(i + 1) == '*') {
                return "不允许注释符:" + s;
            } else if (!isFragmentChar(ch)) {
                return "含非法字符 " + goQuote(ch) + ":" + s;
            }
        }
        if (depth != 0) {
            return "括号不匹配:" + s;
        }
        return null;
    }

    /** 片段里允许出现的普通字符(对应 Go isFragmentByte)。 */
    private static boolean isFragmentChar(char c) {
        if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')) {
            return true;
        }
        // '-' 必须放行:负值枚举/负数默认值都合法(注释符 '--' 在上面单独挡)
        return c == '_' || c == ' ' || c == '.' || c == '(' || c == ')' || c == ',' || c == '=' || c == '-';
    }

    /**
     * 把单个字符渲染成 Go {@code %q} 的写法(错误文案里带非法字符时用)。
     * Go 的 {@code fmt.Errorf("含非法字符 %q", string(ch))} 会把双引号写成 {@code "\""}、
     * 反斜杠写成 {@code "\\"}、换行写成 {@code "\n"} —— 直接拼 {@code "\\"" + ch + "\\""} 在
     * 这几个字符上会与 Go 的输出不一致。
     */
    private static String goQuote(char c) {
        switch (c) {
            case '"':
                return "\"\\\"\"";
            case '\\':
                return "\"\\\\\"";
            case '\n':
                return "\"\\n\"";
            case '\r':
                return "\"\\r\"";
            default:
                return "\"" + c + "\"";
        }
    }

    /**
     * 校验列类型片段:safeFragment + 形状校验。对应 Go validateColumnType。
     *
     * <p>形状必须尾锚定:含 '(' 时,与第一个 '(' 配对的那个 ')' 必须正好是整串最后一个字符 ——
     * 只判 HasSuffix(")") 是不够的,{@code String(a) b(c)} 也以 ')' 结尾。
     *
     * @return 通过返回 null,否则返回错误文案
     */
    public static String validateColumnType(String t) {
        String s = t == null ? "" : t.trim();
        String err = safeFragment(s);
        if (err != null) {
            return err;
        }
        int i = s.indexOf('(');
        if (i < 0) {
            return IDENT_RE.matcher(s).matches() ? null : "列类型格式不对:" + t;
        }
        String head = s.substring(0, i).trim();
        if (!IDENT_RE.matcher(head).matches()) {
            return "列类型格式不对:" + t;
        }
        // 计数时要跳过字符串字面量 —— 否则 Enum8('a)' = 1) 里的 ) 会把深度算错(会误拒合法类型)
        int end = matchingParen(s, i);
        if (end != s.length() - 1) {
            return "列类型的括号之后不应再有内容:" + t;
        }
        return null;
    }

    /** 返回与 s[i]('(')配对的 ')' 的下标;找不到返回 -1。跳过单引号字面量。对应 Go matchingParen。 */
    static int matchingParen(String s, int i) {
        int depth = 0;
        for (int j = i; j < s.length(); j++) {
            char c = s.charAt(j);
            if (c == '\'') {
                j++;
                for (; j < s.length(); j++) {
                    if (s.charAt(j) == '\\') {
                        j++;
                    } else if (s.charAt(j) == '\'') {
                        // '' 双写不算闭合
                        if (!(j + 1 < s.length() && s.charAt(j + 1) == '\'')) {
                            break;
                        }
                        j++;
                    }
                }
                continue;
            }
            if (c == '(') {
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

    /**
     * 校验 DEFAULT 表达式:safeFragment + 形状,而且要求整串匹配。对应 Go validateDefaultExpr。
     *
     * @return 通过返回 null,否则返回错误文案
     */
    public static String validateDefaultExpr(String v) {
        String s = v == null ? "" : v.trim();
        String err = safeFragment(s);
        if (err != null) {
            return err;
        }
        if (isSingleStringLiteral(s) || NUM_LITERAL_RE.matcher(s).matches()
                || "true".equalsIgnoreCase(s) || "false".equalsIgnoreCase(s) || "null".equalsIgnoreCase(s)) {
            return null;
        }
        int i = s.indexOf('(');
        // 尾锚定:函数调用必须以 ')' 收尾(否则 "now() 加任意尾巴" 能过)
        if (i > 0 && IDENT_RE.matcher(s.substring(0, i).trim()).matches() && matchingParen(s, i) == s.length() - 1) {
            return null;
        }
        return "默认值只支持:数字、单引号字符串、true/false/NULL 或函数调用(如 now()、now64(3)):" + v;
    }

    /** 判断整串是否恰好是一个单引号字面量(结尾后不能再有内容)。对应 Go isSingleStringLiteral。 */
    static boolean isSingleStringLiteral(String s) {
        if (s == null || s.length() < 2 || s.charAt(0) != '\'') {
            return false;
        }
        for (int i = 1; i < s.length(); i++) {
            if (s.charAt(i) == '\\') {
                i++;
                continue;
            }
            if (s.charAt(i) == '\'') {
                if (i + 1 < s.length() && s.charAt(i + 1) == '\'') {
                    i++;
                    continue;
                }
                return i == s.length() - 1;
            }
        }
        return false;
    }
}
