/**
 * 【文件职责】本文件定义数据源管理模块的分析器 SftpDirectoryAnalyzer。承载该模块的核心业务规则，协调仓储、连接器或外部服务，并统一转换业务异常和操作状态。
 */

package com.el2ck.service.datasource;

import com.el2ck.duck.Duck;
import com.el2ck.entity.common.Column;
import com.el2ck.entity.datasource.SftpDirPreview;
import com.el2ck.exception.ApiException;
import com.el2ck.util.ModernCsvTable;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.SftpException;

import java.io.InputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Vector;

/**
 * SFTP 目录预览的解析核心：递归发现 CSV 文件，并逐个探测列结构后归并分组(对齐 Go 的目录预览语义)。
 *
 * 为什么单独抽成包私有类：整段流程只服务于 DataSourceService 的目录预览，并且需要在调用方已建好的
 * SFTP 会话上工作(连接由调用方建立与关闭，本类不管生命周期)。分析过程只读远端、不落库，
 * 每个文件最多预览 100 条逻辑记录，完整保留引号内换行；临时样本在 finally 中删除。
 */
final class SftpDirectoryAnalyzer {
    private final Duck duck;

    SftpDirectoryAnalyzer(Duck duck) {
        this.duck = duck;
    }

    /**
     * 扫描 requestedRoot 下的匹配文件并归并结构。纯函数式：只往新建的 SftpDirPreview 上写，
     * 不做副作用；找不到任何文件时抛 400(而非返回空结果)，让前端能明确区分「目录空」与「路径写错」。
     */
    SftpDirPreview analyze(ChannelSftp channel, String requestedRoot, String requestedPattern, boolean header, String delimiter)
            throws Exception {
        return analyze(channel, requestedRoot, requestedPattern, header, delimiter, true);
    }

    SftpDirPreview analyze(ChannelSftp channel, String requestedRoot, String requestedPattern, boolean header,
                           String delimiter, boolean ignoreExtraColumns) throws Exception {
        return analyze(channel, requestedRoot, requestedPattern, header, delimiter, ignoreExtraColumns, "auto");
    }

