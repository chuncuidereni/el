package com.el2ck.util;

/**
 * 【文件职责】访问 SFTP 等远端文件源，负责会话、目录、文件元数据和流式读取。
 */

import com.el2ck.entity.datasource.DataSource;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.bson.types.ObjectId;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Date;

/** 将远程文件/文档(SFTP 文件或 MongoDB 集合)物化到本机临时目录，后续统一交给 DuckDB 解析。 */
public final class RemoteSourceFile {
    private static final ObjectMapper JSON = new ObjectMapper();
    /** 工具类私有构造器：禁止实例化。 */
    private RemoteSourceFile() {}

    /**
     * 通过 SFTP 把远程文件下载到本机临时目录并返回其本地路径。
     *
     * @param cfg 数据源配置：需含 host、username、password、remotePath，可选 port
     * @return 下载到本机临时目录后的本地文件路径
     * @throws IllegalStateException 主机/用户名/远程路径缺失或下载失败
     */
    public static Path downloadSftp(Map<String, Object> cfg) {
        // 读取 SFTP 主机、用户名与远程路径，缺失时返回空串
        String host = value(cfg, "host");
        String user = value(cfg, "username");
        String path = value(cfg, "remotePath");
        // 三者任一为空即参数不完整，直接抛错
        if (host.isEmpty() || user.isEmpty() || path.isEmpty()) {
            throw new IllegalStateException("SFTP 主机、用户名和远程路径不能为空");
        }
        // 端口默认 22
        int port = integer(cfg, "port", 22);
            // 优先使用数据源配置的格式作为临时文件后缀，确保 DuckDB 后续按同一格式解析；
            // 未配置格式时才沿用远程文件名后缀。
            String suffix = configuredSuffix(cfg, path);
        try {
            // 在系统临时目录新建专属子目录，避免多任务并发互相覆盖
            Path dir = Files.createTempDirectory("el2ck-sftp-");
            // 本地文件名固定为 source+后缀，不再沿用远程文件名(远程名可能含非法字符)
            Path local = dir.resolve("source" + suffix);
            // 创建 JSch 会话并配置用户名/主机/端口
            JSch jsch = new JSch();
            Session session = jsch.getSession(user, host, port);
            session.setPassword(value(cfg, "password"));
            // 关闭主机密钥严格校验(内网可信主机)，避免首次连接时的交互确认
            java.util.Properties props = new java.util.Properties();
            props.put("StrictHostKeyChecking", "no");
            session.setConfig(props);
            session.setTimeout(5000);
            // 建立会话连接，超时 5 秒
            session.connect(5000);
            // 打开并连接 SFTP 通道
            ChannelSftp channel = (ChannelSftp) session.openChannel("sftp");
            channel.connect(5000);
            try {
                // 从远程服务器拉取文件到本地临时路径
                channel.get(path, local.toString());
            } finally {
                // 无论成功失败，都释放 SFTP 通道与会话连接
                if (channel.isConnected()) channel.disconnect();
                if (session.isConnected()) session.disconnect();
            }
            // 返回本地物化后的文件路径
            return local;
        } catch (Exception e) {
            // 下载过程任一步骤异常统一包装后抛出。
            // 注意：此处不清理已创建的临时文件/目录(异常时调用方拿不到路径)，只能依赖系统临时目录回收。
            throw new IllegalStateException("SFTP 下载失败: " + e.getMessage(), e);
        }
    }

    /** SFTP 目录里一个匹配到的文件:完整路径 + 字节数 + 修改时间(用于去重比对)。 */
    public static final class RemoteEntry {
        // 远端文件完整路径(去重键)
        public final String path;
        // 文件字节数
        public final long size;
        // 修改时间(SFTP mtime 的 epoch 秒文本;仅作等值比对,不解析)
        public final String mtime;

        public RemoteEntry(String path, long size, String mtime) {
            this.path = path;
            this.size = size;
            this.mtime = mtime;
        }
    }

