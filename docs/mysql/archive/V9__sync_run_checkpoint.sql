-- 运行历史里的断点续传信息:让每次运行能看出它是不是从断点继续、从哪个键、起始已确认多少行。
-- 断点内容本身仍在 el_sync_checkpoints;这里只存运行维度的只读快照,供列表/历史查询。
ALTER TABLE el_sync_runs
  ADD COLUMN resumed TINYINT NOT NULL DEFAULT 0,
  ADD COLUMN checkpoint_from VARCHAR(255) NOT NULL DEFAULT '',
  ADD COLUMN checkpoint_rows BIGINT NOT NULL DEFAULT 0;
