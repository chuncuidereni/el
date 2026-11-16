/**
 * 【文件职责】本文件定义数据源管理模块的业务服务 DataSourceService。承载该模块的核心业务规则，协调仓储、连接器或外部服务，并统一转换业务异常和操作状态。
 */

package com.el2ck.service.datasource;

import com.el2ck.service.table.TableService;

import com.el2ck.exception.ApiException;
import com.el2ck.util.Sql;
import com.el2ck.util.SourceFile;
import com.el2ck.util.RemoteSourceFile;
import com.el2ck.ckpool.CkClient;
import com.el2ck.ckpool.CkConfig;
import com.el2ck.duck.DuckQueryResult;
import com.el2ck.duck.Duck;
import com.el2ck.util.Crypto;
import com.el2ck.entity.common.Column;
import com.el2ck.entity.datasource.CSVPreviewOptions;
import com.el2ck.entity.datasource.CSVPreviewResult;
import com.el2ck.entity.datasource.DataSource;
import com.el2ck.entity.datasource.DataSourceDTO;
import com.el2ck.entity.datasource.DataSourceDetail;
import com.el2ck.entity.datasource.DataSourceInput;
import com.el2ck.entity.datasource.LocalFilePreviewRequest;
import com.el2ck.entity.datasource.SftpBrowseRequest;
import com.el2ck.entity.datasource.SftpBrowseResult;
import com.el2ck.entity.datasource.SftpDirPreview;
import com.el2ck.entity.datasource.SftpDirPreviewRequest;
import com.el2ck.entity.datasource.SftpEntry;
import com.el2ck.entity.datasource.SftpRemotePreviewRequest;
import com.el2ck.util.CsvEncoding;
import com.el2ck.repository.metadata.MetadataRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.bson.Document;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpException;

import java.io.File;
import java.net.URLEncoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;

/**
 * 数据源:同步输入数据源的管理业务逻辑(对应 Go internal/service/datasource_service.go)。
 *
 * 数据源类型:csv / json(本地文件) / sftp / mysql / pgsql / clickhouse / mongodb。
 * 配置(含密码等敏感字段)以整段加密 JSON 存入 SQLite data_sources.config_enc;对外永远只给脱敏 DTO。
 * 本地文件类型用内置 DuckDB 按扩展名 read_csv / read_json 直接解析(字段/预览/计数/存在性),不建库。
 * 远程类型支持连通性、对象/结构探测;实际同步时由同步引擎先下载/导出为本地文件再摄取。
 *
 * 错误语义:校验 400、记录不存在 404、配置加解密失败/内部 500、下游连接与解析失败 502。
 */
public class DataSourceService {

    // 单次上传允许的最大字节数:200MB(200L 左移 20 位即 200×2^20)
    private static final long MAX_UPLOAD = 200L << 20;

    // SQLite 元数据仓库(数据源记录的新增/查询/更新/删除)
    private final MetadataRepository repository;
    // 内置 DuckDB(本地文件 describe/preview/count 解析)
    private final Duck duck;
    // 配置加解密所用的密钥(对称加密)
    private final String secret;
    // 本地上传文件保存的根目录
    private final String uploadsDir;
    private final SourceObservationService observations;
    private final java.util.concurrent.ConcurrentMap<String, MetadataEntry> metadataCache = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long METADATA_TTL_MS = 60_000L;

    /** 独立的 ObjectMapper(不经 Spring 注入,静态持有,供配置 JSON 序列化使用)。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    // 构造注入四类依赖
    /**
     * 创建不启用访问观察器的数据源服务，兼容旧调用和测试装配。
     *
     * @param repository 平台元数据仓库
     * @param duck 本地 DuckDB 查询组件
     * @param secret 配置加解密密钥
     * @param uploadsDir 上传文件根目录
     */
    public DataSourceService(MetadataRepository repository, Duck duck, String secret, String uploadsDir) {
        this(repository, duck, secret, uploadsDir, null);
    }

    /**
     * 创建数据源服务并注入可选的访问观察器。
     *
     * @param repository 平台元数据仓库
     * @param duck 本地 DuckDB 查询组件
     * @param secret 配置加解密密钥
     * @param uploadsDir 上传文件根目录
     * @param observations 访问结果观察器；null 时禁用缓存和状态记录
     */
    public DataSourceService(MetadataRepository repository, Duck duck, String secret, String uploadsDir,
                             SourceObservationService observations) {
        this.repository = repository;
        this.duck = duck;
        this.secret = secret;
        this.uploadsDir = uploadsDir;
        this.observations = observations;
    }

    /** 仅缓存显式元数据请求；每次命中前仍校验归属，保存任务可要求 fresh。 */
    private <T> T metadata(long id,String operation,boolean fresh,com.fasterxml.jackson.core.type.TypeReference<T> type,
                           java.util.function.Supplier<T> read) {
        DataSource source = repository.getDataSource(id);
        if (source == null) throw ApiException.notFound("记录不存在");
        if (observations == null || DataSource.isLocalFile(source.getType()) || DataSource.CK_TABLE.equals(source.getType())
                || DataSource.SFTP.equals(source.getType()))
            return read.get();
        String key = com.el2ck.config.AuthContext.userId()+":"+id+":"+observations.token(source)+":"+operation;
        long now = System.currentTimeMillis();
        metadataCache.entrySet().removeIf(e -> e.getValue().result.isDone() && now-e.getValue().completedAt > METADATA_TTL_MS);
        MetadataEntry previous = metadataCache.get(key);
        if (fresh && previous != null && previous.result.isDone()) metadataCache.remove(key,previous);
        if (metadataCache.size() >= 256 && !metadataCache.containsKey(key))
            return readAndObserve(source,read);
        MetadataEntry entry = new MetadataEntry();
        previous = metadataCache.putIfAbsent(key,entry);
        if (previous == null) {
            try {
                T result = readAndObserve(source,read);
                entry.completedAt = System.currentTimeMillis();
                entry.result.complete(JSON.writeValueAsString(result));
                return result;
            } catch (Throwable e) {
                entry.result.completeExceptionally(e);
                metadataCache.remove(key,entry);
                if (e instanceof Error) throw (Error)e;
                if (e instanceof RuntimeException) throw (RuntimeException)e;
                throw new IllegalStateException("元数据读取失败",e);
            }
        }
        try { return JSON.readValue(previous.result.get(30,TimeUnit.SECONDS),type); }
        catch (InterruptedException e) {Thread.currentThread().interrupt();throw new ApiException(409,"字段读取等待已取消");}
        catch (java.util.concurrent.ExecutionException e) {
            if (e.getCause() instanceof RuntimeException) throw (RuntimeException)e.getCause();
            throw new IllegalStateException("元数据读取失败",e.getCause());
        } catch (Exception e) {throw ApiException.badGateway("元数据仍在读取，请稍后重试");}
    }

    /**
     * 执行一次元数据读取并记录成功或连接类失败。
     *
     * @param source 数据源
     * @param read 实际读取回调
     * @param <T> 读取结果类型
     * @return 回调结果
     * @throws RuntimeException 读取失败时原样抛出，供上层保持既有错误语义
     */
    private <T> T readAndObserve(DataSource source,java.util.function.Supplier<T> read) {
        SourceObservationService.Ticket ticket = observations.begin(source,"metadata");
        try {
            T result = read.get();
            observations.record(ticket,true);
            return result;
        } catch(RuntimeException error) {
            observations.connectionFailure(ticket,error);
            throw error;
        }
    }
    private static final class MetadataEntry {
        final java.util.concurrent.CompletableFuture<String> result = new java.util.concurrent.CompletableFuture<>();
        volatile long completedAt = Long.MAX_VALUE;
    }

    /** 校验、编码规范化并按数据源类型保存上传的文件。 */
    public Map<String, Object> uploadFile(String type, String originalName, long reportedSize, byte[] content) {
        return uploadFile(type, originalName, reportedSize,
                content == null ? null : new java.io.ByteArrayInputStream(content));
    }

