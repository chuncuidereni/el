# el-to-ck Java

Java / Spring Boot 数据同步平台，前端位于 `web/`。

按模块了解项目：[项目介绍](docs/项目介绍.md)。

212 服务器部署使用 Docker 发布包：在项目根目录执行 `.\package.ps1`，上传 `release/el-to-ck-212.tar.gz` 及 `.sha256`，再按 [Docker 部署说明](docs/Docker部署说明.md) 解压到新的时间戳目录、校验、升级 MySQL、预检并启动。生产入口为 `http://192.168.0.212:17878`；`17373` 只是 Vite 开发端口。

| 目录 | 内容 |
| --- | --- |
| `src/main/` | Java 源码、配置和 Mapper XML |
| `web/src/`、`web/public/` | 当前前端源码与静态资源 |
| `data/` | 本地运行文件：上传文件、DuckDB、日志及历史备份（平台元数据使用 MySQL） |
| `target/` | 构建产物；`classes/` 可被开发环境直接运行，jar 用于部署 |

后端构建：`mvn clean package`。前端类型检查与构建：在 `web/` 中执行 `npm run check`。

本机开发使用 `dev` profile，连接信息沿用 `src/main/resources/application-dev.yml`。启动前需补齐元数据库结构，操作见 [MySQL 表结构维护：dev 环境](docs/mysql/README.md#dev-环境)。例如启动日志提示缺少 `el_arb_workflow_runs.progress` 时，在 **dev 实际连接的元数据库** 执行已有的 `docs/mysql/20261029_review_hardening.sql`，然后重新启动；无需重新填写密码或关闭结构校验。后端默认监听 `17878`，可访问 `http://127.0.0.1:17878/api/health?deep=true` 验证 MySQL 和服务状态。

各业务层按模块分包：`controller/<模块>`、`service/<模块>`、`mapper/<模块>`、`entity/<模块>`；JDBC 仓储放在 `repository/<模块>`，元数据库基础设施放在 `repository/metadata`。模块包括 `auth`、`connection`、`database`、`datasource`、`group`、`sync`、`table`、`query`、`queryworkflow`、`workflow`、`arb`、`widetable`、`user`、`system`、`tool`，公共类型放在 `common`。

例如同步模块：

```text
src/main/java/com/el2ck/
├─ controller/sync/SyncTaskController.java
├─ service/sync/SyncService.java
├─ mapper/sync/SyncTaskMapper.java
└─ entity/sync/SyncTask.java
src/main/resources/com/el2ck/mapper/sync/SyncTaskMapper.xml
```

每个 Mapper 对应相同模块下的同名 XML。SQL、公共列和主键回填配置统一在 XML 中维护，`namespace` 对应接口全名，语句 `id` 对应方法名。XML 扫描路径为 `classpath*:com/el2ck/mapper/**/*Mapper.xml`。调试 SQL 可将 `logging.level.com.el2ck.mapper` 设为 `DEBUG`。

增量同步诊断：`java -jar target/el-to-ck-server.jar --check-incremental --help`。

服务与诊断统一由 Spring Boot 加载 `application.yml`、外部配置及 profile，`AppConfig` 只负责绑定 `app.*`。诊断仅启动配置容器，不执行元数据库迁移、账号初始化或定时任务。可追加 `--spring.config.location=file:./config/application.yml`、`--spring.profiles.active=dev`。

装配按职责放在 `config/`：`InfrastructureConfiguration` 管理数据库和连接池，`ServiceConfiguration` 装配业务服务，`StartupConfiguration` 处理启动初始化，`SchedulingConfiguration` 管理调度。

增量键支持数值、日期及 `DateTime` / `DateTime64` 时间戳。读取和水位比较保留小数秒；源时间精度超过实际目标列精度时，同步会拒绝提交并提示提高目标精度。

同步任务向导支持选择 ClickHouse 表、MongoDB 集合及本地 XLSX 文件。默认字段映射保留整数宽度、Decimal 精度和时间戳小数秒精度；保存时校验空映射、重复源字段和增量键的源/目标类型兼容性。

MongoDB 字段探测支持 `profile.version` 等嵌套路径，对象和数组可按 JSON 文本保存。库名优先使用配置中的 `database`，未配置时读取连接串中的库名。数值增量键写入前会检查整数范围、Decimal 截断及浮点舍入，避免精度损失改变后续同步水位。

MongoDB 自动建表时，嵌套路径默认转成下划线列名（如 `profile_version`），同名字段自动添加数字后缀，手填名称保留。日期增量键独立校验，拒绝时刻截断及超出目标 `Date` 范围的值；写入同时检查映射类型和实际目标类型。数值水位支持科学计数法，无效数值会在源查询前报错。

ClickHouse 和 MongoDB 同步源通过单次查询游标分批读取，MongoDB 增量条件直接在源端执行，无须先导出完整集合到临时文件。ClickHouse 使用独立读取连接，避免长查询占用目标写入连接。



单次运行状态可通过 `GET /api/sync-tasks/{taskId}/runs/{runId}` 查询，查询限定所属任务，不受最近 20 条历史限制。列表尚未更新或已有后续运行时，页面仍能跟踪本次结果；旧后端缺少此接口时回退到历史查询。已结束的运行记录保留首次收尾结果，迟到的回调不会覆盖状态、时间或行数。

字段映射页即时显示系统字段冲突、重复列名、源/目标字段缺失和类型问题，并展示源类型、映射类型及已有目标表的实际类型。明确不兼容的转换和增量键范围/精度不足会阻止进入下一步或保存；文本解析等依赖实际值的转换显示警告，运行时仍检查数据。读取结构失败时可在页面重试；编辑数据库源时刷新类型并保留映射，忽略切换源或目标后晚到的旧响应。

字段校验同时覆盖保存接口和执行入口：保存读取最新源与目标结构，执行在建表、补列和创建批次前重新检查。普通字段和自动维护列均检查缺列与类型冲突；源结构不完整或目标读取失败时不保存未经验证的任务。增量映射必须保留源类型的范围和精度；普通字段依赖具体数据的转换仍给出提醒，并保留写入时的值校验。

数据源连接按主机、类型和用户名查重，同一组合不因端口、库名、密码或分组不同而重复保存，重复时提示“数据库已连接”。MongoDB URI 使用实际用户名及主机列表参与检查；编辑排除当前记录。列表按创建 ID 倒序，新建成功后清除筛选并回到第一页。


映射同步按全量/增量策略分批直接写入目标表，不创建批次恢复字段或暂存表。全量任务仅在配置清空时清空目标；SFTP 强制重建会清空目标与文件去重记录。失败可能保留已成功写入的批次，重跑按原任务策略执行。保留既有默认字段和映射类型校验；任务修改、删除不依赖旧恢复记录。

平台元数据库现使用 MySQL，所有平台表以 `el_` 开头。服务器配置及建表说明见 [MySQL 部署说明](docs/MySQL部署说明.md)。

ClickHouse 支持按连接选择 native（TCP，通常 9000，驱动 `com.github.housepower:clickhouse-native-jdbc:2.6.4`）或 HTTP（通常 8123，驱动 `com.clickhouse:clickhouse-jdbc`），驱动由 Maven 解析并打包。HTTP 映射到自定义端口（如 8132）时也要明确设置协议为 `http`。默认连接每次启动时以 `app.default-ck` 为准。其它连接使用平台连接管理中保存的配置。
