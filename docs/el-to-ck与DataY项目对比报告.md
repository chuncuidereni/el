# el-to-ck 与 DataY 项目对比报告

对比日期：2026-10-26。对比对象：

- **项目 A，el-to-ck**：`E:\wenjian\el\el-to-ck-java`。
- **项目 B，DataY**：`E:\wenjian\datay\datay`，包含 `datay-core` 和 `datay-web`。

本报告根据两个目录的**当前工作区源码、路由、依赖和配置**编写。Git 基线分别为 `56b430a`、`068f447`，工作区可能包含未提交修改，结论不等同于这两个提交的发布能力。

这次采用静态代码核查，没有启动服务、连接业务数据库、执行任务或进行性能压测。“有实现”指找到相应代码链路，不代表已完成生产环境验收；“未发现”指本次检查范围内没有发现对应实现，不表示无法通过二次开发实现。

## 1. 结论先行

**两个项目有共同的数据集成基础，但产品中心和运行机制明显不同。**

el-to-ck 的主线是：**多源读取 → 精细字段映射 → ClickHouse 入库 → 用户与表级权限 → 多源仲裁及查询**。目标存储、数据分层、表生命周期和用户真实数据库身份联系紧密，适合目前项目已经明确的 ClickHouse 业务。

DataY 的主线是：**可扩展输入/处理/输出组件 → 数据流编排 → 调度执行 → 模型/指标/数据集/看板 → AI 与 MCP**。它将集成引擎和平台产品拆成两个模块，接入面和平台功能更广，适合作为通用集成与分析平台。

最重要的判断：

1. **两边的“增量同步”不是同一种实现。** A 从目标表取水位；B 的流式 JDBC 通过批次携带并保存读取状态，另外还有独立 CDC 组件。
2. **两边的“数据隔离”不是同一个权限模型。** A 主要是资源归属、表共享和真实 ClickHouse 授权；B 主要是租户、角色和查询时的数据范围。
3. **两边的“Kafka 输入”不是同一种消费方式。** A 是有结束边界的快照；B 是持续运行的消费者。
4. **两边的“工作流”不能直接对应。** A 当前菜单中的工作流实际是 ClickHouse 多源仲裁；B 同时有 ETL 数据流和任务依赖 DAG。
5. **DataY 不能直接替换 A 的真实 CK 用户授权与仲裁逻辑；A 也不能直接承担 B 的 CDC、通用目标输出、语义指标和看板职责。**

## 2. 相同的基础能力

| 共同点 | A 中的表现 | B 中的表现 |
| --- | --- | --- |
| Java Web 后端 | Spring Boot、REST、分层服务 | Spring Boot、REST、分层服务 |
| Vue 前端 | Vue 3、TypeScript、Element Plus、Pinia、Vite | 同一类前端技术栈，另使用 Vue Flow 等组件 |
| 数据源管理 | 保存连接、测试连接、枚举对象、读取字段 | Provider 统一接入，保存、测试、元数据与查询 |
| 集成与同步 | 全量/水位增量、字段映射、批量写入 | JDBC 全量/状态增量、组件映射、批量输出 |
| 自动建表 | 根据字段映射生成 CK 表 | 根据元数据和数据库转换器生成目标表 |
| DuckDB | 文件解析、预览、中间计算与工作流物化 | 文件/SQL 处理、ETL 物化、数仓及 DuckLake 相关能力 |
| 可视化与 SQL | SQL 编辑、查询计划、结果导出 | SQL 查询、ETL 设计、模型、指标、数据集 |
| 任务运维 | 执行、取消、定时触发、运行记录 | 任务实例、定时、依赖、停止、调度事件与日志 |
| 访问控制 | 登录、角色、资源归属、共享 | 登录、角色、租户、维度/数据集权限 |

这些共同点说明可以交流设计思路，但**技术名词相同不代表数据结构、接口或执行语义兼容**。

## 3. 主要差异总表

