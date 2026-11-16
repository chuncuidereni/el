# ClickHouse 库表与权限操作

> 占位符说明：`{db}` 库名、`{table}` 表名、`{col}` 列名、`{role}` 角色名、`{user}` 用户名、`{type}` 列类型、`{expr}` 表达式。所有标识符用反引号包裹，内容里的反引号双写转义；字符串用单引号，内容里的单引号双写转义。本文不涉及 `ON CLUSTER`。

## 一、数据库

```sql
-- 幂等建库
CREATE DATABASE IF NOT EXISTS `{db}`;

-- 建库并带备注
CREATE DATABASE IF NOT EXISTS `{db}` COMMENT '{备注}';

-- 幂等删库（default 库不删除）
DROP DATABASE IF EXISTS `{db}`;

-- 查看库引擎/是否存在
SELECT engine FROM system.databases WHERE name = '{db}';

-- 查看库清单与备注
SELECT name, comment FROM system.databases WHERE name IN ('{db1}', '{db2}', ...) ORDER BY name;
```

## 二、表

### 2.1 建表

```sql
CREATE TABLE IF NOT EXISTS `{db}`.`{table}` (
  `{col1}` {type1} [DEFAULT {expr}] [COMMENT '{列备注}'],
  `{col2}` Nullable({type2}) [COMMENT '{列备注}'],
  ...
) ENGINE = {引擎}
[PARTITION BY {分区表达式}]
ORDER BY ({排序列, ...})
[PRIMARY KEY ({主键列, ...})]
[COMMENT '{表备注}'];
```

规则：

- MergeTree 系列必须写 `ORDER BY`；`PRIMARY KEY` 必须是 `ORDER BY` 的前缀。
- 排序键列不能是 `Nullable`；其它列可以 `Nullable(...)`。
- 单列排序键可以直接写列名，多列用括号。
- 常用引擎：`MergeTree`、`ReplacingMergeTree`、`SummingMergeTree`、`AggregatingMergeTree`、`CollapsingMergeTree`、`VersionedCollapsingMergeTree`、`Log`、`TinyLog`、`StripeLog`、`Memory`。
- 需要参数的引擎写法：`ENGINE = ReplacingMergeTree({版本列})`、`ENGINE = SummingMergeTree(({求和列}, ...))`、`ENGINE = CollapsingMergeTree({符号列})`。
- 系统附加列示例：`created_at DateTime DEFAULT now()`、`source_id String DEFAULT ''`。

### 2.2 复制结构 / 临时表

```sql
-- 只复制结构
CREATE TABLE `{db}`.`{tmp}` AS `{db}`.`{table}` ENGINE = MergeTree;

-- 结构 + 数据
CREATE TABLE `{db}`.`{tmp}` ENGINE = MergeTree AS
SELECT * FROM `{db}`.`{table}`;

-- 一条语句建表并写入
CREATE TABLE `{db}`.`{table}` ENGINE = MergeTree ORDER BY `{col}` AS
SELECT ...;

-- 一条语句建表并写入，末尾带表备注
CREATE TABLE `{db}`.`{table}` ENGINE = MergeTree ORDER BY `{col}` AS
SELECT ... COMMENT '{表备注}';
```

存在性判断：

```sql
EXISTS TABLE `{db}`.`{table}`;

SELECT count() FROM system.tables WHERE database = '{db}' AND name = '{table}';
```

### 2.3 删除、清空、重命名、合并

```sql
DROP TABLE IF EXISTS `{db}`.`{table}`;

-- 等待删除完成后再返回
DROP TABLE IF EXISTS `{db}`.`{table}` SYNC;

TRUNCATE TABLE IF EXISTS `{db}`.`{table}` SYNC;

RENAME TABLE `{db}`.`{old}` TO `{db}`.`{new}`;

-- 原子交换两张表（常用于「临时表灌好数据后与原表互换」）
EXCHANGE TABLES `{db}`.`{table_a}` AND `{db}`.`{table_b}`;

-- 合并数据分片
OPTIMIZE TABLE `{db}`.`{table}` FINAL;
```

`SYNC` 表示等待清空落盘后再返回。

### 2.4 修改列

