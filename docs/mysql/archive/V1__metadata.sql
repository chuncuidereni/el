-- Platform metadata only. ClickHouse business tables are not affected.

-- MySQL 8.0.13+ / MariaDB 10.11; UTF-8 case-sensitive identifiers and values.

CREATE TABLE IF NOT EXISTS `el_schema_migrations` (
  `version` INT NOT NULL PRIMARY KEY,
  `name` VARCHAR(255) NOT NULL,
  `checksum` VARCHAR(64) NOT NULL,
  `applied_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS `el_connections` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `name` VARCHAR(255) NOT NULL,
  `host` LONGTEXT NOT NULL DEFAULT ('127.0.0.1'),
  `port` BIGINT NOT NULL DEFAULT 9000,
  `database` LONGTEXT NOT NULL DEFAULT ('default'),
  `username` LONGTEXT NOT NULL DEFAULT ('default'),
  `password_enc` LONGTEXT NOT NULL DEFAULT (''),
  `secure` BIGINT NOT NULL DEFAULT 0,
  `description` LONGTEXT NOT NULL DEFAULT (''),
  `is_default` BIGINT NOT NULL DEFAULT 0,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `dial_timeout_ms` BIGINT,
  `query_timeout_ms` BIGINT,
  `conn_max_lifetime_min` BIGINT,
  `read_only` BIGINT,
  `params_json` LONGTEXT NOT NULL DEFAULT ('{}'),
  PRIMARY KEY (`id`),
  UNIQUE KEY `el_connections_unique_0` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS `el_app_settings` (
  `key` VARCHAR(255) NOT NULL,
  `value` LONGTEXT NOT NULL,
  PRIMARY KEY (`key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS `el_user_remarks` (
  `conn_id` BIGINT NOT NULL,
  `user_name` VARCHAR(255) NOT NULL,
  `remark` LONGTEXT NOT NULL DEFAULT (''),
  PRIMARY KEY (`conn_id`, `user_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS `el_data_sources` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `code` VARCHAR(255) NOT NULL,
  `name` LONGTEXT NOT NULL,
  `type` LONGTEXT NOT NULL,
  `config_enc` LONGTEXT NOT NULL DEFAULT ('{}'),
  `description` LONGTEXT NOT NULL DEFAULT (''),
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `group_id` BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `el_idx_data_sources_group` (`group_id`),
  UNIQUE KEY `el_data_sources_unique_1` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS `el_sync_tasks` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `name` LONGTEXT NOT NULL,
  `description` LONGTEXT NOT NULL DEFAULT (''),
  `data_source_id` BIGINT NOT NULL,
  `source_object` LONGTEXT NOT NULL DEFAULT (''),
  `connection_id` BIGINT NOT NULL,
  `target_db` LONGTEXT NOT NULL,
  `target_table` LONGTEXT NOT NULL,
  `mapping_json` LONGTEXT NOT NULL DEFAULT ('[]'),
  `mode` LONGTEXT NOT NULL DEFAULT ('full'),
  `incr_column` LONGTEXT NOT NULL DEFAULT (''),
  `order_key` LONGTEXT NOT NULL DEFAULT (''),
  `enabled` BIGINT NOT NULL DEFAULT 1,
  `truncate` BIGINT NOT NULL DEFAULT 1,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `auto_create_table` BIGINT NOT NULL DEFAULT 1,
  `schedule_type` LONGTEXT NOT NULL DEFAULT ('manual'),
  `cron_expr` LONGTEXT NOT NULL DEFAULT (''),
  `source_id_column` LONGTEXT NOT NULL DEFAULT (''),
  `remote_path` LONGTEXT NOT NULL DEFAULT (''),
  `file_pattern` LONGTEXT NOT NULL DEFAULT ('*.csv'),
  `has_header` BIGINT NOT NULL DEFAULT 1,
  `schema_json` LONGTEXT NOT NULL DEFAULT ('[]'),
  `is_ods` BIGINT NOT NULL DEFAULT 0,
  `partition_expr` LONGTEXT NOT NULL DEFAULT (''),
  `sorting_keys` LONGTEXT NOT NULL DEFAULT (''),
  `primary_keys` LONGTEXT NOT NULL DEFAULT (''),
  `phone_column` LONGTEXT NOT NULL DEFAULT (''),
  `arb_include` BIGINT NOT NULL DEFAULT 0,
  `arb_priority` BIGINT NOT NULL DEFAULT 0,
  `arb_mdn_norm` LONGTEXT NOT NULL DEFAULT (''),
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS `el_sync_runs` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `task_id` BIGINT NOT NULL,
  `status` LONGTEXT NOT NULL DEFAULT ('running'),
  `started_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `finished_at` VARCHAR(32) NOT NULL DEFAULT '',
  `rows_read` BIGINT NOT NULL DEFAULT 0,
  `rows_written` BIGINT NOT NULL DEFAULT 0,
  `error` LONGTEXT NOT NULL DEFAULT (''),
  `note` LONGTEXT NOT NULL DEFAULT (''),
  PRIMARY KEY (`id`),
  KEY `el_idx_sync_runs_task` (`task_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS `el_wf_tasks` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `name` LONGTEXT NOT NULL,
  `description` LONGTEXT NOT NULL DEFAULT (''),
  `nodes_json` LONGTEXT NOT NULL DEFAULT ('[]'),
  `edges_json` LONGTEXT NOT NULL DEFAULT ('[]'),
  `enabled` BIGINT NOT NULL DEFAULT 1,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `cron_expr` LONGTEXT NOT NULL DEFAULT (''),
  `group_id` BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `el_idx_wf_tasks_group` (`group_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS `el_wf_runs` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `workflow_id` BIGINT NOT NULL,
  `status` LONGTEXT NOT NULL DEFAULT ('running'),
  `started_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `finished_at` VARCHAR(32) NOT NULL DEFAULT '',
  `error` LONGTEXT NOT NULL DEFAULT (''),
  PRIMARY KEY (`id`),
  `running_owner_id` BIGINT GENERATED ALWAYS AS (CASE WHEN `status` = 'running' THEN `workflow_id` ELSE NULL END) STORED,
  UNIQUE KEY `el_idx_wf_runs_one_running` (`running_owner_id`),
  KEY `el_idx_wf_runs_wf` (`workflow_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS `el_wf_run_logs` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `run_id` BIGINT NOT NULL,
  `node_id` LONGTEXT NOT NULL,
  `node_type` LONGTEXT NOT NULL DEFAULT (''),
  `status` LONGTEXT NOT NULL DEFAULT ('running'),
  `rows_in` BIGINT NOT NULL DEFAULT 0,
  `rows_out` BIGINT NOT NULL DEFAULT 0,
  `duration_ms` BIGINT NOT NULL DEFAULT 0,
  `error` LONGTEXT NOT NULL DEFAULT (''),
  PRIMARY KEY (`id`),
  KEY `el_idx_wf_run_logs_run` (`run_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS `el_ds_groups` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `name` LONGTEXT NOT NULL,
  `remark` LONGTEXT NOT NULL DEFAULT (''),
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS `el_db_groups` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `connection_id` BIGINT NOT NULL,
  `name` LONGTEXT NOT NULL,
  `remark` LONGTEXT NOT NULL DEFAULT (''),
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `el_idx_db_groups_connection` (`connection_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS `el_group_dbs` (
  `group_id` BIGINT NOT NULL,
  `db_name` VARCHAR(255) NOT NULL,
  PRIMARY KEY (`group_id`, `db_name`),
  KEY `el_idx_group_dbs_name` (`db_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS `el_sync_files` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `task_id` BIGINT NOT NULL,
  `remote_path` LONGTEXT NOT NULL,
  `size` BIGINT NOT NULL DEFAULT 0,
  `mtime` LONGTEXT NOT NULL DEFAULT (''),
  `synced_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  `remote_path_hash` BINARY(32) GENERATED ALWAYS AS (UNHEX(SHA2(`remote_path`, 256))) STORED,
  UNIQUE KEY `el_sync_files_unique_0` (`task_id`, `remote_path_hash`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS `el_tbl_groups` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `connection_id` BIGINT NOT NULL,
  `db` LONGTEXT NOT NULL,
  `name` LONGTEXT NOT NULL,
  `remark` LONGTEXT NOT NULL DEFAULT (''),
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS `el_tbl_group_tables` (
  `group_id` BIGINT NOT NULL,
  `table_name` VARCHAR(255) NOT NULL,
  PRIMARY KEY (`group_id`, `table_name`),
  KEY `el_idx_tbl_group_tables_tbl` (`table_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS `el_query_workflows` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `name` LONGTEXT NOT NULL,
  `description` LONGTEXT NOT NULL DEFAULT (''),
  `enabled` BIGINT NOT NULL DEFAULT 1,
  `config_json` LONGTEXT NOT NULL DEFAULT ('{}'),
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS `el_query_workflow_runs` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `workflow_id` BIGINT NOT NULL,
  `status` LONGTEXT NOT NULL DEFAULT ('running'),
  `started_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `finished_at` VARCHAR(32) NOT NULL DEFAULT '',
  `rows_read` BIGINT NOT NULL DEFAULT 0,
  `generated_sql` LONGTEXT NOT NULL DEFAULT (''),
  `error` LONGTEXT NOT NULL DEFAULT (''),
  PRIMARY KEY (`id`),
  `running_owner_id` BIGINT GENERATED ALWAYS AS (CASE WHEN `status` = 'running' THEN `workflow_id` ELSE NULL END) STORED,
  UNIQUE KEY `el_idx_qw_runs_one_running` (`running_owner_id`),
  KEY `el_idx_qw_runs_wf` (`workflow_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS `el_sql_query_groups` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `name` LONGTEXT NOT NULL,
  `remark` LONGTEXT NOT NULL DEFAULT (''),
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS `el_sql_queries` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `name` LONGTEXT NOT NULL,
  `description` LONGTEXT NOT NULL DEFAULT (''),
  `database` LONGTEXT NOT NULL DEFAULT ('ods_data'),
  `sql_text` LONGTEXT NOT NULL DEFAULT (''),
  `group_id` BIGINT NOT NULL DEFAULT 0,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `visual_config` LONGTEXT NOT NULL DEFAULT (''),
  PRIMARY KEY (`id`),
  KEY `el_idx_sql_queries_group` (`group_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS `el_wf_groups` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `name` LONGTEXT NOT NULL,
  `remark` LONGTEXT NOT NULL DEFAULT (''),
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS `el_wide_table_tasks` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `name` LONGTEXT NOT NULL,
  `description` LONGTEXT NOT NULL DEFAULT (''),
  `enabled` BIGINT NOT NULL DEFAULT 1,
  `config_json` LONGTEXT NOT NULL DEFAULT ('{}'),
  `target_db` LONGTEXT NOT NULL DEFAULT ('dwd_data'),
  `target_table` LONGTEXT NOT NULL,
  `order_key` LONGTEXT NOT NULL DEFAULT (''),
  `last_run_at` VARCHAR(32),
  `last_status` LONGTEXT,
  `last_rows` BIGINT NOT NULL DEFAULT 0,
  `last_error` LONGTEXT,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS `el_wide_table_runs` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `task_id` BIGINT NOT NULL,
  `status` LONGTEXT NOT NULL,
  `rows` BIGINT NOT NULL DEFAULT 0,
  `started_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `finished_at` VARCHAR(32),
  `error` LONGTEXT,
  `sql` LONGTEXT,
  PRIMARY KEY (`id`),
  `running_owner_id` BIGINT GENERATED ALWAYS AS (CASE WHEN `status` = 'running' THEN `task_id` ELSE NULL END) STORED,
  UNIQUE KEY `el_idx_wt_runs_one_running` (`running_owner_id`),
  KEY `el_idx_wt_runs_task` (`task_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS `el_arb_workflows` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `name` LONGTEXT NOT NULL,
  `description` LONGTEXT NOT NULL DEFAULT (''),
  `enabled` BIGINT NOT NULL DEFAULT 1,
  `connection_id` BIGINT NOT NULL DEFAULT 0,
  `target_db` LONGTEXT NOT NULL DEFAULT (''),
  `target_table` LONGTEXT NOT NULL DEFAULT (''),
  `config_json` LONGTEXT NOT NULL DEFAULT ('{}'),
  `last_run_at` VARCHAR(32),
  `last_status` LONGTEXT,
  `last_rows` BIGINT,
  `last_error` LONGTEXT,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS `el_arb_workflow_runs` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `workflow_id` BIGINT NOT NULL,
  `action` LONGTEXT NOT NULL DEFAULT ('setup'),
  `status` LONGTEXT NOT NULL DEFAULT ('running'),
  `rows` BIGINT NOT NULL DEFAULT 0,
  `sql` LONGTEXT NOT NULL DEFAULT (''),
  `error` LONGTEXT NOT NULL DEFAULT (''),
  `started_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `finished_at` VARCHAR(32),
  PRIMARY KEY (`id`),
  `running_owner_id` BIGINT GENERATED ALWAYS AS (CASE WHEN `status` = 'running' THEN `workflow_id` ELSE NULL END) STORED,
  UNIQUE KEY `el_idx_arb_runs_one_running` (`running_owner_id`),
  KEY `el_idx_arb_runs_wf` (`workflow_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS `el_app_users` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `username` VARCHAR(255) NOT NULL,
  `password_hash` LONGTEXT NOT NULL,
  `salt` LONGTEXT NOT NULL,
  `role` LONGTEXT NOT NULL DEFAULT ('user'),
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `el_app_users_unique_0` (`username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
