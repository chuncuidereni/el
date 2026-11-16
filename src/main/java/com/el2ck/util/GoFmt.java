package com.el2ck.util;

/**
 * 【文件职责】格式化 Go 风格模板或代码片段，供查询计划展示和生成使用。
 */

/**
 * 对齐 Go {@code fmt} 的少数几个动词,用于让错误文案与 Go 版逐字一致。
 *
 * <p>存在的唯一理由:{@code %q}(带引号的字符串)在 Java 的 {@code String.format} 里没有对应动词,
 * 而这些文案要跨版本对得上(验收时两端报错要能直接比对),所以只能自己实现一份。
 * 除 {@link #q(String)} 外不要往这里加东西 —— 见 {@link Sql} 里关于「同一条规则只留一份」的说明。
 */
public final class GoFmt {

    /** 工具类私有构造器:禁止实例化。 */
    private GoFmt() {
    }

    /**
     * 等价于 Go 的 {@code fmt.Sprintf("%q", s)} / {@code strconv.Quote(s)}:
     * 用双引号包裹,转义双引号与反斜杠,控制字符用 {@code \n \t \r} 或 {@code \xNN}。
     *
     * <p>可打印的非 ASCII 字符(如中文)按原样保留 —— Go 的 Quote 对可打印 unicode 也是原样输出,
     * 不用反斜杠加 u 的转义形式(Java 源码里那是 Unicode 转义,写在注释里也会被解析)。
     *
     * <p>null 渲染成空引号串,不抛异常:调用方传的都是配置里的字符串字段,判空散落在各处更容易漏。
     */
    public static String q(String s) {
        if (s == null) {
            return "\"\"";
        }
        StringBuilder out = new StringBuilder(s.length() + 2);
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    out.append("\\\"");
                    break;
                case '\\':
                    out.append("\\\\");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                default:
                    // 不可打印字符按 \xNN 输出,与 Go 对 ASCII 控制字符的处理一致
                    if (c < 0x20 || c == 0x7f) {
                        out.append(String.format("\\x%02x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        out.append('"');
        return out.toString();
    }
}