    /**
     * 递归列出 SFTP 远端目录下匹配 {@code pattern}(glob,如 {@code *.csv})的文件,返回路径+size+mtime。
     * 供 SFTP 目录同步遍历使用(对应 Go sftpcli.WalkCSV);连接方式与 {@link #downloadSftp} 一致。
     *
     * @param cfg     数据源配置:host/username/password,可选 port
     * @param root    远端根目录
     * @param pattern 文件名匹配(空则默认 {@code *.csv})
     */
    public static List<RemoteEntry> listSftpFiles(Map<String, Object> cfg, String root, String pattern) {
        // 一次性列目录:开一个会话、列完即关。目录同步的「列 + 多次下载」请用 openSftpSession 复用会话。
        try (SftpSession sftp = openSftpSession(cfg)) {
            return sftp.list(root, pattern);
        }
    }

    /**
     * 一次目录同步内可复用的 SFTP 会话:开一次,完成「列目录 + 下载多个文件」,用完一并关闭。
     * 避免每个文件各握手一次(Go 侧用 sftpcli 连接池达到同样目的;此处做「单次运行内复用」这一实际收益)。
     *
     * <p>主机密钥校验行为与 {@link #downloadSftp}/{@link #listSftpFiles} 完全一致(内网可信主机,
     * StrictHostKeyChecking=no),不做任何收紧或放宽。用完必须 {@link #close()}(建议 try-with-resources)。
     */
    public static final class SftpSession implements AutoCloseable {
        private final Session session;
        private final ChannelSftp channel;
        // 保存数据源配置:下载时据其 format 决定本地后缀,与 downloadSftp 同口径。
        private final Map<String, Object> cfg;

        private SftpSession(Session session, ChannelSftp channel, Map<String, Object> cfg) {
            this.session = session;
            this.channel = channel;
            this.cfg = cfg;
        }

        /** 递归列出根目录下匹配 {@code pattern}(glob)的文件,复用本会话通道。 */
        public List<RemoteEntry> list(String root, String pattern) {
            String dir = root == null ? "" : root.trim();
            if (dir.isEmpty()) {
                throw new IllegalStateException("SFTP 远端目录不能为空");
            }
            String glob = pattern == null || pattern.trim().isEmpty() ? "*.csv" : pattern.trim();
            java.util.regex.Pattern re = globToRegex(glob);
            List<RemoteEntry> out = new ArrayList<>();
            try {
                walkSftp(channel, dir, re, 0, out);
            } catch (com.jcraft.jsch.SftpException e) {
                throw failure("遍历 SFTP 目录失败",e);
            } catch (Exception e) {
                throw new IllegalStateException("遍历 SFTP 目录失败: " + e.getMessage(), e);
            }
            return out;
        }

        /** 下载某远端文件到本机临时目录并返回本地路径;后缀按数据源格式(与 {@link #downloadSftp} 一致)。 */
        public Path download(String remotePath) {
            if (remotePath == null || remotePath.trim().isEmpty()) {
                throw new IllegalStateException("远端文件路径不能为空");
            }
            String suffix = configuredSuffix(cfg, remotePath);
            try {
                Path dir = Files.createTempDirectory("el2ck-sftp-");
                Path local = dir.resolve("source" + suffix);
                channel.get(remotePath, local.toString());
                return local;
            } catch (Exception e) {
                throw new IllegalStateException("SFTP 下载失败: " + e.getMessage(), e);
            }
        }

        /** 仅接受普通文件；恢复前后的版本检查使用远端 size/mtime。 */
        public RemoteEntry stat(String path) {
            try {
                com.jcraft.jsch.SftpATTRS attrs=channel.lstat(path);
                if(!attrs.isReg()) throw new IllegalStateException("SFTP 续传仅支持普通文件: "+path);
                return new RemoteEntry(path,attrs.getSize(),Integer.toString(attrs.getMTime()));
            } catch(com.jcraft.jsch.SftpException e) { throw failure("读取 SFTP 文件信息失败",e); }
        }

        /** skip 是远端字节偏移，调用方负责验证版本及本地已落盘前缀。 */
        public java.io.InputStream openRange(String path,long skip) {
            if(skip<0) throw new IllegalArgumentException("非法下载偏移");
            try { return channel.get(path,null,skip); }
            catch(com.jcraft.jsch.SftpException e) { throw failure("打开 SFTP 文件失败",e); }
        }

