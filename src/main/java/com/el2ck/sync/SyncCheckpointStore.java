package com.el2ck.sync;

/**
 * 【文件职责】持久化同步断点、目标写入区间和重试令牌；目标表会话锁配合 SyncExecutionGuard 的任务锁，跨实例保护执行与断点。
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import com.el2ck.repository.metadata.MysqlNamedLock;
import org.springframework.stereotype.Component;
import javax.sql.DataSource;
import java.sql.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

/**
 * 同步断点的持久化与互斥:断点内容存在元数据库的 {@code el_sync_checkpoints.state_json},
 * 同一目标表的写入用一条连接级 MySQL 命名锁跨实例串行化。锁连接在整次运行期间保持,
 * 每次保存断点前都会确认锁仍属于本连接；同一任务由 SyncExecutionGuard 的任务锁另外保护。
 */
@Component
public class SyncCheckpointStore {
    // 持久化契约: state_json 是恢复的唯一可信快照，命名锁只负责跨实例串行化同一目标表。
    // 锁连接必须活到 Session.close；pending/pendingHash 表示“已写但尚未确认”，不能等同于 last。
    // retryToken 把自动重试绑定到同一轮次，防止旧 complete 或另一轮状态被误当成可重放断点。
    private final DataSource database;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(SyncCheckpointStore.class);

    public SyncCheckpointStore(DataSource database) { this.database = database; }

    public static class State {
        /** MySQL 日期分表：当前轮次冻结清单与跨轮次的逐表水位；不会写入 ClickHouse 业务字段。 */
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
        public java.util.List<MysqlDateTableReader.Item> dateTablePlan;
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
        public java.util.Map<String,String> dateTableWatermarks = new java.util.LinkedHashMap<>();
        /**
         * 增量任务新发现日期表的时间型基线：取上一次成功扫描的轮次开始时间，只接该时刻之后的数据，不补历史。
         * 首次运行没有历史可参考，用本轮开始时间。
         */
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        public Long dateTableLastScanAtMs;
        /** 时间型新表还没有确认行时保留基线，下一轮继续从同一基线之后读取，避免重新取“当前时刻”漏行。 */
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
        public java.util.Map<String,String> dateTableBaselines = new java.util.LinkedHashMap<>();
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        public String dateTableEndDate;
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_DEFAULT)
        public int dateTableMissing;
        public String fingerprint, upper, last, pending, pendingHash, targetIdentity, runStartKey;
        public String token = "el2ck_sync_" + UUID.randomUUID().toString().replace("-", "");
        /** 重新传输前尚未确认停止的旧轮次；随新断点落库，不能只留在 Session 内存中。 */
        public java.util.List<String> pendingStopTokens = new java.util.ArrayList<>();
        public boolean initialized, complete, upperCaptured, replaceTarget;
        public String sourceType;
        /** 非唯一键 ClickHouse 分批读取模式及源表身份；沿用 state_json，无需修改元数据库表结构。 */
        public String readerMode, sourceIdentity;
        /** v2 写入协议只在 MySQL 保存续传信息，不再向目标表追加技术列。 */
        public String writerMode, targetLock, batchTime, chunkHash;
        /** 实例 UUID + 库表名的锁；targetLock 保留旧值以兼容原断点和旧地址锁。 */
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        public String physicalTargetLock;
        /** 跨 serverUUID 能力版本的库/表名称锁；存入既有 JSON，不增加 MySQL 表字段。 */
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        public String compatibilityTargetLock;
        /** 完整 CREATE 定义的摘要，补足元数据修改时间精度不足的校验缺口。 */
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        public String targetDefinitionHash;
        /** 未确认子批次发出前观察到的服务端版本；旧断点可能没有该证据。 */
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        public String writerServerVersion;