    /** 调用方负责关闭输入流；上传、编码校验和转码均使用固定大小缓冲区。 */
    public Map<String, Object> uploadFile(String type, String originalName, long reportedSize, java.io.InputStream content) {
        // 去除路径只保留文件基本名(原始名称为空则取空串)
        String filename = originalName == null ? "" : new File(originalName.replace('\\', '/')).getName();
        // 文件名或内容为空时校验失败
        if (filename.trim().isEmpty() || content == null) {
            throw ApiException.validation("请选择要上传的文件");
        }

        // 统一转小写以判断扩展名(Windows 下扩展名大小写任意,匹配前先归一)
        String lower = filename.toLowerCase(java.util.Locale.ROOT);
        boolean csv = lower.endsWith(".csv") || lower.endsWith(".txt");
        // 判断是否为受支持的文件扩展名(csv/json/ndjson/jsonl/xlsx/xls)
        boolean supported = csv || lower.endsWith(".json") || lower.endsWith(".ndjson")
                || lower.endsWith(".jsonl") || lower.endsWith(".xlsx") || lower.endsWith(".xls");
        // 扩展名不受支持则校验失败
        if (!supported) {
            throw ApiException.validation("仅支持 .txt / .csv / .json / .xlsx / .xls 文件");
        }
        // 上传目录按数据源类型隔离，避免 CSV/JSON/Excel 文件混放。
        // 前端未显式传类型时,由扩展名反推类型(顺序敏感:.json 等判定在 csv 之后,互不重叠)。
        String sourceType = type == null ? "" : type.trim().toLowerCase(java.util.Locale.ROOT);
        if (sourceType.isEmpty()) {
            if (csv) sourceType = DataSource.CSV;
            else if (lower.endsWith(".json") || lower.endsWith(".ndjson") || lower.endsWith(".jsonl"))
                sourceType = DataSource.JSON;
            else if (lower.endsWith(".xlsx") || lower.endsWith(".xls")) sourceType = DataSource.XLSX;
        }
        // 仅本地文件三兄弟允许上传(远程类型靠连接参数,不经此接口)
        if (!DataSource.CSV.equals(sourceType) && !DataSource.JSON.equals(sourceType)
                && !DataSource.XLSX.equals(sourceType)) {
            throw ApiException.validation("上传文件必须指定 CSV、JSON 或 Excel 数据源类型");
        }
        // 类型与扩展名必须互相匹配:否则落盘目录/类型与后续 DuckDB 解析方式会不一致
        if ((DataSource.CSV.equals(sourceType) && !csv)
                || (DataSource.JSON.equals(sourceType) && !(lower.endsWith(".json") || lower.endsWith(".ndjson") || lower.endsWith(".jsonl")))
                || (DataSource.XLSX.equals(sourceType) && !(lower.endsWith(".xlsx") || lower.endsWith(".xls")))) {
            throw ApiException.validation("文件扩展名与数据源类型不匹配");
        }
        // 流读取时还会检查实际字节数，不能只信任客户端上报大小。
        if (reportedSize > MAX_UPLOAD) {
            throw ApiException.validation("文件过大(最大 200MB)");
        }

        // 指向上传根目录,不存在则尝试创建
        File uploadDirectory = new File(userUploadsDir(), sourceType);
        if (!uploadDirectory.exists() && !uploadDirectory.mkdirs()) {
            throw ApiException.server("创建上传目录失败: " + uploadsDir);
        }

        // 用日期时间戳拼唯一前缀
        String prefix = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        String unique = UUID.randomUUID().toString().substring(0, 8);
        // 目标文件 = 上传目录 + 前缀 + 随机串 + 原文件名
        File target = new File(uploadDirectory, prefix + "_" + unique + "_" + filename);
        Path staging = null;
        Path normalized = null;
        long size;
        try {
            staging = Files.createTempFile(uploadDirectory.toPath(), ".upload-", ".part");
            long received = 0;
            try (java.io.OutputStream out = Files.newOutputStream(staging)) {
                byte[] buffer = new byte[64 * 1024];
                int n;
                while ((n = content.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("上传已取消");
                    received += n;
                    if (received > MAX_UPLOAD) throw ApiException.validation("文件过大(最大 200MB)");
                    out.write(buffer, 0, n);
                }
            }
            if (received == 0) throw ApiException.validation("请选择要上传的文件");
            Path completed = staging;
            if (csv) {
                normalized = Files.createTempFile(uploadDirectory.toPath(), ".normalized-", ".part");
                CsvEncoding.normalizeCSV(staging, normalized);
                completed = normalized;
            }
            size = Files.size(completed);
            Files.move(completed, target.toPath());
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            // 写入失败抛 500
            throw ApiException.server("保存文件失败: " + message(e));
        } finally {
            for (Path temporary : new Path[] {staging, normalized}) {
                if (temporary != null) {
                    try { Files.deleteIfExists(temporary); }
                    catch (java.io.IOException e) { System.err.println("清理上传暂存文件失败: " + temporary); }
                }
            }
        }

        // 组装返回:绝对路径(统一用 /)、文件名、实际大小
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", target.getAbsolutePath().replace('\\', '/'));
        result.put("name", filename);
        result.put("size", size);
        return result;
    }

    // =====================================================================
    // CRUD 与 DTO(对应 Go List/Create/Update/Delete/toDTO)
    // =====================================================================

    /** 数据源列表。 */
    public List<DataSourceDTO> list() {
        // 结果收集容器
        List<DataSourceDTO> out = new ArrayList<>();
        // 遍历全部数据源记录,逐条脱敏转换为 DTO
        for (DataSource d : repository.listDataSources()) {
            out.add(toDTO(d));
        }
        return out;
    }

    /** 新增数据源:唯一标识查重,配置整段加密后落库。 */
    public synchronized DataSourceDTO create(DataSourceInput in) {
        if (in == null) {
            throw ApiException.validation("请求内容不能为空");
        }
        // 新增与更新必须使用同一套校验，避免非法 JSON/空路径保存后在同步阶段才失败。
        validateInput(in);
        validateLocalFilePath(in.getType(), in.getConfig());
        validateLocalFileExists(in.getType(), in.getConfig());
        // 按唯一标识查重
        DataSource exist = repository.getDataSourceByCode(in.getCode());
        if (exist != null) {
            throw ApiException.validation(String.format("唯一标识字段 \"%s\" 已存在", in.getCode()));
        }
        // 整段加密类型与配置(无旧密文)
        String schemaWarning = prepareLocalSchema(in, null);
        String enc = encryptConfig(in.getType(), in.getConfig(), null);
        requireUniqueConnection(0, in.getType(), enc);
        DataSource d = new DataSource();
        d.setCode(in.getCode());
        d.setName(in.getName());
        d.setType(in.getType());
        d.setConfigEnc(enc);
        d.setDescription(def(in.getDescription()));
        // 落库并返回新记录对应的 DTO
        d.setGroupId(resolveGroupId(in.getGroupId(), 0));
        long id = repository.createDataSource(d);
        DataSourceDTO result = toDTO(repository.getDataSource(id));
        result.setWarning(schemaWarning);
        return result;
    }

    /** 在凭据恢复后比较实际连接；创建和更新共用同一个写入锁。 */
    private void requireUniqueConnection(long excludedId, String type, String encryptedConfig) {
        com.el2ck.util.DataSourceConnectionIdentity requested =
                com.el2ck.util.DataSourceConnectionIdentity.of(type, decryptRaw(encryptedConfig));
        if (requested == null) return;
        for (DataSource existing : repository.listDataSources()) {
            if (existing.getId() == excludedId || !type.equals(existing.getType())) continue;
            if (requested.conflictsWith(com.el2ck.util.DataSourceConnectionIdentity.of(type, decryptConfig(existing))))
                throw ApiException.validation("数据库已连接");
        }
    }

    /** 更新场景下保留原密码:入参密码为空时,从旧密文回填明文密码。 */
    private void preservePasswordForUpdate(DataSourceInput in, DataSource old) {
        // 取该类型对应的密码字段名
        String field = passwordFieldOf(def(in.getType()));
        // 该类型无密码字段或未携带配置则无需处理
        if (field.isEmpty() || in.getConfig() == null) {
            return;
        }
        // 入参已提供(非空)密码时无需回填
        Object supplied = in.getConfig().get(field);
        if (supplied != null && !String.valueOf(supplied).trim().isEmpty()) {
            return;
        }
        // 解密旧配置
        Map<String, Object> oldCfg = decryptConfig(old);
        Object previous = oldCfg.get(field);
        // 旧密码存在时写回入参配置,供后续加密沿用
        if (previous != null && !String.valueOf(previous).isEmpty()) {
            in.getConfig().put(field, previous);
        }
    }

    /** 更新数据源:密码留空则保持原密文,查重排除自身。 */
    public synchronized DataSourceDTO update(long id, DataSourceInput in) {
        if (in == null) {
            throw ApiException.validation("请求内容不能为空");
        }
        // 按 id 取旧记录,不存在则 404
        DataSource old = repository.getDataSource(id);
        if (old == null) {
            throw ApiException.notFound("记录不存在");
        }
        // 密码留空时先回填旧密码
        preservePasswordForUpdate(in, old);
        // 校验入参
        validateInput(in);
        validateLocalFilePath(in.getType(), in.getConfig());
        validateLocalFileExists(in.getType(), in.getConfig());
        // 唯一标识查重(排除自身),重复则校验失败
        DataSource dup = repository.getDataSourceByCode(in.getCode());
        if (dup != null && dup.getId() != id) {
            throw ApiException.validation(String.format("唯一标识字段 \"%s\" 已存在", in.getCode()));
        }
        // 加密新配置;密码沿用逻辑在 encryptConfig 内部处理
        String schemaWarning = prepareLocalSchema(in, old);
        String enc = encryptConfig(in.getType(), in.getConfig(), old.getConfigEnc());
        requireUniqueConnection(id, in.getType(), enc);
        DataSource d = new DataSource();
        d.setId(id);
        d.setCode(in.getCode());
        d.setName(in.getName());
        d.setType(in.getType());
        d.setConfigEnc(enc);
        d.setDescription(def(in.getDescription()));
        // 更新落库并返回最新 DTO
        if (java.util.Objects.equals(old.getType(), d.getType())
                && decryptConfig(old).equals(decryptConfig(d))) {
            // AES uses a fresh nonce. Preserve ciphertext for unchanged settings so a rename does not retire shares.
            d.setConfigEnc(old.getConfigEnc());
        }
        d.setGroupId(resolveGroupId(in.getGroupId(), old.getGroupId()));
        repository.updateDataSource(d);
        DataSourceDTO result = toDTO(repository.getDataSource(id));
        result.setWarning(schemaWarning);
        return result;
    }

    /** 基线由服务端生成，不能信任客户端回传的快照。类型推断不参与结构比较。 */
    private String prepareLocalSchema(DataSourceInput in, DataSource old) {
        if (!DataSource.isLocalFile(in.getType())) return null;
        Map<String, Object> cfg = new LinkedHashMap<>(in.getConfig());
        cfg.remove("schema_snapshot");
        in.setConfig(cfg);
        Map<String, Object> previous = old != null && DataSource.isLocalFile(old.getType())
                ? decryptConfig(old) : null;
        Object baseline = previous == null ? null : previous.get("schema_snapshot");
        if (duck == null || !duck.isReady()) {
            throw ApiException.validation("文件解析服务尚未就绪，无法校验文件结构，请稍后重试");
        }
        Map<String, Object> next = localSchema(in.getType(), cfg);
        String warning = null;
        if (previous != null) {
            if (!(baseline instanceof Map)) {
                // 老记录尽量从原文件补齐基线；原文件已丢失时明确提示人工核对。
                validateLocalFilePath(old.getType(), previous);
                if (new File(cfgStr(previous, "path", "")).isFile()) {
                    baseline = localSchema(old.getType(), previous);
                } else {
                    warning = "原文件已不存在，无法比对旧结构，请核对字段映射";
                }
            }
            if (baseline instanceof Map) {
                Map<?, ?> before = (Map<?, ?>) baseline;
                Object count = before.get("colCount");
                if (count instanceof Number && ((Number) count).intValue() != ((Number) next.get("colCount")).intValue()) {
                    throw ApiException.validation("新文件列数与原文件不一致，字段映射将失效；请换回原文件或新建数据源");
                }
                if (!java.util.Objects.equals(before.get("columns"), next.get("columns"))
                        || !java.util.Objects.equals(before.get("raw"), next.get("raw"))
                        || !java.util.Objects.equals(before.get("header"), next.get("header"))) {
                    warning = "新文件列名、列顺序或表头设置与原文件不一致，已刷新结构基线，请核对字段映射";
                }
            }
        }
        cfg.put("schema_snapshot", next);
        return warning;
    }

    /**
     * 解析本地文件的数据结构快照。
     *
     * @param type 本地文件类型
     * @param cfg 已解密配置
     * @return 文件结构元数据
     */
    private Map<String, Object> localSchema(String type, Map<String, Object> cfg) {
        try {
            String path = SourceFile.readableCsv(duck, type, cfg);
            boolean header = headerFromConfig(cfg);
            List<Column> raw = duck.describeFile(path, header, null, SourceFile.delimiter(cfg));
            List<String> configuredNames = cfgNames(cfg);
            List<Column> columns = configuredNames == null ? raw
                    : duck.describeFile(path, header, configuredNames, SourceFile.delimiter(cfg));
            if (columns.isEmpty() || raw.isEmpty()) throw new IllegalStateException("文件没有可识别字段");
            Map<String, Object> snapshot = new LinkedHashMap<>();
            List<String> names = new ArrayList<>();
            List<String> rawNames = new ArrayList<>();
            for (Column column : columns) names.add(column.getName());
            for (Column column : raw) rawNames.add(column.getName());
            snapshot.put("header", header);
            snapshot.put("columns", names);
            snapshot.put("raw", rawNames);
            snapshot.put("colCount", raw.size());
            return snapshot;
        } catch (RuntimeException e) {
            throw ApiException.validation("读取文件结构失败，请检查文件和解析设置: " + message(e));
        }
    }

    /**
     * 删除数据源。本地文件类型(CSV/JSON)且文件位于上传目录、又无其它数据源引用时,
     * 顺带删除该文件(清理失败仅记录日志,不阻塞数据源删除)。
     */
    public void delete(long id) {
        // 按 id 取记录,不存在则 404
        DataSource d = repository.getDataSource(id);
        if (d == null) {
            throw ApiException.notFound("记录不存在");
        }
        // 先删除数据源记录本身
        repository.deleteDataSource(id);
        if (observations != null) observations.remove(id);
        // 仅本地文件类型且上传目录已配置时才尝试清理文件
        if (DataSource.isLocalFile(d.getType()) && uploadsDir != null && !uploadsDir.isEmpty()) {
            try {
                // 解密配置取得文件路径
                Map<String, Object> cfg = decryptConfig(d);
                String path = cfgStr(cfg, "path", "");
                // 路径非空、位于上传目录内且未被其它数据源引用时才删除
                if (!path.isEmpty() && isInsideUploads(userUploadsDir(), path) && !fileReferencedByOthers(id, path)) {
                    File f = new File(path);
                    if (f.exists() && !f.delete()) {
                        // 文件存在但删除失败,仅记录日志不抛错
                        System.err.println("数据源 \"" + d.getCode() + "\"(id=" + id + ")已删除,但文件清理失败: " + path);
                    }
                }
            } catch (RuntimeException ignored) {
                // 配置解不出密文则跳过文件清理,不影响数据源删除。
            }
        }
    }

    /** 组装对外 DTO:解密 config 并把密码字段替换为 hasPassword 布尔值。 */
    private DataSourceDTO toDTO(DataSource d) {
        // 解密结果配置容器
        Map<String, Object> cfg = new LinkedHashMap<>();
        boolean hasPwd = false;
        // 取该类型密码字段名
        String pwdField = passwordFieldOf(d.getType());
        // 存在密文配置时先解密回填
        if (d.getConfigEnc() != null && !d.getConfigEnc().isEmpty()) {
            try {
                Map<String, Object> dec = decryptConfig(d);
                cfg.putAll(dec);
            } catch (RuntimeException e) {
                throw ApiException.server(e.getMessage());
            }
            // 该类型有密码字段且解密出非空密码时,置 hasPwd 并从配置中移除明文
            if (!pwdField.isEmpty()) {
                Object p = cfg.get(pwdField);
                if (p instanceof String && !((String) p).isEmpty()) {
                    hasPwd = true;
                    cfg.remove(pwdField);
                }
            }
            // MongoDB 的 host 可能是整条连接串,口令藏在 URI 里而非 password 字段:
            // 只删 password 会让口令原样下发,这里统一掩码并据此置 hasPassword。
            if (DataSource.MONGODB.equals(d.getType())) {
                Object host = cfg.get("host");
                if (host instanceof String) {
                    String[] r = maskUriCredentials((String) host);
                    if (!r[1].isEmpty()) {
                        cfg.put("host", r[0]);
                        hasPwd = true;
                    }
                }
            }
        }
        DataSourceDTO dto = new DataSourceDTO();
        Map<String, Object> owner = repository.dataSourceOwner(d.getId());
        dto.setOwnerUserId(((Number) owner.get("ownerUserId")).longValue());
        dto.setOwnerUsername((String) owner.get("ownerUsername"));
        dto.setId(d.getId());
        dto.setGroupId(d.getGroupId());
        dto.setCode(d.getCode());
        dto.setName(d.getName());
        dto.setType(d.getType());
        dto.setDescription(d.getDescription());
        dto.setHasPassword(hasPwd);
        dto.setConfig(cfg);
        dto.setCreatedAt(d.getCreatedAt());
        dto.setUpdatedAt(d.getUpdatedAt());
        return dto;
    }

    // =====================================================================
    // 配置加密/解密/工具
    // =====================================================================

    /** 各类型配置里的密码字段名(用于加密与脱敏)。 */
    static String passwordFieldOf(String dsType) {
        switch (dsType) {
            case DataSource.KAFKA:
            case DataSource.HIVE:
            case DataSource.SFTP:
            case DataSource.MYSQL:
            case DataSource.PGSQL:
            case DataSource.CLICKHOUSE:
            case DataSource.MONGODB:
                // 远程/数据库类型统一用 "password" 字段
                return "password";
            default:
                return ""; // csv/json 等本地文件类型无密码字段
        }
    }

    /** 掩码后留在连接串里的凭据片段(移植 Go maskedCredMarker)。 */
    static final String MASKED_CRED_MARKER = "***:***@";

    /**
     * 掩码 URI(如 mongodb://user:pass@host:port/db)里的 user:pass@ 凭据。移植 Go dsMaskURICredentials。
     *
     * <p>为什么需要它:mongodb 数据源的 host 允许直接写整条连接串,口令在 URI 里而不在 password 字段——
     * 脱敏只删 password 时,这条串会带着明文口令从创建响应和 detail 接口下发。
     *
     * @return {@code [maskedUri, hasCred]};hasCred 为 "1" 表示原串带凭据(调用方据此置 hasPassword),否则为 ""。
     */
    static String[] maskUriCredentials(String uri) {
        if (uri == null) {
            return new String[]{"", ""};
        }
        int sep = uri.indexOf("://");
        if (sep < 0) {
            return new String[]{uri, ""};
        }
        String rest = uri.substring(sep + 3);
        // 凭据只可能在 authority 段(首个 '/' 之前);其后是库名与查询参数
        String authority = rest;
        String tail = "";
        int slash = rest.indexOf('/');
        if (slash >= 0) {
            authority = rest.substring(0, slash);
            tail = rest.substring(slash);
        }
        // 取最后一个 '@':口令里未转义的 '@' 也算作凭据的一部分
        int at = authority.lastIndexOf('@');
        if (at <= 0) { // 无 userinfo(或 mongodb://@host 这种空 userinfo)
            return new String[]{uri, ""};
        }
        return new String[]{uri.substring(0, sep + 3) + MASKED_CRED_MARKER + authority.substring(at + 1) + tail, "1"};
    }

    /**
     * 本地文件数据源(csv/json/xlsx)的 path 必须落在【上传目录】内。移植 Go validateLocalFilePath。
     *
     * <p>不加这道守卫,预览/校验/保存就能以任意服务器路径为 path 读取或探测文件系统。
     */
    private void validateLocalFilePath(String type, Map<String, Object> cfg) {
        if (!DataSource.isLocalFile(type) || cfg == null) {
            return;
        }
        if (uploadsDir == null || uploadsDir.isEmpty()) {
            return;
        }
        String path = cfgStr(cfg, "path", "").trim();
        if (path.isEmpty()) {
            return; // 路径必填由 validateDataSourceConfig 负责,这里只管越界
        }
        requireUploadPath(path);
    }

    /** 读取请求指定的本地文件前统一校验路径，避免预览、测试和字段探测绕过保存时的护栏。 */
    private void requireUploadPath(String path) {
        if (isInsideUploads(userUploadsDir(), path)) return;
        // A migrated file remains at its old location, but only an exact owned source path is accepted.
        if (isInsideUploads(uploadsDir, path)) {
            for (DataSource d : repository.listDataSources()) {
                if (!DataSource.isLocalFile(d.getType())) continue;
                try {
                    String saved = cfgStr(decryptConfig(d), "path", "");
                    if (!saved.isEmpty() && new File(path).getCanonicalFile().equals(new File(saved).getCanonicalFile())) return;
                } catch (java.io.IOException ignored) { }
            }
        }
        throw ApiException.validation("文件不属于当前用户");
    }

    /** Recheck saved local file boundaries before a background job reads the source. */
    public void authorizeExecutionFile(DataSource source) {
        if(DataSource.isLocalFile(source.getType())) {
            repository.getDataSource(source.getId());
            requireUploadPath(cfgStr(decryptConfig(source),"path",""));
        }
    }

    /**
     * 计算当前用户的隔离上传目录，并确保目录存在。
     *
     * @return 当前用户上传目录的绝对路径
     */
    private String userUploadsDir() {
        return new File(uploadsDir, "user_" + com.el2ck.config.AuthContext.userId()).getPath();
    }

    /**
     * 入参 config 以整段 JSON 加密。更新时(oldConfigEnc 非空)且新密码为空时,沿用旧密码。
     */
    private String encryptConfig(String dsType, Map<String, Object> in, String oldConfigEnc) {
        // 复制入参配置到新的可变 map
        Map<String, Object> cfg = new LinkedHashMap<>();
        if (in != null) {
            cfg.putAll(in);
        }
        // 取该类型密码字段名
        String pwdField = passwordFieldOf(dsType);
        // 该类型有密码字段时,处理密码为空的情况
        if (!pwdField.isEmpty()) {
            Object p = cfg.get(pwdField);
            String pwd = p instanceof String ? (String) p : "";
            // 新密码为空且传入了旧密文时,尝试沿用旧密码
            if (pwd.isEmpty() && oldConfigEnc != null && !oldConfigEnc.isEmpty()) {
                // 保持原密码:解密旧配置取出其密码字段回填。
                try {
                    Map<String, Object> oldCfg = decryptRaw(oldConfigEnc);
                    Object oldPwd = oldCfg.get(pwdField);
                    if (oldPwd instanceof String) {
                        cfg.put(pwdField, oldPwd);
                    }
                } catch (RuntimeException ignored) {
                    // 旧配置无法解密则沿用空密码,不阻塞保存。
                }
            }
        }
        // MongoDB 的凭据可能内嵌在 host 连接串里,对外 DTO 已把它掩码成 ***:***@。
        // 客户端若把掩码值原样提交,存下去等于把真实口令抹成字面量 ***:***@,该数据源之后一律
        // 认证失败且不可恢复。三种情况分别对待:没给 / 就是掩码回显 → 沿用旧值;其余仍带掩码 → 报错。
        if (DataSource.MONGODB.equals(dsType)) {
            String host = cfg.get("host") instanceof String ? (String) cfg.get("host") : "";
            String oldHost = "";
            if (oldConfigEnc != null && !oldConfigEnc.isEmpty()) {
                try {
                    Object oh = decryptRaw(oldConfigEnc).get("host");
                    oldHost = oh instanceof String ? (String) oh : "";
                } catch (RuntimeException ignored) {
                    // 旧配置解不开则按无旧值处理。
                }
            }
            String[] maskedOld = maskUriCredentials(oldHost);
            if (host.isEmpty() && !oldHost.isEmpty()) {
                cfg.put("host", oldHost);
            } else if (!maskedOld[1].isEmpty() && host.equals(maskedOld[0])) {
                cfg.put("host", oldHost);
            } else if (host.contains(MASKED_CRED_MARKER)) {
                throw ApiException.validation("MongoDB 连接串里含掩码凭据(" + MASKED_CRED_MARKER
                        + "),请重新填写完整连接串(口令不能留掩码回显值)");
            }
        }
        // 整体序列化为 JSON 后,用密钥加密得到密文
        String raw = toJson(cfg);
        return Crypto.encrypt(secret, raw);
    }

    /** 解出某数据源的明文配置(密文为空时返回空 map)。 */
    public Map<String, Object> decryptConfig(DataSource d) {
        // 直接委托 decryptRaw 解密该数据源的密文字段
        return decryptRaw(d.getConfigEnc());
    }

    /** 解密一段密文配置为明文 map。 */
    private Map<String, Object> decryptRaw(String configEnc) {
        // 密文为空返回空 map
        if (configEnc == null || configEnc.isEmpty()) {
            return new LinkedHashMap<>();
        }
        try {
            // 用密钥解密出 JSON 明文
            String raw = Crypto.decrypt(secret, configEnc);
            // 反序列化为 map
            Map<String, Object> m = JSON.readValue(raw, Map.class);
            // 解析结果为空时兜底空 map
            return m == null ? new LinkedHashMap<String, Object>() : m;
        } catch (Exception e) {
            // 解密或反序列化失败抛 500
            throw ApiException.server("数据源配置解密失败: " + Sql.messageOf(e));
        }
    }

    /** 将配置 map 序列化为 JSON 字符串。 */
    private static String toJson(Map<String, Object> cfg) {
        try {
            return JSON.writeValueAsString(cfg);
        } catch (Exception e) {
            throw ApiException.server("数据源配置序列化失败: " + e.getMessage());
        }
    }

    /**
     * 由「数据源配置 Map」组装 ClickHouse 配置 —— 数据源侧的统一入口。
     *
     * <p>与 {@link com.el2ck.service.connection.ConnectionService#fromConnection} 的区别是:
     * 这里面对的是**未经落库、字段名宽松的 Map**(来自数据源配置),没有全局设置可回退,
     * 故各字段直接用内置缺省补齐。原先 {@link DbSource#ckConfig} 与本类的
     * {@code ckConfigFrom} 各写了一份,两处仅查询超时不同,合并为此方法并参数化超时。
     *
     * @param cfg            数据源配置
     * @param defaultDb      未配置 database 时的默认库
     * @param queryTimeoutMs 查询超时(读取用较长、探测用较短,故由调用方决定)
     */
    public static CkConfig fromCkMap(Map<String, Object> cfg, String defaultDb, long queryTimeoutMs) {
        CkConfig ck = new CkConfig();
        ck.setHost(cfgStr(cfg, "host", "127.0.0.1"));
        String protocol = CkConfig.normalizeProtocol(cfgStr(cfg, "protocol", "native"));
        int port = cfgInt(cfg, "port", 0);
        if (port <= 0) {
            port = CkConfig.defaultPort(protocol);
        }
        try {
            CkConfig.requireCompatibleProtocolPort(protocol, port);
        } catch (IllegalArgumentException e) {
            throw ApiException.validation(e.getMessage());
        }
        ck.setPort(port);
        ck.setProtocol(protocol);
        ck.setDatabase(cfgStr(cfg, "database", defaultDb));
        ck.setUsername(cfgStr(cfg, "username", "default"));
        ck.setPassword(cfgStr(cfg, "password", ""));
        // secure 需显式为 "true" 才启用 TLS
        ck.setSecure(Boolean.TRUE.equals(cfg.get("secure")) || "true".equals(cfgStr(cfg, "secure", "false")));
        ck.setDialTimeoutMs(5000L);
        ck.setQueryTimeoutMs(queryTimeoutMs);
        // 连接最大存活 30 分钟(与 CkClient.open 的缺省兜底一致,显式写出便于阅读)
        ck.setConnMaxLifetimeMs(30L * 60_000L);
        return ck;
    }

    /** 读取配置字符串字段(缺失或空时返回默认值)。 */
    static String cfgStr(Map<String, Object> cfg, String key, String def) {        // 取原始值
        Object v = cfg.get(key);
        if (v instanceof String && !((String) v).isEmpty()) {
            return (String) v;
        }
        return def;
    }

    /** 读取配置整数字段(兼容 double/int/long/string;缺省返回默认值)。 */
    static int cfgInt(Map<String, Object> cfg, String key, int def) {
        // 取原始值
        Object v = cfg.get(key);
        if (v instanceof Number) {
            return ((Number) v).intValue();
        }
        if (v instanceof String) {
            try {
                // 字符串转整数
                return Integer.parseInt(((String) v).trim());
            } catch (NumberFormatException e) {
                return def;
            }
        }
        return def;
    }

    /** 从 config 中读取手动列名映射(支持逗号分隔字符串或 JSON 数组)。 */
    static List<String> cfgNames(Map<String, Object> cfg) {
        Object raw = cfg.get("names");
        if (raw == null) {
            return null;
        }
        // 解析结果收集容器
        List<String> out = new ArrayList<>();
        if (raw instanceof String) {
            String s = ((String) raw).trim();
            if (!s.isEmpty()) {
                // 逗号分隔逐段裁剪
                for (String p : s.split(",")) {
                    String t = p.trim();
                    if (!t.isEmpty()) {
                        out.add(t);
                    }
                }
            }
            return out.isEmpty() ? null : out;
        }
        if (raw instanceof List) {
            // JSON 数组:收集其中非空字符串元素
            for (Object it : (List<?>) raw) {
                if (it instanceof String && !((String) it).isEmpty()) {
                    out.add((String) it);
                }
            }
            return out.isEmpty() ? null : out;
        }
        return null;
    }

    /** 组装文件读取选项:header 布尔值(config 缺省 true)。 */
    private static boolean headerFromConfig(Map<String, Object> cfg) {
        Object h = cfg.get("header");
        return h instanceof Boolean ? (Boolean) h : true;
    }

    /** 仅含 path 的临时配置(Excel 转 CSV / 探测用,不落库)。 */
    private static Map<String, Object> cfgWithPath(String path) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("path", path);
        return m;
    }