    SftpDirPreview analyze(ChannelSftp channel, String requestedRoot, String requestedPattern, boolean header,
                           String delimiter, boolean ignoreExtraColumns, String csvEncoding) throws Exception {
        csvEncoding = com.el2ck.util.CsvEncoding.normalize(csvEncoding);
        // 未指定目录时用登录后的当前工作目录(channel.pwd() 可能带结尾斜杠，故拼接前先去掉)
        String root = requestedRoot == null || requestedRoot.trim().isEmpty()
                ? channel.pwd() : requestedRoot.trim();
        // 相对路径挂到当前工作目录下；不解析 ".."，由 SFTP 服务端按实际路径处理
        if (!root.startsWith("/")) root = channel.pwd().replaceAll("/+$", "") + "/" + root;
        // 未指定匹配模式时只认 CSV 文件
        String pattern = requestedPattern == null || requestedPattern.trim().isEmpty()
                ? "*.csv" : requestedPattern.trim();
        List<RemoteFile> matches = new ArrayList<>();
        // 从 root 起递归收集全部匹配文件；传入 depth=0 表示这是根目录
        walk(channel, root, pattern, 0, matches);
        // 排序后再处理：保证 reference 文件与分组顺序在同一次目录内容下稳定可复现
        matches.sort(Comparator.comparing(file -> file.path));
        if (matches.isEmpty()) {
            throw ApiException.validation("远端目录 " + root + " 下未找到匹配 \"" + pattern + "\" 的 CSV 文件");
        }

        SftpDirPreview result = new SftpDirPreview();
        result.setRoot(root);
        result.setPattern(pattern);
        // 以「列名列表」为键做分组：LinkedHashMap 让同构文件聚在一起，且分组保持首次出现的顺序
        Map<List<String>, SftpDirPreview.ColGroup> groups = new LinkedHashMap<>();
        for (RemoteFile match : matches) {
            SftpDirPreview.FileSchema file = new SftpDirPreview.FileSchema();
            file.setPath(match.path);
            file.setSize(match.size);
            file.setMtime(match.mtime);
            Path sample = null;
            try {
                // 与运行期共用记录解析器；样本重新规范转义，DuckDB 只负责类型推断。
                sample = Files.createTempFile("el2ck-sftp-schema-", ".csv");
                List<String> headerNames = sample(channel, match.path, sample, 100, header, delimiter, ignoreExtraColumns, csvEncoding, file);
                if (headerNames.isEmpty()) throw new IllegalArgumentException("CSV 文件为空，没有可识别字段");
                List<Column> columns = duck.describeFile(sample.toString(), true, headerNames, ",");
                List<String> names = names(columns);
                file.setColumns(names);
                file.setColumnCount(columns.size());
                // 排序后的第一个文件作为「基准 schema」：提供分组参照、列定义与数据预览
                if (result.getReference().isEmpty()) {
                    result.setReference(match.path);
                    result.setReferenceColumns(names);
                    result.setColumns(columns);
                    try {
                        result.setPreview(duck.previewFile(sample.toString(), 10, true, headerNames, ","));
                    } catch (Exception ignored) {
                        // 行预览只是附加信息，失败不影响结构探测的可用性，故静默忽略
                    }
                }
                // 列名与基准完全一致才视为同构(仅比列名，不看类型)
                file.setSameSchema(names.equals(result.getReferenceColumns()));
                // computeIfAbsent 内即时把新分组挂到结果上，保证分组顺序与首次遇到该 schema 的文件顺序一致
                SftpDirPreview.ColGroup group = groups.computeIfAbsent(names, key -> {
                    SftpDirPreview.ColGroup created = new SftpDirPreview.ColGroup();
                    created.setColumns(columns);
                    created.setColNames(names);
                    result.getGroups().add(created);
                    return created;
                });
                group.setFileCount(group.getFileCount() + 1);
                group.getMemberFiles().add(match.path);
            } catch (Exception error) {
                // 单个文件解析失败不影响整体：仍为它建一个「无法解析」分组，让该文件在结果中可见
                file.setError(error.getMessage() == null ? error.toString() : error.getMessage());
                SftpDirPreview.ColGroup failed = new SftpDirPreview.ColGroup();
                failed.setColNames(java.util.Collections.singletonList("(无法解析)"));
                failed.setFileCount(1);
                failed.getMemberFiles().add(match.path);
                failed.setError(file.getError());
                result.getGroups().add(failed);
            } finally {
                // 临时样本文件无论成功失败都要删掉，避免在临时目录堆积
                if (sample != null) Files.deleteIfExists(sample);
            }
            result.getFiles().add(file);
        }
        return result;
    }