| 对比维度 | A：el-to-ck | B：DataY | 对实际使用的影响 |
| --- | --- | --- | --- |
| 产品定位 | 面向 ClickHouse 的同步、库表与多源仲裁平台 | 通用集成引擎加建模、调度、分析平台 | 一个偏具体业务闭环，一个偏平台能力组合 |
| 工程结构 | 单个后端 Maven 模块，独立 `web/` | Maven 父模块 + core/web 双模块 | B 更便于独立嵌入引擎，A 本地修改路径较直接 |
| Java / Boot | Java 8、Boot 2.7.18 | Java 17、Boot 3.4.5 | 源码直接迁移涉及 Java API、javax/jakarta 等适配 |
| 元数据持久化 | MyBatis XML + JdbcTemplate，MySQL `el_` 表 | JPA/Hibernate、MapStruct、Liquibase；dev H2、prod MySQL 配置 | 数据访问和迁移机制不同 |
| 核心目标端 | ClickHouse | JDBC 多目标、DuckDB/DuckLake、ES、Doris 等组件目标 | B 的输出边界更宽 |
| 接入扩展 | 类型常量、服务分支、引擎分支、前端选项 | 数据源 Provider 注册 + ETL Component 注册 | A 新增类型更容易出现“能保存但不能执行”的接入遗漏 |
| 常规增量 | 目标表最大值作为源端过滤水位 | 保存每个组件/源表的读取状态 | 目标数据变更与状态文件分别影响重跑结果 |
| CDC | 本次未发现对应日志 CDC 组件 | MySQL Binlog、PG WAL、Oracle LogMiner、Mongo Change Stream | 对更新/删除捕获的能力不同 |
| Kafka | 全量快照，独立分区分配、不提交位点 | 消费组/分区消费者，持续 poll，自动提交默认开启 | 重跑、停止条件和丢重边界不同 |
| Hive | 专用 HiveServer2 JDBC 接入，当前同步仅全量 | 通用 JDBC + 可配置/动态加载驱动接入路径 | B 不依赖独立 Hive 类型也可扩展，但需核验驱动与方言 |
| 文件处理 | 上传文件和 SFTP 目录变化处理是具体业务链路 | FileInput、HTTP、文件/SQL 组件等 | B 的 FileInput 不应直接视为 A 的 SFTP 去重替代品 |
| 数据归属 | 默认按用户拥有数据源和任务 | 平台实体按 tenant_id 隔离 | 同一租户内的用户不自动等同于 A 的个人数据隔离 |
| 数据库授权 | 用户绑定真实 CK 身份，下发 GRANT/REVOKE | 使用配置的数据源凭据执行，平台租户/角色控制 | B 的租户隔离不等于创建真实数据库用户 |
| 行级权限 | 核心是表归属/表共享，本次未发现等价的角色行级产品 | 指标维度范围、已保存数据集行级条件 | B 更适合区域/部门看同一报表的不同数据 |
| 多源整合 | 物化视图、聚合表、字段仲裁、服务层 | Join、SQL、DataTransform、流组件 | “同一人多来源取哪个值”需要不同配置及实现 |
| 调度与扩展 | 本地定时 + 可选 XXL-JOB；同步进程内并发限制 | Quartz；standalone 或 Redis master/worker、Leader 选举 | B 内置分布式执行设计更完整，运维组件也更多 |
| 数据服务与分析 | CK 工作台、SQL、可视化查询 | 模型、指标、数据集、看板、Data API、AI、MCP | B 在集成之后还有较完整的分析产品层 |
| 手机号治理 | 同步时拦截明文手机号；哈希/加解密工具与查询算子 | 本次未发现同等内置手机号入库拦截 | 迁移到 B 时需要单独保留这一业务规则 |
| 失败恢复 | 批次直接落目标，失败可保留已写部分 | 组件输出与状态存储，部分链路有状态续跑 | 两边都不能仅凭“有增量/有状态”认定 exactly-once |

## 4. 相同需求的不同实现方法

### 4.1 需求一：新增一种数据源

**A 的方法：在多个业务层显式接入。**

`DataSource` 声明类型，`DataSourceService` 负责校验/保存/探测，`SyncService` 校验任务是否支持，`SyncEngine` 选择读取方式，前端再控制可选类型和对象/字段加载。Kafka/Hive 已通过 `BigDataSource` 和 `KafkaSnapshot` 加入，但整个链路仍包含多处类型判断。

**B 的方法：注册数据源能力和处理组件。**

`DataSourceProviderFactory` 扫描带注解的 Provider；`ComponentFactory` 扫描 ETL 组件，提供组件名称、分类、描述等元数据。`GenericJdbcDataSourceProvider` 为未内置数据库提供通用 JDBC 入口，驱动加载由专门类处理。

**取舍：** A 便于对某一种来源增加业务特例；B 更利于按统一协议持续扩展。B 也不是“加一个类就自动完成所有功能”：Provider、组件参数、前端配置和输出适配仍需对应。

**建议 A 借鉴：** 建立统一能力描述，例如 `canListObjects`、`canReadSchema`、`supportsFull`、`supportsIncremental`、`isStreaming`，让前端和保存接口读取同一能力来源，降低之前 Kafka/Hive 被页面筛掉的同类问题。

代码依据：[A 数据源类型](../src/main/java/com/el2ck/entity/datasource/DataSource.java)、[A 同步校验](../src/main/java/com/el2ck/service/sync/SyncService.java)、[B Provider 工厂](E:/wenjian/datay/datay/datay-core/src/main/java/com/data/datasource/DataSourceProviderFactory.java)、[B Component 工厂](E:/wenjian/datay/datay/datay-core/src/main/java/com/data/job/ComponentFactory.java)。

