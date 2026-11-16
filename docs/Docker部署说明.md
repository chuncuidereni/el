# Docker 部署到 212

默认连接外部 MySQL、ClickHouse 的部署方式保持不变。需要程序自带固定版本数据库，或服务器离线加载镜像时，见[一体化部署说明](一体化部署说明.md)：`.\package.ps1 -Mode standalone -Offline`。

## 0. 212 前置条件

- Docker Engine，以及可用的 Docker Compose v2。
- 支持 BuildKit/cache mount 的 Docker buildx。
- 首次构建可访问 Docker Hub、Maven Central 和 npm registry；后续构建会复用缓存。
- 为镜像、旧镜像回滚和构建缓存预留足够磁盘空间，建议至少保留 10 GiB 可用空间。

## 1. 打包边界

在项目根目录执行：

```powershell
.\package.ps1
```

`package.ps1` 固定调用 `python docker/package_release.py --profile test`。打包配置按以下顺序合并：

1. `application.yml` 与 `application-test.yml` 的默认值；
2. 根目录 `.env` 中的允许字段；
3. 当前进程环境变量，优先级最高。

支持通过 `.env` 或进程环境覆盖以下连接字段：

- MySQL：`METADATA_URL`、`METADATA_USERNAME`、`METADATA_PASSWORD`；
- ClickHouse：`CK_HOST`、`CK_PROTOCOL`、`CK_PORT`、`CK_DATABASE`、`CK_USERNAME`、`CK_PASSWORD`、`CK_SECURE`；
- 首次管理员：`ADMIN_USER`、`ADMIN_PASSWORD`。`ADMIN_PASSWORD` 留空时仅允许已有管理员继续登录，新库应由启动日志中的一次性随机密码完成首次登录。

`CK_PROTOCOL` 未显式设置时由端口推断：`9000`、`9001` 为 `native`，`8123`、`8132` 为 `http`。显式协议与端口冲突时打包失败。发布包生成后只在控制台显示脱敏目标摘要，例如 MySQL 和 ClickHouse 的主机与端口；不会打印用户名、密码或带凭据的 URL。

输出文件：

- `release/el-to-ck-212.tar.gz`：私有源码发布包；
- `release/el-to-ck-212.tar.gz.sha256`：外层归档校验文件。

打包完成还会打印 `Release ID` 和源码摘要；同一份信息写入归档的 `web/public/assets/release-info.json`，随前端构建进入镜像。该公开标记只有发布编号、打包时间和源码摘要，不包含账号、连接配置或 `.env` 摘要。

归档内 `.env` 的权限固定为 `0600`，且必须不含 `CHANGE_ME`。真实凭据在 Windows 打包机上配置并由打包器写入 `.env`；服务器只核验该文件，不手工修改。如果服务器检查到占位符，应回到打包机补齐配置并重新执行 `package.ps1`。`.env` 会进入 `release-files.sha256`；哈希不会暴露凭据，部署脚本在每次构建、启动和回滚前都会用它发现 `.env` 被误改或替换。归档内容还会回读校验，失败时不会替换上一次成功包。

`package.ps1` 会在生成归档前把 `release` 目录的 Windows ACL 设为私有，并在打包后分别收紧归档和校验文件：移除继承权限，只保留当前用户、`Administrators` 和 `SYSTEM`。发布包含部署凭据，不要通过聊天、邮件或公共网盘传输。

每次代码或部署配置发生变化后，都在 Windows 项目根目录重新执行 `package.ps1`。按现有操作习惯，手动把归档和同名 `.sha256` 一起复制到服务器的 `/home/wuhaiyang/upload/`；如使用其他指定位置，修改下一节的 `upload` 和 `upload_checksum` 即可。脚本继续使用已有配置和密码，无需为普通重新打包再次填写。

上传前，在服务器准备目录：

```bash
umask 077
mkdir -p /home/wuhaiyang/upload
chmod 700 /home/wuhaiyang/upload
```

如果只重启、查看日志或回滚现有容器，不需要重新打包。只要修改了前后端、Dockerfile、Compose、`.env` 或应用配置，就必须重新打包并走下面的新目录发布流程。

## 2. 解压到新的时间戳目录

不要覆盖现有部署目录，也不要在旧目录上叠加解压。每个发布包使用独立的新空目录：