    /**
     * 深度优先扫描 SFTP 目录中的 CSV 文件。
     *
     * <p>根目录列举失败会向上抛出；子目录无权限或不存在时静默跳过。隐藏文件和
     * 符号链接不参与扫描，避免目录环。匹配文件会转换为本地可读时间并追加到
     * {@code out}，该方法本身不限制目录深度。
     *
     * @param channel 已连接的 SFTP 通道
     * @param dir 当前目录
     * @param pattern 文件名通配模式
     * @param depth 当前递归深度，0 表示用户指定根目录
     * @param out 扫描结果累加列表
     * @throws SftpException 根目录无法列举
     */
    private static void walk(ChannelSftp channel, String dir, String pattern, int depth, List<RemoteFile> out)
            throws SftpException {
        Vector<ChannelSftp.LsEntry> entries;
        try {
            entries = channel.ls(dir);
        } catch (SftpException error) {
            // 根目录列举失败必须上抛(多为目录不存在或无权限)；子目录失败则静默跳过，
            // 不因个别无权限子目录中断整轮扫描。
            if (depth == 0) throw error;
            return;
        }
        for (ChannelSftp.LsEntry entry : entries) {
            String name = entry.getFilename();
            // 跳过隐藏文件与符号链接：链接可能回指上级目录，递归会成环
            if (name == null || name.startsWith(".") || entry.getAttrs().isLink()) continue;
            String path = dir.replaceAll("/+$", "") + "/" + name;
            if (entry.getAttrs().isDir()) {
                // 子目录继续下钻，深度 +1 用于区分「根目录失败要报错」
                walk(channel, path, pattern, depth + 1, out);
            } else if (name.toLowerCase(java.util.Locale.ROOT).endsWith(".csv") && matches(pattern, name)) {
                // 服务器返回的是秒级时间戳，统一转为本地时区的可读字符串供前端展示
                String mtime = LocalDateTime.ofInstant(Instant.ofEpochSecond(entry.getAttrs().getMTime()),
                        ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                out.add(new RemoteFile(path, entry.getAttrs().getSize(), mtime));
            }
        }
    }

    static boolean matches(String pattern, String name) {
        // 模式不含 glob 元字符(*、?、[)时退化为简单前缀匹配(与 Go 行为一致)
        if (!pattern.matches(".*[\\\\*?\\[].*")) return name.startsWith(pattern);
        // 用 Paths.get 而非 Path.of:后者是 Java 11 才有的方法,本工程编译目标是 Java 8
        return java.nio.file.FileSystems.getDefault().getPathMatcher("glob:" + pattern)
                .matches(Paths.get(name));
    }

    /** 提取列名列表。它既是「是否同构」的比较对象，也是分组的键(见 analyze 中的 LinkedHashMap)。 */
    private static List<String> names(List<Column> columns) {
        List<String> out = new ArrayList<>();
        for (Column column : columns) out.add(column.getName());
        return out;
    }

    /**
     * 抽取完整逻辑记录，再写成规范 CSV。字段内换行不会截断样本，不改写字段内容。
     *
     * <p>调用方负责管理 channel；读取器仅关闭当前文件流。样本始终包含规范化表头。
     *
     * @param channel 已连接的 SFTP 通道
     * @param remote 远端文件路径
     * @param local 样本输出文件
     * @param lines 期望读取的最大行数
     * @throws Exception SFTP 下载或本地文件写入失败时抛出
     */
    private static List<String> sample(ChannelSftp channel, String remote, Path local, int lines,
                                       boolean header, String delimiter, boolean ignoreExtraColumns, String csvEncoding,
                                       SftpDirPreview.FileSchema file) throws Exception {
        try (InputStream in = channel.get(remote);
             ModernCsvTable csv = ModernCsvTable.open(in, remote, header, delimiter, ignoreExtraColumns, csvEncoding);
             Writer out = Files.newBufferedWriter(local, StandardCharsets.UTF_8)) {
            file.setCsvEncoding(csv.encoding()); file.setCsvDelimiter(csv.delimiter());
            List<String> names = names(csv.columns());
            if (!names.isEmpty()) writeRecord(out, names);
            long sampleChars = 0;
            for (int i = 0; i < lines && sampleChars < 1024 * 1024; i++) {
                List<List<String>> batch = csv.next(1);
                if (batch.isEmpty()) break;
                List<String> row = batch.get(0);
                writeRecord(out, row);
                for (String value : row) sampleChars += value.length();
            }
            return names;
        }
    }

    /** 内部样本只做 CSV 引号转义，不使用面向 Excel 的公式防护转义，以免改变原字段。 */
    private static void writeRecord(Writer out, List<String> values) throws java.io.IOException {
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) out.write(',');
            out.write('"'); out.write(values.get(i).replace("\"", "\"\"")); out.write('"');
        }
        out.write('\n');
    }

    /** 扫描期的文件描述。私有不可变结构：只在包内流转、不参与 JSON，故不必套一层对外实体。 */
    private static final class RemoteFile {
        final String path;
        final long size;
        /** 已格式化好的时间字符串(服务器给的是 epoch 秒)。 */
        final String mtime;

        RemoteFile(String path, long size, String mtime) {
            this.path = path;
            this.size = size;
            this.mtime = mtime;
        }
    }
}
