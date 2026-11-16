# MySQL 部署说明

本文只说明 212 环境中的 MySQL 元数据升级；当前运行时和打包器不支持 MariaDB JDBC URL。完整的打包、容器预检、ClickHouse 检查和回滚流程见 [Docker 部署说明](Docker部署说明.md)。

2026-10-30 的后续优化新增关键索引及 InnoDB 引擎预检，核对的是既有结构，没有新增升级 DDL。标准 V1–V11 结构无需因此改表；如果旧库缺失约束或使用了非事务引擎，新预检会拒绝启动。定位与处理方式见 [MySQL 结构维护说明](mysql/README.md#关键索引与事务预检2026-10-30-补充)。

## 配置来源与部署边界

发布包在本地由 `package.ps1` 生成。连接配置按 `application.yml`、`application-test.yml`、根目录 `.env`、当前进程环境变量的顺序合并，后写入的来源覆盖先前的来源。MySQL 使用 `METADATA_URL`、`METADATA_USERNAME`、`METADATA_PASSWORD`；`METADATA_URL` 必须是 `jdbc:mysql://...`，当前运行时和打包器都不接受 `jdbc:mariadb://...`；ClickHouse 使用 `CK_HOST`、`CK_PROTOCOL`、`CK_PORT`、`CK_DATABASE`、`CK_USERNAME`、`CK_PASSWORD`、`CK_SECURE`。

不要在服务器上手写或覆盖 `config/application.yml`，也不要把凭据写到命令行。真实连接信息由打包机上的根目录 `.env` 或进程环境提供，`package.ps1` 会将其写入发布包内的私有 `.env`。服务器只核验并部署该文件，不手工修改；解压后权限必须是 `0600`，且不得包含 `CHANGE_ME`。如果出现占位符，应停止部署并在 Windows 打包机补齐配置后重新打包，不能修改服务器上的 `.env`。该文件进入 `release-files.sha256`，部署脚本会同时校验其内容、存在性和权限；哈希本身不会暴露凭据。

每次发布都应上传归档到一个新的空时间戳目录，再解压，不能覆盖旧目录，也不能把旧目录中的 `.env`、清单或 `.deploy` 状态混入新包：

```bash
set -euo pipefail
umask 077
upload=/home/wuhaiyang/upload/el-to-ck-212.tar.gz
upload_checksum=/home/wuhaiyang/upload/el-to-ck-212.tar.gz.sha256
release_root=/home/wuhaiyang/el2ck-releases
stamp="$(date +%Y%m%d-%H%M%S)"
release_dir="$release_root/$stamp"

test -f "$upload"
test -f "$upload_checksum"
chmod 600 "$upload" "$upload_checksum"
# Verify the outer archive before creating or extracting the release directory.
(cd "$(dirname "$upload")" && sha256sum -c "$(basename "$upload_checksum")")

mkdir -p "$release_root"
chmod 700 "$release_root"
test ! -e "$release_dir"
mkdir -m 700 "$release_dir"
tar -xzf "$upload" -C "$release_dir"
cd "$release_dir"
test "$(stat -c '%a' .env)" = "600"
if grep -q 'CHANGE_ME' .env; then
  echo 'Packaged .env contains CHANGE_ME; repackage on the build machine.' >&2
  exit 1
fi
sha256sum -c --status release-files.sha256
rm -f "$upload" "$upload_checksum"
```

应用启动不会自动建表、改表或重放历史迁移。升级前先停止旧的同步和流程任务，避免新旧版本同时写入。

### 执行增量前的只读基线检查

增量脚本只适用于已经具备完整 V1–V6 基础结构的元数据库。使用同一份私有客户端配置，先确认实际选中的数据库中存在全部 33 张基础表：

```bash
mysql --defaults-extra-file=/secure/path/mysql-client.cnf \
  --host=192.168.0.212 --port=3306 --database=eltock \
  --batch --skip-column-names --execute="
SELECT COUNT(*)
FROM information_schema.TABLES
WHERE TABLE_SCHEMA=DATABASE()
  AND TABLE_NAME IN (
    'el_access_audit','el_app_settings','el_app_users',
    'el_arb_workflow_runs','el_arb_workflows',
    'el_ck_access_operations','el_ck_instances','el_connections',
    'el_data_assets','el_data_sources','el_db_groups','el_ds_groups',
    'el_group_dbs','el_query_workflow_runs','el_query_workflows',
    'el_resource_grants','el_resource_owners','el_sql_queries',
    'el_sql_query_groups','el_sync_files','el_sync_runs','el_sync_tasks',
    'el_tbl_group_tables','el_tbl_groups','el_user_ck_bindings',
    'el_user_remarks','el_user_security','el_wf_groups','el_wf_run_logs',
    'el_wf_runs','el_wf_tasks','el_wide_table_runs','el_wide_table_tasks'
  );
"
```

结果必须是 `33`。少于 `33` 时停止升级，先核对数据库名或恢复缺失的 V1–V6 基础结构；不要把历史 V1–V6 脚本直接整批重放。`el_schema_migrations` 不参与这个计数。

## 唯一需要执行的 SQL

确认 MySQL 客户端已选中实际元数据数据库后，只按顺序执行以下两份脚本：

```bash
mysql --defaults-extra-file=/secure/path/mysql-client.cnf \
  --host=192.168.0.212 --port=3306 --database=eltock \
  < docs/mysql/20261029_resume_and_progress.sql

mysql --defaults-extra-file=/secure/path/mysql-client.cnf \
  --host=192.168.0.212 --port=3306 --database=eltock \
  < docs/mysql/20261029_review_hardening.sql
```

`mysql-client.cnf` 必须是仅管理员可读的私有文件，用户名和密码只放在该文件中，不要出现在命令行。数据库名通过 `--database=eltock` 显式指定；如果实际库名不同，两处命令同步替换。

约束如下：

- 只执行 `docs/mysql/20261029_resume_and_progress.sql` 和 `docs/mysql/20261029_review_hardening.sql`。
- 不重放 `docs/mysql/archive/V1__` 至 `V6__`。
- 不修改、清空、补写或手工修复 `el_schema_migrations`。
- 两份脚本允许重复执行，只补充缺失的表或列，不改写账号口令和业务数据。
- 执行失败时停止后续步骤，先备份并确认报错；不要通过修改迁移 checksum 绕过。
- `CREATE TABLE IF NOT EXISTS` 和“列已存在则跳过”不会修复同名但类型、可空性或长度错误的旧对象。新版本预检会报告“定义不兼容”，此时应按实际报错单独编写 `ALTER TABLE`，不要删除业务表。

如果旧容器报 `V2 checksum mismatch`，不要先启动新镜像：新版本会在启动预检时发现缺少增量结构并退出。正确顺序是停止旧任务和旧容器、执行上面的两份手工 SQL、再启动新镜像。新版应用的 `MetadataCheckCommand` 会检查 36 张表和所需列，并进一步核对本次新增字段的类型、可空性和关键字符串长度；它不读取历史迁移 checksum，也不会自动修正结构。

## 地址、入口与连通性

MySQL 位于宿主机 `192.168.0.212:3306`。应用运行在容器内时，容器里的 `127.0.0.1` 只表示容器自身，不能用来连接宿主机 MySQL；`METADATA_URL` 必须使用容器可达的 `192.168.0.212` 地址。

唯一对宿主机发布的业务入口是 `17878`。页面和 API 都由 Spring Boot 在同一个端口提供：

```text
http://192.168.0.212:17878
```

容器内的 `127.0.0.1:17878` 只表示容器自身，不是给外部访问的宿主机地址。部署预检会验证镜像中的 `/app/app.jar` 和 `/app/web/dist/index.html`，并同时执行 `MetadataCheckCommand` 与 `ClickHouseCheckCommand`；ClickHouse 检查通过 `CkClient.open()` 后执行 `SELECT 1`。

## ClickHouse 端口与身份

`CK_PROTOCOL` 与 `CK_PORT` 必须匹配：

| 协议 | 端口 | 用途 |
| --- | ---: | --- |
| `native` | `9000` | ClickHouse 原生 TCP 端口 |
| `native` | `9001` | 原生协议代理或备用入口，转发到原生服务 |
| `http` | `8123` | ClickHouse HTTP 端口 |
| `http` | `8132` | HTTP 协议代理或备用入口，转发到 HTTP 服务 |

未显式设置 `CK_PROTOCOL` 时，打包脚本按端口推断协议；显式设置冲突会直接失败。`CK_SECURE` 独立控制 TLS，是否可用取决于代理和 ClickHouse 实际监听配置。

- 业务与预检身份使用 `CK_USERNAME`/`CK_PASSWORD`。只授予业务库表所需权限，并按实际功能验证 `system.tables`、`system.columns`、`system.databases`、`system.parts` 等系统表的读取权限。
- 管理身份使用 `CK_MANAGEMENT_USERNAME`/`CK_MANAGEMENT_PASSWORD`。仅在启用 ClickHouse 账号管理时需要 `SHOW ACCESS`、`CREATE USER`、`CREATE ROLE` 及相应授权能力。
- 不要默认授予 `SELECT *.*` 或 `ACCESS MANAGEMENT`。监控或预检失败时先核对身份、主机、协议、端口和最小授权。

## 验证与回滚

部署后用健康检查确认 MySQL 连通性；深度检查结果中的 MySQL 项必须正常。容器日志和预检日志不应打印 URL、用户名或密码，部署脚本失败时只输出固定的脱敏提示。

首次在某个新发布目录执行 `up`、`start` 或 `stop` 时，脚本会把旧容器的完整有效环境保存到 `.deploy/previous.env`，权限固定为 `0600`，同时记录旧镜像 ID；快照失败时不会继续部署或停止容器。后续普通 `up`、`start`、`stop` 不会覆盖已经验证的回滚快照，避免新版本稳定运行后被误设为回滚目标。需要建立新基线时，应确认当前版本稳定后使用新的空发布目录部署。回滚命令是：

```bash
bash deploy.sh rollback
```

回滚使用 `--env-file .deploy/previous.env` 重建旧容器，并保留命名数据卷；脚本不会打印环境内容。回滚不撤销已经执行的手工 SQL，也不回滚业务数据。升级前应完成数据库备份和任务排空，并在新版本稳定前保留 `.deploy/previous.env`、`.deploy/previous-image` 和旧镜像。