```bash
set -euo pipefail
umask 077
upload=/home/wuhaiyang/upload/el-to-ck-212.tar.gz
upload_checksum=/home/wuhaiyang/upload/el-to-ck-212.tar.gz.sha256
release_root=/home/wuhaiyang/el2ck-releases
stamp="$(date +%Y%m%d-%H%M%S)"
release_dir="$release_root/$stamp"

# 手动复制也必须先核验外层归档，再解压；校验失败即停止。
test -f "$upload"
test -f "$upload_checksum"
chmod 600 "$upload" "$upload_checksum"
(cd "$(dirname "$upload")" && sha256sum -c "$(basename "$upload_checksum")")

mkdir -p "$release_root"
chmod 700 "$release_root"
test ! -e "$release_dir"
mkdir -m 700 "$release_dir"
tar -xzf "$upload" -C "$release_dir"
cd "$release_dir"
test "$(stat -c '%a' .env)" = "600"
sha256sum -c --status release-files.sha256
rm -f "$upload" "$upload_checksum"
```

新目录避免旧 `.env`、旧清单和旧 `.deploy` 状态混入新包。`release-files.sha256` 必须包含 `.env`，并会在部署前校验其内容；若缺少该条目，说明包由旧脚本生成，必须重新打包，不能继续部署。解压及校验成功后删除上传目录中的归档和校验文件，避免凭据副本长期留存。

## 3. MySQL 手工升级

