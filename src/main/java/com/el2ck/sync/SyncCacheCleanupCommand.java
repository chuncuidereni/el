package com.el2ck.sync;

import java.nio.file.Files;

/**
 * 【文件职责】容器部署前的同步缓存强制清理入口。
 *
 * <p>删除同步任务在数据卷里留下的源快照、SFTP 下载缓存、xlsx 转换缓存和轮转日志，
 * 并删除对应的 {@code el_sync_checkpoints} 行，避免“缓存已删、断点还在”导致下次运行
 * 报缓存缺失。应用运行期间的常规清理仍由 {@link SyncCacheCleanup} 负责；本命令只给
 * 部署脚本在停机窗口使用。
 *
 * <p>安全约束：
 * <ul>
 *   <li>先连元数据库；连不上直接失败，绝不删缓存后留下无法恢复的断点；</li>
 *   <li>只删除 {@code sync-resume/sftp/task-N}、{@code sync-resume/snapshots/sftp/task-N}
 *       这类固定布局，不遍历 uploads、DuckDB 主库或其它目录；</li>
 *   <li>任务仍存在 running 运行记录时跳过；部署脚本先停容器，并以
 *       {@code SYNC_CACHE_CLEANUP_FORCE=true} 忽略历史遗留的 running 行；</li>
 *   <li>拒绝符号链接，删除范围必须落在数据目录内。</li>
 * </ul>
 */
public final class SyncCacheCleanupCommand {
    private static final java.nio.file.LinkOption NOFOLLOW = java.nio.file.LinkOption.NOFOLLOW_LINKS;

    private SyncCacheCleanupCommand() { }

    public static void main(String[] args) {
        try {
            java.nio.file.Path dataDirectory = java.nio.file.Paths
                    .get(env("APP_DATADIR", "/app/data")).toAbsolutePath().normalize();
            String url = env("APP_METADATA_URL", null);
            String username = env("APP_METADATA_USERNAME", null);
            String password = env("APP_METADATA_PASSWORD", null);
            if (url == null || username == null || password == null) {
                throw new IllegalStateException("Missing metadata connection environment");
            }
            Summary summary = new Summary();
            java.util.Set<Long> cleanedTasks = new java.util.HashSet<>();
            // 部署脚本已先停掉容器；force=true 时不再因历史遗留的 running 行跳过。
            boolean force = "true".equalsIgnoreCase(env("SYNC_CACHE_CLEANUP_FORCE", "false"));
            // 显式加载驱动：部署脚本用 -cp 直接跑主类，不能依赖 Spring Boot 的自动配置。
            Class.forName("com.mysql.cj.jdbc.Driver");
            try (java.sql.Connection connection =
                         java.sql.DriverManager.getConnection(url, username, password)) {
                connection.setAutoCommit(true);
                cleanFamily(connection, dataDirectory.resolve("sync-resume").resolve("sftp"), summary, cleanedTasks, force);
                cleanFamily(connection, dataDirectory.resolve("sync-resume").resolve("snapshots").resolve("sftp"), summary, cleanedTasks, force);
                cleanChildren(dataDirectory.resolve("duckdb").resolve("xlsx-cache"), summary);
                cleanRotatedLogs(dataDirectory.resolve("logs"), summary);
            }
            System.out.println("Sync cache cleanup: tasks=" + summary.tasks
                    + " files=" + summary.files + " bytes=" + summary.bytes
                    + " skipped_running=" + summary.skippedRunning);
        } catch (Exception e) {
            // 不输出 JDBC URL、账号或口令。
            System.err.println("Sync cache cleanup failed: " + e.getClass().getSimpleName());
            System.exit(1);
        }
    }

    private static void cleanFamily(java.sql.Connection connection, java.nio.file.Path family,
                                    Summary summary, java.util.Set<Long> cleanedTasks, boolean force) throws Exception {
        if (!Files.isDirectory(family, NOFOLLOW)) return;
        java.nio.file.Path base = family.toRealPath();
        try (java.nio.file.DirectoryStream<java.nio.file.Path> tasks = Files.newDirectoryStream(family)) {
            for (java.nio.file.Path task : tasks) {
                String name = task.getFileName().toString();
                if (!name.matches("task-[1-9][0-9]*") || !Files.isDirectory(task, NOFOLLOW)) continue;
                long taskId;
                try { taskId = Long.parseLong(name.substring("task-".length())); }
                catch (NumberFormatException invalid) { continue; }
                if (!force && hasRunningRun(connection, taskId)) {
                    summary.skippedRunning++;
                    continue;
                }
                deleteTree(task, base, summary);
                deleteCheckpoint(connection, taskId);
                if (cleanedTasks.add(taskId)) summary.tasks++;
            }
        }
    }

