# MySQL 表结构手工维护

## 执行日志逻辑删除（2026-11-10）

部署此版本前，在现有元数据库执行 [20261110_sync_run_logical_delete.sql](20261110_sync_run_logical_delete.sql)。
脚本增加 `el_sync_runs.deleted_at DATETIME NULL` 和按任务查询未删除日志的索引，可重复执行，不删除历史数据。
212 与本机 dev 如果使用不同的元数据库，需要分别执行；应用启动不会自动改表。

执行日志在操作列提供详情和单条逻辑删除，仅允许删除已成功或已失败的日志，运行中及排队中的日志不可删除。
删除后历史列表、筛选和分页总数会隐藏对应记录；任务最近执行状态、业务数据、断点及续传逻辑保持不变。
成功文件统计、CSV 尾列策略等普通提示记录在运行说明中，只有实际失败或结构排除原因记录在错误中。
历史成功记录中已知的普通摘要会在读取时按说明展示，不需要改写原始记录。

全新 standalone 数据卷会在首次初始化时执行该脚本；已有 standalone 数据卷也需手工升级。

独立交付的全新空库例外：`standalone` 部署模式由 MySQL 容器首次初始化执行包内历史结构及后续字段脚本，应用仍只做结构检查。已有数据库不会重复初始化；外部数据库的手工升级方式不变。参见[一体化部署说明](../一体化部署说明.md)。

Standalone 全新数据卷的初始化顺序为 V1 至 V11、20261029_review_hardening.sql、20261110_sync_run_logical_delete.sql，最后写入 el_bundle_bootstrap.ready=1。20261029_resume_and_progress.sql 的对象已由 V8–V11 覆盖，因此不重复执行。部署检查应等待 ready=1，不能只看 MySQL 端口已开放。

应用启动连接元数据库，按 `metadata-schema.yaml` 检查所需表/列，按 `metadata-critical-schema.yaml` 检查关键字段的类型、可空性和适用的字符串长度，并按 `metadata-critical-indexes.yaml` 检查关键唯一约束、同步历史查询索引及相关表的 InnoDB 引擎。缺少或不兼容时明确报错并终止启动，不自动建表、ALTER、执行迁移或比对迁移校验和。src/main/resources/db 已弃用并移出运行资源目录；旧脚本移到 [archive](archive/) 作为历史结构参考，不随应用 JAR 打包。

## 关键索引与事务预检（2026-10-30 补充）

这次增强核对既有 V1–V11 结构，没有新增业务表或升级 DDL。检查资产位置、资源所有权、账号绑定、幂等操作及断点唯一键，避免同名字段存在但唯一约束丢失时继续运行。相关事务表必须是 InnoDB，行锁和回滚才有相应保证。

索引名称允许不同；唯一键允许等价的列顺序，但普通索引、前缀索引、表达式索引或增加了其他列的唯一键不能替代完整唯一约束。同步历史查询接受以 `task_id,id` 为最左列的覆盖索引。所有历史字段的默认值、类型和索引尚未全部纳入契约，不应把预检通过理解为数据库结构的全面审计。

如果报告 `el_data_assets.instance_location`，这是契约标签，不要求数据库中也使用这个索引名；实际需要的唯一键列是 `ck_instance_id,database_name,table_name`。可在已选中的元数据库执行以下只读查询定位差异：

```sql
SELECT TABLE_NAME, ENGINE
FROM information_schema.TABLES
WHERE TABLE_SCHEMA=DATABASE()
  AND TABLE_NAME IN ('el_data_assets','el_sync_tasks','el_resource_owners','el_sync_checkpoints');

SELECT TABLE_NAME, INDEX_NAME, NON_UNIQUE, SEQ_IN_INDEX, COLUMN_NAME, SUB_PART
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA=DATABASE()
  AND TABLE_NAME IN ('el_data_assets','el_sync_runs','el_resource_owners','el_sync_checkpoints')
ORDER BY TABLE_NAME, INDEX_NAME, SEQ_IN_INDEX;
```

已有结构符合契约则无需改表。缺少唯一键时，先检查并人工核对重复数据，再按实际情况补约束；不要直接删除重复业务记录、清库、重放 V1–V6 或关闭预检。应用只报告问题，不自动执行修正。

## MySQL 日期分表同步

使用方式与续传规则见 [MySQL 按日期分表同步](date-table-sync.md)。该功能复用 `el_sync_tasks.schema_json` 和 `el_sync_checkpoints.state_json`，无需新增列或表，不改变目标字段映射。

表名规则提示为 `xx_xx_{yyyyMMdd}`，不预填；两端日期都为空时匹配所有有效日期表，仅填开始日期时读取到业务当天，仅填结束日期不允许。

## dev 环境

开发环境也需要同步元数据库结构。连接地址和账号继续使用 `src/main/resources/application-dev.yml` 中的 `app.metadata`；若 IDE 或进程设置了 `APP_METADATA_*` 环境变量，以实际生效配置为准。

