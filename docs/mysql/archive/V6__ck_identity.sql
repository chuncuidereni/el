-- CK 真实用户与表级权限:实例、用户绑定、表资产生命周期、授权状态与可恢复操作。
-- V1-V5 已发布,不可修改;本文件只做追加。

-- 物理 ClickHouse 实例。多个连接别名指向同一实例时共用一个 ck_instance_id。
CREATE TABLE IF NOT EXISTS `el_ck_instances` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `instance_key` VARCHAR(512) NOT NULL COMMENT '协议+主机+端口+secure 归一化后的实例键',
  `name` VARCHAR(255) NOT NULL,
  `host` VARCHAR(255) NOT NULL,
  `port` INT NOT NULL,
  `protocol` VARCHAR(16) NOT NULL DEFAULT 'native',
  `secure` BOOLEAN NOT NULL DEFAULT FALSE,
  `management_connection_id` BIGINT NULL COMMENT '用于账号/授权管理的连接别名',
  `status` VARCHAR(24) NOT NULL DEFAULT 'active',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `el_ck_instance_key` (`instance_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

ALTER TABLE `el_connections`
  ADD COLUMN `ck_instance_id` BIGINT NULL COMMENT '所属物理 ClickHouse 实例',
  ADD KEY `el_connection_instance` (`ck_instance_id`);

-- 平台用户与真实 CK 账号的绑定。凭据只保存密文,不返回浏览器。
CREATE TABLE IF NOT EXISTS `el_user_ck_bindings` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `user_id` BIGINT NOT NULL,
  `ck_instance_id` BIGINT NOT NULL,
  `ck_username` VARCHAR(128) NOT NULL,
  `ck_user_uuid` VARCHAR(64) NULL,
  `credential_ciphertext` LONGTEXT NOT NULL,
  `credential_version` BIGINT NOT NULL DEFAULT 1,
  `status` VARCHAR(24) NOT NULL DEFAULT 'pending' COMMENT 'pending/active/failed/disabling/disabled',
  `last_error` VARCHAR(500) NULL COMMENT '脱敏错误,不含口令',
  `last_verified_at` DATETIME NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `el_binding_user_instance` (`user_id`, `ck_instance_id`),
  UNIQUE KEY `el_binding_instance_user` (`ck_instance_id`, `ck_username`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- 表资产补充物理实例、创建者、生命周期与权限版本。
ALTER TABLE `el_data_assets`
  ADD COLUMN `ck_instance_id` BIGINT NULL,
  ADD COLUMN `creator_user_id` BIGINT NULL COMMENT '原始创建者;历史未知为空',
  ADD COLUMN `creator_name_snapshot` VARCHAR(255) NULL COMMENT '创建时的显示名快照',
  ADD COLUMN `generation` INT NOT NULL DEFAULT 1 COMMENT '生命周期代次,重建时递增',
  ADD COLUMN `physical_table_uuid` VARCHAR(64) NULL COMMENT '物理对象核对标识',
  ADD COLUMN `status` VARCHAR(24) NOT NULL DEFAULT 'active' COMMENT 'provisioning/active/changing/deleting/missing/failed/deleted',
  ADD COLUMN `origin_type` VARCHAR(32) NOT NULL DEFAULT 'legacy' COMMENT 'manual/sync/workflow/history',
  ADD COLUMN `origin_id` BIGINT NULL,
  ADD COLUMN `permission_version` BIGINT NOT NULL DEFAULT 0,
  ADD COLUMN `original_table_name` VARCHAR(255) NULL COMMENT '删除/改名后保留原表名',
  ADD COLUMN `last_error` VARCHAR(500) NULL,
  ADD COLUMN `deleted_at` DATETIME NULL,
  ADD COLUMN `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  ADD UNIQUE KEY `el_asset_ck_location` (`ck_instance_id`, `database_name`, `table_name`),
  ADD KEY `el_asset_status` (`status`);

-- 现有资产的创建者无法从历史数据确定,不把当前所有者伪装成创建者;
-- 统一标记为历史来源,列表显示「历史数据」,由管理员在历史归属页面显式核对。
UPDATE `el_data_assets`
SET `origin_type` = 'history',
    `permission_version` = 1
WHERE `creator_user_id` IS NULL;

-- 共享记录增加授权状态。只有 status=active 且 CK 授权完成才放行。
ALTER TABLE `el_resource_grants`
  ADD COLUMN `permission` VARCHAR(16) NOT NULL DEFAULT 'view',
  ADD COLUMN `status` VARCHAR(16) NOT NULL DEFAULT 'pending' COMMENT 'pending/active/revoking/revoked/failed',
  ADD COLUMN `expected_version` BIGINT NOT NULL DEFAULT 0,
  ADD COLUMN `applied_version` BIGINT NOT NULL DEFAULT 0,
  ADD COLUMN `last_error` VARCHAR(500) NULL,
  ADD COLUMN `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP;

-- V5 之前写入的共享记录不能直接视为 CK 已授权,统一进入待核对状态。
UPDATE `el_resource_grants`
SET `status` = 'pending'
WHERE `status` = 'pending';

-- 可恢复的 CK 管理操作:创建账号、授权、撤权、禁用、轮换、生命周期。
CREATE TABLE IF NOT EXISTS `el_ck_access_operations` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `operation_key` VARCHAR(160) NOT NULL COMMENT '幂等键',
  `asset_id` BIGINT NULL,
  `user_id` BIGINT NULL,
  `ck_instance_id` BIGINT NULL,
  `action` VARCHAR(32) NOT NULL,
  `desired_state` VARCHAR(32) NOT NULL DEFAULT '',
  `status` VARCHAR(24) NOT NULL DEFAULT 'pending' COMMENT 'pending/running/active/failed/done',
  `expected_version` BIGINT NOT NULL DEFAULT 0,
  `attempts` INT NOT NULL DEFAULT 0,
  `next_retry_at` DATETIME NULL,
  `lease_until` DATETIME NULL,
  `last_error` VARCHAR(500) NULL,
  `created_by` BIGINT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `el_ck_operation_key` (`operation_key`),
  KEY `el_ck_operation_retry` (`status`, `next_retry_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