### 4.2 需求二：做增量同步

**A：目标端水位。**

执行时取目标表增量字段最大值，生成源条件 `source_key > watermark`。MySQL/PG 读取使用可用的单列主键推进分页；ClickHouse、MongoDB 使用游标读取。范围、Decimal 精度、日期和小数秒精度在保存与执行阶段都参与校验。

**B：独立读取状态。**

`AbstractJdbcInput` 加载每表 `lastIncrValue`，构造大于水位的读取条件；`StreamJdbcInput` 将最新水位放入 `FlowFile.status`，下游 sink 或末端执行后由 `FlowComponent` 保存到执行上下文。状态存储支持本地文件和 MinIO 策略。

| 具体情形 | A 的表现 | B 的表现 |
| --- | --- | --- |
| 手动清空目标表 | 下一次水位可能回退为空，读取范围变大 | 状态未清除时可能仍从旧水位继续，无法自动补回清空的数据 |
| 状态文件丢失 | 常规水位主要由目标表决定 | 读取水位可能丢失，需明确恢复和重跑策略 |
| 低于当前水位的旧记录被修改 | 单靠最大值增量通常无法捕获 | 普通 JDBC 最大值增量同样有此限制 |
| 删除源记录 | 常规水位增量不能自然转成目标删除 | 需要使用相应 CDC 和下游删除语义，而不是普通增量 |
| 多表同步 | A 以任务选定对象为主 | B 的流式 JDBC 对多张表分别携带状态 |

**不能混淆：** B 的“状态增量”和“CDC”是两种链路。CDC 需要源端日志能力、权限、保留策略和对应 sink 支持，不是勾选普通增量即可获得。

代码依据：[A 同步引擎](../src/main/java/com/el2ck/sync/SyncEngine.java)、[A 精度水位工具](../src/main/java/com/el2ck/util/SyncWatermark.java)、[B JDBC 输入](E:/wenjian/datay/datay/datay-core/src/main/java/com/data/job/component/AbstractJdbcInput.java)、[B 流式输入](E:/wenjian/datay/datay/datay-core/src/main/java/com/data/job/component/StreamJdbcInput.java)、[B 状态保存](E:/wenjian/datay/datay/datay-core/src/main/java/com/data/job/FlowComponent.java:60)。

### 4.3 需求三：消费 Kafka 数据

**A 是可结束的快照任务。**

`KafkaSnapshot` 固定开始时各分区的结束位点，从最早保留位点读到边界；不开自动提交、不加入消费组，使用 `read_committed`。前 100 条 JSON 对象识别字段，默认字段类型为 String。当前仅支持全量，重复执行会重读仍保留的数据。

**B 是持续数据流输入。**

`KafkaConsumerComponent` 配置 `groupId`、`autoOffsetReset`、自动提交、批量大小；支持指定分区/起始 offset 等参数，循环 poll；输出带 `_topic`、`_partition`、`_offset` 等元数据，消息不是 JSON 对象时可保存在 `_value` 中。

**运行效果差别：** A 运行完会结束，适合一次性导入、人工验证；B 等待新消息持续运行，更接近实时采集。不能把 A 的 Topic 快照当作 B 的实时订阅，也不能把 B 的默认自动提交当作“目标成功后才提交”。

**本次发现的两个具体边界：**

- B 的 `enableAutoCommit` 默认是 `true`，本消费者中没有看到与下游持久化成功绑定的确认提交；生产使用需要单独验证消费位点与目标写入的一致性。
- B 的 `seekToTimestamp` 当前实现没有调用按时间戳查询 offset 的 API，而是 seek 到开头、poll、再设置当前 position；传入的 timestamp 没有用于实际定位。参数名称/日志与实现存在差距，不能据此承诺精确时间回放。

这两个判断来自当前文件，不代表做过 Kafka 故障注入或已验证丢失场景。

代码依据：[A KafkaSnapshot](../src/main/java/com/el2ck/service/datasource/KafkaSnapshot.java)、[B KafkaConsumerComponent](E:/wenjian/datay/datay/datay-core/src/main/java/com/data/job/component/KafkaConsumerComponent.java:30)。

### 4.4 需求四：用户只能看到自己的数据，或者共享给别人

**A 的隔离单位主要是个人资源与物理表。**

创建数据源/任务时登记 `el_resource_owners`，列表按 owner 读取。表资产与共享授权另外登记。执行 CK 业务 SQL 时，`CkIdentityService` 使用用户绑定的真实 CK 凭据；授权服务负责 GRANT/REVOKE，并维护授权状态。后台同步也需要恢复任务所属用户身份。

当前代码有一个明确例外：内置 admin 对允许的业务库具有特殊库表访问能力。这个例外不会自动放开别人的数据源、任务和上传文件。不要沿用早期设计文字，把当前 admin 理解成与普通用户完全相同的库表范围。