2026-11-11 本地补齐记录：已对 dev 配置的 `127.0.0.1:13308/eltock` 执行 `20261110_sync_run_logical_delete.sql`，补齐 `el_sync_runs.deleted_at` 和 `idx_sync_runs_visible_history(task_id,deleted_at,id)`。随后按当前三份元数据契约复核 36 张必需表、所需列、关键字段定义、关键索引和相关 InnoDB 引擎，未发现缺项。同步任务和执行日志条数执行前后保持一致。本次未操作 212，也未启动或重启应用。

如果启动报 `MySQL 元数据结构不完整，缺少：el_arb_workflow_runs.progress`，说明已连接到 MySQL，但当前库尚未执行工作流进度字段升级。在该元数据库执行 [20261029_review_hardening.sql](20261029_review_hardening.sql)，补上 `progress TEXT NULL` 后重新启动 dev 后端。脚本会检查列是否已存在，可以重复执行；不要清库或修改账号密码，也不要移除启动校验，否则工作流运行记录接口仍会报缺列。

若同时缺少断点表及同步进度字段，先按下节的适用条件执行 [20261029_resume_and_progress.sql](20261029_resume_and_progress.sql)，再执行工作流进度脚本。只升级 212 不会同步本机 dev 库，反之亦然。

2026-10-29 本机排障记录：对 `127.0.0.1:13308/eltock` 执行工作流进度脚本，全部所需表/列校验通过。用 Java 8 和 dev profile 验证启动成功，`GET http://127.0.0.1:17878/api/health?deep=true` 返回 `{"status":"up","checks":{"mysql":"ok"}}`。验证进程已停止，端口留给 IDE 使用；此操作未修改 212 数据库。

## 212 已部署数据库

在 212 的客户端选中实际元数据库（现有部署文档为 eltock），执行 [20261029_resume_and_progress.sql](20261029_resume_and_progress.sql)。该脚本适用于已有用户、权限、连接、同步任务等 V1–V6 基础结构的数据库，补齐 V7–V11 对应的近期字段和表。

代码审查修复版本还需执行 [20261029_review_hardening.sql](20261029_review_hardening.sql)，仅添加 `el_arb_workflow_runs.progress`，将工作流进度与 SQL 审计分开。两份脚本都可重复执行。首次升级请先等待同步任务结束并停止旧容器，避免旧版（没有任务会话锁）与新版同时执行任务。

| 对象 | 新增内容 |
| --- | --- |
| el_sync_tasks | timeout_minutes、resumable、stable_key、batch_size |
| el_sync_runs | resumed、checkpoint_from、checkpoint_rows、progress_json |
| el_sync_checkpoints | 同步任务断点 |
| el_arb_checkpoints | 工作流断点 |
| el_datasource_observations | 最近一次连接测试的观测记录 |

执行前先按 [MySQL 部署说明](../MySQL部署说明.md) 的只读基线检查确认 V1–V6 的 33 张基础表齐全。然后选择整个脚本，在同一客户端连接中顺序执行，设置遇到 SQL 错误停止。脚本使用 PREPARE，不需要创建存储过程，也不需要 DELIMITER。先确认开头输出的 selected_metadata_database 正确。

已经存在的字段会跳过，不修改原定义；表使用 CREATE TABLE IF NOT EXISTS。脚本可重复执行，结尾列出实际类型、空值和默认值供核对。若提示基础表不存在，说明不是此次增量适用的库，应先核对所选数据库，不要把历史脚本直接全部重放。

脚本不修改用户、口令、权限、任务配置值或业务数据，也不读写 el_schema_migrations。新加 resumable 列默认 1；旧列若仍默认 0 不必改，程序已对新旧任务默认使用续传机制。

MySQL DDL 不作为一个可回滚事务执行。中途因连接或权限失败时，修复后重新执行该脚本补齐剩余字段。已经存在但类型、可空性或长度不同的字段不会自动纠正，应根据结尾输出或应用预检的“定义不兼容”提示单独编写 ALTER。

若 V7–V11 结构已存在，仍需核对本次新增的 `progress` 列；该列也已存在时无需再改表。代码修复过程没有连接或修改 212，由管理员执行 SQL 后再部署。

## 历史与新环境

archive 中 V1–V11 保留原始内容，供追溯及 standalone 全新数据卷初始化使用。V6 包含资产归属及共享状态的 UPDATE，不应对已有环境重复执行。外部数据库的全新空库需由管理员按版本顺序评审和执行；应用不会自动初始化已有或外部空库。

已有 el_schema_migrations 表保留即可，本版本不再使用；不需要清空、删除或补版本记录。其他旧部署仍可能依赖它。

今后改 MySQL 结构，在本目录新增标明日期与用途的手工 SQL，注明适用前置结构、是否可重跑和核对查询，并更新部署说明。不要再新增运行资源迁移脚本或在 Java 启动流程执行 DDL。

本次只涉及平台 MySQL 元数据库。同步业务创建 ClickHouse 目标表、记录运行数据、管理账号等正常功能保持原行为。