        /** 继承目标身份时必须同时继承结构证据，避免跨轮次或全量转增量丢失保护。 */
        void inheritTarget(State previous) {
            targetIdentity = previous.targetIdentity;
            targetDefinitionHash = previous.targetDefinitionHash;
            writerServerVersion = previous.writerServerVersion;
        }
        public int chunkOffset;
        /** 修改去重设置前保存原表定义；进程在 ALTER 与确认之间退出时可验证恢复。 */
        public String dedupSetupSchema, dedupSetupUuid;
        public String progressUnit;
        public Long completedUnits,totalUnits;
        public String phase,currentObject,pendingAction;
        public Integer fileCleared,fileFinalized;
        public boolean fileRecordsCleared;
        public java.util.Map<String,String> fileHashes=new java.util.LinkedHashMap<>();
        public java.util.Map<String,String> excludedFiles=new java.util.LinkedHashMap<>();
        public java.util.List<com.el2ck.entity.common.Column> snapshotColumns;
        public java.util.List<String> snapshotHashes=new java.util.ArrayList<>();
        /** 完整摘要清单独立保存在缓存文件；断点只携带固定长度摘要和页数。旧数组仅供升级读取。 */
        public String snapshotManifestHash;
        public int snapshotPageCount;
        public long snapshotRows;
        public boolean snapshotComplete;
        public String snapshotWatermark,snapshotBaseline;
        public SftpSegmentReader.Item snapshotRemote;
        public long rows;
        public Long runId, estimatedTotalRows;
        public boolean estimateAttempted;
        public long runStartRows, runStartedAtMs, updatedAtMs;
        public int retryCount;
        /** 接管存量数据的审计信息；与本轮新写入行数分开。直接存入现有 state_json，无需 DDL。 */
        public Long baselineAdoptedAtMs,baselineAdoptedBy,baselineTargetRows;
    }


/**
 * 以非增量、非自动重试方式打开同步断点会话。
 *
 * <p>该方法等价于 {@code retryToken=null、incremental=false} 的完整重载：按目标表
 * 获取 MySQL 命名锁，并在锁内读取或初始化任务断点。返回的 Session 持有该锁和连接，
 * 调用方必须关闭。
 *
 * @param taskId 同步任务 ID
 * @param target 目标表身份，用于生成跨实例共享的命名锁
 * @param fingerprint 任务、连接及表结构指纹
 * @param restart 为 {@code true} 时替换旧断点并重新开始
 * @return 已取得命名锁的断点会话
 * @throws IllegalStateException 锁被占用、断点不兼容或数据库操作失败时抛出
 * @see #open(long, String, String, boolean, String, boolean)
 */
    public Session open(long taskId, String target, String fingerprint, boolean restart) {
        return open(taskId,target,fingerprint,restart,null);
    }

/**
 * 以非增量模式打开同步断点会话，可选择仅恢复指定自动重试轮次。
 *
 * <p>{@code retryToken} 为空时按普通方式读取或新建断点；非空时要求持久化断点的
 * token 和 fingerprint 都与本轮一致，防止自动重试误接另一轮或已完成轮次。锁与
 * 连接由返回的 Session 持有，调用方必须关闭。
 *
 * @param taskId 同步任务 ID
 * @param target 目标表身份，用于生成命名锁
 * @param fingerprint 当前任务、连接和表结构指纹
 * @param restart 为 {@code true} 时允许替换旧断点并重新开始
 * @param retryToken 自动重试必须继续的轮次 token；普通打开时可为 {@code null}
 * @return 已取得命名锁的断点会话
 * @throws IllegalStateException 锁被占用、token/fingerprint 不匹配或数据库操作失败时抛出
 * @see #open(long, String, String, boolean, String, boolean)
 */
    public Session open(long taskId, String target, String fingerprint, boolean restart, String retryToken) {
        return open(taskId,target,fingerprint,restart,retryToken,false);
    }


