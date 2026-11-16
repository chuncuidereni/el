package com.el2ck.sync;

import com.el2ck.config.AppConfig;
import com.el2ck.service.sync.SyncExecutionGuard;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import javax.sql.DataSource;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 补偿清理同步源快照与 SFTP 下载缓存。成功运行仍由执行引擎立即清理；本组件处理
 * 进程退出、清理失败、删除任务和替换轮次后留下的文件，不修改任何数据库记录。
 *
 * <p>删除依据是数据库中的当前断点，而非文件年龄或最近一次运行成功/失败状态。
 * 持有与执行/删除任务相同的 MySQL 任务锁，锁内重新读取状态，再获取本地轮次文件锁。
 * 当前未完成轮次必须保留；无法确认数据库状态、锁或目录归属时不删除。
 */
@Component
public final class SyncCacheCleanup {
    private static final Logger LOG = LoggerFactory.getLogger(SyncCacheCleanup.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final LinkOption NOFOLLOW = LinkOption.NOFOLLOW_LINKS;
    static final String STATE_SQL = "SELECT "
            + "(SELECT COUNT(*) FROM el_sync_tasks WHERE id=?) AS task_exists,"
            + "(SELECT state_json FROM el_sync_checkpoints WHERE task_id=?) AS state_json,"
            + "EXISTS(SELECT 1 FROM el_sync_runs WHERE task_id=? AND status='running') AS running";

    private final Path dataDirectory;
    private final SyncExecutionGuard guard;
    private final JdbcTemplate jdbc;
    private final boolean enabled;
    private final AtomicBoolean running = new AtomicBoolean();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(action -> {
        Thread thread = new Thread(action, "sync-cache-cleanup");
        thread.setDaemon(true);
        return thread;
    });

    @Autowired
    public SyncCacheCleanup(AppConfig config, DataSource database, SyncExecutionGuard guard,
                            @Value("${app.sync-cache-cleanup.enabled:true}") boolean enabled) {
        this(Paths.get(config.getDataDir()), guard, new JdbcTemplate(database), enabled);
    }

    SyncCacheCleanup(Path dataDirectory, SyncExecutionGuard guard, JdbcTemplate jdbc, boolean enabled) {
        this.dataDirectory = dataDirectory.toAbsolutePath().normalize();
        this.guard = guard;
        this.jdbc = jdbc;
        this.jdbc.setQueryTimeout(5);
        this.enabled = enabled;
    }

    /** 启动一分钟后补扫历史缓存，以后每十分钟一次；耗时文件删除不占用全局定时线程。 */
    @Scheduled(initialDelayString = "${app.sync-cache-cleanup.initial-delay-ms:60000}",
            fixedDelayString = "${app.sync-cache-cleanup.interval-ms:600000}")
    public void schedule() {
        if (!enabled || worker.isShutdown() || !running.compareAndSet(false, true)) return;
        try {
            worker.execute(() -> {
                try { sweep(); }
                finally { running.set(false); }
            });
        } catch (java.util.concurrent.RejectedExecutionException shutdown) {
            running.set(false);
        }
    }

    @PreDestroy
    public void shutdown() { worker.shutdownNow(); }

    static final class Totals {
        long files, bytes, rounds;
        int failures;
    }

    /** 只扫描程序约定的两种目录布局，不遍历 uploads、DuckDB、日志或其他数据卷。 */
    Totals sweep() {
        Totals totals = new Totals();
        if (!enabled) return totals;
        try {
            if (!Files.isDirectory(dataDirectory)) return totals;
            // 用户配置的数据根允许是挂载点；从其真实路径开始，后续每级拒绝符号链接。
            Path base = dataDirectory.toRealPath();
            Path resume = childDirectory(base, "sync-resume");
            if (resume == null) return totals;
            scanFamily(childDirectory(resume, "sftp"), totals);
            Path snapshots = childDirectory(resume, "snapshots");
            if (snapshots != null) scanFamily(childDirectory(snapshots, "sftp"), totals);
        } catch (IOException | RuntimeException error) {
            totals.failures++;
            LOG.warn("event=sync.cache.scan_failed error_type={}", error.getClass().getSimpleName());
        }
        if (totals.files > 0 || totals.rounds > 0 || totals.failures > 0)
            LOG.info("event=sync.cache.cleanup files={} bytes={} rounds={} failures={}",
                    totals.files, totals.bytes, totals.rounds, totals.failures);
        return totals;
    }

    private static Path childDirectory(Path parent, String name) {
        Path path = parent.resolve(name);
        return Files.isDirectory(path, NOFOLLOW) ? path : null;
    }

    private void scanFamily(Path family, Totals totals) throws IOException {
        if (family == null) return;
        try (DirectoryStream<Path> tasks = Files.newDirectoryStream(family)) {
            for (Path task : tasks) {
                if (Thread.currentThread().isInterrupted()) return;
                String name = task.getFileName().toString();
                if (!name.matches("task-[1-9][0-9]*") || !Files.isDirectory(task, NOFOLLOW)) continue;
                final long taskId;
                try { taskId = Long.parseLong(name.substring(5)); }
                catch (NumberFormatException invalid) { continue; }
                try (SyncExecutionGuard.Lease lease = guard.tryReserveForMaintenance(taskId)) {
                    if (lease == null) continue;
                    lease.check();
                    Retention retention = loadRetention(taskId);
                    if (retention.keepAll) continue;
                    try (DirectoryStream<Path> rounds = Files.newDirectoryStream(task)) {
                        for (Path round : rounds) {
                            String token = round.getFileName().toString();
                            if (!token.matches("[a-f0-9]{32}") || !Files.isDirectory(round, NOFOLLOW)
                                    || token.equals(retention.keepToken)) continue;
                            cleanRound(round, lease, totals);
                        }
                    }
                    // 仅移除空任务目录；未知文件/目录保持原样，下轮继续扫描。
                    fence(lease);
                    try { Files.delete(task); }
                    catch (java.nio.file.DirectoryNotEmptyException retained) { /* Expected. */ }
                } catch (IOException | RuntimeException error) {
                    totals.failures++;
                    LOG.warn("event=sync.cache.cleanup_failed task_id={} error_type={}",
                            taskId, error.getClass().getSimpleName());
                }
            }
        }
    }

    /** 已删除任务可清全部；仍有 running、缺断点、损坏断点均保守保留。 */
    Retention loadRetention(long taskId) {
        return jdbc.queryForObject(STATE_SQL, (row, index) -> {
            if (row.getBoolean("running")) return Retention.ALL;
            if (row.getLong("task_exists") == 0) return Retention.NONE;
            return retention(row.getString("state_json"));
        }, taskId, taskId, taskId);
    }

    static final class Retention {
        static final Retention ALL = new Retention(true, null);
        static final Retention NONE = new Retention(false, null);
        final boolean keepAll;
        final String keepToken;
        Retention(boolean keepAll, String keepToken) { this.keepAll = keepAll; this.keepToken = keepToken; }
    }

    static Retention retention(String payload) {
        if (payload == null) return Retention.ALL;
        try {
            // 不反序列化 State：其 token 默认值会把缺字段的损坏断点伪装成新的合法轮次。
            JsonNode state = JSON.readTree(payload);
            if (state == null || !state.isObject() || !state.path("token").isTextual()
                    || !state.path("complete").isBoolean()) return Retention.ALL;
            String token = SftpResumeCache.cacheToken(state.get("token").asText());
            if (token == null) return Retention.ALL;
            // 行值分页完全依赖数据库断点，不再读取旧的未完成快照。任务空闲且持锁时即可回收旧缓存。
            if (CkRowSegmentReader.MODE.equals(state.path("readerMode").asText())
                    && state.path("sourceIdentity").asText().matches("[0-9a-f]{64}")
                    && state.path("snapshotRows").asLong(-1)==0
                    && state.path("snapshotHashes").isArray() && state.path("snapshotHashes").size()==0
                    && state.path("snapshotComplete").isBoolean() && !state.path("snapshotComplete").asBoolean())
                return Retention.NONE;
            if (!state.get("complete").asBoolean()) return new Retention(false, token);
            if (state.hasNonNull("pending") || state.hasNonNull("pendingAction")) return Retention.ALL;
            return Retention.NONE;
        } catch (IOException invalid) { return Retention.ALL; }
    }

    private static void fence(SyncExecutionGuard.Lease lease) {
        if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("缓存清理已停止");
        lease.check();
    }

    /**
     * 仅删除已识别的平面轮次目录。先全量检查内容，再在轮次锁内逐文件删除；不递归，
     * 不跟随链接，发现未知文件整轮保留。数据库不可用或失锁会立即停止后续删除。
     */
    private static void cleanRound(Path round, SyncExecutionGuard.Lease lease, Totals totals) throws IOException {
        fence(lease);
        requireRealDirectory(round);
        Path lockPath = round.resolve("round.lock");
        if (Files.isSymbolicLink(lockPath)) return;
        try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, NOFOLLOW)) {
            FileLock lock;
            try { lock = channel.tryLock(); }
            catch (OverlappingFileLockException busy) { return; }
            if (lock == null) return;
            try {
                try (DirectoryStream<Path> contents = Files.newDirectoryStream(round)) {
                    for (Path file : contents) {
                        String name = file.getFileName().toString();
                        if (!Files.isRegularFile(file, NOFOLLOW) || !(name.equals("round.lock")
                                || name.equals("snapshot-manifest.sha256")
                                || name.matches("file-[0-9]+\\.(csv|part|meta|meta\\.tmp|json|xlsx)"))) return;
                    }
                }
                try (DirectoryStream<Path> contents = Files.newDirectoryStream(round)) {
                    for (Path file : contents) {
                        if (file.equals(lockPath)) continue;
                        fence(lease);
                        requireRealDirectory(round);
                        long bytes = Files.readAttributes(file, java.nio.file.attribute.BasicFileAttributes.class, NOFOLLOW).size();
                        Files.delete(file);
                        totals.files++; totals.bytes += bytes;
                    }
                }
            } finally { lock.release(); }
        }
        fence(lease);
        requireRealDirectory(round);
        Files.deleteIfExists(lockPath);
        Files.delete(round);
        totals.rounds++;
    }

    /** 再次核对祖先路径，拒绝扫描后被改成链接的目录。 */
    private static void requireRealDirectory(Path round) throws IOException {
        if (!Files.isDirectory(round, NOFOLLOW) || !round.equals(round.toRealPath()))
            throw new IOException("缓存目录路径已改变");
    }
}
