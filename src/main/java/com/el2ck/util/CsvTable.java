package com.el2ck.util;

import com.el2ck.entity.common.Column;
import java.io.*;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

/**
 * 目录同步的文本 CSV 读取器。列结构只来自表头（无表头时来自首条记录），不从数据样本扩列。
 * 按逻辑记录流式读取，支持双引号包裹、双写引号和字段内换行；不转换数字、日期或空白。
 * 默认按有效表头列数（无表头时首条记录列数）投影，忽略额外尾列；可显式关闭。
 * v2 默认修复数据行开头由导出器补齐的连续空列，再按表头列数投影；v1 断点保持原输出。`r`n * 缺列和未闭合引号仍明确报错，宽松列投影不会改变逻辑记录边界。
 * 不落额外快照文件，每次只持有一条记录和调用方要求的批次。
 */
public final class CsvTable implements CsvRows {
    private static final int PROBE_LIMIT = 256 * 1024;
    private static final int MAX_RECORD_CHARS = 16 * 1024 * 1024;
    private final BufferedReader input;
    private final Records records;
    private final boolean ignoreExtraColumns;
    private final boolean repairLeadingEmptyColumns;
    private final List<Column> columns = new ArrayList<>();
    private List<String> firstData;

    public static CsvTable open(String path, boolean header, String delimiter) {
        return open(path, header, delimiter, true);
    }

    public static CsvTable open(String path, boolean header, String delimiter, boolean ignoreExtraColumns) {
        return open(path, header, delimiter, ignoreExtraColumns, true);
    }