    // =====================================================================
    // 入参校验(对应 Go validateDataSourceInput / validateDataSourceConfig)
    // =====================================================================

    /** 校验数据源入参:trim code/name 后检查唯一标识/名称合法性与类型支持,再按类型校验配置必填字段。 */
    private long resolveGroupId(Long requested, long previous) {
        long groupId = requested == null ? previous : requested;
        if (groupId < 0) {
            throw ApiException.validation("分组 ID 不能为负数");
        }
        if (groupId != 0 && !repository.dataSourceGroupExists(groupId)) {
            throw ApiException.validation("数据源分组不存在");
        }
        return groupId;
    }

    /**
     * 校验数据源名称、类型和各类配置的公共约束。
     *
     * @param in 创建或更新参数
     * @throws com.el2ck.exception.ApiException 参数缺失、类型不支持或配置非法时抛出 400
     */
    private void validateInput(DataSourceInput in) {
        // 唯一标识去除首尾空白
        if (in.getCode() != null) {
            in.setCode(in.getCode().trim());
        }
        // 名称去除首尾空白
        if (in.getName() != null) {
            in.setName(in.getName().trim());
        }
        // 唯一标识非空校验
        if (in.getCode() == null || in.getCode().isEmpty()) {
            throw ApiException.validation("唯一标识字段不能为空");
        }
        // 唯一标识须为合法标识符
        if (!Sql.validIdent(in.getCode())) {
            throw ApiException.validation("唯一标识字段仅允许字母/数字/下划线,且不能以数字开头");
        }
        // 名称非空校验
        if (in.getName() == null || in.getName().isEmpty()) {
            throw ApiException.validation("名称不能为空");
        }
        String type = def(in.getType());
        // 类型须受支持
        if (!DataSource.validType(type)) {
            throw ApiException.validation(String.format("不支持的数据源类型 \"%s\"", type));
        }
        // 按类型继续校验配置必填字段
        validateDataSourceConfig(type, in.getConfig());
    }