**B 的隔离单位首先是租户。**

JWT 带租户声明，`TenantFilter` 写入上下文，`TenantFilterAspect` 为服务/仓储启用 Hibernate tenantFilter；实体保存 `tenant_id`。无租户时多数业务调用会施加 dummy tenant 条件，同时存在租户管理和显式跳过过滤的路径。

**同一租户不等于同一用户。** B 的这套设计首先解决组织/空间间隔离；若要实现 A 的“甲创建的数据源默认乙看不到”，还需按 B 的实际资源授权规则单独设计，不能只增加 `tenant_id`。

代码依据：[A ResourceAccessService](../src/main/java/com/el2ck/service/auth/ResourceAccessService.java)、[A CkIdentityService](../src/main/java/com/el2ck/service/ck/CkIdentityService.java)、[B TenantFilter](E:/wenjian/datay/datay/datay-web/src/main/java/com/data/datafusion/security/TenantFilter.java)、[B TenantFilterAspect](E:/wenjian/datay/datay/datay-web/src/main/java/com/data/datafusion/config/TenantFilterAspect.java)。

### 4.5 需求五：同一张报表让不同部门看不同的数据

**A：主要控制能不能访问表。** 只读共享一张表，并不等同于配置“这个用户只看 region=华东 的行”。本次未发现与 B 对应的角色维度/数据集范围管理产品。

**B：在指定分析查询路径添加过滤。**

- 指标维度权限：同维度条件按 OR，不同维度按 AND。
- 数据集权限：对保存的数据集输出加外层条件；多个角色的适用规则合并。
- `ROLE_ADMIN` 不追加数据范围；没有配置规则时也不追加限制。
- 文档明确提示：数据集内嵌定义、未保存实时预览等不是所有规则都生效的入口。
- 指标未关联受控维度时有 `SKIP`/`DENY` 策略，默认跳过与强制拒绝的意义不同。

**关键边界：** B 的分析行级权限不是数据库原生 row policy，也不能推导为所有任意 SQL、所有导出和所有数据库客户端都受到相同限制。A 的真实 CK 账号授权与 B 的查询过滤可以组合，但不能互相代替。

代码依据：[B 数据权限说明](E:/wenjian/datay/datay/docs/help/guide/data-permission.md)、[B RoleDataScopeService](E:/wenjian/datay/datay/datay-web/src/main/java/com/data/datafusion/service/RoleDataScopeService.java:114)、[B DatasetQueryService](E:/wenjian/datay/datay/datay-web/src/main/java/com/data/datafusion/service/dashboard/DatasetQueryService.java)。

### 4.6 需求六：组织一个数据处理工作流

**A 有三类容易混淆的能力。**

1. `SyncEngine`：映射入库任务，不是通用组件流。
2. `workflow/Engine`：有数据源、过滤、映射、去重、代码、CK 写入节点；中间结果较多借助 DuckDB 物化。
3. `arb/ArbPlanBuilder` / `ArbEngine`：当前 `/workflows` 菜单对应的多源仲裁，创建每源物化视图、聚合层、服务层；不是通用拖拽 ETL 的等价物。旧宽表入口也不代表当前菜单全部开放。

**B 区分数据流和任务依赖流。**

- ETL 数据流：节点是输入、处理、输出组件，通过 FlowFile 传递记录、元数据和状态；每条连接使用有界阻塞队列，当前容量为 20 个队列对象，不是 20 行。
- 组件可配置并行度；ETLFlowTask 创建线程池运行组件，失败时取消其它任务。
- 任务 DAG：节点是任务及其依赖，另有 Quartz/调度实例管理。

**处理方法的差异：** A 的部分链路偏“生成 SQL/物化结果再处理”；B 的流式链路偏“按批跨节点传递”。B 同时也有 JdbcInput/SqlUnit 等物化方式，因此不能简单说 B 全部不落盘、A 全部不流式。

代码依据：[A 通用 Engine](../src/main/java/com/el2ck/workflow/Engine.java)、[A 当前路由](../web/src/router/index.ts)、[B ETLFlowTask](E:/wenjian/datay/datay/datay-core/src/main/java/com/data/job/ETLFlowTask.java:94)、[B FlowComponent](E:/wenjian/datay/datay/datay-core/src/main/java/com/data/job/FlowComponent.java)、[B 任务 DAG](E:/wenjian/datay/datay/datay-web/src/main/java/com/data/datafusion/job/dag/DAGTask.java)。

### 4.7 需求七：把多来源的同一个用户整合成一条记录

**A：将规则下沉到 ClickHouse 对象。**

仲裁计划生成聚合表、每源物化视图和服务视图；可使用仲裁、sum、min、max 等字段策略。代码使用 `SimpleAggregateFunction` 等 CK 类型，历史回填、重建、服务层整理和对象删除有独立路径。