```sql
ALTER TABLE `{db}`.`{table}` ADD COLUMN `{col}` {type} [DEFAULT {expr}] [COMMENT '{备注}'];

ALTER TABLE `{db}`.`{table}` ADD COLUMN IF NOT EXISTS `{col}` {type};

ALTER TABLE `{db}`.`{table}` DROP COLUMN `{col}`;

ALTER TABLE `{db}`.`{table}` MODIFY COLUMN `{col}` {type} [COMMENT '{备注}'];

ALTER TABLE `{db}`.`{table}` RENAME COLUMN `{old}` TO `{new}`;

-- 只改表/列注释（纯元数据）
ALTER TABLE `{db}`.`{table}` MODIFY COMMENT '{表备注}';

-- 一条语句并列多个动作
ALTER TABLE `{db}`.`{table}`
  ADD COLUMN IF NOT EXISTS `{col1}` {type1},
  MODIFY COLUMN `{col2}` {type2} COMMENT '{备注}';

ALTER TABLE `{db}`.`{table}`
  MODIFY COMMENT '{表备注}',
  MODIFY COLUMN `{col1}` {type1} COMMENT '{列备注}',
  MODIFY COLUMN `{col2}` {type2} COMMENT '{列备注}';
```

注意：`ADD COLUMN IF NOT EXISTS` 即使列已存在也会刷新表的 `metadata_modification_time`，依赖表元数据时间做断点校验时不要无条件执行。

### 2.5 分区操作

```sql
-- 用另一张表的整个分区替换当前表分区
ALTER TABLE `{db}`.`{table}`
  REPLACE PARTITION '{分区值}' FROM `{db}`.`{source}`;

-- 挂载另一张表的全部分区
ALTER TABLE `{db}`.`{table}`
  ATTACH PARTITION ALL FROM `{db}`.`{source}`;
```

### 2.6 查看结构

```sql
DESCRIBE TABLE `{db}`.`{table}`;

-- 查看子查询的结果列（结果列恒为 String，避免驱动解析复杂类型）
DESCRIBE (SELECT ...);

SHOW CREATE TABLE `{db}`.`{table}`;
```

## 三、视图与物化视图

```sql
-- 普通视图（可显式声明列类型/注释）
CREATE VIEW `{db}`.`{view}` (
  `{col1}` {type1} COMMENT '{列备注}',
  ...
) AS
SELECT ...
[COMMENT '{视图备注}'];

-- 物化视图：直接写入目标表
CREATE MATERIALIZED VIEW `{db}`.`{mv}` TO `{db}`.`{target}` AS
SELECT ...;

-- 幂等重建物化视图
CREATE MATERIALIZED VIEW IF NOT EXISTS `{db}`.`{mv}` TO `{db}`.`{target}` AS
SELECT ...;

-- 删除
DROP VIEW IF EXISTS `{db}`.`{view}`;
```

注意：

- 物化视图只对建立之后写入源表的数据生效；历史数据需要另外执行 `INSERT INTO ... SELECT ...` 回填。
- 删除物化视图用 `DROP VIEW`；它写入的目标表是普通表，按需 `DROP TABLE`。
- 修改物化视图通常需要先删后建。

## 四、用户、角色与授权

### 4.1 用户

```sql
CREATE USER `{user}` IDENTIFIED WITH sha256_password BY '{密码}' HOST {主机规则};

-- 平台绑定的业务账号：创建时不带默认角色
CREATE USER `{user}` IDENTIFIED WITH sha256_password BY '{密码}' DEFAULT ROLE NONE;

ALTER USER `{user}` IDENTIFIED WITH sha256_password BY '{新密码}' HOST {主机规则};

DROP USER IF EXISTS `{user}`;
```

`HOST` 可写 `ANY`、`LOCAL` 或具体模式（如 `'192.168.%'`、`'10.0.0.1'`）；不写时默认 `ANY`。

### 4.2 角色

```sql
CREATE ROLE IF NOT EXISTS `{role}`;

DROP ROLE IF EXISTS `{role}`;
```

### 4.3 授予

```sql
-- 只读角色：查询与 SHOW CREATE TABLE
GRANT SELECT, SHOW ON `{db}`.`{table}` TO `{role}`;

-- 写权限（按需取舍）
GRANT INSERT, ALTER UPDATE, ALTER DELETE,
      ALTER ADD COLUMN, ALTER DROP COLUMN,
      ALTER MODIFY COLUMN, ALTER RENAME COLUMN,
      TRUNCATE, OPTIMIZE
ON `{db}`.`{table}` TO `{user}`;

-- 把角色授给用户
GRANT `{role}` TO `{user}`;

-- 临时建表权限（CREATE TABLE ... AS SELECT 还需要 INSERT）
GRANT CREATE TABLE, INSERT ON `{db}`.`{table}` TO `{user}`;

-- 库/全局
GRANT SELECT ON `{db}`.* TO `{user}`;
GRANT SELECT ON *.* TO `{user}`;
```

### 4.4 回收