    /** 按类型校验配置的必填字段。 */
    private static void validateDataSourceConfig(String type, Map<String, Object> cfg) {
        // 配置为空时兜底为空 map
        if (cfg == null) {
            cfg = new LinkedHashMap<>();
        }
        if (BigDataSource.supports(type)) {
            BigDataSource.validate(type, cfg);
            return;
        }
        if (DataSource.SFTP.equals(type) || DataSource.MYSQL.equals(type)
                || DataSource.PGSQL.equals(type) || DataSource.CLICKHOUSE.equals(type)
                || DataSource.MONGODB.equals(type)) {
            String host = cfgStr(cfg, "host", "").trim();
            // Mongo 的 host 允许直接填整段连接串(mongodb:// / mongodb+srv://):
            // 此时端口与主机由 URI 自带,跳过端口校验,否则会对 "mongodb://..." 误判为非法端口。
            if (!(DataSource.MONGODB.equals(type)
                    && (host.startsWith("mongodb://") || host.startsWith("mongodb+srv://")))) {
                int port = cfgInt(cfg, "port", 0);
                if (DataSource.CLICKHOUSE.equals(type)) {
                    String protocol = CkConfig.normalizeProtocol(cfgStr(cfg, "protocol", "native"));
                    if (port <= 0) {
                        port = CkConfig.defaultPort(protocol);
                    }
                    try {
                        CkConfig.requireCompatibleProtocolPort(protocol, port);
                    } catch (IllegalArgumentException e) {
                        throw ApiException.validation(e.getMessage());
                    }
                } else if (port < 1 || port > 65535) {
                    // 非 CK 类型仍要求显式端口；CK 的缺省值由协议推导。
                    throw ApiException.validation("port must be between 1 and 65535");
                }
            }
        }
        switch (type) {
            case DataSource.CSV:
            case DataSource.JSON:
            case DataSource.XLSX:
                // 本地文件类型:文件路径必填
                req(cfg, "path", "文件路径");
                return;
            case DataSource.SFTP:
                // SFTP:主机/用户名/密码必填
                req(cfg, "host", "主机");
                req(cfg, "username", "用户名");
                req(cfg, "password", "密码");
                return;
            case DataSource.MYSQL:
            case DataSource.PGSQL:
            case DataSource.CLICKHOUSE:
                // SQL 库源:主机/用户名/密码/数据库必填
                req(cfg, "host", "主机");
                req(cfg, "username", "用户名");
                req(cfg, "password", "密码");
                req(cfg, "database", "数据库");
                return;
            case DataSource.MONGODB:
                // Mongo:host 已带完整连接串前缀时视为内嵌 URI,免校验
                String h = cfgStr(cfg, "host", "");
                if (h.startsWith("mongodb://") || h.startsWith("mongodb+srv://")) {
                    return;
                }
                req(cfg, "host", "主机");
                req(cfg, "database", "数据库");
                return;
            case DataSource.CK_TABLE:
                req(cfg, "database", "源数据库");
                req(cfg, "table", "源表");
                if (!Sql.validIdent(cfgStr(cfg, "database", ""))
                        || !Sql.validIdent(cfgStr(cfg, "table", ""))) {
                    throw ApiException.validation("源库/源表名非法");
                }
                return;
            default:
        }
    }

    /** 校验单个必填字段:值缺失(空或 nil)时报"label不能为空"。 */
    private static void req(Map<String, Object> cfg, String key, String label) {
        // 取字段值并去除空白,空串与 "<nil>" 视为缺失
        String v = cfg.get(key) == null ? "" : String.valueOf(cfg.get(key)).trim();
        if (v.isEmpty() || "<nil>".equals(v)) {
            throw ApiException.validation(label + "不能为空");
        }
    }

    // =====================================================================
    // 本地文件路径清理辅助
    // =====================================================================

    /** 判断 path 是否位于上传目录内(规范化后比较,防路径穿越)。 */
    private static boolean isInsideUploads(String uploadsDir, String path) {
        try {
            // 基准与目标各自取规范化绝对文件
            File base = new File(uploadsDir).getCanonicalFile();
            File target = new File(path).getCanonicalFile();
            String basePath = base.getPath();
            String targetPath = target.getPath();
            // 目标即目录本身视为在范围内
            if (targetPath.equals(basePath)) {
                return true;
            }
            // 以"目录路径 + 分隔符"开头即位于其下
            return targetPath.startsWith(basePath + File.separator);
        } catch (Exception e) {
            return false;
        }
    }

    /** 检查除 id 外的其它本地数据源是否引用同一文件(查询失败时宁保留文件)。 */
    private boolean fileReferencedByOthers(long id, String path) {
        List<DataSource> list;
        try {
            list = repository.listDataSources();
        } catch (RuntimeException e) {
            // 列表查询失败时保守返回 true,避免误删文件
            return true;
        }
        // 遍历全部数据源逐个比对路径
        for (DataSource ds : list) {
            // 跳过自身与非本地文件类型
            if (ds.getId() == id || !DataSource.isLocalFile(ds.getType())) {
                continue;
            }
            try {
                // 解密该数据源配置并比对 path
                Map<String, Object> cfg = decryptConfig(ds);
                if (path.equals(cfgStr(cfg, "path", ""))) {
                    return true;
                }
            } catch (RuntimeException ignored) {
                // 跳过解密失败的其它数据源。
            }
        }
        return false;
    }

    // =====================================================================
    // 连通性测试 / 对象存在性(对应 Go TestByID/TestConfig/CheckObject)
    // =====================================================================

    /** 测试已保存数据源(id)的连通性。 */
    public void testById(long id) {
        // 按 id 取记录
        DataSource d = repository.getDataSource(id);
        if (d == null) {
            throw ApiException.notFound("记录不存在");
        }
        // 解密配置后按其类型做连通性测试
        Map<String,Object> config = decryptConfig(d);
        if (observations == null) testDataSource(d.getType(), config);
        else observations.test(id,d.getType(),config,() -> testDataSource(d.getType(),config),observations.begin(d,"manual"));
    }

    /** 测试尚未保存的配置。 */
    public void testConfig(DataSourceInput in) {
        if (in == null) {
            throw ApiException.validation("请求内容不能为空");
        }
        // 类型须受支持
        String type = def(in.getType());
        if (!DataSource.validType(type)) {
            throw ApiException.validation(String.format("不支持的数据源类型 \"%s\"", type));
        }
        // 配置为空时兜底为空 map
        Map<String, Object> config = in.getConfig() == null
                ? new LinkedHashMap<String, Object>() : in.getConfig();
        // 校验必填配置后做连通性测试
        validateDataSourceConfig(type, config);
        if (observations == null) testDataSource(type, config);
        else observations.test(0,type,config,() -> testDataSource(type,config),null);
    }

    /** 测试编辑表单中的配置;密码留空时仅在测试期间沿用已保存密码。 */
    public void testConfig(long id, DataSourceInput in) {
        if (in == null) {
            throw ApiException.validation("请求内容不能为空");
        }
        // 取旧记录用于回填密码
        DataSource old = repository.getDataSource(id);
        if (old == null) {
            throw ApiException.notFound("记录不存在");
        }
        // 未传配置时兜底为空 map
        if (in.getConfig() == null) {
            in.setConfig(new LinkedHashMap<String, Object>());
        }
        // 测试期间沿用已保存密码,再走无 id 的校验/测试流程
        preservePasswordForUpdate(in, old);
        String type = def(in.getType());
        if (!DataSource.validType(type)) throw ApiException.validation("不支持的数据源类型");
        validateDataSourceConfig(type,in.getConfig());
        if (observations == null) testDataSource(type,in.getConfig());
        else {
            // 测试尚未保存的修改，不能污染已保存连接的状态。
            boolean same = observations.token(old).equals(observations.token(type,in.getConfig()));
            observations.test(id,type,in.getConfig(),() -> testDataSource(type,in.getConfig()),
                    same ? observations.begin(old,"manual") : null);
        }
    }

    /** 检查表/文件/集合是否存在(csv 校验文件本身)。 */
    public boolean checkObject(long id, String object) {
        // 按 id 取记录
        DataSource d = repository.getDataSource(id);
        if (d == null) {
            throw ApiException.notFound("记录不存在");
        }
        // 对象名去除首尾空白
        object = object == null ? "" : object.trim();
        if (object.isEmpty()) {
            throw ApiException.validation("检查对象不能为空");
        }
        // 解密配置后按类型检查对象是否存在
        return checkObjectExists(d.getType(), decryptConfig(d), object);
    }

    // =====================================================================
    // 库源表结构 / 同步任务字段映射
    // =====================================================================

    /** 仅读取文件结构以还原默认字段注释，不做 countFile，避免新建任务时多扫一次大文件。 */
    public Map<String, String> fileColumnComments(long id) {
        DataSource source = repository.getDataSource(id);
        if (source == null) throw ApiException.notFound("数据源不存在");
        if (!DataSource.isLocalFile(source.getType())) return java.util.Collections.emptyMap();
        Map<String, Object> cfg = decryptConfig(source);
        boolean header = headerFromConfig(cfg);
        if (!header && !DataSource.JSON.equals(source.getType())) return java.util.Collections.emptyMap();
        String path = cfgStr(cfg, "path", "");
        requireUploadPath(path);
        String readable = SourceFile.readableCsv(duck, source.getType(), cfg);
        String delimiter = SourceFile.delimiter(cfg);
        List<Column> columns = duck.describeFile(readable, header, cfgNames(cfg), delimiter);
        List<Column> raw = duck.describeFile(readable, header, null, delimiter);
        return SourceFile.columnComments(columns, raw);
    }

    /**
     * 远程 SQL 库源(clickhouse/mysql/pgsql)某源表的列结构(DESCRIBE / SELECT * LIMIT 0)。
     * 供同步任务向导读取源列做字段映射;非库源类型或源表不可读时报错。
     */
    public List<Column> sourceColumns(long id, String object) {
        return sourceColumns(id,object,false);
    }

    /**
     * 读取任意受支持数据源的字段，并可按请求绕过元数据缓存。
     *
     * @param id 数据源 ID
     * @param object 表、Topic、集合或文件对象名
     * @param fresh true 表示强制重新读取远端元数据
     * @return 字段名与类型列表
     */
    public List<Column> sourceColumns(long id,String object,boolean fresh) {
        return metadata(id,"columns:"+def(object),fresh,new com.fasterxml.jackson.core.type.TypeReference<List<Column>>() {},
                () -> readSourceColumns(id,object));
    }

    /**
     * 按数据源类型分派字段读取实现。
     *
     * @param id 数据源 ID
     * @param object 待读取对象名
     * @return 字段列表
     */
    private List<Column> readSourceColumns(long id, String object) {
        // 按 id 取记录
        DataSource d = repository.getDataSource(id);
        if (d == null) {
            throw ApiException.notFound("记录不存在");
        }
        if (DataSource.MONGODB.equals(d.getType())) {
            return mongoColumns(decryptConfig(d), object);
        }
        if (DataSource.HIVE.equals(d.getType())) return BigDataSource.columns(decryptConfig(d), object);
        if (DataSource.KAFKA.equals(d.getType())) return KafkaSnapshot.columns(decryptConfig(d), object);
        // 本地 CSV/JSON/Excel：同步任务向导也必须走与预览一致的 DuckDB 解析链路，
        // 否则会误落入 SQL 数据源分支，导致 JSON/Excel 无法生成默认字段映射。
        if (DataSource.isLocalFile(d.getType())) {
            Map<String, Object> cfg = decryptConfig(d);
            String configuredPath = cfgStr(cfg, "path", "").trim();
            String requestedPath = object == null ? "" : object.trim();
            // 向导显式传入的对象路径优先;未传则退回数据源配置里的源路径
            String path = requestedPath.isEmpty() ? configuredPath : requestedPath;
            if (path.isEmpty()) {
                throw ApiException.validation("本地文件路径不能为空");
            }
            File file = new File(path);
            // 请求的文件不是普通文件(如向导按旧配置询问)时,回退到配置路径解析
            if (!file.isFile() && !configuredPath.isEmpty()) {
                file = new File(configuredPath);
                path = configuredPath;
            }
            requireUploadPath(path);
            if (!file.isFile()) {
                throw ApiException.badGateway("文件不存在或不是普通文件: " + path);
            }
            try {
                Map<String, Object> local = new LinkedHashMap<>(cfg);
                local.put("path", file.getPath());
                String readable = SourceFile.readableCsv(duck, d.getType(), local);
                return duck.describeFile(readable, headerFromConfig(local), cfgNames(local), SourceFile.delimiter(local));
            } catch (RuntimeException e) {
                throw ApiException.badGateway("读取本地文件字段失败: " + Sql.messageOf(e));
            }
        }
        // SFTP:先下载到临时本地文件再解析列；MongoDB 已由上面的专用采样逻辑处理。
        if (DataSource.SFTP.equals(d.getType())) {
            // 解密配置
            Map<String, Object> cfg = decryptConfig(d);
            // 请求对象名去除空白
            String requestedObject = object == null ? "" : object.trim();
            java.nio.file.Path path = null;
            try {
                Map<String, Object> sftpCfg = new LinkedHashMap<>(cfg);
                if (!requestedObject.isEmpty()) sftpCfg.put("remotePath", requestedObject);
                path = RemoteSourceFile.downloadSftp(sftpCfg);
                // 在配置副本中把 path 覆盖为本地临时文件
                Map<String, Object> local = new LinkedHashMap<>(cfg);
                local.put("path", path.toString());
                // 按远程文件扩展名/format 推断文件类型
                String fileType = RemoteSourceFile.fileType(path, cfgStr(cfg, "format", ""));
                // 得到可解析路径并交由 DuckDB 描述列结构
                String readable = SourceFile.readableCsv(duck, fileType, local);
                return duck.describeFile(readable, SourceFile.header(local), SourceFile.names(local), SourceFile.delimiter(local));
            } catch (RuntimeException e) {
                throw ApiException.badGateway(Sql.messageOf(e));
            } finally {
                // 无论成功与否都删除临时文件
                RemoteSourceFile.deleteTemp(path);
            }
        }
        // 非 SQL 库源类型不支持读取源表结构
        if (!DbSource.isSqlSource(d.getType())) {
            throw ApiException.validation("仅 clickhouse/mysql/pgsql 库源可读取源表结构");
        }
        // 源表名必填
        String obj = object == null ? "" : object.trim();
        if (obj.isEmpty()) {
            throw ApiException.validation("请填写源表名(sourceObject)");
        }
        try {
            // 经 DbSource 分发驱动执行 DESCRIBE / SELECT * LIMIT 0 得到列结构
            return DbSource.describe(DbSource.engineOf(d.getType()), decryptConfig(d), obj);
        } catch (RuntimeException e) {
            throw ApiException.badGateway(Sql.messageOf(e));
        }
    }

