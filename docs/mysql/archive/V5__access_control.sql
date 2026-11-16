CREATE TABLE IF NOT EXISTS el_user_security (
  user_id BIGINT NOT NULL PRIMARY KEY,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  must_change_password BOOLEAN NOT NULL DEFAULT FALSE,
  auth_version BIGINT NOT NULL DEFAULT 0,
  last_login_at DATETIME NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS el_resource_owners (
  resource_type VARCHAR(40) NOT NULL,
  resource_id BIGINT NOT NULL,
  owner_user_id BIGINT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (resource_type, resource_id),
  KEY el_resource_owner_user (owner_user_id, resource_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS el_data_assets (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  backend_type VARCHAR(24) NOT NULL,
  backend_id BIGINT NOT NULL,
  database_name VARCHAR(255) NOT NULL DEFAULT '',
  table_name VARCHAR(255) NOT NULL,
  owner_user_id BIGINT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY el_asset_location (backend_type, backend_id, database_name, table_name),
  KEY el_asset_owner (owner_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS el_resource_grants (
  resource_type VARCHAR(40) NOT NULL,
  resource_id BIGINT NOT NULL,
  grantee_user_id BIGINT NOT NULL,
  granted_by BIGINT NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (resource_type, resource_id, grantee_user_id),
  KEY el_grant_user (grantee_user_id, resource_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS el_access_audit (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  actor_user_id BIGINT NULL,
  action VARCHAR(80) NOT NULL,
  resource_type VARCHAR(40) NOT NULL DEFAULT '',
  resource_id BIGINT NULL,
  target_user_id BIGINT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
