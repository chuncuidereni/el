package com.el2ck.util;

import com.el2ck.entity.common.Column;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import java.io.*;
import java.nio.charset.CharacterCodingException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

/**
 * 新轮次 CSV 读取器。Commons CSV 负责逻辑记录，编码层和列投影在预览/执行中共用。
 * 保留字段原文，不 trim、不猜测号码或日期类型；只保留一条记录和请求批次。
 */
public final class ModernCsvTable implements CsvRows {
    private static final int PROBE_CHARS = 256 * 1024;
    private final List<Column> columns = new ArrayList<>();
    private final boolean ignoreExtraColumns;
    private final String sourceName;
    private String encoding;
    private char delimiter;
    private CSVParser parser;
    private Iterator<CSVRecord> iterator;
    private BudgetReader budget;
    private List<String> firstData;

    public static ModernCsvTable open(String path, boolean header, String delimiter, boolean ignoreExtra, String encoding) {
        return open(path, path, header, delimiter, ignoreExtra, encoding);
    }

    public static ModernCsvTable open(String path, String displayName, boolean header, String delimiter, boolean ignoreExtra, String encoding) {
        try { return open(Files.newInputStream(Paths.get(path)), displayName, header, delimiter, ignoreExtra, encoding); }
        catch (IOException e) { throw new IllegalStateException("打开 CSV 失败：" + displayName, e); }
    }

    /** 接管流，初始化失败时也关闭；远端预览不必先下载整份文件。 */
    public static ModernCsvTable open(InputStream input, String name, boolean header, String delimiter,
                                      boolean ignoreExtra, String encoding) {
        return new ModernCsvTable(input, name, header, delimiter, ignoreExtra, encoding);
    }

    private ModernCsvTable(InputStream source, String name, boolean header, String requestedDelimiter,
                           boolean ignoreExtra, String requestedEncoding) {
        sourceName = name; ignoreExtraColumns = ignoreExtra;
        Reader owned = null;
        try {
            CsvEncoding.Decoded decoded = CsvEncoding.open(source, requestedEncoding);
            encoding = decoded.encoding;
            BufferedReader input = new BufferedReader(decoded.reader, 8192);
            owned = input;
            delimiter = chooseDelimiter(input, requestedDelimiter);
            budget = new BudgetReader(input);
            parser = new CSVParser(budget, format(delimiter));
            iterator = parser.iterator();
            List<String> first = readRecord();
            if (first == null) return;
            if (header) while (first.size() > 1 && first.get(first.size() - 1).isEmpty()) first.remove(first.size() - 1);
            int digits = String.valueOf(Math.max(0, first.size() - 1)).length();
            Set<String> used = new HashSet<>();
            for (int i = 0; i < first.size(); i++) {
                String base = header ? first.get(i) : "";
                if (base.isEmpty()) base = "column" + String.format(Locale.ROOT, "%0" + digits + "d", i);
                String columnName = base;
                int suffix = 1;
                while (!used.add(columnName.toLowerCase(Locale.ROOT))) columnName = base + "_" + suffix++;
                Column column = new Column(); column.setName(columnName); column.setType("VARCHAR"); columns.add(column);
            }
            if (!header) firstData = first;
        } catch (Exception e) {
            if (owned != null) try { owned.close(); } catch (IOException ignored) { }
            else try { source.close(); } catch (IOException ignored) { }
            throw failure("读取表头或探测格式失败", e);
        }
    }

    private static CSVFormat format(char separator) {
        return CSVFormat.DEFAULT.builder().setDelimiter(separator).setQuote('"')
                .setIgnoreEmptyLines(true).setIgnoreSurroundingSpaces(false).setTrim(false).build();
    }

    private static char chooseDelimiter(BufferedReader input, String configured) throws IOException {
        if ("\t".equals(configured) || "\\t".equals(configured)) return '\t';
        if (configured != null && !configured.trim().isEmpty()) {
            if (configured.length() != 1 || "\"\r\n\u0000".indexOf(configured.charAt(0)) >= 0)
                throw new IOException("CSV 分隔符必须为单个非引号、非换行字符");
            return configured.charAt(0);
        }
        input.mark(PROBE_CHARS + 1);
        char[] sample = new char[PROBE_CHARS];
        int length = 0;
        while (length < sample.length) {
            int n = input.read(sample, length, sample.length - length);
            if (n < 0) break;
            length += n;
        }
        input.reset();
        char best = ',';
        int count = -1;
        for (char candidate : new char[]{',', '\t', ';', '|'}) {
            try (CSVParser probe = new CSVParser(new CharArrayReader(sample, 0, length), format(candidate))) {
                Iterator<CSVRecord> rows = probe.iterator();
                int size = rows.hasNext() ? rows.next().size() : 0;
                if (size > count) { count = size; best = candidate; }
            } catch (IOException | UncheckedIOException ignored) {
                // 仅候选分隔符解析失败可以试下一个；实际读取不会跳过损坏记录。
            }
        }
        if (count < 0) throw new IOException("无法识别 CSV 分隔符，请检查首条记录引号或明确配置分隔符");
        return best;
    }

