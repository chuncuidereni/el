package com.el2ck.util;

/**
 * 【文件职责】解析文件数据源配置，确定可读路径、表头、列名和分隔符。
 */

import com.el2ck.duck.Duck;
import com.el2ck.duck.Xlsx;
import com.el2ck.entity.datasource.DataSource;
import com.el2ck.entity.common.Column;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

/**
 * 本地文件数据源读取的统一配置工具类(供 DataSourceService / SyncEngine / workflow.Engine 复用)。
 * <p>负责汇聚三类文件的解析参数：是否首行为列头(header)、手动指定列名(names)、
 * 自定义分隔符(delimiter，CSV/文本用；为 null 表示走 DuckDB 默认逗号分隔)。
 * Excel(xlsx) 数据源会先经 {@link Xlsx} 转成临时 CSV 文件并缓存于
 * duckdb 库目录下的 xlsx-cache 子目录，再交由 DuckDB read_csv 读取——
 * 因此对上层透明，拿到手的始终是一条"可直接解析的 CSV 路径"。
 */
public final class SourceFile {

    /** 工具类私有构造器：禁止实例化。 */
    private SourceFile() {
    }

    /**
     * 将读取时的字段名对应回原始文件表头，供字段映射的默认注释使用。
     * names 重命名不改变文件列位置；结构长度不一致时不猜测对应关系，防止注释串列。
     * 不向 Column 增加属性，避免改变历史任务 schema 的断点指纹。
     */
    public static Map<String, String> columnComments(List<Column> columns, List<Column> rawColumns) {
        Map<String, String> comments = new LinkedHashMap<>();
        if (columns == null || rawColumns == null || columns.size() != rawColumns.size()) return comments;
        for (int i = 0; i < columns.size(); i++) {
            Column column = columns.get(i), raw = rawColumns.get(i);
            if (column != null && raw != null && column.getName() != null && raw.getName() != null
                    && !raw.getName().trim().isEmpty()) {
                comments.put(column.getName(), raw.getName());
            }
        }
        return comments;
    }

    /** 从配置里读取是否把首行当表头(csv/xlsx 有效；缺省 true)。 */
    public static boolean header(Map<String, Object> cfg) {
        Object h = cfg.get("header");
        return h instanceof Boolean ? (Boolean) h : true;
    }

    /** 从配置里读取手动指定的列名(逗号分隔字符串或 JSON 数组)；未配置则返回 null。 */
    public static List<String> names(Map<String, Object> cfg) {
        Object raw = cfg.get("names");
        if (raw == null) {
            return null;
        }
        List<String> out = new ArrayList<>();
        if (raw instanceof String) {
            for (String p : ((String) raw).split(",")) {
                String s = p.trim();
                if (!s.isEmpty()) {
                    out.add(s);
                }
            }
        } else if (raw instanceof List) {
            for (Object it : (List<?>) raw) {
                if (it instanceof String && !((String) it).isEmpty()) {
                    out.add((String) it);
                }
            }
        }
        // 配置了 names 但清洗后一项不剩时同样返回 null，等价于「未指定列名」(由 DuckDB 自行推断)
        return out.isEmpty() ? null : out;
    }

    /** 从配置里读取自定义分隔符；空串或逗号则返回 null(DuckDB 默认)；"\t"/"\\t" 视为制表符。 */
    public static String delimiter(Map<String, Object> cfg) {
        Object d = cfg.get("delimiter");
        if (d == null) {
            return null;
        }
        return normalize(String.valueOf(d));
    }

    /** 新目录 CSV 链路保留明确配置的逗号及真实制表符，不再把它们丢回自动识别。 */
    public static String directoryDelimiter(Map<String, Object> cfg) {
        Object value = cfg.get("delimiter");
        return value == null ? null : String.valueOf(value);
    }

    /** 归一化自定义分隔符(请求覆盖 / 保存时用)：null、空串或逗号统一归为 null；制表符统一为 "\\t"。 */
    public static String normalize(String d) {
        if (d == null) {
            return null;
        }
        String s = d.trim();
        if (s.isEmpty() || ",".equals(s)) {
            return null;
        }
        if ("\t".equals(s) || "\\t".equals(s)) {
            return "\\t";
        }
        return s;
    }

    /**
     * 返回一条可供 DuckDB 直接解析的路径。Excel 类型会先转换为临时 CSV 并缓存，其余类型原样返回。
     *
     * @param duck duckdb(需已就绪)；未就绪时抛出 IllegalStateException
     * @param type 数据源类型：csv/json/xlsx
     * @param cfg  已解密的配置(需含 path)
     */
    public static String readableCsv(Duck duck, String type, Map<String, Object> cfg) {
        // 必填：本地源文件路径
        String path = cfg.get("path") == null ? "" : String.valueOf(cfg.get("path")).trim();
        if (path.isEmpty()) {
            throw new IllegalStateException("文件路径不能为空");
        }
        // 非 Excel 类型本就可供 DuckDB 直接解析，无需转换，原样返回
        if (!DataSource.XLSX.equals(type)) {
            return path;
        }
        // 转换需要把缓存目录放在 duckdb 库同级，故必须等 duckdb 就绪
        if (duck == null || !duck.isReady()) {
            throw new IllegalStateException("duckdb 未初始化");
        }
        File src = new File(path);
        if (!src.exists()) {
            throw new IllegalStateException("文件不存在: " + path);
        }
        // 缓存目录固定为 duckdb 库文件父目录下的 xlsx-cache 子目录
        String cacheDirPath = duck.file();
        File cacheDir = new File(new File(cacheDirPath).getParentFile(), "xlsx-cache");
        if (!cacheDir.exists() && !cacheDir.mkdirs()) {
            throw new IllegalStateException("创建 Excel 缓存目录失败: " + cacheDir);
        }
        // 缓存键 = 路径哈希 + 文件大小 + 最后修改时间：文件改动后键随之变化，可自动触发重新转换
        String key = Integer.toHexString(path.hashCode()) + "-" + src.length() + "-" + src.lastModified();
        File out = new File(cacheDir, key + ".csv");
        // 命中缓存则复用已有 CSV；未命中才执行转换并顺带清理过期缓存
        // (检查与转换非原子：并发时同一文件可能被重复转换，产物内容一致，后写入者覆盖前者)
        if (!out.exists()) {
            Xlsx.toCsv(src, out);
            gc(cacheDir);
        }
        // 返回转换结果(或缓存命中结果)的绝对路径供 DuckDB 读取
        return out.getAbsolutePath();
    }

    /** 清理一天前的转换缓存，避免长期堆积(尽力而为，失败忽略)。 */
    private static void gc(File cacheDir) {
        try {
            long threshold = System.currentTimeMillis() - 24L * 3600L * 1000L;
            File[] files = cacheDir.listFiles();
            if (files == null) {
                return;
            }
            for (File f : files) {
                if (f.isFile() && f.lastModified() < threshold) {
                    f.delete();
                }
            }
        } catch (RuntimeException ignored) {
            // 清理失败不影响主流程。
        }
    }

    /** 纯数据校验：当数据源类型不是本地文件时抛错。当前仓库内已无调用点；code 参数未参与判断，仅为调用方签名保留。 */
    public static void requireLocalFile(String type, String code) {
        if (!DataSource.isLocalFile(type)) {
            throw new IllegalStateException(String.format(
                    "暂仅支持本地文件(CSV/JSON/Excel)数据源，当前类型 \"%s\"", type));
        }
    }
}