        private RuntimeException failure(String message,com.jcraft.jsch.SftpException e) {
            if(e.id==ChannelSftp.SSH_FX_CONNECTION_LOST || e.id==ChannelSftp.SSH_FX_NO_CONNECTION
                    || !session.isConnected() || !channel.isConnected())
                return new SftpConnectionException(message,e);
            return new IllegalStateException(message+": "+e.getMessage(),e);
        }

        /** 释放 SFTP 通道与会话(尽力而为,不抛)。 */
        @Override
        public void close() {
            try {
                if (channel.isConnected()) channel.disconnect();
            } catch (RuntimeException ignored) {
                // 关闭失败不影响已完成的同步结果
            }
            try {
                if (session.isConnected()) session.disconnect();
            } catch (RuntimeException ignored) {
                // 同上
            }
        }
    }

    /**
     * 打开一个可复用的 SFTP 会话(host/username 缺失即抛)。用完必须 {@link SftpSession#close()},
     * 建议 try-with-resources。连接方式与 {@link #downloadSftp} 完全一致。
     */
    public static SftpSession openSftpSession(Map<String, Object> cfg) {
        return openSftpSession(cfg,5000);
    }

    public static final class SftpConnectionException extends RuntimeException {
        public SftpConnectionException(String message,Throwable cause) { super(message,cause); }
    }


    public static SftpSession openSftpSession(Map<String,Object> cfg,int readTimeoutMs) {
        String host = value(cfg, "host");
        String user = value(cfg, "username");
        if (host.isEmpty() || user.isEmpty()) {
            throw new IllegalStateException("SFTP 主机、用户名不能为空");
        }
        int port = integer(cfg, "port", 22);
        Session session=null;
        ChannelSftp channel=null;
        try {
            JSch jsch = new JSch();
            session = jsch.getSession(user, host, port);
            session.setPassword(value(cfg, "password"));
            java.util.Properties props = new java.util.Properties();
            props.put("StrictHostKeyChecking", "no");
            session.setConfig(props);
            session.setTimeout(readTimeoutMs);
            session.connect(5000);
            channel = (ChannelSftp) session.openChannel("sftp");
            channel.connect(5000);
            return new SftpSession(session, channel, cfg);
        } catch (Exception e) {
            if(channel!=null) channel.disconnect();
            if(session!=null) session.disconnect();
            throw new IllegalStateException("SFTP 连接失败: " + e.getMessage(), e);
        }
    }

    /** 递归遍历(限深 32 层,防环)。目录进入,普通文件按文件名匹配后收集。 */
    @SuppressWarnings("unchecked")
    private static void walkSftp(ChannelSftp channel, String dir, java.util.regex.Pattern re, int depth,
                                 List<RemoteEntry> out) throws Exception {
        if (depth > 32) return;
        java.util.Vector<ChannelSftp.LsEntry> entries = channel.ls(dir);
        for (ChannelSftp.LsEntry e : entries) {
            String name = e.getFilename();
            if (".".equals(name) || "..".equals(name)) continue;
            String full = dir.endsWith("/") ? dir + name : dir + "/" + name;
            if (e.getAttrs().isDir()) {
                walkSftp(channel, full, re, depth + 1, out);
            } else if (re.matcher(name).matches()) {
                out.add(new RemoteEntry(full, e.getAttrs().getSize(),
                        Integer.toString(e.getAttrs().getMTime())));
            }
        }
    }