/**
 * 获取目标表级命名锁并打开同步断点会话。
 *
 * <p>命名锁由目标表身份计算，按目标表跨实例串行化；调用方还需持有 SyncExecutionGuard
 * 的任务锁。锁使用非等待获取，已被持有时立即失败。方法在锁内
 * 读取任务断点：普通模式可续传 fingerprint 相同的未完成断点；无断点、已完成或
 * {@code restart=true} 时创建新轮次；非空 {@code retryToken} 只允许恢复相同 token 和
 * fingerprint 的轮次。增量模式在旧断点已完成且 fingerprint 相同时沿用旧水位和目标
 * 身份。任何初始化失败都会释放锁并关闭连接；成功时调用方拥有返回的 Session，必须关闭。
 *
 * @param taskId 同步任务 ID
 * @param target 目标表身份，用于生成命名锁
 * @param fingerprint 任务、连接和表结构指纹，不能为空
 * @param restart 为 {@code true} 时替换旧断点并重新开始
 * @param retryToken 自动重试需继续的轮次 token；普通打开时可为 {@code null}
 * @param incremental 为 {@code true} 时，在旧轮次完成且指纹一致的情况下继承水位
 * @return 已取得命名锁并载入状态的会话
 * @throws IllegalStateException 锁被占用、轮次或指纹不匹配、状态读取失败或初始化失败时抛出
 */
    public Session open(long taskId, String target, String fingerprint, boolean restart, String retryToken, boolean incremental) {
        return open(taskId,target,fingerprint,restart,retryToken,incremental,null,null);
    }

    /** 同时持有旧地址锁和物理名称锁，不改历史指纹；UUID 用于识别尚未升级的跨地址断点。 */
    public Session open(long taskId, String target, String fingerprint, boolean restart, String retryToken,
                        boolean incremental, String physicalTarget, String tableUuid) {
        return open(taskId,target,fingerprint,restart,retryToken,incremental,physicalTarget,tableUuid,null);
    }

    /** 兼容锁与主物理锁同时持有，避免旧版 CK 升级或不同协议连接绕过同一目标的互斥。 */
    public Session open(long taskId, String target, String fingerprint, boolean restart, String retryToken,
                        boolean incremental, String physicalTarget, String tableUuid, String compatibilityTarget) {
        Connection connection = null;
        String lock = lockName(target);
        String physicalLock = physicalTarget == null ? null : lockName(physicalTarget);
        String compatibilityLock = compatibilityTarget == null ? null : lockName(compatibilityTarget);
        try {
            connection = database.getConnection();
            if (!MysqlNamedLock.tryAcquire(connection, lock))
                throw new IllegalStateException("该目标表正在同步，请等待当前任务结束");
            if (physicalLock != null && !physicalLock.equals(lock) && !MysqlNamedLock.tryAcquire(connection,physicalLock))
                throw new IllegalStateException("该物理目标表正在被其他连接同步，请等待当前任务结束");
            if (compatibilityLock != null && !compatibilityLock.equals(lock) && !compatibilityLock.equals(physicalLock)
                    && !MysqlNamedLock.tryAcquire(connection,compatibilityLock))
                throw new IllegalStateException("该物理目标表正在被其他连接同步，请等待当前任务结束");
            // 失败后连接锁会释放，但未确认的去重令牌仍必须保留。其他任务不能越过它写入同一表，
            // 否则有限的 ClickHouse 去重窗口可能被挤出。成功完成或删除任务后才解除此保留。
            try (PreparedStatement q = connection.prepareStatement("SELECT task_id FROM el_sync_checkpoints WHERE task_id<>? "
                    + "AND (JSON_UNQUOTE(JSON_EXTRACT(state_json,'$.targetLock'))=? "
                    + "OR JSON_UNQUOTE(JSON_EXTRACT(state_json,'$.physicalTargetLock'))=? "
                    + "OR JSON_UNQUOTE(JSON_EXTRACT(state_json,'$.physicalTargetLock'))=? "
                    + "OR JSON_UNQUOTE(JSON_EXTRACT(state_json,'$.compatibilityTargetLock'))=? "
                    + "OR JSON_UNQUOTE(JSON_EXTRACT(state_json,'$.targetIdentity')) LIKE ?) "
                    + "AND (JSON_UNQUOTE(JSON_EXTRACT(state_json,'$.writerMode'))='mysql-dedup-v1' "
                    + "OR COALESCE(JSON_LENGTH(JSON_EXTRACT(state_json,'$.pendingStopTokens')),0)>0) "
                    + "AND JSON_UNQUOTE(JSON_EXTRACT(state_json,'$.complete'))='false' LIMIT 1")) {
                q.setLong(1,taskId); q.setString(2,lock);
                q.setString(3,physicalLock);
                q.setString(4,compatibilityLock);
                q.setString(5,compatibilityLock);
                // 旧断点没有物理锁字段，但写入前已经记录目标 UUID。UUID 不含 LIKE 通配符。
                q.setString(6,tableUuid == null ? null : "[MergeTree, " + tableUuid + ", %");
                q.setQueryTimeout(5);
                try(ResultSet r=q.executeQuery()) {
                    if(r.next()) throw new IllegalStateException("目标表有未完成的同步任务 " + r.getLong(1) + "，请先继续或处理该任务");
                }
            }
            Session session = new Session(connection, lock, physicalLock, compatibilityLock, taskId);
            try (PreparedStatement q = connection.prepareStatement("SELECT state_json FROM el_sync_checkpoints WHERE task_id=?")) {
                q.setLong(1, taskId);
                try (ResultSet r = q.executeQuery()) {
                    if (r.next()) { session.exists = true; session.state = JSON.readValue(r.getString(1), State.class); }
                }
            }
            State old = session.state;
            session.previousState=old;
            if(old!=null) {
                if (!old.complete && !restart && ((old.physicalTargetLock!=null
                        && !matchesPhysicalLock(old.physicalTargetLock,physicalLock,compatibilityLock))
                        || (old.compatibilityTargetLock!=null && !old.compatibilityTargetLock.equals(compatibilityLock))))
                    throw new IllegalStateException("目标实例或数据库身份已变化，已保留原断点，请恢复原目标连接");
                if(old.pendingStopTokens==null) old.pendingStopTokens=new java.util.ArrayList<>();
                for(String token:old.pendingStopTokens) requireWriteToken(token);
            }
            if(retryToken!=null && (old==null || !retryToken.equals(old.token) || !fingerprint.equals(old.fingerprint)))
                throw new IllegalStateException("本轮断点缺失或已被其他运行替换，停止自动重试，请检查运行历史");
            if (old != null && !old.complete && !restart && !fingerprint.equals(old.fingerprint)) {
                throw new IllegalStateException("任务、连接或表结构已改变，原断点不可继续；请恢复原配置，或使用新的空目标表建立任务");
            }
            if (retryToken==null && (old == null || old.complete || restart)) {
                session.previousToken = old == null ? null : old.token;
                boolean previousPending = old != null && (old.pending != null || old.pendingAction != null
                        || (!old.initialized && old.targetIdentity != null));
                // 有在途写入时不能把停止命令发往另一个实例。目标锁只包含主机与表名，
                // 不足以识别同主机不同端口，必须同时用原配置指纹确认连接和结构未变。
                if(old!=null && (previousPending || !old.pendingStopTokens.isEmpty())
                        && (!fingerprint.equals(old.fingerprint) || (old.targetLock!=null && !lock.equals(old.targetLock))
                        || (old.physicalTargetLock!=null && !matchesPhysicalLock(old.physicalTargetLock,physicalLock,compatibilityLock))
                        || (old.compatibilityTargetLock!=null && !old.compatibilityTargetLock.equals(compatibilityLock))))
                    throw new IllegalStateException("旧写入尚未确认停止，请恢复原任务及连接配置继续传输，确认停止后再修改配置");
                session.state = new State();
                session.state.fingerprint = fingerprint;
                session.state.replaceTarget = restart;
                // 连续点击重新传输、进程退出及自动重试都必须继承未完成的停止义务。
                if(old!=null) session.state.pendingStopTokens.addAll(old.pendingStopTokens);
                if(previousPending && !session.state.pendingStopTokens.contains(old.token)) {
                    requireWriteToken(old.token);
                    session.state.pendingStopTokens.add(old.token);
                }
                if(old!=null && !restart) {
                    session.state.baselineAdoptedAtMs=old.baselineAdoptedAtMs;
                    session.state.baselineAdoptedBy=old.baselineAdoptedBy;
                    session.state.baselineTargetRows=old.baselineTargetRows;
                }
                if(incremental && old!=null && old.complete && !restart) {
                    if(!fingerprint.equals(old.fingerprint))
                        throw new IllegalStateException("增量任务配置已改变，不能沿用旧水位；请恢复原配置，或使用新的空目标表建立任务");
                    session.state.last=old.last;
                    session.state.inheritTarget(old);
                }
            } else {
                session.saveAttempted=true;
            }
            session.state.targetLock=lock;
            session.state.physicalTargetLock=physicalLock;
            session.state.compatibilityTargetLock=compatibilityLock;
            return session;
        } catch (Exception | Error e) {
            MysqlNamedLock.releaseAndClose(connection, lock, physicalLock, compatibilityLock);
            if (e instanceof Error) throw (Error)e;
            throw new IllegalStateException("打开同步断点失败: " + e.getMessage(), e);
        }
    }

    private static boolean matchesPhysicalLock(String previous, String primary, String compatibility) {
        return previous.equals(primary) || previous.equals(compatibility);
    }

    private static void requireWriteToken(String token) {
        if(token==null || !token.matches("el2ck_sync_[a-f0-9]{32}"))
            throw new IllegalStateException("待停止的旧写入标识无效，已保留原断点");
    }