    private List<String> readRecord() {
        if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("CSV 读取已取消");
        budget.resetBudget();
        if (!iterator.hasNext()) return null;
        CSVRecord record = iterator.next();
        List<String> row = new ArrayList<>(record.size());
        for (String value : record) row.add(value);
        return row;
    }

    public String encoding() { return encoding; }
    public String delimiter() { return String.valueOf(delimiter); }
    @Override public List<Column> columns() { return new ArrayList<>(columns); }

    @Override public List<List<String>> next(int limit) {
        if (limit < 1 || limit > 100000) throw new IllegalArgumentException("非法 CSV 批次大小");
        List<List<String>> result = new ArrayList<>();
        try {
            while (result.size() < limit) {
                List<String> row = firstData;
                firstData = null;
                if (row == null) row = readRecord();
                if (row == null) break;
                if (row.stream().allMatch(String::isEmpty)) continue;
                int expected = columns.size(), actual = row.size();
                if (actual > expected) {
                    int start = 0;
                    if (ignoreExtraColumns) {
                        // 只有超宽记录才修复前导空占位；等宽行的开头空值必须保留。
                        while (start < actual && row.get(start).isEmpty()) start++;
                        if (actual - start < expected) throw new IOException("删除额外前导空列后不足 " + expected + " 列，不能确认列对齐");
                    } else {
                        for (int i = expected; i < actual; i++) if (!row.get(i).isEmpty())
                            throw new IOException("列数不一致：预期 " + expected + "，实际 " + actual + "；第 " + (i + 1) + " 列有额外内容");
                    }
                    row = new ArrayList<>(row.subList(start, start + expected));
                } else if (actual < expected) {
                    throw new IOException("列数不一致：预期 " + expected + "，实际 " + actual + "；未自动补齐缺列");
                }
                result.add(row);
            }
            return result;
        } catch (Exception e) { throw failure("读取记录失败", e); }
    }

    private IllegalStateException failure(String stage, Exception error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
        String detail = cause instanceof CharacterCodingException
                ? "字节无法按 " + encoding + " 解码，请选择实际文件编码；不支持混合编码，不会替换或丢弃错误字节"
                : cause instanceof InterruptedIOException ? "读取已取消"
                : cause.getMessage();
        // Commons CSV 的错误只描述语法位置；不输出记录正文。
        return new IllegalStateException("CSV " + stage + "：" + sourceName + "（编码=" + (encoding == null ? "未确定" : encoding)
                + (parser == null ? "" : "，已解析记录=" + parser.getRecordNumber() + "，物理行附近=" + parser.getCurrentLineNumber()
                + "，分隔符=" + (delimiter == '\t' ? "TAB" : delimiter)) + "）：" + detail, error);
    }

    @Override public void close() { if (parser != null) try { parser.close(); } catch (IOException ignored) { } }

    /** 限制一次逻辑记录期间的底层读取，防止未闭合引号吞掉数 GB 文件；预留解析器缓冲余量。 */
    private static final class BudgetReader extends FilterReader {
        private int remaining;
        BudgetReader(Reader source) { super(source); resetBudget(); }
        void resetBudget() { remaining = 16 * 1024 * 1024 + 8192; }
        @Override public int read(char[] chars, int offset, int length) throws IOException {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("CSV 读取已取消");
            if (remaining <= 0) throw new IOException("单条 CSV 记录超过 16 Mi 字符限制，请检查未闭合引号或异常长字段");
            int n = super.read(chars, offset, Math.min(length, remaining));
            if (n > 0) remaining -= n;
            return n;
        }
        @Override public int read() throws IOException {
            char[] one = new char[1];
            return read(one, 0, 1) < 0 ? -1 : one[0];
        }
    }
}