应用启动不会自动建表、改表或重放历史迁移。进入下面的升级命令前，必须先按 [MySQL 部署说明](MySQL部署说明.md#执行增量前的只读基线检查) 确认 V1–V6 的 33 张基础表齐全，并完成 `eltock` 数据库备份。基线检查或备份失败时停止发布，不要先停容器或执行增量 SQL。

正确顺序是：先在页面停止并确认没有运行中的旧同步/工作流任务，再停止旧容器，执行增量 SQL，最后启动新镜像。不要在新镜像启动失败后才补表，也不要在旧任务仍写入时执行升级。

```bash
# 1. 可在旧容器继续运行时先构建新镜像，不替换容器
bash deploy.sh build

# 2. 确认业务任务已经排空，然后停止旧容器
bash deploy.sh stop

# 3. 使用仅管理员可读的私有客户端配置执行两份增量脚本
mysql --defaults-extra-file=/secure/path/mysql-client.cnf \
  --host=192.168.0.212 --port=3306 --database=eltock \
  < docs/mysql/20261029_resume_and_progress.sql
mysql --defaults-extra-file=/secure/path/mysql-client.cnf \
  --host=192.168.0.212 --port=3306 --database=eltock \
  < docs/mysql/20261029_review_hardening.sql

# 4. 预检 36 张表、关键字段、索引及事务表引擎，通过后启动新镜像
bash deploy.sh start
```

上述脚本只补 V7–V11 增量对象；基线检查、数据库备份和任务排空都必须在执行脚本前完成。同名但定义错误的对象不会被 `CREATE TABLE IF NOT EXISTS` 或“列已存在并跳过”自动修复。

要求：

- 只执行上述两份 `20261029` 脚本；
- 不重放 `docs/mysql/archive/V1__` 至 `V6__`；
- 不修改、清空或补写 `el_schema_migrations`；
- 两份脚本都允许重复执行，只补齐缺失表或列，不修改账号口令和业务数据。

如果旧镜像报 `V2 checksum mismatch`，不要通过修改 checksum 表绕过，也不要先启动新镜像。应先排空任务、停止旧容器并执行上面的手工 SQL，再启动本次构建的新镜像。新版应用的 `MetadataCheckCommand` 会检查 36 张表及所需列，并核对本次新增字段的数据类型、可空性和关键字符串长度；它不读取历史迁移 checksum，也不会自动修改表结构。

MySQL 在宿主机 `192.168.0.212:3306`。容器内的 `127.0.0.1` 指容器自身，不能用于连接宿主机 MySQL；`METADATA_URL` 应使用容器可达的 `192.168.0.212` 地址。数据库名、用户名和密码仍只从私有 `.env` 读取。

## 4. 部署与预检

从缺少 V7–V11 结构的旧版本升级时，必须按第 3 节执行，不要直接运行 `bash deploy.sh up`。数据库已经升级完成后的后续发布（包括本次同步进度显示修复）没有新增 SQL 要求，可在确认任务排空后执行：

```bash
bash deploy.sh up
```

`up` 的顺序是：检查私有 `.env` 与发布清单、验证 Compose、构建镜像、执行预检、保存旧容器状态、停止旧容器、清理同步缓存、启动新容器并等待健康检查。新镜像已经构建、只需应用已有镜像时使用：

```bash
bash deploy.sh start
```

预检会验证：

- `/app/app.jar` 存在且非空；
- `/app/web/dist/index.html` 存在且非空；
- `/app/data`、`logs`、`uploads`、`sync-resume`、`duckdb` 可由 UID 10001 创建并实际写入、删除临时探测文件；
- 并行执行 `MetadataCheckCommand` 和 `ClickHouseCheckCommand`；
- ClickHouse 检查通过 `CkClient.open()` 后执行 `SELECT 1`。

Java 检查日志会被临时重定向到 `.deploy` 下的 0600 文件并在结束后删除。普通连接或驱动失败只输出固定的脱敏提示，不展示 URL 或凭据；如果日志中存在 `MySQL 元数据结构检查失败` 这一行，则会额外输出该结构摘要，明确指出缺表、缺列或定义不兼容项。预检通过前不会替换现有容器。

如果预检提示 `/app/data` 对 UID `10001` 不可写，说明旧版容器曾用 root 写入命名卷。先停止旧任务，再执行一次所有权修复；该命令只改变卷内文件的属主，不删除数据：

```bash
docker compose --env-file .env run --rm --no-deps --user root \
  --entrypoint sh app -c 'chown -R 10001:10001 /app/data'
```

修复后重新运行 `bash deploy.sh up` 或 `bash deploy.sh start`。不要用 `docker compose down -v` 删除卷来规避权限问题。

### 4.1 部署时清理同步缓存

`bash deploy.sh up`、`start`、`restart` 在停止旧容器之后、启动新容器之前，会执行一次
`SyncCacheCleanupCommand`，清理同步任务在 `app-data` 卷里留下的文件：

| 清理对象 | 说明 |
|---|---|
| `/app/data/sync-resume/sftp/task-*` | SFTP 下载断点缓存 |
| `/app/data/sync-resume/snapshots/sftp/task-*` | 同步源快照缓存（通常占用最大） |
| `/app/data/duckdb/xlsx-cache/*` | xlsx 转换缓存，可重建 |
| `/app/data/logs/*.gz` | 已轮转的历史日志；当前 `el-to-ck.log` 不动 |

同时删除这些任务在 `el_sync_checkpoints` 中的断点行，避免“文件已删、断点还在”导致下次运行
报缓存缺失。部署脚本会先停止旧容器，因此清理时不会再跳过历史遗留的 `running` 行；如果同一目标
有另一个实例仍在运行，请勿使用本清理（单实例部署假设）。

注意：

- 未完成的同步会失去断点缓存，下次运行从新的轮次开始；已完成的同步不受影响。
- 不会删除 `/app/data/uploads`、`/app/data/duckdb/files.db` 或当前日志。
- 清理命令先连元数据库；连不上会直接失败，不会只删文件而留下坏断点。
- 清理失败只输出 WARNING，不阻塞部署；应用启动后的定时清理（`SyncCacheCleanup`）仍会兜底。
- 如需保留断点跨部署续传，可关闭本次清理：

```bash
CLEAN_SYNC_CACHE_ON_DEPLOY=false bash deploy.sh up
```

唯一对宿主机发布的业务入口是 `17878`，页面和 API 都由 Spring Boot 在同一端口提供：

```text
http://192.168.0.212:17878
```

`17373` 只是本地 Vite 开发服务器端口，生产镜像和 Compose 都不会监听或发布该端口；生产访问不通时不要检查 `17373`。容器内固定监听 `17878`。不再额外发布或对外暴露后端端口；容器内 `127.0.0.1:17878` 只表示容器自身。

### 确认新包已经在 212 生效

从新发布目录部署完成后，先核对公开发布标记，再刷新浏览器：

```bash
curl --fail --silent --show-error \
  http://127.0.0.1:17878/assets/release-info.json
```

返回的 `releaseId` 和 `sourceSha256` 应与本次 Windows 打包输出一致。404、HTML 或旧编号均不能证明新包已生效；检查是否仍从旧目录启动、仅重启了旧镜像，或上传的是旧归档。随后用 Ctrl+F5 刷新页面，确认任务列表出现“执行状态与传输进度”。

总量未知时，新界面显示活动进度条及已读取/已确认写入数量，不编造百分比；下载文件时另显示字节进度。若一直停留在“等待进度上报”，应在执行日志查看任务是否仍在预检、等待源端响应或发生错误；不要仅靠重启重复触发任务。

## 5. ClickHouse 协议与端口

`CK_PROTOCOL` 与 `CK_PORT` 必须成对配置：

| 协议 | 端口 | 用途 |
| --- | ---: | --- |
| `native` | `9000` | ClickHouse 原生 TCP 端口 |
| `native` | `9001` | 原生协议代理或备用入口，转发到原生服务 |
| `http` | `8123` | ClickHouse HTTP 端口 |
| `http` | `8132` | HTTP 协议代理或备用入口，转发到 HTTP 服务 |

若 `.env` 只改端口而不写 `CK_PROTOCOL`，打包脚本会按上表推断协议。显式写了不匹配的协议会立即报错。`CK_SECURE` 独立表示 TLS；是否可用取决于代理和 ClickHouse 实际监听配置，部署前应分别验证。

无 `sum` 列的服务层使用 VIEW。更新时应用先在随机临时名称创建新 VIEW，执行 `SELECT * LIMIT 0` 验证，再通过 `EXCHANGE TABLES` 原子替换；为避免失败时破坏旧视图，目标数据库必须使用 `Atomic` 引擎。上线前可检查：

```sql
SELECT name, engine
FROM system.databases
WHERE name IN ('ods_data', 'dwd_data');
```

若返回 `Ordinary`，普通查询仍可使用，但摊平发布会被明确拒绝；需要先由 ClickHouse 管理员规划数据库引擎升级，应用不会退回到先删除旧视图的非安全流程。

## 6. CK 身份与系统表权限

- `CK_USERNAME`/`CK_PASSWORD`：业务和预检使用的最小权限身份。只授予实际需要的业务库表权限，并按功能验证 `system.tables`、`system.columns`、`system.databases`、`system.parts` 等系统表的读取权限；不要用管理身份代替日常查询。
- `CK_MANAGEMENT_USERNAME`/`CK_MANAGEMENT_PASSWORD`：用于账号、角色、授权管理及受控的续传旧写入终止。账号管理需要 `SHOW ACCESS`、`CREATE USER`、`CREATE ROLE` 和相应的 `GRANT OPTION`；续传恢复还需要 `SELECT(query_id, user, query) ON system.processes` 和 `KILL QUERY ON *.*`。旧写入终止仅匹配当前有效业务绑定账号与本轮断点标记，写入仍由业务账号执行。已有配置继续复用，无需重新填写密码。详细排障见 [续传权限说明](ClickHouse续传权限说明.md)。
- 监控或预检失败时，先核对身份、主机、协议/端口和最小授权；不要把 `SELECT *.*` 或 `ACCESS MANAGEMENT` 作为默认修复手段。
- 凭据只保存在 `.env` 和旧环境备份中，两者都必须保持 `0600`。不要用 `set -x`、`docker inspect` 明文输出或命令行参数排查。

## 7. 状态、日志与回滚

```bash
bash deploy.sh status
bash deploy.sh logs
```

首次在某个新发布目录执行 `up`、`start` 或 `stop` 时，脚本会把旧容器的完整有效环境写入 `.deploy/previous.env`，权限固定为 `0600`，同时记录旧镜像 ID；快照失败时不会继续部署或停止容器。该文件包含旧容器实际环境以及 Compose 回滚所需的插值别名，全程不打印值。后续普通 `up`、`start`、`stop` 不会覆盖已经验证的回滚快照，避免新版本稳定运行后被误设为回滚目标。需要建立新基线时，应确认当前版本稳定后使用新的空发布目录部署。按第 3 节完成“停容器、执行 SQL”后，即使新镜像预检失败，旧镜像和旧环境仍可用于回滚。

只重启当前已经部署的版本时执行：

```bash
bash deploy.sh restart
```

该命令会先完成发布清单、Compose 能力、镜像内容和数据库预检，再执行 `docker compose restart app`，随后等待容器通过健康检查；它不会重新构建镜像，也不会覆盖 `.deploy/previous.env` 和 `.deploy/previous-image`。

回滚：

```bash
bash deploy.sh rollback
```

回滚使用 `.deploy/previous.env` 和记录的旧镜像 ID，通过 `--env-file .deploy/previous.env` 重建旧容器，并保留命名数据卷。脚本不会打印环境内容。回滚不撤销已经执行的手工 SQL，也不回滚业务数据；升级前应完成数据库备份和任务排空。不要删除 `.deploy/previous.env`、`.deploy/previous-image` 或旧镜像，直到新版本稳定运行。