    /** 本地文件数据源保存前确认文件存在且扩展名与类型一致。 */
    private static void validateLocalFileExists(String type, Map<String, Object> cfg) {
        if (!DataSource.isLocalFile(type) || cfg == null) {
            return;
        }
        String path = cfgStr(cfg, "path", "").trim();
        File file = new File(path);
        if (!file.isFile()) {
            throw ApiException.validation("文件不存在或不是普通文件: " + path);
        }
        String name = file.getName().toLowerCase(java.util.Locale.ROOT);
        boolean valid = (DataSource.CSV.equals(type) && (name.endsWith(".csv") || name.endsWith(".txt")))
                || (DataSource.JSON.equals(type) && (name.endsWith(".json") || name.endsWith(".ndjson") || name.endsWith(".jsonl")))
                || (DataSource.XLSX.equals(type) && (name.endsWith(".xls") || name.endsWith(".xlsx")));
        if (!valid) {
            throw ApiException.validation("文件扩展名与数据源类型不匹配: " + name);
        }
    }

    /** 与同步读取共用类型规则，合并顶层字段及嵌套点分路径。 */
    private List<Column> mongoColumns(Map<String, Object> cfg, String collection) {
        String db = com.el2ck.util.MongoFields.database(cfg);
        String name = collection == null ? "" : collection.trim();
        if (name.isEmpty()) name = cfgStr(cfg, "collection", "").trim();
        if (db.isEmpty() || name.isEmpty()) throw ApiException.validation("MongoDB 数据库和集合不能为空");
        try (com.mongodb.client.MongoClient client = com.mongodb.client.MongoClients.create(mongoUri(cfg))) {
            List<Document> samples = new ArrayList<>();
            try (com.mongodb.client.MongoCursor<Document> cursor = client.getDatabase(db).getCollection(name)
                    .find().limit(200).maxTime(30, TimeUnit.SECONDS).iterator()) {
                while (cursor.hasNext()) samples.add(cursor.next());
            }
            return com.el2ck.util.MongoFields.columns(samples);
        } catch (RuntimeException e) {
            throw ApiException.badGateway("读取 MongoDB 集合字段失败: " + Sql.messageOf(e));
        }
    }