/** 按任务 ID 读取已持久化状态；记录不存在时返回 {@code null}。 */
    public State read(long taskId) {
        try(Connection c=database.getConnection(); PreparedStatement q=c.prepareStatement("SELECT state_json FROM el_sync_checkpoints WHERE task_id=?")) {
            q.setLong(1,taskId);
            try(ResultSet r=q.executeQuery()) { return r.next() ? JSON.readValue(r.getString(1),State.class) : null; }
        } catch(Exception e) { throw new IllegalStateException("读取同步断点失败",e); }
    }

    /** 批量列表与单条断点遵循相同的持久化 JSON 契约，不把原始断点暴露给前端。 */
    public static State decodeState(String payload) {
        if (payload == null) return null;
        try { return JSON.readValue(payload, State.class); }
        catch (java.io.IOException e) { throw new IllegalStateException("读取同步断点失败", e); }
    }

    /** 行值游标含业务列，仅留在断点表；运行历史、页面和日志只展示行号，避免泄露源数据。 */
    public static String positionLabel(State state,String cursor) {
        if (MysqlDateTableReader.MODE.equals(state.readerMode)) return MysqlDateTableReader.positionLabel(cursor);
        if(cursor==null || !CkRowSegmentReader.MODE.equals(state.readerMode)) return cursor;
        try {
            com.fasterxml.jackson.databind.JsonNode position=JSON.readTree(cursor);
            return position.has("ordinal") ? "行号 "+position.get("ordinal").asLong() : "行值断点";
        } catch(Exception e) { return "行值断点"; }
    }

