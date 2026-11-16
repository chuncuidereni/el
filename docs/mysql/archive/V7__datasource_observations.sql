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