**B：使用通用 Join/SQL/Transform 建立整合过程。**

这些组件可以组合完成关联、字段处理、聚合，但本次未发现与 A 完全对应的“多源优先级/字段仲裁 + CK 聚合对象生命周期”产品。要取得相同结果，需要重新表达规则、增量触发、历史重算和目标表组织。

**示例：** CRM、APP、线下三个来源都提供同一人的城市，需求是“按来源优先级和更新时间取值”。在 A 中属于仲裁配置的核心场景；在 B 中通常需要 Join/SQL/排序去重或自定义组件，并明确新事件到来后如何更新最终结果。

代码依据：[A ArbPlanBuilder](../src/main/java/com/el2ck/arb/ArbPlanBuilder.java)、[A ArbEngine](../src/main/java/com/el2ck/arb/ArbEngine.java)、[B Join](E:/wenjian/datay/datay/datay-core/src/main/java/com/data/job/component/Join.java)、[B DataTransform](E:/wenjian/datay/datay/datay-core/src/main/java/com/data/job/component/DataTransform.java)。

### 4.8 需求八：任务失败后继续运行并避免丢数/重复

**A：以任务策略和目标实际数据为依据。**

字段类型与目标结构先校验，再分批写入。全量配置清空时直接清表重写；不清空则追加。普通任务没有通用批次恢复暂存表，失败前已写数据可保留。SFTP 另按路径、大小、mtime 记录文件处理结果，变化文件按 path 重灌。

**B：以组件状态传播和持久化为依据。**

流式 JDBC 将水位随 FlowFile 传递，sink 执行后保存状态。相比只记录任务最终状态，这提供了更细的续跑基础；但目标数据库提交与状态文件/对象存储写入并非本次已证明的统一事务。

**典型故障窗口：** B 中若目标写入成功、状态还没持久化就退出，下次可能重读这一批。A 中若某些大水位已写入而同水位的其它记录未写完，单字段水位恢复需要专门验证是否漏读。两边的覆盖、幂等键、upsert 和重跑策略都要结合目标引擎验证。

**结论：** B 的 checkpoint 思路值得借鉴，但两边都不能承诺天然无重复、无丢失、事务回滚或 exactly-once。

代码依据：[A README 的同步失败语义](../README.md)、[A SFTP/transfer 实现](../src/main/java/com/el2ck/sync/SyncEngine.java)、[B sink 后状态保存](E:/wenjian/datay/datay/datay-core/src/main/java/com/data/job/FlowComponent.java:60)、[B 状态存储接口](E:/wenjian/datay/datay/datay-core/src/main/java/com/data/status/StatusStorageStrategy.java)。

## 5. 其它重要差异

### 5.1 DuckDB 相同，承担的职责不同

A 的主要长期业务数据在 ClickHouse，DuckDB 主要承担文件结构识别、查询和部分中间计算。B 将 DuckDB 放在更中心的位置，既有任务实例中的中间数据库，也有 DuckDB/DuckLake 数据源、Appender 输出和模型物化路径。

B 的 `DataModelMaterializeService` 按选定目标数据源和 `DatabaseConverter` 转换类型、生成 DDL，**不能因为 README 强调内嵌数仓，就把全部模型物化理解成只能写本地 DuckDB**。

A DuckDB JDBC 依赖为 `0.10.3`，B 父 POM 为 `1.5.3.0`；同名引擎的 SQL、扩展及原生库依赖也存在版本差异，复用 SQL 或数据文件前需验证兼容性。

依据：[A pom](../pom.xml)、[B pom](E:/wenjian/datay/datay/pom.xml)、[B DuckDBEngine](E:/wenjian/datay/datay/datay-core/src/main/java/com/data/job/DuckDBEngine.java)、[B 模型物化](E:/wenjian/datay/datay/datay-web/src/main/java/com/data/datafusion/service/DataModelMaterializeService.java)。

### 5.2 调度：外接执行器与内置 master/worker

A 有进程内定时调度，支持可选 XXL-JOB 触发；`SyncService` 当前用进程内 `Semaphore(3)` 限制同步并发，并有运行中限制、取消与超时处理。进程内锁不等于跨实例锁，不能据此直接多开副本处理同一组任务。

B 有 Quartz、单机事件服务，以及基于 Redis 队列的 master/worker 事件分发；LeaderElection 以 Redisson 锁选出调度 Leader。standalone 模式不强依赖 Redis，集群角色需要 Redis。集群故障转移和执行幂等仍需部署验收，存在代码不等于任何故障下都不重复。

依据：[A ScheduledSyncRunner](../src/main/java/com/el2ck/service/sync/ScheduledSyncRunner.java)、[A SyncService](../src/main/java/com/el2ck/service/sync/SyncService.java)、[B 集群配置](E:/wenjian/datay/datay/datay-web/src/main/java/com/data/datafusion/config/SchedulerClusterConfiguration.java)、[B Redis 事件服务](E:/wenjian/datay/datay/datay-web/src/main/java/com/data/datafusion/service/jobevent/RedisEventService.java)。

