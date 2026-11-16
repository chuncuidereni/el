-- 代码审查修复：将 ARB 进度与 SQL 审计分开。
-- 前提：已手工执行 20261029_resume_and_progress.sql；选中实际元数据库，在同一会话执行。
-- 首次升级请先停止旧版本任务，禁止新旧版本混跑（旧版本不持有新的同步任务会话锁）。
-- 只添加缺失列，不改历史迁移 checksum、账号口令或业务数据；允许重复执行。
SET NAMES utf8mb4;
SELECT DATABASE() AS selected_metadata_database;
SELECT id FROM el_arb_workflow_runs LIMIT 0;
SET @el_review_ddl = IF(
  EXISTS (SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
          AND TABLE_NAME='el_arb_workflow_runs' AND COLUMN_NAME='progress'),
  'SELECT ''el_arb_workflow_runs.progress already exists'' AS result',
  'ALTER TABLE `el_arb_workflow_runs` ADD COLUMN `progress` TEXT NULL'
);
PREPARE el_review_stmt FROM @el_review_ddl;
EXECUTE el_review_stmt;
DEALLOCATE PREPARE el_review_stmt;
SET @el_review_ddl = NULL;
SELECT TABLE_NAME,COLUMN_NAME,COLUMN_TYPE FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='el_arb_workflow_runs' AND COLUMN_NAME='progress';