    /** 列出配置库里可选作 SQL 来源的基础表;MySQL 取配置的库,PostgreSQL 取 public schema。 */
    public List<String> sqlTables(long id) {
        return sqlTables(id,false);
    }
    /**
     * 读取 SQL 数据源中的表名。
     *
     * @param id 数据源 ID
     * @param fresh true 表示绕过元数据缓存
     * @return 表名列表
     */
    public List<String> sqlTables(long id,boolean fresh) {
        return metadata(id,"sql-tables",fresh,new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {},
                () -> readSqlTables(id));
    }
    /**
     * 使用已保存的 SQL 数据源配置读取表清单。
     *
     * @param id 数据源 ID
     * @return 表名列表
     */
    private List<String> readSqlTables(long id) {
        DataSource source = requireSqlCatalogSource(id);
        Map<String, Object> cfg = decryptConfig(source);
        boolean mysql = DataSource.MYSQL.equals(source.getType());
        // MySQL 的库名即 information_schema 的 TABLE_SCHEMA,需绑定配置库名;
        // PostgreSQL 的库由连接串决定、schema 固定 public,故条件写死在 SQL 里(无需参数)。
        String query = mysql
                ? "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA=? AND TABLE_TYPE='BASE TABLE' ORDER BY TABLE_NAME"
                : "SELECT table_name FROM information_schema.tables WHERE table_schema='public' AND table_type='BASE TABLE' ORDER BY table_name";
        List<String> tables = new ArrayList<>();
        // try-with-resources 依次关闭 ResultSet → Statement → Connection
        try (Connection connection = DriverManager.getConnection(mysql ? mysqlUrl(cfg) : pgUrl(cfg));
             PreparedStatement statement = connection.prepareStatement(query)) {
            if (mysql) statement.setString(1, cfgStr(cfg, "database", ""));
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) tables.add(rows.getString(1));
            }
            return tables;
        } catch (Exception e) {
            throw ApiException.badGateway("读取源表列表失败: " + Sql.messageOf(e));
        }
    }

    /** Resolve a saved SQL source's table, optionally overridden by the task wizard. */
    public List<Column> sqlColumns(long id, String tableOverride) {
        return sqlColumns(id,tableOverride,false);
    }
    /**
     * 读取 SQL 数据源字段，并允许为目录型数据源覆盖当前表名。
     *
     * @param id 数据源 ID
     * @param tableOverride 可空表名覆盖值
     * @param fresh true 表示绕过元数据缓存
     * @return 字段列表
     */
    public List<Column> sqlColumns(long id,String tableOverride,boolean fresh) {
        return metadata(id,"sql-columns:"+def(tableOverride),fresh,new com.fasterxml.jackson.core.type.TypeReference<List<Column>>() {},
                () -> readSqlColumns(id,tableOverride));
    }
    /**
     * 使用已保存的 SQL 数据源配置读取字段。
     *
     * @param id 数据源 ID
     * @param tableOverride 可空表名覆盖值
     * @return 字段列表
     */
    private List<Column> readSqlColumns(long id, String tableOverride) {
        DataSource source = requireSqlCatalogSource(id);
        Map<String, Object> cfg = decryptConfig(source);
        // 向导传入的表名优先(可临时切换源表);未传或空白则回退数据源配置里的表名
        String table = tableOverride == null || tableOverride.trim().isEmpty()
                ? cfgStr(cfg, "table", "").trim() : tableOverride.trim();
        if (table.isEmpty()) throw ApiException.validation("请先选择源表");
        boolean mysql = DataSource.MYSQL.equals(source.getType());
        try {
            // 与执行时使用同一份 JDBC 元数据，保留小数和小数秒精度。
            return DbSource.describe(mysql ? "mysql" : "postgres", cfg, table);
        } catch (RuntimeException e) {
            throw ApiException.badGateway("读取源表字段失败: " + Sql.messageOf(e));
        }
    }

    /**
     * 读取并校验可作为 SQL 目录浏览的数据源。
     *
     * @param id 数据源 ID
     * @return 类型受支持的数据源
     * @throws com.el2ck.exception.ApiException 不存在或类型不支持时抛出
     */
    private DataSource requireSqlCatalogSource(long id) {
        DataSource source = repository.getDataSource(id);
        if (source == null) throw ApiException.notFound("数据源不存在");
        if (!DataSource.MYSQL.equals(source.getType()) && !DataSource.PGSQL.equals(source.getType()))
            throw ApiException.validation("仅 MySQL 和 PostgreSQL 数据源支持该表选择器");
        return source;
    }

    /**
     * 按数据源类型列出同步任务可选的源对象(每项为 {name, kind})。
     *
     * <p>五类分支各有来源:本地文件直接取配置路径;SFTP 枚举远程目录;MongoDB 取库下全部集合;
     * MySQL/PostgreSQL 走 JDBC 元数据枚举表与视图;ClickHouse 查 {@code system.tables}。
     * 同名对象在不同库/schema 下会冲突,故 MySQL/PG 分支把 schema 拼进名字。
     */
    public List<Map<String, Object>> sourceObjects(long id) {
        return sourceObjects(id,false);
    }
    /**
     * 枚举数据源可读取的对象，并可按请求刷新缓存。
     *
     * @param id 数据源 ID
     * @param fresh true 表示强制重新读取远端对象
     * @return 对象描述列表
     */
    public List<Map<String,Object>> sourceObjects(long id,boolean fresh) {
        return metadata(id,"objects",fresh,new com.fasterxml.jackson.core.type.TypeReference<List<Map<String,Object>>>() {},
                () -> readSourceObjects(id));
    }
    /**
     * 按数据源类型分派对象枚举。
     *
     * @param id 数据源 ID
     * @return 文件、表、Topic 或集合描述列表
     */
    private List<Map<String, Object>> readSourceObjects(long id) {
        DataSource d = repository.getDataSource(id);
        if (d == null) throw ApiException.notFound("数据源不存在");
        Map<String, Object> cfg = decryptConfig(d);
        if (BigDataSource.supports(d.getType())) return BigDataSource.objects(d.getType(), cfg);
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            if (DataSource.isLocalFile(d.getType())) {
                String path = cfgStr(cfg, "path", "");
                if (!path.isEmpty()) out.add(object(path, "file"));
            } else if (DataSource.SFTP.equals(d.getType())) {
                // SFTP:枚举远程路径下的可读文件
                listSftpObjects(cfg, out);
            } else if (DataSource.MONGODB.equals(d.getType())) {
                // Mongo:枚举库下全部集合作为候选源对象
                String db = com.el2ck.util.MongoFields.database(cfg);
                if (db.isEmpty()) throw ApiException.validation("MongoDB 数据库不能为空");
                try (com.mongodb.client.MongoClient client = com.mongodb.client.MongoClients.create(mongoUri(cfg))) {
                    for (String name : client.getDatabase(db).listCollectionNames()) out.add(object(name, "collection"));
                }
            } else if (DataSource.MYSQL.equals(d.getType()) || DataSource.PGSQL.equals(d.getType())) {
                // MySQL/PG:经 JDBC 元数据枚举库内表与视图(mysql 以配置库名作 catalog,pgsql 的库经 schema 区分)
                String url = DataSource.MYSQL.equals(d.getType()) ? mysqlUrl(cfg) : pgUrl(cfg);
                try (Connection conn = DriverManager.getConnection(url);
                     ResultSet rs = conn.getMetaData().getTables(
                             DataSource.MYSQL.equals(d.getType()) ? cfgStr(cfg, "database", "") : null,
                             null, "%", new String[]{"TABLE", "VIEW"})) {
                    while (rs.next()) {
                        // 归属 schema 非空时拼成 schema.name,避免不同库/模式下的同名表冲突
                        String schema = rs.getString("TABLE_SCHEM");
                        String name = rs.getString("TABLE_NAME");
                        if (name != null && !name.isEmpty()) out.add(object(
                                schema == null || schema.isEmpty() ? name : schema + "." + name, "table"));
                    }
                }
            } else if (DataSource.CLICKHOUSE.equals(d.getType())) {
                // ClickHouse:从 system.tables 枚举指定库的表,engine 列作为对象种类
                try (CkClient client = CkClient.open(ckConfigFrom(cfg, "default"))) {
                    com.el2ck.ckpool.QueryResult rs = client.query(
                            "SELECT name, engine FROM system.tables WHERE database = ? ORDER BY name",
                            java.util.Collections.singletonList((Object) cfgStr(cfg, "database", "default")));
                    for (List<Object> row : rs.getRows()) {
                        // 第二列 engine 作为 kind;行内列数不足时兜底为 "table",避免越界
                        if (!row.isEmpty()) out.add(object(String.valueOf(row.get(0)), row.size() > 1 ? String.valueOf(row.get(1)) : "table"));
                    }
                }
            }
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.badGateway("读取源对象失败: " + Sql.messageOf(e));
        }
        return out;
    }

    /** 组装单个源对象条目:对象名(name)+ 种类(kind:file/table/collection)。 */
    private static Map<String, Object> object(String name, String kind) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", name);
        item.put("kind", kind);
        return item;
    }

    /** 浏览 SFTP 目录(对应 Go BrowseSFTPDir):只列当前目录一层,目录优先、名称不区分大小写排序。 */
    public SftpBrowseResult browseSftp(SftpBrowseRequest req) {
        Map<String, Object> cfg = sftpBrowseConfig(req);
        RemoteSFTP sftp = null;
        try {
            sftp = connectSFTP(cfgStr(cfg, "host", ""), cfgInt(cfg, "port", 22),
                    cfgStr(cfg, "username", ""), cfgStr(cfg, "password", ""));
            String path = req.getPath() == null ? "" : req.getPath();
            // 未指定路径时用登录后的当前工作目录(相对路径交给 SFTP 服务端解析)
            if (path.isEmpty()) path = sftp.channel.pwd();
            List<SftpEntry> entries = new ArrayList<>();
            @SuppressWarnings("unchecked")
            java.util.Vector<ChannelSftp.LsEntry> listed = sftp.channel.ls(path);
            for (ChannelSftp.LsEntry item : listed) {
                String name = item.getFilename();
                // 跳过 . / .. 及空名(ls 会把当前目录与父目录一并返回)
                if (name == null || name.isEmpty() || ".".equals(name) || "..".equals(name)) continue;
                SftpEntry entry = new SftpEntry();
                entry.setName(name);
                // 子项完整路径 = 父路径去掉尾部斜杠后拼 '/';根目录 "" 拼接后即为 "/name"
                entry.setPath(path.replaceAll("/+$", "") + "/" + name);
                entry.setDir(item.getAttrs().isDir());
                // 目录的 getSize() 无意义(部分服务端给 0 或块大小),前端按 isDir 忽略该值
                entry.setSize(item.getAttrs().getSize());
                entry.setHidden(name.startsWith("."));
                // SFTP 的 mtime 是 epoch 秒,按服务器本地时区渲染为字符串
                entry.setMtime(LocalDateTime.ofInstant(
                        Instant.ofEpochSecond(item.getAttrs().getMTime()), ZoneId.systemDefault())
                        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
                entries.add(entry);
            }
            // 目录优先(reversed 使 true 排前),同类型按名称不区分大小写升序
            entries.sort(Comparator.comparing(SftpEntry::isDir).reversed()
                    .thenComparing(SftpEntry::getName, String.CASE_INSENSITIVE_ORDER));
            SftpBrowseResult result = new SftpBrowseResult();
            result.setPath(path);
            result.setEntries(entries);
            return result;
        } catch (Exception error) {
            // 目录读取(含连接)失败统一按下游不可用处理
            throw ApiException.badGateway("读取 SFTP 目录失败: " + Sql.messageOf(error));
        } finally {
            // 无论成功失败都断开 SSH 会话,避免连接泄漏
            if (sftp != null) sftp.disconnect();
        }
    }

    /**
     * 用请求里现填的凭据预览 SFTP 目录 —— 用于数据源尚未保存时试读。
     *
     * <p>因为不查已存配置,三项凭据必须齐全,缺一即 400;与已保存数据源的预览
     * ({@link #previewSavedSftpDirectory})构成同一功能的「未保存」入口。
     */
    public SftpDirPreview previewSftpDirectory(SftpDirPreviewRequest req) {
        // 明文连接方式(未保存数据源)必须把三项凭据填全,否则无法建立会话
        if (req == null || req.getHost() == null || req.getHost().trim().isEmpty()
                || req.getUsername() == null || req.getUsername().trim().isEmpty()
                || req.getPassword() == null || req.getPassword().isEmpty()) {
            throw ApiException.validation("请先填写 SFTP 主机/用户名/密码");
        }
        // hasHeader 取反判定:只有显式传 false 才关闭表头,缺省(null)按有表头处理
        return analyzeSftpDirectory(sftpBrowseConfig(req), req.getRemotePath(),
                req.getFilePattern(), !Boolean.FALSE.equals(req.getHasHeader()), !Boolean.FALSE.equals(req.getIgnoreExtraCsvColumns()), req.getCsvEncoding());
    }

    /**
     * 用已保存数据源的凭据预览 SFTP 目录。
     *
     * <p>三个入参都可缺省,缺省时回退到数据源配置里保存的远程路径与通配符(通配符默认 {@code *.csv}),
     * 所以前端可以只传 id 让后端按存量配置预览。
     */
    public SftpDirPreview previewSavedSftpDirectory(long id, String remotePath,
                                                     String filePattern, Boolean hasHeader) {
        return previewSavedSftpDirectory(id, remotePath, filePattern, hasHeader, true);
    }

    public SftpDirPreview previewSavedSftpDirectory(long id, String remotePath,
                                                     String filePattern, Boolean hasHeader, boolean ignoreExtraCsvColumns) {
        return previewSavedSftpDirectory(id, remotePath, filePattern, hasHeader, ignoreExtraCsvColumns, "auto");
    }

    public SftpDirPreview previewSavedSftpDirectory(long id, String remotePath,
                                                     String filePattern, Boolean hasHeader, boolean ignoreExtraCsvColumns, String csvEncoding) {
        SftpBrowseRequest request = new SftpBrowseRequest();
        request.setId(id);
        Map<String, Object> cfg = sftpBrowseConfig(request);
        // 未显式传入时回退到数据源配置里保存的远程路径与通配符(默认 *.csv)
        if (remotePath == null || remotePath.isEmpty()) remotePath = cfgStr(cfg, "remotePath", "");
        if (filePattern == null || filePattern.isEmpty()) filePattern = cfgStr(cfg, "filePattern", "*.csv");
        return analyzeSftpDirectory(cfg, remotePath, filePattern, !Boolean.FALSE.equals(hasHeader), ignoreExtraCsvColumns, csvEncoding);
    }

    /**
     * 建立临时 SFTP 连接并分析目录内匹配文件的结构。
     *
     * <p>无论分析成功与否都会断开连接；已带业务语义的 ApiException 原样透传。
     *
     * @param cfg 已解密 SFTP 配置
     * @param remotePath 远程目录
     * @param filePattern 文件名通配符
     * @param hasHeader 文件是否包含表头
     * @return 目录文件预览结果
     */
    private SftpDirPreview analyzeSftpDirectory(Map<String, Object> cfg, String remotePath,
                                                String filePattern, boolean hasHeader, boolean ignoreExtraCsvColumns, String csvEncoding) {
        RemoteSFTP sftp = null;
        try {
            sftp = connectSFTP(cfgStr(cfg, "host", ""), cfgInt(cfg, "port", 22),
                    cfgStr(cfg, "username", ""), cfgStr(cfg, "password", ""));
            return new SftpDirectoryAnalyzer(duck).analyze(sftp.channel, remotePath, filePattern, hasHeader,
                    SourceFile.directoryDelimiter(cfg), ignoreExtraCsvColumns, csvEncoding);
        } catch (ApiException error) {
            // 分析器已给出明确语义(如"未匹配到文件")的异常原样透传,不降级成 502
            throw error;
        } catch (Exception error) {
            throw ApiException.badGateway("SFTP 目录结构分析失败: " + Sql.messageOf(error));
        } finally {
            if (sftp != null) sftp.disconnect();
        }
    }

    Map<String, Object> sftpBrowseConfig(SftpBrowseRequest req) {
        if (req == null) throw ApiException.validation("参数不能为空");
        // id 为正表示操作已保存的数据源:必须存在且类型为 SFTP,凭据从密文里解出
        if (req.getId() != null && req.getId() > 0) {
            DataSource source = repository.getDataSource(req.getId());
            if (source == null) throw ApiException.notFound("数据源不存在");
            if (!DataSource.SFTP.equals(source.getType()))
                throw ApiException.validation("数据源不是 SFTP 类型");
            return decryptConfig(source);
        }
        // 否则用请求里现填的参数(新增向导场景,尚未落库)
        String host = req.getHost() == null ? "" : req.getHost().trim();
        String username = req.getUsername() == null ? "" : req.getUsername().trim();
        if (host.isEmpty() || username.isEmpty())
            throw ApiException.validation("缺少 SFTP 连接参数(host/username)");
        // 端口缺省/非法(<=0)回落标准 22;超范围直接报错而非静默纠正
        int port = req.getPort() == null || req.getPort() <= 0 ? 22 : req.getPort();
        if (port > 65535) throw ApiException.validation("SFTP 端口必须在 1-65535 之间");
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("host", host);
        cfg.put("port", port);
        cfg.put("username", username);
        // 浏览/预览允许空密码(免密或密钥场景),故不在此做强校验
        cfg.put("password", req.getPassword() == null ? "" : req.getPassword());
        return cfg;
    }

    /** CSV 直接流式预览；JSON/Excel 保留临时下载并在解析后清理。 */
    public CSVPreviewResult previewSftpFile(SftpRemotePreviewRequest req) {
        if (req == null || req.getPath() == null || req.getPath().trim().isEmpty())
            throw ApiException.validation("请选择要预览的远端文件");
        String remotePath = req.getPath().trim();
        // 按扩展名推断解析类型,不支持的类型不进 SFTP 流程直接 400
        String type = remoteFileType(remotePath);
        if (type == null) throw ApiException.validation("仅支持 TXT、CSV、JSON、Excel 文件预览");
        Map<String, Object> cfg = sftpBrowseConfig(req);
        RemoteSFTP sftp = null;
        // 本地临时文件句柄,finally 中负责清理
        Path local = null;
        try {
            sftp = connectSFTP(cfgStr(cfg, "host", ""), cfgInt(cfg, "port", 22),
                    cfgStr(cfg, "username", ""), cfgStr(cfg, "password", ""));
            // 相对路径先补全为绝对路径:后续 stat/get 都以绝对路径为准
            if (!remotePath.startsWith("/")) {
                remotePath = sftp.channel.pwd().replaceAll("/+$", "") + "/" + remotePath;
            }
            // 先 stat 判目录,避免把整个目录拉下来
            if (sftp.channel.stat(remotePath).isDir())
                throw ApiException.validation("请选择文件，不支持预览目录");
            // CSV 与目录检测/新轮次同步共用读取器，只取所需逻辑记录，不下载完整大文件、不扫描总行数。
            if (DataSource.CSV.equals(type)) {
                int limit = req.getLimit() == null || req.getLimit() <= 0 ? 10 : Math.min(req.getLimit(), 1000);
                boolean header = !Boolean.FALSE.equals(req.getHeader());
                try (com.el2ck.util.ModernCsvTable csv = com.el2ck.util.ModernCsvTable.open(sftp.channel.get(remotePath),
                        remotePath, header, SourceFile.directoryDelimiter(cfg), !Boolean.FALSE.equals(req.getIgnoreExtraCsvColumns()), req.getCsvEncoding())) {
                    CSVPreviewResult result = new CSVPreviewResult();
                    result.setColumns(csv.columns()); result.setHasHeader(header); result.setCsvEncoding(csv.encoding());
                    DuckQueryResult rows = new DuckQueryResult();
                    for (Column column : result.getColumns()) { rows.getColumns().add(column.getName()); rows.getTypes().add(column.getType()); }
                    rows.setRows(csv.next(limit)); result.setRows(rows);
                    if (rows.getRows().size() < limit) result.setTotal((long) rows.getRows().size());
                    return result;
                }
            }
            // 保留原扩展名创建临时文件(DuckDB 按后缀选择 read_csv/read_json)
            String suffix = remotePath.substring(remotePath.lastIndexOf('.'));
            local = Files.createTempFile("el2ck-sftp-preview-", suffix);
            sftp.channel.get(remotePath, local.toString());
            // header 优先级:请求显式指定 > (非 CSV 默认 true / CSV 走首行探测)
            boolean header = req.getHeader() != null ? req.getHeader()
                    : !DataSource.CSV.equals(type) || DuckHeader.detect(local.toString());
            // 本地可解析路径(Excel 会先转成临时 CSV)
            String readable = SourceFile.readableCsv(duck, type, cfgWithPath(local.toString()));
            // 预览行数缺省 10,并夹取到 1000 上限防止一次性拉回过多数据
            int limit = req.getLimit() == null || req.getLimit() <= 0 ? 10 : Math.min(req.getLimit(), 1000);
            return previewFileInternal(readable, header, null, null, limit);
        } catch (ApiException error) {
            // 校验类错误(如选了目录)保持原语义,不包装成 502
            throw error;
        } catch (Exception error) {
            throw ApiException.badGateway("SFTP 文件预览失败: " + Sql.messageOf(error));
        } finally {
            if (sftp != null) sftp.disconnect();
            if (local != null) {
                try {
                    Files.deleteIfExists(local);
                } catch (Exception ignored) {
                    // 临时文件清理失败不掩盖预览结果。
                }
            }
        }
    }

    static String remoteFileType(String path) {
        String lower = path == null ? "" : path.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".csv") || lower.endsWith(".txt")) return DataSource.CSV;
        if (lower.endsWith(".json") || lower.endsWith(".jsonl") || lower.endsWith(".ndjson")) return DataSource.JSON;
        if (lower.endsWith(".xlsx") || lower.endsWith(".xls")) return DataSource.XLSX;
        return null;
    }

    /** 枚举 SFTP 远程路径下的可读文件作为源对象;远程路径本身是文件时仅返回它自己。 */
    private void listSftpObjects(Map<String, Object> cfg, List<Map<String, Object>> out) throws Exception {
        String path = cfgStr(cfg, "remotePath", "");
        if (path.isEmpty()) throw ApiException.validation("SFTP 远程文件夹不能为空");
        RemoteSFTP sftp = null;
        try {
            sftp = connectSFTP(cfgStr(cfg, "host", ""), cfgInt(cfg, "port", 22),
                    cfgStr(cfg, "username", ""), cfgStr(cfg, "password", ""));
            try {
                // stat 判明路径是目录还是文件:是文件则作为唯一候选直接返回
                com.jcraft.jsch.SftpATTRS attrs = sftp.channel.stat(path);
                if (!attrs.isDir()) {
                    if (isReadableRemoteFile(path)) out.add(object(path, "file"));
                    else throw ApiException.validation("SFTP 仅支持 TXT、CSV、JSON、Excel 文件");
                    return;
                }
            } catch (SftpException ignored) {
                // 个别服务端 stat 受限会抛异常:忽略后改由下方 ls 兜底(ls 更通用)
            }
            @SuppressWarnings("unchecked")
            // 列目录,过滤 .、.. 与子目录,仅收扩展名受支持的文件
            java.util.Vector<ChannelSftp.LsEntry> entries = sftp.channel.ls(path);
            for (ChannelSftp.LsEntry entry : entries) {
                String name = entry.getFilename();
                if (!".".equals(name) && !"..".equals(name) && !entry.getAttrs().isDir()
                        && isReadableRemoteFile(name)) {
                    // 拼接完整远程路径(去掉尾部斜杠)作为对象名
                    out.add(object(path.replaceAll("/+$", "") + "/" + name, "file"));
                }
            }
        } finally {
            if (sftp != null) sftp.disconnect();
        }
    }

    /** SFTP/远程文件允许的文本、JSON 和 Excel 扩展名。TXT 按 CSV 文本规则解析。 */
    private static boolean isReadableRemoteFile(String path) {
        String name = path == null ? "" : path.toLowerCase(java.util.Locale.ROOT);
        return name.endsWith(".txt") || name.endsWith(".csv")
                || name.endsWith(".json") || name.endsWith(".ndjson") || name.endsWith(".jsonl")
                || name.endsWith(".xls") || name.endsWith(".xlsx");
    }

    // =====================================================================
    // 本地文件预览(对应 Go PreviewLocalFile / CSVPreview)
    // =====================================================================

    /** 按路径直接预览本地文件(新增向导用,不依赖已保存数据源)。 */
    public CSVPreviewResult previewLocalFile(LocalFilePreviewRequest req) {
        if (req == null) {
            throw ApiException.validation("请求内容不能为空");
        }
        // 类型须为受支持的本地文件类型
        String type = def(req.getType());
        if (!DataSource.validType(type) || !DataSource.isLocalFile(type)) {
            throw ApiException.validation(String.format("不支持的文件类型 \"%s\"", type));
        }
        // 文件路径去除空白且非空
        String path = req.getPath() == null ? "" : req.getPath().trim();
        if (path.isEmpty()) {
            throw ApiException.validation("文件路径不能为空");
        }
        requireUploadPath(path);
        // 本地文件须真实存在
        if (!new File(path).exists()) {
            throw ApiException.badGateway("文件不存在: " + path);
        }
        // header 默认 true;JSON 无表头概念保持 true,CSV/Excel 由请求覆盖或探测
        boolean header = true;
        if (DataSource.CSV.equals(type)) {
            // CSV 未显式指定时自动探测首行是否表头
            header = req.getHeader() != null ? req.getHeader() : DuckHeader.detect(path);
        } else if (DataSource.XLSX.equals(type)) {
            // Excel 未显式指定时默认首行为表头
            header = req.getHeader() != null ? req.getHeader() : true;
        }
        // 手动列名:空列表视为未指定
        List<String> names = req.getNames() == null || req.getNames().isEmpty() ? null : req.getNames();
        // 归一化自定义分隔符
        String delimiter = SourceFile.normalize(req.getDelimiter());
        String readable;
        try {
            // 转为可解析路径(xlsx 先转临时 csv)
            readable = SourceFile.readableCsv(duck, type, cfgWithPath(path));
        } catch (RuntimeException e) {
            throw ApiException.badGateway(e.getMessage());
        }
        // 内部统一生成预览结果
        return previewFileInternal(readable, header, names, delimiter, req.getLimit());
    }

    /** 已保存本地文件数据源的预览。 */
    public CSVPreviewResult csvPreview(long id, CSVPreviewOptions opt) {
        // 按 id 取记录
        DataSource d = repository.getDataSource(id);
        if (d == null) throw ApiException.notFound("记录不存在");
        // 仅本地文件类型支持预览
        if (!DataSource.isLocalFile(d.getType())) throw ApiException.validation("仅本地文件数据源支持预览");
        // 解密配置取路径
        Map<String, Object> cfg = decryptConfig(d);
        String path = cfgStr(cfg, "path", "");
        if (path.isEmpty()) throw ApiException.badGateway("文件路径不能为空");
        requireUploadPath(path);
        // 本地文件须存在
        if (!new File(path).exists()) throw ApiException.badGateway("文件不存在: " + path);
        // header:取配置默认值,非 json 可被请求覆盖(JSON 以键名当列名,没有"表头行"概念,恒为 true)
        boolean header = headerFromConfig(cfg);
        if (!DataSource.JSON.equals(d.getType()) && opt.getHeader() != null) header = opt.getHeader();
        // names:取配置值,请求指定则覆盖
        List<String> names = cfgNames(cfg);
        if (opt.getNames() != null && !opt.getNames().isEmpty()) names = opt.getNames();
        // delimiter:请求归一化后为空则回退配置值
        String delimiter = SourceFile.normalize(opt.getDelimiter());
        if (delimiter == null) delimiter = SourceFile.delimiter(cfg);
        String readable;
        try {
            // 转为可解析路径
            readable = SourceFile.readableCsv(duck, d.getType(), cfg);
        } catch (RuntimeException e) {
            throw ApiException.badGateway(e.getMessage());
        }
        // 内部生成预览结果
        CSVPreviewResult res = previewFileInternal(readable, header, names, delimiter, opt.getLimit());
        return res;
    }

    /** 生成文件预览结果:describe 字段 + 前 N 行 + 总数(path 为可解析路径)。 */
    private CSVPreviewResult previewFileInternal(String path, boolean header, List<String> names,
                                                 String delimiter, Integer limit) {
        // DuckDB 须已初始化
        if (!duck.isReady()) {
            throw ApiException.badGateway("duckdb 未初始化");
        }
        // 预览行数:null 视为 0(不设上限)
        int lim = limit == null ? 0 : limit;
        List<Column> cols;
        DuckQueryResult rows;
        long total;
        try {
            // 描述字段结构
            cols = duck.describeFile(path, header, names, delimiter);
            // 取前 N 行预览数据
            rows = duck.previewFile(path, lim, header, names, delimiter);
            // 统计总行数
            total = duck.countFile(path, header, names, delimiter);
        } catch (RuntimeException e) {
            throw ApiException.badGateway(Sql.messageOf(e));
        }
        // 组装预览结果
        CSVPreviewResult out = new CSVPreviewResult();
        out.setColumns(cols);
        out.setRows(rows);
        out.setTotal(total);
        out.setHasHeader(header);
        return out;
    }

    /** CSV 行头自动探测,委托 Duck 静态方法。 */
    private static final class DuckHeader {
        static boolean detect(String path) {
            return com.el2ck.duck.Duck.detectCsvHeader(path);
        }
    }

    // =====================================================================
    // 详情(对应 Go Detail)
    // =====================================================================

    /** 返回数据源详情:本地文件解析出类型/行数/大小;连接类返回连接信息。 */
    public DataSourceDetail detail(long id) {
        // 按 id 取记录
        DataSource d = repository.getDataSource(id);
        if (d == null) {
            throw ApiException.notFound("记录不存在");
        }
        // 解密配置
        Map<String, Object> cfg = decryptConfig(d);
        DataSourceDetail det = new DataSourceDetail();
        det.setId(d.getId());
        det.setCode(d.getCode());
        det.setName(d.getName());
        det.setType(d.getType());
        switch (d.getType()) {
            case DataSource.CSV:
            case DataSource.JSON:
            case DataSource.XLSX: {
                // 本地文件:填充 path 与文件大小
                String type = d.getType();
                String path = cfgStr(cfg, "path", "");
                det.setPath(path);
                File f = new File(path);
                if (f.exists()) {
                    det.setSize(f.length());
                }
                // 读取解析参数
                boolean header = headerFromConfig(cfg);
                List<String> names = cfgNames(cfg);
                String delimiter = SourceFile.delimiter(cfg);
                // DuckDB 就绪时尽力解析字段与总数
                if (duck.isReady()) {
                    String readable;
                    try {
                        // 转为可解析路径
                        readable = SourceFile.readableCsv(duck, type, cfg);
                    } catch (RuntimeException e) {
                        readable = null;
                    }
                    if (readable != null) {
                        try {
                            // 描述字段(带 names 映射)
                            det.setColumns(duck.describeFile(readable, header, names, delimiter));
                        } catch (RuntimeException ignored) {
                            // 解析失败则字段留空,不阻塞详情。
                        }
                        // 原始列名(不带 names 映射),供「原始列名 vs 字段映射」对照展示。
                        try {
                            det.setRawColumns(duck.describeFile(readable, header, null, delimiter));
                        } catch (RuntimeException ignored) {
                            // 同上。
                        }
                        try {
                            // 统计总行数
                            det.setTotal(duck.countFile(readable, header, names, delimiter));
                        } catch (RuntimeException ignored) {
                            // 同上。
                        }
                    }
                }
                if (header || DataSource.JSON.equals(type)) {
                    det.setColumnComments(SourceFile.columnComments(det.getColumns(), det.getRawColumns()));
                }
                det.setHasHeader(header);
                det.setNames(names);
                return det;
            }
            case DataSource.SFTP:
                // SFTP:回填主机/端口/用户名/远程路径
                det.setHost(cfgStr(cfg, "host", ""));
                det.setPort(cfgInt(cfg, "port", 22));
                det.setUsername(cfgStr(cfg, "username", ""));
                det.setRemotePath(cfgStr(cfg, "remotePath", ""));
                return det;
            case DataSource.MONGODB:
                // Mongo:回填主机/端口/库名/集合/用户名;host 可能内嵌整条连接串,凭据先掩码再下发
                det.setHost(maskUriCredentials(cfgStr(cfg, "host", ""))[0]);
                det.setPort(cfgInt(cfg, "port", 27017));
                det.setDatabase(cfgStr(cfg, "database", ""));
                det.setCollection(cfgStr(cfg, "collection", ""));
                det.setUsername(cfgStr(cfg, "username", ""));
                return det;
            case DataSource.KAFKA:
                det.setHost(cfgStr(cfg, "bootstrapServers", ""));
                det.setTable(cfgStr(cfg, "topic", ""));
                det.setUsername(cfgStr(cfg, "username", ""));
                return det;
            default: // mysql/pgsql/clickhouse/hive
                // 连接类库源:回填主机/端口/库名/表名/用户名
                det.setHost(cfgStr(cfg, "host", ""));
                det.setPort(cfgInt(cfg, "port", 0));
                det.setDatabase(cfgStr(cfg, "database", ""));
                det.setTable(cfgStr(cfg, "table", ""));
                det.setUsername(cfgStr(cfg, "username", ""));
                return det;
        }
    }

    // =====================================================================
    // 类型分发的测试/存在性实现
    // =====================================================================

    /** 按类型分发到各测试实现。 */
    private void testDataSource(String type, Map<String, Object> cfg) {
        if (BigDataSource.supports(type)) {
            BigDataSource.test(type, cfg);
            return;
        }
        switch (type) {
            case DataSource.CSV:
            case DataSource.JSON:
            case DataSource.XLSX:
                // 本地文件测试
                testLocalFile(type, cfg);
                return;
            case DataSource.SFTP:
                testSFTP(cfg);
                return;
            case DataSource.MYSQL:
                testSQL("mysql", mysqlUrl(cfg));
                return;
            case DataSource.PGSQL:
                testSQL("postgres", pgUrl(cfg));
                return;
            case DataSource.CLICKHOUSE:
                testClickHouse(cfg);
                return;
            case DataSource.MONGODB:
                testMongo(cfg);
                return;
            case DataSource.CK_TABLE:
                return; // Uses the sync task's target connection; no credentials on the source.
            default:
                throw ApiException.validation(String.format("不支持的数据源类型 \"%s\"", type));
        }
    }

    /** 本地文件:存在 + DuckDB 可解析(试查 1 行;Excel 先转临时 CSV)。 */
    private void testLocalFile(String type, Map<String, Object> cfg) {
        // 取配置路径
        String path = cfgStr(cfg, "path", "");
        if (path.isEmpty()) {
            throw ApiException.validation("文件路径不能为空");
        }
        // 校验文件存在且可被解析
        requireReadableLocalFile(type, cfg, path);
    }

    /** 本地文件可解析性检查(测试/对象存在性共用;file 为源文件路径)。 */
    private void requireReadableLocalFile(String type, Map<String, Object> cfg, String file) {
        requireUploadPath(file);
        // 文件须存在
        File source = new File(file);
        if (!source.isFile()) {
            throw ApiException.badGateway("文件不存在或不是普通文件: " + file);
        }
        // DuckDB 须就绪
        if (!duck.isReady()) {
            throw ApiException.badGateway("duckdb 未初始化");
        }
        try {
            // 转可解析路径并让 DuckDB 实际读取校验
            String readable = SourceFile.readableCsv(duck, type, cfgWithPath(file));
            duck.fileReadable(readable, headerFromConfig(cfg), cfgNames(cfg), SourceFile.delimiter(cfg));
        } catch (RuntimeException e) {
            throw ApiException.badGateway(Sql.messageOf(e));
        }
    }

    /** SFTP:SSH 登录 + SFTP 子系统握手(允许任意主机密钥,内网工具场景)。 */
    private void testSFTP(Map<String, Object> cfg) {
        // 读取 SFTP 主机/端口/用户/密码
        String host = cfgStr(cfg, "host", "");
        int port = cfgInt(cfg, "port", 22);
        String user = cfgStr(cfg, "username", "");
        String pwd = cfgStr(cfg, "password", "");


        // 主机必填
        if (host.isEmpty()) {
            throw ApiException.validation("主机地址不能为空");
        }
        if (port < 1 || port > 65535) {
            throw ApiException.validation("SFTP 端口必须在 1-65535 之间");
        }
        // 建立会话,连接失败抛 502
        RemoteSFTP sftp = null;
        try {
            sftp = connectSFTP(host, port, user, pwd);
        } catch (Exception e) {
            throw ApiException.badGateway("SSH 连接失败 " + host + ":" + port + ": " + Sql.messageOf(e));
        }
        // 连接成功即代表凭据可用;若配了远程路径再额外探测其可达性
        try {
            // 配置了远程路径时执行 stat 探测其可达性
            String rp = cfgStr(cfg, "remotePath", "");
            if (!rp.isEmpty()) {
                try {
                    sftp.stat(rp);
                } catch (SftpException e) {
                    throw ApiException.badGateway("远程路径 " + rp + " 不可访问: " + e.getMessage());
                }
            }
        } finally {
            // 断开会话释放连接
            sftp.disconnect();
        }
    }

    /** MySQL JDBC 连接串。 */
    static String mysqlUrl(Map<String, Object> cfg) {
        // 读取连接参数(带默认值)
        String host = cfgStr(cfg, "host", "127.0.0.1");
        int port = cfgInt(cfg, "port", 3306);
        String user = cfgStr(cfg, "username", "root");
        String pwd = cfgStr(cfg, "password", "");
        String db = cfgStr(cfg, "database", "");
        // 拼接 JDBC URL;用户名/密码含 @ : / 等字符时必须 URL 编码后才能内联进连接串。
        // 超时单位为毫秒;serverTimezone 固定 UTC,避免与 JVM 默认时区不一致导致时间列偏移。
        return "jdbc:mysql://" + host + ":" + port + "/" + db
                + "?connectTimeout=5000&socketTimeout=10000&serverTimezone=UTC&user=" + enc(user) + "&password=" + enc(pwd);
    }

    /** PostgreSQL JDBC 连接串。 */
    static String pgUrl(Map<String, Object> cfg) {
        // 读取连接参数(带默认值)
        String host = cfgStr(cfg, "host", "127.0.0.1");
        int port = cfgInt(cfg, "port", 5432);
        String user = cfgStr(cfg, "username", "postgres");
        String pwd = cfgStr(cfg, "password", "");
        String db = cfgStr(cfg, "database", "postgres");
        // SSL 模式默认关闭(disable);PG 驱动缺省为 prefer,这里显式禁用避免内网自签证书握手失败
        String ssl = cfgStr(cfg, "sslmode", "disable");
        // 注意:PG 的 connectTimeout 单位是秒(与 MySQL 驱动的毫秒不同)
        return "jdbc:postgresql://" + host + ":" + port + "/" + db
                + "?connectTimeout=5&sslmode=" + enc(ssl) + "&user=" + enc(user) + "&password=" + enc(pwd);
    }

    /** 通用 SQL 连通性测试(连接 + SELECT 1)。 */
    private void testSQL(String driver, String url) {
        // 连接成功并执行 SELECT 1 即视为连通(try-with-resources 自动释放)
        try (Connection conn = DriverManager.getConnection(url);
             ResultSet rs = conn.createStatement().executeQuery("SELECT 1")) {
            rs.next();
        } catch (Exception e) {
            throw ApiException.badGateway(driver + " 连接失败: " + Sql.messageOf(e));
        }
    }

    /** ClickHouse:复用 ckpool 打开临时连接 + SELECT 1。 */
    private void testClickHouse(Map<String, Object> cfg) {
        // 组装探测用 CkConfig(默认库 default)
        CkConfig ck = ckConfigFrom(cfg, "default");
        try {
            // 打开连接
            CkClient c = CkClient.open(ck);
            try {
                c.query("SELECT 1");
            } finally {
                c.close();
            }
        } catch (RuntimeException e) {
            throw ApiException.badGateway(Sql.messageOf(e));
        }
    }

    /** 组装 ClickHouse 探测用 CkConfig(探测只需短超时;合并规则见 {@link #fromCkMap})。 */
    private static CkConfig ckConfigFrom(Map<String, Object> cfg, String defaultDb) {
        return fromCkMap(cfg, defaultDb, 10000L);
    }

    /** MongoDB ping 测试。 */
    private void testMongo(Map<String, Object> cfg) {
        // 组装连接 URI
        String uri = mongoUri(cfg);
        // 创建 Mongo 客户端(负责关闭)
        com.mongodb.client.MongoClient cli = com.mongodb.client.MongoClients.create(uri);
        try {
            // 通过列出库名触发真实网络请求以探测连通性
            cli.listDatabaseNames().first();
        } catch (RuntimeException e) {
            throw ApiException.badGateway("MongoDB 连接失败: " + Sql.messageOf(e));
        } finally {
            cli.close();
        }
    }

    /** MongoDB 连接串:支持 mongodb:// 直传;否则拼接 host:port,有凭据时含账号密码。 */
    static String mongoUri(Map<String, Object> cfg) {
        // 读取连接参数
        String host = cfgStr(cfg, "host", "127.0.0.1");
        int port = cfgInt(cfg, "port", 27017);
        String user = cfgStr(cfg, "username", "");
        String pwd = cfgStr(cfg, "password", "");
        String database = cfgStr(cfg, "database", "");
        // 认证库默认 admin
        String authSource = cfgStr(cfg, "authSource", "admin");
        // host 已带连接串前缀时原样返回
        if (host.startsWith("mongodb://") || host.startsWith("mongodb+srv://")) {
            return host;
        }
        // 有凭据:拼接账号密码段,可选库名与 authSource
        if (!user.isEmpty()) {
            String uri = "mongodb://" + enc(user) + ":" + enc(pwd) + "@" + host + ":" + port;
            if (!database.isEmpty()) uri += "/" + enc(database);
            return uri + "?authSource=" + enc(authSource.isEmpty() ? "admin" : authSource);
        }
        // 无凭据:直接 host:port(+ 可选库名)
        return "mongodb://" + host + ":" + port + (database.isEmpty() ? "" : "/" + enc(database));
    }

    // ---- 对象存在性检查 ----

    /** 按类型分发检查表/文件/集合是否存在。 */
    private boolean checkObjectExists(String type, Map<String, Object> cfg, String object) {
        if (BigDataSource.supports(type)) return BigDataSource.exists(type, cfg, object);
        switch (type) {
            case DataSource.CSV:
            case DataSource.JSON:
            case DataSource.XLSX:
                // 本地文件:object 即文件路径,应检查存在性 + 可解析性(Excel 先转临时 CSV)。
                requireReadableLocalFile(type, cfg, object);
                return true;
            case DataSource.SFTP:
                // SFTP:stat 远程路径
                return sftpPathExists(cfg, object);
            case DataSource.MYSQL:
                return sqlTableExists("mysql", mysqlUrl(cfg), cfg, object, true);
            case DataSource.PGSQL:
                return sqlTableExists("postgres", pgUrl(cfg), cfg, object, false);
            case DataSource.CLICKHOUSE:
                return ckTableExists(cfg, object);
            case DataSource.MONGODB:
                return mongoCollectionExists(cfg, object);
            default:
                throw ApiException.validation(String.format("不支持的数据源类型 \"%s\"", type));
        }
    }

    /** 通用 SQL 查表存在性(isMysql 决定默认 schema 来源)。 */
    private boolean sqlTableExists(String driver, String url, Map<String, Object> cfg, String table, boolean isMysql) {
        // 默认 schema:mysql 取配置库名,pgsql 固定 public
        String defaultSchema = isMysql ? cfgStr(cfg, "database", "") : "public";
        // 拆成 schema/name 两段
        String[] qualified = objectParts(table, defaultSchema);
        try (Connection conn = DriverManager.getConnection(url)) {
            String sql = "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=? AND table_name=?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                // 参数化绑定 schema 与表名
                ps.setString(1, qualified[0]);
                ps.setString(2, qualified[1]);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    // 计数大于 0 即表存在
                    return rs.getInt(1) > 0;
                }
            }
        } catch (Exception e) {
            throw ApiException.badGateway(driver + " 查表失败: " + Sql.messageOf(e));
        }
    }

    /** ClickHouse 查表存在性(system.tables)。 */
    private boolean ckTableExists(Map<String, Object> cfg, String table) {
        // 组装探测配置(默认库 default)
        CkConfig ck = ckConfigFrom(cfg, "default");
        // 拆成 database/name 两段
        String[] qualified = objectParts(table, ck.getDatabase());
        try {
            // 打开连接
            CkClient c = CkClient.open(ck);
            try {
                // 参数化查询 system.tables 计数
                com.el2ck.ckpool.QueryResult res = c.query(
                        "SELECT count() FROM system.tables WHERE database = ? AND name = ?",
                        java.util.Arrays.asList((Object) qualified[0], qualified[1]));
                // 结果非空时取首单元格计数
                if (!res.getRows().isEmpty() && !res.getRows().get(0).isEmpty()) {
                    return TableService.rowCountOf(res.getRows().get(0).get(0)) > 0;
                }
                return false;
            } finally {
                c.close();
            }
        } catch (RuntimeException e) {
            throw ApiException.badGateway(Sql.messageOf(e));
        }
    }

    /** 将 table 或 schema.table 拆成查询 information_schema/system.tables 所需的两段。 */
    private static String[] objectParts(String object, String defaultNamespace) {
        // 去空白得到对象名
        String value = object == null ? "" : object.trim();
        String namespace = defaultNamespace == null ? "" : defaultNamespace.trim();
        String name = value;
        // 按首个点尝试拆分 schema.table
        int dot = value.indexOf('.');
        if (dot >= 0) {
            // 多于一个点视为非法格式
            if (dot != value.lastIndexOf('.')) {
                throw ApiException.validation("对象名仅支持 table 或 database.table 格式");
            }
            namespace = value.substring(0, dot).trim();
            name = value.substring(dot + 1).trim();
        }
        // 两段均须为合法标识符
        if (!Sql.validIdent(namespace) || !Sql.validIdent(name)) {
            throw ApiException.validation("对象名仅允许字母、数字和下划线，且不能以数字开头");
        }
        return new String[]{namespace, name};
    }

    /** MongoDB 集合存在性检查。 */
    private boolean mongoCollectionExists(Map<String, Object> cfg, String coll) {
        String dbName = com.el2ck.util.MongoFields.database(cfg);
        if (dbName.isEmpty()) throw ApiException.validation("MongoDB 数据库不能为空");
        String uri = mongoUri(cfg);
        // 创建客户端遍历集合名比对
        com.mongodb.client.MongoClient cli = com.mongodb.client.MongoClients.create(uri);
        try {
            for (String n : cli.getDatabase(dbName).listCollectionNames()) {
                if (n.equals(coll)) {
                    return true;
                }
            }
            return false;
        } catch (RuntimeException e) {
            throw ApiException.badGateway("MongoDB 列出集合失败: " + Sql.messageOf(e));
        } finally {
            cli.close();
        }
    }

    /** SFTP 远程路径存在性检查。 */
    private boolean sftpPathExists(Map<String, Object> cfg, String path) {
        // 读取连接参数
        String host = cfgStr(cfg, "host", "");
        int port = cfgInt(cfg, "port", 22);
        String user = cfgStr(cfg, "username", "");
        String pwd = cfgStr(cfg, "password", "");
        RemoteSFTP sftp = null;
        try {
            // 建立会话并对目标路径执行 stat
            sftp = connectSFTP(host, port, user, pwd);
            try {
                sftp.stat(path);
                return true;
            } catch (SftpException e) {
                // 明确"文件不存在"返回 false,其余异常抛 502
                if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) {
                    return false;
                }
                throw ApiException.badGateway(e.getMessage());
            }
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.badGateway("SSH 连接失败 " + host + ":" + port + ": " + Sql.messageOf(e));
        } finally {
            // 断开会话
            if (sftp != null) {
                sftp.disconnect();
            }
        }
    }

    /** 建立并保持一个 SFTP 会话(通道保持打开供后续使用)。 */
    private RemoteSFTP connectSFTP(String host, int port, String user, String pwd) throws Exception {
        JSch jsch = new JSch();
        // 按 用户/主机/端口 获取会话并设置密码
        Session session = jsch.getSession(user, host, port);
        session.setPassword(pwd);
        java.util.Properties conf = new java.util.Properties();
        conf.put("StrictHostKeyChecking", "no"); // 数据源管理允许任意主机密钥(内网工具场景)。
        session.setConfig(conf);
        // 设置超时并建立会话连接
        session.setTimeout(5000);
        session.connect(5000);
        // 打开 sftp 通道并连接
        ChannelSftp ch = (ChannelSftp) session.openChannel("sftp");
        ch.connect(5000);
        // 返回同时持有 session 与 channel 的句柄
        return new RemoteSFTP(session, ch);
    }

    /** SFTP 会话句柄(同时持有 session 与 channel)。 */
    private static final class RemoteSFTP {
        // SSH 会话
        final Session session;
        // SFTP 通道
        final ChannelSftp channel;

        RemoteSFTP(Session session, ChannelSftp channel) {
            this.session = session;
            this.channel = channel;
        }

        void stat(String path) throws SftpException {
            channel.stat(path);
        }

        void disconnect() {
            // 先断开 channel 再断开 session
            if (channel != null && channel.isConnected()) {
                channel.disconnect();
            }
            if (session != null && session.isConnected()) {
                session.disconnect();
            }
        }
    }

    /** URL 编码(用户名/密码进入连接串前使用)。 */
    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return s;
        }
    }

    /** null 兜底为空串。 */
    private static String def(String s) {
        return s == null ? "" : s;
    }

    // 取异常消息;为空时退化为异常对象自身的字符串表示
    /**
     * 提取异常消息，空消息回退为异常对象的字符串表示。
     *
     * @param error 原始异常
     * @return 非空错误描述
     */
    private static String message(Throwable error) {
        String value = error.getMessage();
        return value == null || value.isEmpty() ? String.valueOf(error) : value;
    }
}
