-- 在实际元数据库中执行；仅增加日志逻辑删除标记和查询索引，不删除或改写历史数据。
-- 可重复执行，兼容 MySQL / MariaDB；应用启动仍只读检查结构。
SET NAMES utf8mb4;
SET @el_manual_ddl = IF(
  EXISTS (SELECT 1 FROM information_schema.COLUMNS
          WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='el_sync_runs' AND COLUMN_NAME='deleted_at'),
  'SELECT ''el_sync_runs.deleted_at already exists'' AS result',
  'ALTER TABLE `el_sync_runs` ADD COLUMN `deleted_at` DATETIME NULL DEFAULT NULL COMMENT ''执行日志逻辑删除时间'''
);
PREPARE el_manual_stmt FROM @el_manual_ddl;
EXECUTE el_manual_stmt;
DEALLOCATE PREPARE el_manual_stmt;

SET @el_manual_ddl = IF(
  EXISTS (SELECT 1 FROM information_schema.STATISTICS
          WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='el_sync_runs' AND INDEX_NAME='idx_sync_runs_visible_history'),
  'SELECT ''idx_sync_runs_visible_history already exists'' AS result',
  'CREATE INDEX `idx_sync_runs_visible_history` ON `el_sync_runs` (`task_id`,`deleted_at`,`id`)'
);
PREPARE el_manual_stmt FROM @el_manual_ddl;
EXECUTE el_manual_stmt;
DEALLOCATE PREPARE el_manual_stmt;