### 5.3 登录与凭据：Session 和 JWT，各有具体边界

| 项目 | A | B |
| --- | --- | --- |
| 登录态 | 服务端 Session；账号版本、禁用状态、会话时限校验 | JWT Bearer，stateless，令牌带角色/租户声明 |
| 请求保护 | Session 配合 CSRF 配置 | JWT 路径主要禁用 CSRF，按 Bearer 校验 |
| 登录密码 | 当前 BCrypt，并兼容旧盐化 SHA-256 校验 | BCrypt |
| 连接凭据 | 数据源整段配置 AES-GCM 加密，DTO 去口令 | 检查到数据源实体普通 password 字段及直接实体/DTO 映射 |

B 当前 `DataSource.password` 没有看到与 A 对应的属性加密转换器；`DataSyncService` 生成任务 JSON 时还会放入连接口令，实体 `toString()` 也包含 password。**源码可确认这些处理路径，实际数据库是否另有外部透明加密、日志是否启用需要另行确认。** 不能把登录 BCrypt 与数据库连接口令加密混为一谈。

A 的整段配置加密也依赖密钥保管；它不代表密钥轮换、备份和运行时日志已经自动解决。

依据：[A SessionAuthFilter](../src/main/java/com/el2ck/config/SessionAuthFilter.java)、[A SecurityConfiguration](../src/main/java/com/el2ck/config/SecurityConfiguration.java)、[A Crypto](../src/main/java/com/el2ck/util/Crypto.java)、[A AppUserService](../src/main/java/com/el2ck/service/auth/AppUserService.java)、[B SecurityConfiguration](E:/wenjian/datay/datay/datay-web/src/main/java/com/data/datafusion/config/SecurityConfiguration.java)、[B DataSource 实体](E:/wenjian/datay/datay/datay-web/src/main/java/com/data/datafusion/domain/DataSource.java:53)、[B 同步 JSON 生成](E:/wenjian/datay/datay/datay-web/src/main/java/com/data/datafusion/service/DataSyncService.java:291)。

### 5.4 数据治理：手机号拦截与分析数据范围不是一类能力

A 当前 `checkMdnPage` 拒绝检测到的明文手机号，允许 MD5 等不被识别为明文的值；它优先检查映射到 mdn/phone/mobile 的源列，或任务标注的手机号列。阈值以内全检，超出阈值使用既有抽样；未知行数源先预读到阈值后继续处理。它不是全字段敏感信息扫描，也不是自动 MD5 转换。

A 另有哈希/加解密工具、查询侧程序算子。B 的 DataTransform/脚本/自定义组件可用于搭建清洗流程，但本次未发现相同的默认手机号入库拦截策略；B 的角色数据范围是“限制谁能看哪些行”，不解决“入库前是否含明文手机号”。

依据：[A SyncEngine 的手机号校验](../src/main/java/com/el2ck/sync/SyncEngine.java:879)、[A 程序算子](../src/main/java/com/el2ck/workflow/ProgOps.java)、[B DataTransform](E:/wenjian/datay/datay/datay-core/src/main/java/com/data/job/component/DataTransform.java)。

### 5.5 数据服务和分析产品层

B 有 DataModel、Metric、AnalysisDataset、AnalysisDashboard、DataApi、告警、AI/RAG、MCP Harness 等领域对象与服务，能够把集成后的数据进一步封装为可复用的模型、口径、数据集和对外接口。

A 已有 SQL、可视化查询、分组、库表操作和多源仲裁，但本次未发现对应的完整指标语义层、仪表盘设计、AI 问数或 MCP 产品实现。A 的 ODS/DWD 页面属于库表分层管理，不等同于 B 的事实/维度模型和指标口径。

B 的告警也应按实际范围描述：`AlertRuleService` 当前允许的主要事件是任务失败和超时，不能据此说已有完整业务数据质量告警平台。

依据：[B DatasetQueryService](E:/wenjian/datay/datay/datay-web/src/main/java/com/data/datafusion/service/dashboard/DatasetQueryService.java)、[B MetricQueryService](E:/wenjian/datay/datay/datay-web/src/main/java/com/data/datafusion/service/MetricQueryService.java)、[B AI 目录](E:/wenjian/datay/datay/datay-web/src/main/java/com/data/datafusion/ai)、[B MCP 目录](E:/wenjian/datay/datay/datay-web/src/main/java/com/data/datafusion/mcp)、[B 告警规则](E:/wenjian/datay/datay/datay-web/src/main/java/com/data/datafusion/service/alert/AlertRuleService.java)。

### 5.6 构建部署与维护成本