    /** 把 glob(仅支持 {@code *} 与 {@code ?})转成锚定正则,其余字符按字面量转义。 */
    static java.util.regex.Pattern globToRegex(String glob) {
        StringBuilder sb = new StringBuilder("^");
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*') {
                sb.append(".*");
            } else if (c == '?') {
                sb.append('.');
            } else {
                sb.append(java.util.regex.Pattern.quote(String.valueOf(c)));
            }
        }
        sb.append('$');
        return java.util.regex.Pattern.compile(sb.toString());
    }

    /**
     * 将 MongoDB 集合导出为 JSONL(每行一个文档，即 JSON Lines)，DuckDB 可直接用 read_json_auto 读取。
     *
     * @param cfg        数据源配置：host、username、password、database、port、authSource
     * @param collection 待导出的集合名
     * @return 导出生成的本地 .jsonl 文件路径
     * @throws IllegalStateException 数据库/集合缺失或导出失败
     */
    public static Path exportMongoJsonl(Map<String, Object> cfg, String collection) {
        // 读取目标库名
        String db = value(cfg, "database");
        // 库名或集合名为空时直接抛错
        if (db.isEmpty() || collection == null || collection.trim().isEmpty()) {
            throw new IllegalStateException("MongoDB 数据库和集合不能为空");
        }
        // 依据配置拼装连接串
        String uri = mongoUri(cfg);
        Path local;
        try {
            // 在临时目录创建 .jsonl 输出文件
            local = Files.createTempFile("el2ck-mongo-", ".jsonl");
            // 建立 MongoClient，同时打开写文件流(UTF-8)
            try (com.mongodb.client.MongoClient client = com.mongodb.client.MongoClients.create(uri);
                 BufferedWriter out = Files.newBufferedWriter(local, StandardCharsets.UTF_8)) {
                // 遍历集合内全部文档，逐个以 JSON 形式写成一行
                for (Document doc : client.getDatabase(db).getCollection(collection.trim()).find()) {
                    out.write(JSON.writeValueAsString(normalizeMongoDocument(doc)));
                    out.newLine();
                }
            }
            return local;
        } catch (Exception e) {
            // 与 SFTP 下载同理：异常时临时 .jsonl 文件已无法被调用方定位，不做清理。
            throw new IllegalStateException("MongoDB 集合导出失败: " + e.getMessage(), e);
        }
    }

    /**
     * 将 Mongo 文档转换成稳定的 JSONL 结构：顶层字段保持列，嵌套对象/数组序列化为 JSON 字符串，
     * 避免不同文档的 STRUCT/LIST 类型冲突；Mongo 专有值统一转换为普通字符串。
     */
    private static Map<String, Object> normalizeMongoDocument(Document doc) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : doc.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Map || value instanceof List) {
                try {
                    out.put(entry.getKey(), JSON.writeValueAsString(normalizeMongoValue(value)));
                } catch (Exception e) {
                    out.put(entry.getKey(), String.valueOf(value));
                }
            } else {
                out.put(entry.getKey(), normalizeMongoValue(value));
            }
        }
        return out;
    }

    /**
     * 递归地把单个 Mongo 值归一化成 JSONL 可稳定序列化的形式：
     * 基本类型(null/字符串/数字/布尔)原样透传；Mongo 专有类型(ObjectId/Date/Decimal128)
     * 统一转成普通字符串，避免不同文档出现类型冲突；嵌套的 Map/List 递归处理保持结构；
     * 其余未知类型以 toString 兜底，防止序列化抛错。
     */
    private static Object normalizeMongoValue(Object value) {
        // null 与字符串/数字/布尔都是 JSON 原生类型，无需处理直接透传
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) return value;
        // Mongo 专有类型不转字符串会导致 DuckDB 跨文档类型冲突，故统一按普通字符串输出
        if (value instanceof ObjectId) return value.toString();
        if (value instanceof Date) return ((Date) value).toInstant().toString();
        if (value instanceof Decimal128) return value.toString();
        if (value instanceof Map) {
            // 映射：键统一转成字符串、值递归归一化，保证字段名与结构稳定
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) {
                out.put(String.valueOf(e.getKey()), normalizeMongoValue(e.getValue()));
            }
            return out;
        }
        if (value instanceof List) {
            // 数组：逐元素递归归一化，保持原有顺序
            List<Object> out = new ArrayList<>();
            for (Object item : (List<?>) value) out.add(normalizeMongoValue(item));
            return out;
        }
        // 其余未知类型以字符串形式兜底输出
        return String.valueOf(value);
    }

    /** 判定文件类型：支持 TXT/CSV、JSON/NDJSON、Excel，其它扩展名直接拒绝。 */
    public static String fileType(Path path, String configured) {
        // 配置指定的类型，统一小写并去除空白
        String type = configured == null ? "" : configured.trim().toLowerCase();
        // 配置类型属于三种内置类型之一则直接采用
        if (DataSource.JSON.equals(type) || DataSource.XLSX.equals(type) || DataSource.CSV.equals(type)) return type;
        String name = path.getFileName().toString().toLowerCase();
        // 按文件扩展名推断：.json/.jsonl → JSON
        if (name.endsWith(".json") || name.endsWith(".jsonl")) return DataSource.JSON;
        // .xlsx/.xls → Excel
        if (name.endsWith(".xlsx") || name.endsWith(".xls")) return DataSource.XLSX;
        // TXT 和无扩展名文本按 CSV 规则读取
        if (name.endsWith(".txt") || name.endsWith(".csv") || !name.contains(".")) return DataSource.CSV;
        throw new IllegalArgumentException("仅支持 TXT、CSV、JSON、Excel 文件");
    }

    /** 删除下载/导出产生的临时文件及其专属缓存目录(尽力而为，失败忽略)。 */
    public static void deleteTemp(Path path) {
        // null 路径直接返回
        if (path == null) return;
        try {
            // 删除临时文件本身
            Files.deleteIfExists(path);
            Path parent = path.getParent();
            // 父目录若是本工具创建的 el2ck-* 临时目录，则一并删除
            if (parent != null && parent.getFileName().toString().startsWith("el2ck-")) Files.deleteIfExists(parent);
        } catch (IOException ignored) {
            // 临时文件清理失败不应覆盖同步结果
        }
    }

    /** 从配置读取 key 对应的字符串值并去除首尾空白；缺失或为 null 时返回空串。 */
    private static String value(Map<String, Object> cfg, String key) {
        Object v = cfg == null ? null : cfg.get(key);
        return v == null ? "" : String.valueOf(v).trim();
    }

    /** 从配置解析整数(如端口号)；缺失或解析失败时回退到 fallback 默认值。 */
    private static int integer(Map<String, Object> cfg, String key, int fallback) {
        try { return Integer.parseInt(value(cfg, key)); } catch (RuntimeException e) { return fallback; }
    }

    /** 取远程路径最后一段的文件扩展名(含点)作为本地后缀；无扩展名时默认 .csv。 */
    private static String suffix(String path) {
        // 取文件名最后一段
        String name = new File(path).getName();
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot).toLowerCase() : ".csv";
    }

    /** 根据 SFTP 配置格式生成解析后缀；未知格式回退到远程文件后缀。 */
    private static String configuredSuffix(Map<String, Object> cfg, String path) {
        String format = value(cfg, "format").toLowerCase();
        if ("json".equals(format) || "ndjson".equals(format) || "jsonl".equals(format)) {
            return ".jsonl";
        }
        if ("xlsx".equals(format) || "xls".equals(format) || "excel".equals(format)) {
            return ".xlsx";
        }
        if ("csv".equals(format) || "txt".equals(format)) {
            return ".csv";
        }
        return suffix(path);
    }

    /** 拼接 MongoDB 连接串：host 已带协议则原样返回；否则按 mongodb:// 组装并对各段做 URL 编码。 */
    public static String mongoUri(Map<String, Object> cfg) {
        String host = value(cfg, "host");
        // 已显式带协议前缀时不再重复拼接
        if (host.startsWith("mongodb://") || host.startsWith("mongodb+srv://")) return host;
        String user = value(cfg, "username");
        String pwd = value(cfg, "password");
        String database = value(cfg, "database");
        String authSource = value(cfg, "authSource");
        // 用户名非空时拼接 user:password@ 认证前缀
        String auth = user.isEmpty() ? "" : encode(user) + ":" + encode(pwd) + "@";
        String uri = "mongodb://" + auth + host + ":" + integer(cfg, "port", 27017);
        // 指定了库名时作为路径段追加
        if (!database.isEmpty()) uri += "/" + encode(database);
        // 指定了用户名时追加 authSource 参数，缺省指向 admin 库
        if (!user.isEmpty()) uri += "?authSource=" + encode(authSource.isEmpty() ? "admin" : authSource);
        return uri;
    }

    /** 对字符串做 UTF-8 的 URL 编码；编码失败时原样返回。 */
    private static String encode(String value) {
        try { return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8.name()); }
        catch (Exception e) { return value; }
    }
}
