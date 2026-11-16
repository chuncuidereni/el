package com.el2ck.util;

/**
 * 【文件职责】解析和生成 CSV，统一处理引号、分隔符、换行和转义。
 */

import java.util.regex.Pattern;

/**
 * CSV 导出的单元格处理:值 → 文本、文本 → 转义后的 CSV 字段。
 *
 * <p>对应 Go {@code internal/service/table_service.go} 的 {@code csvVal} / {@code csvEscape} /
 * {@code signedNumberRe},三条导出路径(表导出、SQL 结果导出、查询工作流导出)共用这一份 ——
 * 同一个值不该因为走的是哪条导出而形状不同。
 *
 * <p>⚠️ 与前端 {@code web/src/utils/download.ts} 的 {@code csvCell} / {@code SIGNED_NUMBER}
 * 是同一套规则,改这里要一起改(HashToolView / CryptoToolView 的客户端导出走的是前端那份)。
 */
public final class Csv {

    /** 工具类私有构造器:禁止实例化。 */
    private Csv() {
    }

    /**
     * 带符号的纯数字(含小数 / 科学计数)。
     *
     * <p>与前端 {@code download.ts} 的 {@code SIGNED_NUMBER} 逐字一致。
     */
    private static final Pattern SIGNED_NUMBER =
            Pattern.compile("^[+-](\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?$");

    /**
     * CSV 的 UTF-8 BOM(写成 {@code \\uFEFF} 转义而不是字面量:U+FEFF 在编辑器里不可见,
     * 复制粘贴一圈就可能悄悄丢掉)。
     *
     * <p>不带它 Excel 打开 UTF-8 的中文 CSV 会乱码 —— 与 Go {@code csvBOM = []byte{0xEF,0xBB,0xBF}} 一致。
     */
    public static final String BOM = "\uFEFF";

    /** BOM 的原始字节,流式写出用(与 Go 一样直接写字节,不经字符编码)。 */
    public static byte[] bomBytes() {
        // EF BB BF
        return new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    }

    /** 单元格值 → 导出字符串(与结果网格的显示一致;null → 空串)。对应 Go csvVal。 */
    public static String value(Object v) {
        // null 转空串,否则按字符串化后的值导出。
        return v == null ? "" : String.valueOf(v);
    }

    /**
     * CSV 转义:含分隔符 / 引号 / 换行时用双引号包裹并转义内部引号。
     *
     * <p>⚠️ 还要挡 CSV 公式注入:导出明确面向 Excel(响应专门加了 BOM),而 Excel 会把
     * {@code =} / {@code @} / 制表符 / 回车 开头(以及「不是纯数字」的 {@code +} / {@code -} 开头)
     * 的单元格当公式执行 —— Go 侧实测 {@code =cmd|'/c calc'!A1} 原样落进导出文件,打开就触发。
     * 补一个前导单引号让它按文本处理。
     *
     * <p>但带符号的<b>纯数字</b>(负数、{@code +8613800000000} 这种带国家码的号码)是常见正常数据,
     * 一律加前导单引号会让下游再导入全变字符串(Excel 里也变成左对齐的文本)——
     * 与前端同一规则:只中和「看起来不像纯数字」的。
     *
     * <p>表头字段同样走这里(用户能自定义列别名),所以表头也被一并护住。
     */
    public static String escape(String s, String delim) {
        // null 兜底空串。
        if (s == null) {
            s = "";
        }
        // 公式前缀中和:先看首字符是不是表格软件会当公式起的类型。
        if (!s.isEmpty()) {
            char c0 = s.charAt(0);
            if (c0 == '=' || c0 == '@' || c0 == '\t' || c0 == '\r'
                    || ((c0 == '+' || c0 == '-') && !SIGNED_NUMBER.matcher(s).matches())) {
                s = "'" + s;
            }
        }
        // 分隔符兜底逗号(上游已兜底,这里防直接调用)。
        String d = (delim == null || delim.isEmpty()) ? "," : delim;
        // 是否含需要引号包裹的敏感字符。
        boolean need = s.contains(d) || s.indexOf('"') >= 0
                || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0;
        if (!need) {
            return s;
        }
        // 双引号包裹并把内部单个引号双写。
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    /**
     * 拼一行 CSV(逐格转义后按分隔符连接,不含换行)。
     *
     * <p>表头行与数据行共用,免得两处各拼一次、表头忘了转义。
     */
    public static String row(java.util.List<Object> cells, String delim) {
        // 分隔符兜底逗号。
        String d = (delim == null || delim.isEmpty()) ? "," : delim;
        // 按列数预分配。
        StringBuilder sb = new StringBuilder();
        // 逐格转义并拼接。
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                sb.append(d);
            }
            sb.append(escape(value(cells.get(i)), d));
        }
        return sb.toString();
    }

    /**
     * 往 Writer 里逐行写 CSV 的写出器。
     *
     * <p>表导出与 SQL 结果导出共用:表头开关、分隔符、逐行转义、行数统计只有这一份实现,
     * 免得两条导出路径各写一遍再慢慢漂移(Go 侧 {@code writeExportCSV} 就是为此抽出来的)。
     */
    public static final class Sink {

        /** 目标 Writer。 */
        private final java.io.Writer out;

        /** 分隔符(构造时已兜底)。 */
        private final String delim;

        /** 已写出的数据行数(不含表头)。 */
        private long rows;

        /** 构造:指定输出与分隔符(空 → 逗号)。 */
        public Sink(java.io.Writer out, String delim) {
            // 保存输出流。
            this.out = out;
            // 分隔符兜底。
            this.delim = (delim == null || delim.isEmpty()) ? "," : delim;
        }

        /**
         * 写表头行。
         *
         * <p>⚠️ 调用方必须自己判断「要不要表头」——以前 Go 侧只判了表头名非空就写,
         * 于是前端关掉「包含表头」也照样导出表头(用户实测报过)。
         */
        public void header(java.util.List<String> names) throws java.io.IOException {
            // 表头名同样过 escape:用户能自定义列别名,不挡就等于放行公式注入。
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < names.size(); i++) {
                if (i > 0) {
                    sb.append(delim);
                }
                sb.append(escape(names.get(i), delim));
            }
            out.write(sb.append('\n').toString());
        }

        /** 写一行数据。 */
        public void row(java.util.List<Object> cells) throws java.io.IOException {
            // 显式写 Csv. 前缀:不带前缀会解析到本类的 row(List) 自己,编译不过。
            out.write(Csv.row(cells, delim));
            out.write('\n');
            rows++;
        }

        /** 已写出的数据行数(不含表头)。 */
        public long rows() {
            return rows;
        }
    }
}