A 需要 MySQL 元数据库、ClickHouse 业务库及应用；前端单独构建，已有 Docker/发布脚本。B 可将前端一起打入 Web 包，core 还能独立运行；默认 dev 配置可以使用 H2，生产配置转 MySQL，集群角色再增加 Redis，MinIO/AI 服务等按功能选用。

因此，“B 能轻量启动”与“开启全部功能零外部依赖”是两回事。A 的 CK 原生权限管理需要管理身份和业务身份；B 的通用驱动和分析功能需要更多兼容性、组件参数及产品规则维护。不能仅凭源代码规模推导内存、速度或稳定性结论。

### 5.7 结构升级与任务重试也有不同处理

**元数据库升级：** A 的 `MysqlMetadataSchema` 自行管理 V1–V6 SQL，使用 MySQL 命名锁串行化初始化，以 SHA-256 校验已执行脚本。它提供版本和校验机制，不能归类为“每次启动直接重建库”；代码也明确说明 MySQL DDL 隐式提交，不承诺迁移事务回滚。B 使用 Liquibase changelog 和 JPA 实体配合，属于工具化变更集管理。两种方式都需要维护发布版本与迁移内容，不能用重启应用恢复到某次打包时的结构。

**执行失败重试：** A 的当前同步服务有手动重跑、定时再次触发和超时/取消处理，本次未发现等价的任务级自动重试次数配置。B 的 `TaskExecutionSupport` 按任务实例配置重试次数和超时，失败/超时可重试，手动停止不重试；DAGTask 注释明确整体重试会重新执行全部子任务。这意味着 B 的“支持重试”也不等于“只补失败节点”，已有副作用仍需要幂等设计。

依据：[A MySQL 迁移器](../src/main/java/com/el2ck/repository/metadata/MysqlMetadataSchema.java)、[B Liquibase 配置目录](E:/wenjian/datay/datay/datay-web/src/main/resources/config/liquibase)、[B TaskExecutionSupport](E:/wenjian/datay/datay/datay-web/src/main/java/com/data/datafusion/job/TaskExecutionSupport.java)、[B DAGTask](E:/wenjian/datay/datay/datay-web/src/main/java/com/data/datafusion/job/dag/DAGTask.java)。

## 6. 功能对应及替换难度

| A 的现有功能 | B 的最近似能力 | 是否可直接替换 | 主要改造点 |
| --- | --- | --- | --- |
| 数据源管理 | DataSource + Provider | 否 | 类型、凭据、ID、归属和对象枚举接口 |
| 映射同步 | DataSync 生成 ETL + StreamJdbcInput/Output | 否 | 字段映射结构、CK 类型、系统列、水位策略 |
| Hive 表全量同步 | 通用 JDBC + 流式输出 | 思路可复用 | 驱动、元数据、Hive 方言及 CK 输出验证 |
| Kafka 全量快照 | KafkaConsumer | 否 | 快照边界与持续消费语义不同 |
| SFTP 文件去重 | 文件输入/自定义组件 | 否 | path/大小/mtime、结构排除和变化文件重灌 |
| CK 用户与共享表 | 租户/角色/数据范围 | 否 | 仍需真实 CK 账号、表资产、GRANT/REVOKE |
| 多源仲裁 | Join/SQL/Transform | 否 | 仲裁优先级、状态、MV、历史回填、生命周期 |
| ODS/DWD 库表页面 | 数据源查询/模型页面 | 部分功能相近 | 分层规则、建表管理、用户权限和入口 |
| SQL 查询与导出 | 数据源查询/SQL 数据集 | 部分功能相近 | 查询权限、算子、结果协议及导出路径 |
| 定时任务 | Quartz/Job | 否 | Cron、任务 ID、执行状态、租户/用户上下文 |
| 手机号明文拦截 | 自定义转换/校验组件 | 需开发 | 选列规则、抽样、错误脱敏和写入前校验 |
| 未来指标/看板/AI | B 已有对应模块 | 可作为新增能力来源 | 业务模型、权限、接口集成、运行环境 |

## 7. 哪些设计值得互相借鉴

### 7.1 A 优先借鉴 B 的内容

| 优先级 | 建议 | 解决的问题 | 实施边界 |
| --- | --- | --- | --- |
| P0 | 数据源与同步能力统一注册 | 新类型接入多处遗漏 | 先统一能力描述，不必立即重写引擎 |
| P0 | 任务状态/水位接口标准化 | 恢复策略散落、不同源行为不一致 | 保留已有目标水位兼容模式，增加明确版本与策略 |
| P1 | 独立执行器与组件接口 | SyncEngine 随来源增加而膨胀 | 将读取端、转换、写入拆开，保留 CK 专属约束 |
| P1 | CDC 组件及变更事件协议 | 需要同步旧行更新和删除 | 先选一种数据库，验证位点、快照、删除和幂等 |
| P1 | 任务失败/超时告警 | 目前主要靠运行历史发现故障 | 先做告警事件与投递记录，再扩渠道 |
| P2 | 指标/数据集层 | SQL 难复用、业务口径易分叉 | 与现有真实 CK 权限共同设计 |
| P2 | master/worker 分离 | 单进程执行资源有限 | 任务幂等、跨实例锁、身份传递先明确 |