```sql
REVOKE `{role}` FROM `{user}`;

REVOKE SELECT, SHOW ON `{db}`.`{table}` FROM `{role}`;

REVOKE INSERT, ALTER UPDATE, ALTER DELETE,
       ALTER ADD COLUMN, ALTER DROP COLUMN,
       ALTER MODIFY COLUMN, ALTER RENAME COLUMN,
       TRUNCATE, OPTIMIZE
ON `{db}`.`{table}` FROM `{user}`;

REVOKE CREATE TABLE ON `{db}`.`{table}` FROM `{user}`;

-- 清空某用户的全部授权
REVOKE ALL ON *.* FROM `{user}`;
```

### 4.5 默认角色

```sql
SET DEFAULT ROLE `{role}` TO `{user}`;

SET DEFAULT ROLE `{role1}`, `{role2}` TO `{user}`;

SET DEFAULT ROLE NONE TO `{user}`;
```

### 4.6 校验授权

```sql
-- 返回结果表示当前账号是否具备该权限
CHECK GRANT SELECT ON `{db}`.`{table}`;

-- 查看某用户当前授权
SHOW GRANTS FOR `{user}`;

-- 以接收者身份验证「能读/不能读」
SELECT count() FROM `{db}`.`{table}` LIMIT 0;
SELECT * FROM `{db}`.`{table}` LIMIT 0;

-- 验证当前执行身份
SELECT currentUser();

-- 只读设置回退判断
SELECT getSetting('readonly');
```

建议的授权顺序：先校验参数（库、表、权限名），全部合法后再 `REVOKE ALL` 重建，避免撤销后才发现新授权语句非法。

## 五、运行控制

```sql
-- 按 query_id 终止
KILL QUERY WHERE query_id = '{query_id}' SYNC;

-- 按语句里的注释标记终止（写入语句前加 /*{marker}*/ 注释）
KILL QUERY
WHERE position(query, '/*{marker}*/') > 0
  AND position(query, 'KILL QUERY') = 0
SYNC;

-- 按前缀匹配
KILL QUERY WHERE query LIKE '{marker}%';

-- 排除当前 KILL 语句自身
KILL QUERY
WHERE startsWith(query, '/*{marker}*/')
  AND query_id != currentQueryID()
SYNC;
```

`SYNC` 表示等待查询真正停止后返回。

## 六、system 表（管理/结构查询）

```sql
-- 库内表清单
SELECT name, engine, comment
FROM system.tables
WHERE database = '{db}' AND is_temporary = 0
ORDER BY name;

-- 表引擎与键定义
SELECT engine, partition_key, sorting_key, primary_key
FROM system.tables
WHERE database = '{db}' AND name = '{table}';

-- 建表语句 / 表 UUID
SELECT engine, create_table_query, toString(uuid)
FROM system.tables
WHERE database = '{db}' AND name = '{table}';

-- 物化视图清单
SELECT name
FROM system.tables
WHERE database = '{db}' AND engine = 'MaterializedView';

-- 列与注释
SELECT name, type, comment
FROM system.columns
WHERE database = '{db}' AND table = '{table}';

-- 有效分片的行数（估算总量，不扫业务表）
SELECT toString(sum(rows))
FROM system.parts
WHERE active AND database = '{db}' AND table = '{table}';

-- 最后修改时间
SELECT toString(max(modification_time))
FROM system.parts
WHERE active AND database = '{db}' AND table = '{table}';

-- 连接/会话级设置
SELECT value FROM system.settings WHERE name = 'async_insert';

-- 用户/授权（需要相应 system 表权限）
SELECT name, auth_type, host_ip, host_names, host_names_like
FROM system.users ORDER BY name;

SELECT name, access_management FROM system.users;
SELECT toString(id) FROM system.users WHERE name = '{user}';

SELECT access_type FROM system.grants WHERE user_name = '{user}';
SELECT user_name, access_type FROM system.grants WHERE user_name != '';
SELECT * FROM system.grants WHERE user_name = '{user}' ORDER BY database, table;

-- 进程
SELECT query_id, user, query, elapsed FROM system.processes;

-- 指标与配置
SELECT metric, value FROM system.metrics;
SELECT metric, value FROM system.asynchronous_metrics;

SELECT name, value FROM system.server_settings
WHERE name IN ('max_server_memory_usage', 'max_server_memory_usage_to_ram_ratio');

SELECT * FROM system.disks;
```

会话级设置（native 协议，每个新连接执行一次）：

```sql
SET {setting} = {value};
```

例如 `SET max_result_rows = 0`、`SET async_insert = 0`。

本文不涉及 `ON CLUSTER`、分布式 DDL、`SYSTEM RELOAD/FLUSH/STOP/START`；这些需要由集群运维单独处理。