    private static boolean hasRunningRun(java.sql.Connection connection, long taskId) throws Exception {
        try (java.sql.PreparedStatement query = connection.prepareStatement(
                "SELECT COUNT(*) FROM el_sync_runs WHERE task_id=? AND status='running'")) {
            query.setLong(1, taskId);
            try (java.sql.ResultSet result = query.executeQuery()) {
                return result.next() && result.getLong(1) > 0;
            }
        }
    }

    private static void deleteCheckpoint(java.sql.Connection connection, long taskId) throws Exception {
        try (java.sql.PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM el_sync_checkpoints WHERE task_id=?")) {
            delete.setLong(1, taskId);
            delete.executeUpdate();
        }
    }

    /** 删除一个 task-N 目录下的全部内容；不跟随符号链接，删除前再次核对真实路径。 */
    private static void deleteTree(java.nio.file.Path task, java.nio.file.Path base,
                                   Summary summary) throws Exception {
        if (Files.isSymbolicLink(task) || !task.toRealPath().startsWith(base)) return;
        Files.walkFileTree(task, java.util.EnumSet.noneOf(java.nio.file.FileVisitOption.class),
                Integer.MAX_VALUE, new java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
                    @Override
                    public java.nio.file.FileVisitResult visitFile(java.nio.file.Path file,
                                                                   java.nio.file.attribute.BasicFileAttributes attrs)
                            throws java.io.IOException {
                        // 只删除链接本身，绝不跟随到数据目录之外。
                        if (Files.isSymbolicLink(file)) {
                            Files.delete(file);
                            return java.nio.file.FileVisitResult.CONTINUE;
                        }
                        long size = attrs.size();
                        Files.delete(file);
                        summary.files++;
                        summary.bytes += size;
                        return java.nio.file.FileVisitResult.CONTINUE;
                    }

                    @Override
                    public java.nio.file.FileVisitResult postVisitDirectory(java.nio.file.Path directory,
                                                                            java.io.IOException error)
                            throws java.io.IOException {
                        if (error != null) throw error;
                        Files.delete(directory);
                        return java.nio.file.FileVisitResult.CONTINUE;
                    }
                });
    }

    /** 删除目录下的普通文件与子目录（xlsx 缓存可重建）；不跟随符号链接。 */
    private static void cleanChildren(java.nio.file.Path directory, Summary summary) throws Exception {
        if (!Files.isDirectory(directory, NOFOLLOW)) return;
        java.nio.file.Path base = directory.toRealPath();
        try (java.nio.file.DirectoryStream<java.nio.file.Path> children = Files.newDirectoryStream(directory)) {
            for (java.nio.file.Path child : children) {
                if (Files.isSymbolicLink(child)) {
                    Files.delete(child);
                    continue;
                }
                if (!child.toRealPath().startsWith(base)) continue;
                if (Files.isDirectory(child, NOFOLLOW)) deleteTree(child, base, summary);
                else {
                    long size = Files.size(child);
                    Files.delete(child);
                    summary.files++;
                    summary.bytes += size;
                }
            }
        }
    }

    /** 只删除 logs 目录下由日志轮转产生的 .gz 文件，当前正在写的 .log 不动。 */
    private static void cleanRotatedLogs(java.nio.file.Path directory, Summary summary) throws Exception {
        if (!Files.isDirectory(directory, NOFOLLOW)) return;
        java.nio.file.Path base = directory.toRealPath();
        try (java.nio.file.DirectoryStream<java.nio.file.Path> files = Files.newDirectoryStream(directory, "*.gz")) {
            for (java.nio.file.Path file : files) {
                if (!Files.isRegularFile(file, NOFOLLOW) || Files.isSymbolicLink(file)) continue;
                if (!file.toRealPath().startsWith(base)) continue;
                long size = Files.size(file);
                Files.delete(file);
                summary.files++;
                summary.bytes += size;
            }
        }
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isEmpty() ? fallback : value;
    }

    private static final class Summary {
        long tasks;
        long files;
        long bytes;
        long skippedRunning;
    }
}