### 7.2 B 值得借鉴 A 的内容

- 用户真实数据库身份、表生命周期与共享授权核对，可补强仅平台查询过滤无法覆盖的数据库访问边界。
- 数据源配置整段加密、接口脱敏、凭据版本化连接池，可减少通用平台连接口令的暴露面。
- CK 数值/Decimal/小数秒精度校验与运行时再次校验，对 CK 目标端更有针对性。
- SFTP 目录变化处理和字段结构排除，是较具体的文件落地业务规则。
- 多源字段仲裁与 CK 物化对象生成，是 B 通用组件之上可以增加的业务产品。
- 手机号入库前拦截与错误信息脱敏，可以做成可插拔的数据质量节点或统一写入策略。

## 8. 对当前项目的建议

如果目标仍然是“用户拥有数据源/任务、真实 CK 表权限、共享表、多源仲裁和手机号治理”，**建议保留 A 为业务主系统，优先吸收 B 的可扩展组件设计和执行状态模型**。

如果目标已变为“接更多数据库和非数据库目标、CDC 实时链路、事实/维度建模、指标、看板与 AI”，可以考虑以 B 为平台底座；但应单独安排 A 的真实 CK 授权、仲裁和手机号规则迁移，而不是只迁移页面与表结构。

若两者并存，可采用以下边界作为后续方案起点：

```text
DataY：接入 / CDC / 通用处理 / 调度 / 分析产品
                         ↓
                 ClickHouse 业务表
                         ↑
el-to-ck：用户身份 / 表授权 / 手机号规则 / 仲裁与服务层
```

这只是边界建议，**不是已经兼容的部署方案**。真正并存前必须确定：谁创建 CK 用户、谁授予写权限、谁拥有目标表、谁负责字段变更、谁触发同一任务、失败后谁恢复水位。否则容易发生平台一边登记归属、一边用公共账号写入的权限和生命周期冲突。

## 9. 本次核查发现的具体注意点

1. **A 的文档有部分滞后。** `docs/项目介绍.md` 中的数据源清单和登录账号描述不完全覆盖当前 Kafka/Hive、真实 CK 用户实现；本报告以当前源码为准。
2. **A 的前端“工作流”与后端通用 Engine 不是一个完整一对一入口。** 当前 `/workflows` 绑定仲裁页面；旧宽表入口被注释。不能把目录中全部历史能力算作当前页面已开放能力。
3. **A 的手机号拦截有选列和抽样范围。** 不可将“存在明文拦截”写成“所有字段和所有行均保证无明文”。
4. **B 的 Kafka 时间定位实现与参数名称不一致。** 需修复并验收后才能承诺按 timestamp 回放。
5. **B 的 Kafka 默认自动提交不能证明端到端确认。** 如要求严格交付，需要设计消费位点与 sink 成功的关联。
6. **B 的租户/角色过滤不等于真实数据库个人身份。** 行级权限还有具体查询入口边界，不能将其泛化到任意 SQL。
7. **B 的连接口令存在普通实体字段、任务 JSON 和 toString 路径。** 如实际投产，建议优先核对存储加密、API 返回和日志脱敏。
8. **两个项目均不能仅凭源码宣传给出性能优劣。** 本次没有做同源同目标、相同数据量、相同资源和相同失败场景下的实测。

## 10. 如需进一步作技术选型，建议的共同验收用例

以下是后续建议，不是本次已执行的测试：

| 场景 | 必须比较的结果 |
| --- | --- |
| 同源同目标同步 100 万行 | 用时、峰值内存、读写批次、数值和时间精度 |
| 第三批目标写入后退出 | 重跑是否重复、漏行，水位/状态是否一致 |
| 修改旧行、删除旧行 | 普通增量与 CDC 的结果差别 |
| Kafka 下游断连 | 位点是否前进，恢复后是否补回，错误能否终止任务 |
| 两用户/两租户查询同一表 | 页面、API、直接 SQL、导出、后台任务的实际范围 |
| 源端新增/改型/删字段 | 自动建表/变更策略及失败提示 |
| 明文手机号混入 MD5 数据 | 是否识别、在哪一批停止、旧数据是否保留 |
| 重启应用及调度节点失效 | 任务实例、运行中状态、触发次数与恢复行为 |

本报告的用途是明确能力、实现方式和迁移成本，为后续开发与选型提供依据；不将静态检查当作上线认证。
