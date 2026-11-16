-- 212 已有元数据库：同步续传及运行进度手工补齐（2026-10-29）。
-- 请在客户端选中实际元数据库（通常为 eltock），在同一连接中执行整个文件，遇错停止。
-- 不创建/切换数据库，不写账号、口令、权限或业务数据，不依赖 el_schema_migrations。
-- 前提：已有 V1–V6 对应的基础结构；已有字段不修改，缺少字段才添加。
SET NAMES utf8mb4;
SELECT DATABASE() AS selected_metadata_database;
SELECT id FROM el_sync_tasks LIMIT 0;
SELECT id FROM el_sync_runs LIMIT 0;

CREATE TABLE IF NOT EXISTS el_datasource_observations (
  owner_user_id BIGINT NOT NULL,
  datasource_id BIGINT NOT NULL,
  config_token VARCHAR(64) NOT NULL,
  observation_started_at BIGINT NOT NULL,
  checked_at BIGINT NOT NULL,
  state VARCHAR(16) NOT NULL,
  observation_source VARCHAR(32) NOT NULL,
  PRIMARY KEY (owner_user_id, datasource_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- el_sync_tasks.timeout_minutes
SET @el_manual_ddl = IF(
  EXISTS (SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='el_sync_tasks' AND COLUMN_NAME='timeout_minutes'),
  'SELECT ''el_sync_tasks.timeout_minutes already exists'' AS result',
  'ALTER TABLE `el_sync_tasks` ADD COLUMN `timeout_minutes` INT NOT NULL DEFAULT 30'
);
PREPARE el_manual_stmt FROM @el_manual_ddl;
EXECUTE el_manual_stmt;
DEALLOCATE PREPARE el_manual_stmt;

-- el_sync_tasks.resumable
SET @el_manual_ddl = IF(
  EXISTS (SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='el_sync_tasks' AND COLUMN_NAME='resumable'),
  'SELECT ''el_sync_tasks.resumable already exists'' AS result',
  'ALTER TABLE `el_sync_tasks` ADD COLUMN `resumable` TINYINT NOT NULL DEFAULT 1'
);
PREPARE el_manual_stmt FROM @el_manual_ddl;
EXECUTE el_manual_stmt;
DEALLOCATE PREPARE el_manual_stmt;

-- el_sync_tasks.stable_key
SET @el_manual_ddl = IF(
  EXISTS (SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='el_sync_tasks' AND COLUMN_NAME='stable_key'),
  'SELECT ''el_sync_tasks.stable_key already exists'' AS result',
  'ALTER TABLE `el_sync_tasks` ADD COLUMN `stable_key` VARCHAR(255) NOT NULL DEFAULT '''''
);
PREPARE el_manual_stmt FROM @el_manual_ddl;
EXECUTE el_manual_stmt;
DEALLOCATE PREPARE el_manual_stmt;

-- el_sync_tasks.batch_size
SET @el_manual_ddl = IF(
  EXISTS (SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='el_sync_tasks' AND COLUMN_NAME='batch_size'),
  'SELECT ''el_sync_tasks.batch_size already exists'' AS result',
  'ALTER TABLE `el_sync_tasks` ADD COLUMN `batch_size` INT NOT NULL DEFAULT 10000'
);
PREPARE el_manual_stmt FROM @el_manual_ddl;
EXECUTE el_manual_stmt;
DEALLOCATE PREPARE el_manual_stmt;

-- el_sync_runs.resumed
SET @el_manual_ddl = IF(
  EXISTS (SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='el_sync_runs' AND COLUMN_NAME='resumed'),
  'SELECT ''el_sync_runs.resumed already exists'' AS result',
  'ALTER TABLE `el_sync_runs` ADD COLUMN `resumed` TINYINT NOT NULL DEFAULT 0'
);
PREPARE el_manual_stmt FROM @el_manual_ddl;
EXECUTE el_manual_stmt;
DEALLOCATE PREPARE el_manual_stmt;

-- el_sync_runs.checkpoint_from
SET @el_manual_ddl = IF(
  EXISTS (SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='el_sync_runs' AND COLUMN_NAME='checkpoint_from'),
  'SELECT ''el_sync_runs.checkpoint_from already exists'' AS result',
  'ALTER TABLE `el_sync_runs` ADD COLUMN `checkpoint_from` VARCHAR(255) NOT NULL DEFAULT '''''
);
PREPARE el_manual_stmt FROM @el_manual_ddl;
EXECUTE el_manual_stmt;
DEALLOCATE PREPARE el_manual_stmt;

-- el_sync_runs.checkpoint_rows
SET @el_manual_ddl = IF(
  EXISTS (SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='el_sync_runs' AND COLUMN_NAME='checkpoint_rows'),
  'SELECT ''el_sync_runs.checkpoint_rows already exists'' AS result',
  'ALTER TABLE `el_sync_runs` ADD COLUMN `checkpoint_rows` BIGINT NOT NULL DEFAULT 0'
);
PREPARE el_manual_stmt FROM @el_manual_ddl;
EXECUTE el_manual_stmt;
DEALLOCATE PREPARE el_manual_stmt;

-- el_sync_runs.progress_json
SET @el_manual_ddl = IF(
  EXISTS (SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='el_sync_runs' AND COLUMN_NAME='progress_json'),
  'SELECT ''el_sync_runs.progress_json already exists'' AS result',
  'ALTER TABLE `el_sync_runs` ADD COLUMN `progress_json` LONGTEXT NULL'
);
PREPARE el_manual_stmt FROM @el_manual_ddl;
EXECUTE el_manual_stmt;
DEALLOCATE PREPARE el_manual_stmt;

CREATE TABLE IF NOT EXISTS el_sync_checkpoints (
  task_id BIGINT NOT NULL PRIMARY KEY,
  state_json LONGTEXT NOT NULL,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS el_arb_checkpoints (
  workflow_id BIGINT NOT NULL PRIMARY KEY,
  state_json LONGTEXT NOT NULL,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='工作流回填与摊平断点';

-- 核对实际定义；已存在但定义不同的字段不会被本脚本自动改写。
SELECT TABLE_NAME,COLUMN_NAME,COLUMN_TYPE,IS_NULLABLE,COLUMN_DEFAULT
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA=DATABASE() AND (
  (TABLE_NAME='el_sync_tasks' AND COLUMN_NAME IN ('timeout_minutes','resumable','stable_key','batch_size'))
  OR (TABLE_NAME='el_sync_runs' AND COLUMN_NAME IN ('resumed','checkpoint_from','checkpoint_rows','progress_json'))
  OR TABLE_NAME IN ('el_sync_checkpoints','el_arb_checkpoints','el_datasource_observations')
) ORDER BY TABLE_NAME,ORDINAL_POSITION;
SET @el_manual_ddl = NULL;