/** 返回 UTF-8 文本的 SHA-256 小写十六进制摘要。 */
    public static String hash(String text) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte b : bytes) out.append(String.format("%02x", b & 255));
            return out.toString();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    /** 目标表的 MySQL 命名锁名。MySQL 限制锁名最长 64 字符:前缀 12 + 48 位十六进制 = 60。 */
    static String lockName(String target) {
        return "sync_target_" + hash(target).substring(0, 48);
    }

    public final class Session implements AutoCloseable {
        private final Connection connection;
        private final String lock;
        private final String physicalLock;
        private final String compatibilityLock;
        private final long taskId;
        private boolean exists;
        private volatile boolean closed;
        private boolean saveAttempted;
        public boolean completeSaved;

        public String recoveryToken() { return saveAttempted ? state.token : null; }
        public State state;
        public State previousState;
        /** 当前 prepare 从实际目标元数据读取的版本；只在当前持锁会话有效，不序列化。 */
        String preparedWriterVersion;
        private java.util.function.Consumer<State> progressListener;

        /** 仅订阅展示快照，绑定到本次会话；调用方负责把已载入的断点也发布一次。 */
        void onProgress(java.util.function.Consumer<State> listener) { this.progressListener = listener; }
        /** 仅用于替换轮次后的旧缓存清理；旧写入的终止信息以 State.pendingStopTokens 为准。 */
        public String previousToken;
        private Session(Connection c, String lock, String physicalLock, String compatibilityLock, long id) {
            this.connection=c; this.lock=lock; this.physicalLock=physicalLock; this.compatibilityLock=compatibilityLock; this.taskId=id;
        }

        /** 连接重新获取、建表和授权后复核，不能持 A 实例的锁向 B 实例写入。 */
        void requirePhysicalTarget(String target, String compatibilityTarget) {
            checkLock();
            if (physicalLock==null || !physicalLock.equals(lockName(target))
                    || (compatibilityLock!=null && (compatibilityTarget==null || !compatibilityLock.equals(lockName(compatibilityTarget)))))
                throw new IllegalStateException("目标连接的物理实例与同步锁不一致，已停止写入");
        }

        /**
         * 先持久化停止义务，再执行幂等终止；确认成功后逐个清除并落库。
         * 停止失败或确认保存的响应丢失时，下一次仍能从数据库恢复，绝不靠内存标记放行清表。
         * 调用者必须在新轮次采集、设置目标、清表和写入之前完成本步骤。
         */
        public void stopPreviousWrites(java.util.function.Consumer<String> stop) {
            if(state.pendingStopTokens==null || state.pendingStopTokens.isEmpty()) return;
            save();
            while(!state.pendingStopTokens.isEmpty()) {
                if(Thread.currentThread().isInterrupted()) throw new IllegalStateException("同步已取消，旧写入停止信息已保留");
                checkLock();
                String token=state.pendingStopTokens.get(0);
                requireWriteToken(token);
                stop.accept(token);
                state.pendingStopTokens.remove(0);
                save();
            }
        }
/**
 * 校验 {@code checkLock} 所涉及的前置条件，失败时阻止后续读写以避免产生不一致状态。
 */
        public void checkLock() {
            if (closed) throw new IllegalStateException("同步断点会话已关闭");
            try (PreparedStatement q=connection.prepareStatement("SELECT IS_USED_LOCK(?)=CONNECTION_ID()"
                    + (physicalLock==null ? "" : " AND IS_USED_LOCK(?)=CONNECTION_ID()")
                    + (compatibilityLock==null ? "" : " AND IS_USED_LOCK(?)=CONNECTION_ID()"))) {
                q.setQueryTimeout(5);
                q.setString(1,lock);
                int parameter=2;
                if (physicalLock!=null) q.setString(parameter++,physicalLock);
                if (compatibilityLock!=null) q.setString(parameter,compatibilityLock);
                try(ResultSet r=q.executeQuery()) {
                    if(!r.next() || r.getInt(1)!=1) throw new IllegalStateException("同步锁已失效，停止写入");
                }
            } catch(SQLException e) { throw new IllegalStateException("同步锁连接已断开，停止写入",e); }
        }

/**
 * 在当前命名锁仍有效时将内存状态持久化到同步断点表。
 *
 * <p>写入前校验锁属于当前连接；首次保存执行 INSERT，后续保存执行 UPDATE，并更新
 * {@code updatedAtMs}。只有数据库确认恰好影响一行后才把会话标记为已存在，同时记录
 * 是否已保存完成状态。写入前即设置 {@code saveAttempted}，因此响应丢失时调用方仍可
 * 取得本轮 token 并先查询数据库确认结果。
 *
 * @throws IllegalStateException 线程锁已失效、状态序列化失败、SQL 写入失败或影响行数不为 1 时抛出
 */
        public void save() {
            checkLock();
            // 基线接管等入口可以替换 state；落库的锁标识必须来自当前实际持锁的会话。
            state.targetLock=lock;
            state.physicalTargetLock=physicalLock;
            state.compatibilityTargetLock=compatibilityLock;
            if(state.complete && state.pendingStopTokens!=null && !state.pendingStopTokens.isEmpty())
                throw new IllegalStateException("旧写入尚未确认停止，不能确认本轮完成");
            state.updatedAtMs=System.currentTimeMillis();
            String sql=exists ? "UPDATE el_sync_checkpoints SET state_json=?,updated_at=UTC_TIMESTAMP() WHERE task_id=?"
                    : "INSERT INTO el_sync_checkpoints(state_json,task_id) VALUES(?,?)";
            try(PreparedStatement q=connection.prepareStatement(sql)) {
                q.setString(1,JSON.writeValueAsString(state)); q.setLong(2,taskId);
                saveAttempted=true;
                if(q.executeUpdate()!=1) throw new IllegalStateException("同步断点写入未确认");
                exists=true;
                completeSaved=state.complete;
            } catch(Exception e) { throw new IllegalStateException("保存同步断点失败，已停止后续批次",e); }
            // 必须在断点写入已确认之后通知；展示失败不得触发批次重放或覆盖断点异常。
            if (progressListener != null) {
                try { progressListener.accept(state); }
                catch (RuntimeException e) { LOG.warn("event=sync.progress.publish_failed task_id={}", taskId, e); }
            }
        }

/**
 * 释放当前会话的目标表命名锁并关闭数据库连接。
 *
 * <p>方法使用会话内 {@code closed} 标记保证重复调用不再操作连接。释放失败时先终止
 * 物理会话，再归还连接池，防止遗留锁阻止后续同步。关闭不会删除同步断点。
 */
        public synchronized void close() {
            if(closed) return;
            closed=true;
            MysqlNamedLock.releaseAndClose(connection, lock, physicalLock, compatibilityLock);
        }
    }
}