    public static CsvTable open(String path, boolean header, String delimiter, boolean ignoreExtraColumns,
                                boolean repairLeadingEmptyColumns) {
        try {
            // 此读取路径使用 UTF-8；损坏的编码不能静默替换成问号后继续入库。
            Reader source = new InputStreamReader(Files.newInputStream(Paths.get(path)),
                    StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT));
            return new CsvTable(source, header, delimiter, ignoreExtraColumns, repairLeadingEmptyColumns);
        } catch (IOException e) {
            throw new IllegalStateException("打开 CSV 失败", e);
        }
    }

    /** 接管 source 的生命周期；目录预览可以直接传入 SFTP 流，无需下载完整文件。 */
    public CsvTable(Reader source, boolean header, String delimiter) {
        this(source, header, delimiter, true, true);
    }

    /** 兼容未显式传读取器版本的调用方；新读取使用行首空列修复规则。 */
    public CsvTable(Reader source, boolean header, String delimiter, boolean ignoreExtraColumns) {
        this(source, header, delimiter, ignoreExtraColumns, true);
    }

    /**
     * 按指定策略投影结构内的列；仍解析完整记录，不猜测字段边界或跳过坏行。
     * repairLeadingEmptyColumns 仅控制 v2 的行首空列修复，旧断点可继续按 v1 输出。
     */
    public CsvTable(Reader source, boolean header, String delimiter, boolean ignoreExtraColumns,
                    boolean repairLeadingEmptyColumns) {
        this.ignoreExtraColumns = ignoreExtraColumns;
        this.repairLeadingEmptyColumns = repairLeadingEmptyColumns;
        input = source instanceof BufferedReader ? (BufferedReader) source : new BufferedReader(source, 65536);
        try {
            char separator = separator(delimiter);
            records = new Records(input, separator, 0);
            List<String> first = records.next();
            if (first == null) return;
            // 只移除没有名称的尾部占位列；有名称的空数据列仍然是正式字段。
            if (header) while (first.size() > 1 && first.get(first.size() - 1).isEmpty()) first.remove(first.size() - 1);
            int digits = String.valueOf(Math.max(0, first.size() - 1)).length();
            Set<String> used = new HashSet<>();
            for (int i = 0; i < first.size(); i++) {
                String base = header ? first.get(i) : "";
                if (base.isEmpty()) base = "column" + String.format(Locale.ROOT, "%0" + digits + "d", i);
                String name = base;
                int suffix = 1;
                while (!used.add(name.toLowerCase(Locale.ROOT))) name = base + "_" + suffix++;
                Column c = new Column(); c.setName(name); c.setType("VARCHAR"); columns.add(c);
            }
            if (!header) firstData = first;
        } catch (Exception e) {
            try { input.close(); } catch (IOException ignored) { }
            throw new IllegalStateException("CSV 表头解析失败：" + e.getMessage(), e);
        }
    }

    /** 已配置的分隔符优先；未配置时只根据首条记录选取，不让后续短信内容改变解析规则。 */
    private char separator(String configured) throws IOException {
        if ("\t".equals(configured) || "\\t".equals(configured)) return '\t';
        String value = configured == null ? "" : configured.trim();
        if (!value.isEmpty()) {
            if (value.length() != 1 || value.charAt(0) == '"' || value.charAt(0) == '\r' || value.charAt(0) == '\n')
                throw new IllegalArgumentException("CSV 分隔符必须是单个非引号、非换行字符");
            return value.charAt(0);
        }
        char selected = ',';
        int best = 0;
        for (char candidate : new char[]{',', '\t', ';', '|'}) {
            input.mark(PROBE_LIMIT + 2);
            try {
                List<String> row = new Records(input, candidate, PROBE_LIMIT).next();
                if (row != null && row.size() > best) { best = row.size(); selected = candidate; }
            } catch (IllegalArgumentException ignored) {
                // 错误候选会把引号后的真正分隔符当成非法字符；最终读取会报告真实格式错误。
            } finally { input.reset(); }
        }
        return selected;
    }

    public List<Column> columns() { return new ArrayList<>(columns); }

    public List<List<String>> next(int limit) {
        if (limit < 1 || limit > 100000) throw new IllegalArgumentException("非法 CSV 批次大小");
        List<List<String>> rows = new ArrayList<>();
        try {
            while (rows.size() < limit) {
                if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("CSV 读取已取消");
                List<String> row = firstData;
                firstData = null;
                if (row == null) row = records.next();
                if (row == null) break;
                // 部分导出器会在文件尾部写一条只有逗号的占位列（例如 7 列都是空串）。
                // 它没有可写入的业务值，也不代表列错位；v2 默认直接跳过，不能因此让整份文件失败。
                // 放在列数判断之前，是为了兼容 6 个、8 个或更多连续逗号形成的全空占位记录。
                if (repairLeadingEmptyColumns && row.stream().allMatch(String::isEmpty)) {
                    continue;
                }

                int expected = columns.size(), actual = row.size();
                if (actual > expected) {
                    if (ignoreExtraColumns && repairLeadingEmptyColumns) {
                        // 部分导出工具会在数据行前补齐若干空列。只有删除这些空列后仍能提供完整表头列数时，
                        // 才把它们视为行首占位列，并从第一个业务列开始截取；这样不会把正常业务行本来为空的
                        // 首字段误删。后面的额外尾列仍按“忽略额外尾列”策略丢弃。
                        int leadingEmpty = 0;
                        while (leadingEmpty < actual && row.get(leadingEmpty).isEmpty()) leadingEmpty++;
                        int remaining = actual - leadingEmpty;
                        if (leadingEmpty > 0) {
                            if (remaining < expected) throw records.rowError("预期 " + expected + " 列，实际 " + actual
                                    + " 列；删除开头 " + leadingEmpty + " 个空列后仅剩 " + remaining
                                    + " 列，少于表头列数，未自动写入空值");
                            row = new ArrayList<>(row.subList(leadingEmpty, leadingEmpty + expected));
                        } else {
                            row = new ArrayList<>(row.subList(0, expected));
                        }
                    } else if (ignoreExtraColumns) {
                        // v1 断点继续沿用原规则，只裁掉表头列数之后的尾部，避免恢复批次发生变化。
                        row = new ArrayList<>(row.subList(0, expected));
                    } else {
                        for (int i = expected; i < actual; i++) {
                            if (!row.get(i).isEmpty()) throw records.rowError("预期 " + expected + " 列，实际 " + actual
                                    + " 列，第 " + (i + 1) + " 列含额外内容（" + extraCharacters(row.get(i))
                                    + "）；未截断该记录。若确认超出结构的尾列无需入库，可在任务中开启“忽略额外尾列”；否则请检查分隔符或引号");
                        }
                        row = new ArrayList<>(row.subList(0, expected));
                    }
                } else if (actual < expected) {
                    throw records.rowError("预期 " + expected + " 列，实际 " + actual + " 列；记录缺列，未自动补齐");
                }
                rows.add(row);
            }
            return rows;
        } catch (IOException e) {
            throw new IllegalStateException("CSV 读取失败：" + e.getMessage(), e);
        }
    }

    @Override public void close() { try { input.close(); } catch (IOException ignored) { } }

    /** 只显示额外列的长度和少量码点，不把短信正文复制到运行日志或断点错误中。 */
    private static String extraCharacters(String value) {
        StringBuilder detail = new StringBuilder("UTF-16 长度 ").append(value.length()).append("，前至多 8 个字符编码 ");
        int offset = 0;
        for (int n = 0; n < 8 && offset < value.length(); n++) {
            if (n > 0) detail.append(' ');
            int cp = value.codePointAt(offset);
            detail.append(String.format(Locale.ROOT, "U+%04X", cp));
            offset += Character.charCount(cp);
        }
        if (offset < value.length()) detail.append(" …");
        return detail.toString();
    }

    /** 小型 CSV 状态机；物理换行只在引号外结束记录，错误位置不包含业务数据正文。 */
    private static final class Records {
        private final Reader input;
        private final char separator;
        private final int limit;
        private int consumed, look = -2;
        private boolean first = true;
        private long line = 1, number;
        private long characterLine = 1, lookLine = 1, recordStartLine = 1, recordEndLine = 1;
        private boolean afterCr;
        Records(Reader input, char separator, int limit) { this.input = input; this.separator = separator; this.limit = limit; }

        private int take() throws IOException {
            if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("CSV 读取已取消");
            int c;
            if (look != -2) { c = look; look = -2; characterLine = lookLine; return c; }
            if (limit > 0 && consumed >= limit) throw error("表头过长，无法自动判断分隔符，请显式配置");
            c = input.read(); consumed++;
            if (first) { first = false; if (c == 0xfeff) return take(); }
            // line 指向下一次读取的位置，不能用作已结束记录的行号。
            // CRLF 的两个字符属于同一物理行；裸 CR 的预读字符另保存位置。
            characterLine = c == '\n' && afterCr ? line - 1 : line;
            if (c == '\r') line++;
            if (c == '\n' && !afterCr) line++;
            afterCr = c == '\r';
            return c;
        }

        List<String> next() throws IOException {
            List<String> row = new ArrayList<>();
            StringBuilder cell = new StringBuilder();
            boolean quoted = false, closedQuote = false, touched = false;
            boolean started = false;
            int recordChars = 0;
            while (true) {
                int c = take();
                if (c != -1) {
                    if (!started) { recordStartLine = characterLine; started = true; }
                    recordEndLine = characterLine;
                }
                // 损坏的引号可能吞掉余下整份大文件；在内存耗尽前给出明确位置。
                if (c != -1 && ++recordChars > MAX_RECORD_CHARS)
                    throw error("单条记录超过 16 Mi 字符，请检查未闭合引号或拆分超大字段");
                if (c == -1) {
                    if (quoted) throw error("双引号未闭合");
                    if (!touched && row.isEmpty() && cell.length() == 0) return null;
                    row.add(cell.toString()); number++; return row;
                }
                if (quoted) {
                    if (c == '"') { quoted = false; closedQuote = true; }
                    else cell.append((char)c);
                    continue;
                }
                if (closedQuote && c == '"') { cell.append('"'); quoted = true; closedQuote = false; continue; }
                if (c == separator) { row.add(cell.toString()); cell.setLength(0); touched = true; closedQuote = false; continue; }
                if (c == '\r' || c == '\n') {
                    if (c == '\r') {
                        int following = take();
                        if (following != '\n') { look = following; lookLine = characterLine; }
                    }
                    if (!touched && row.isEmpty() && cell.length() == 0) { recordChars = 0; started = false; continue; }
                    row.add(cell.toString()); number++; return row;
                }
                if (closedQuote) throw error("结束引号后出现非分隔符字符");
                if (c == '"' && cell.length() == 0) { quoted = true; touched = true; continue; }
                cell.append((char)c); touched = true;
            }
        }

        IllegalArgumentException error(String message) {
            return new IllegalArgumentException("CSV 记录 " + (number + 1) + position() + "：" + message);
        }

        IllegalArgumentException rowError(String message) {
            return new IllegalArgumentException("CSV 记录 " + number + position() + "：" + message);
        }

        private String position() {
            String delimiter = separator == '\t' ? "TAB" : String.valueOf(separator);
            return "（物理行 " + recordStartLine + "–" + recordEndLine + "，分隔符=" + delimiter + "）";
        }
    }
}
