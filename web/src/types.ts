/**
 * types.ts
 *
 * 职责：前端共享类型契约：描述连接、数据源、同步、查询、工作流、共享资产、系统指标和用户权限等 API DTO。
 * 边界：字段名称与可空性必须跟随后端接口；认证凭据和密码明文不得写入类型默认值或前端持久化结构。
 */

// 与后端约定一致的接口类型。

/** 数据源类型 */
export type DataSourceType = 'csv' | 'json' | 'xlsx' | 'sftp' | 'mysql' | 'pgsql' | 'clickhouse' | 'mongodb' | 'ck_table' | 'kafka' | 'hive'

/** 数据源分组(输入端分类,单选归属) */
export interface DSGroup {
  id: number
  name: string
  remark?: string
  count?: number
}

/** 数据源历史状态；连接状态不代表实时在线，读取此接口不会探测来源。 */
export interface DataSourceStatus {
  id: number
  /** 检查目标是什么:本地文件 / 连接 / 都不适用 */
  kind: 'file' | 'conn' | 'none'
  /** 结论色:ok 正常 / bad 异常 / none 未检查 */
  state: 'ok' | 'bad' | 'none'
  /** 主文案,由后端给(前端直接显示,不再拼词) */
  text: string
  /** 补充说明 / 失败原因,可选 */
  detail?: string
  checkedAt?: string
  stale?: boolean
  observationSource?: 'manual' | 'metadata' | 'sync'
}

/** 数据源(同步输入端)配置视图，密码只通过 hasPassword 暴露是否存在。 */
export interface DataSource {
  id: number
  ownerUserId?: number
  ownerUsername?: string
  code: string
  name: string
  type: DataSourceType
  description: string
  groupId?: number
  hasPassword: boolean
  config: Record<string, string | number | boolean>
  createdAt: string
  updatedAt: string
  /** 非空表示保存成功但有警告(如 CSV 文件入库失败) */
  warning?: string
}

/** 数据源新增/更新入参(更新时 password 留空表示保持原密码) */
export interface DataSourceInput {
  code: string
  name: string
  type: DataSourceType
  description: string
  /** 所属数据源分组(0=默认分组) */
  groupId?: number
  config: Record<string, string | number | boolean>
}

/** 同步任务(数据源 → ClickHouse) */
export interface SyncFieldMapping {
  /** 入库前将识别到的明文手机号转为 MD5，已有哈希保持原样 */
  autoMdnMd5?: boolean
  sourceColumn: string
  targetColumn: string
  type: string
  /** 自动建表:目标列是否可空 */
  nullable?: boolean
  /** 自动建表:目标列默认值 */
  defaultValue?: string
  /** 自动建表:目标列注释 */
  comment?: string  /**
   * 进宽表时的取值方式(只在任务勾了「纳入宽表」时生效)。
   *   ''/arbitrate 仲裁:多行里挑一个最可信的(非空 > 优先级 > 时间) ← 默认
   *   sum          多行相加(金额/次数这类按号码累加)
   *   max/min      取最大/最小
   * ⚠️ 同一个目标列在所有源里必须用同一种:方式决定物理列类型,同列只能有一种。
   */
  aggMode?: '' | 'arbitrate' | 'sum' | 'max' | 'min'
  /** 仅仲裁用:''/pri_time(默认) | pri(不看时间) | time(不看优先级) */
  aggRule?: '' | 'pri_time' | 'pri' | 'time'
}

/**
 * 同步任务运行配置与最近断点快照。timeoutMinutes=0 表示不限时；stableKey 为空表示不指定分段键，
 * batchSize 控制每次读取/确认的批次；checkpointRows/checkpointKey 是服务端保存的已确认进度，
 * checkpointPending=true 表示当前快照尚未完成，页面据此提示按断点续传。
 */
export interface MysqlDateTableRule {
  tablePattern: string
  startDate: string
  endDate: string
  dateFormat: 'yyyyMMdd'
  timeZone: string
  /** 增量任务新表策略：空=只接上次扫描之后；full=从头全量补齐。 */
  newTablePolicy?: string
}

export interface SyncTask {
  mysqlDateTables?: MysqlDateTableRule | null
  dateTableBaselineReady?: boolean
  timeoutMinutes?: number
  resumable?: boolean
  stableKey?: string
  batchSize?: number
  checkpointRows?: number
  checkpointKey?: string
  checkpointPending?: boolean
  canAdoptBaseline?: boolean
  baselineAdoptionBlockedReason?: string | null
  queued?: boolean
  id: number
  name: string
  description: string
  dataSourceId: number
  dataSourceName: string
  dataSourceCode?: string
  dataSourceType?: string
  dataSourceDesc?: string
  sourceGroup?: string
  sourceObject: string
  connectionId: number
  connectionName: string
  targetGroup?: string
  targetDb: string
  targetTable: string
  filePattern?: string
  remotePath?: string
  hasHeader?: boolean
  /** SFTP CSV：按表头列数读取，忽略超出文件结构的尾列，默认开启。 */
  ignoreExtraCsvColumns?: boolean
  csvEncoding?: string | null
  allowedTargetDefaults?: string[] | null
  schema?: { name: string; type: string }[]
  mapping: SyncFieldMapping[]
  /** full=每次整表重灌;incremental=只取水位之后的新行 */
  mode: 'full' | 'incremental'
  /** 增量键:incremental 时按该列过滤,空则不做过滤(直接追加) */
  incrColumn: string
  orderKey: string
  /** 建表配置(目标表自动创建时用,引擎固定 MergeTree) */
  partitionExpr?: string
  sortingKeys?: string[]
  primaryKeys?: string[]
  enabled: boolean
  truncate: boolean
  autoCreateTable?: boolean
  /** ODS 贴源表:自动建表时附加 ods_insert_at/ods_source_id(SFTP 另含 path) */
  isOds?: boolean
  /** 源的哪一列是手机号(源列名;空=未标注)。目标表不存在时自动作首列 mdn(置灰主键) */
  phoneColumn?: string
  scheduleType: 'manual' | 'scheduled'
  cronExpression: string
  createdAt: string
  updatedAt: string
  /** 最近一次执行 */
  lastRun?: {
    /** 与执行日志共用进度契约；列表接口使用 camelCase。 */
    progress?: SyncRun['Progress']
    id?: number
    status: 'queued' | 'running' | 'success' | 'failed'
    startedAt: string
    finishedAt: string
    rowsRead: number
    rowsWritten: number
    error: string
    note?: string
    /** 本次是否从断点续传 */
    resumed?: boolean
    /** 续传的起始分段键 */
    checkpointFrom?: string
    /** 续传开始时已确认的行数 */
    checkpointRows?: number
  }
  /** 进宽表:是否纳入(默认否) / 源优先级(越大越权威) / 号码列归一化(全平台统一) */
  arbInclude?: boolean
  arbPriority?: number
  arbMdnNorm?: '' | 'md5' | 'prefix7'
}

/** 创建/更新任务时的可写运行配置；断点进度由服务端维护，不在输入契约中。 */
export interface SyncTaskInput {
  mysqlDateTables?: MysqlDateTableRule | null
  timeoutMinutes?: number
  resumable?: boolean
  stableKey?: string
  batchSize?: number
  name: string
  description: string
  dataSourceId: number
  sourceObject: string
  connectionId: number
  targetDb: string
  targetTable: string
  /** SFTP 采集规则:远端根目录(数据源仅承载连接) */
  filePattern?: string
  /** SFTP 采集规则:文件名匹配 */
  remotePath?: string
  /** SFTP 采集规则:首行是否为列名(默认 true;false=无表头按 column1..N 取名) */
  hasHeader?: boolean
  /** SFTP CSV：按表头列数读取，忽略超出文件结构的尾列，默认开启。 */
  ignoreExtraCsvColumns?: boolean
  csvEncoding?: string | null
  allowedTargetDefaults?: string[] | null
  /** SFTP 采集规则:选定的结构类(列) */
  schema?: { name: string; type: string }[]
  mapping: SyncFieldMapping[]
  mode: 'full' | 'incremental'
  incrColumn: string
  orderKey: string
  /** 建表配置(目标表自动创建时用) */
  partitionExpr?: string
  sortingKeys?: string[]
  primaryKeys?: string[]
  enabled: boolean
  truncate: boolean
  autoCreateTable?: boolean
  /** ODS 贴源表 */
  isOds?: boolean
  /** 源的哪一列是手机号(源列名;空=未标注) */
  phoneColumn?: string
  /** 调度方式；manual 表示手动触发，scheduled 表示按 cronExpression 定时触发。 */
  scheduleType: 'manual' | 'scheduled'
  /** Cron 表达式；scheduled 模式使用。 */
  cronExpression: string
  /** 是否纳入宽表(默认否:库里有测试任务,自动纳入等于污染) */
  arbInclude?: boolean
  /** 源优先级,越大越权威 */
  arbPriority?: number
  /** 号码列归一化:'' 原样 | md5 | prefix7(全平台必须统一) */
  arbMdnNorm?: '' | 'md5' | 'prefix7'
}

/** 同步运行历史 */
// 字段名是 PascalCase:后端用 @JsonProperty 固定了这些键名(历史原因),
// 改了就会读不到值 —— 别按驼峰习惯改写这里。
export interface SyncRun {
/**
 * 运行历史使用服务端固定的 PascalCase 键名。DurationMs 是服务端耗时，旧历史记录可能缺失；
 * Progress 是结构化快照对象，不等同于普通字符串进度。
 */
  DurationMs?: number | null
/**
 * 运行进度快照：confirmedRows 为已确认行数，runRows 为本轮已处理行数；totalRows/percent 可能缺失，
 * estimated 表示总量是否为估算。速度、ETA、重试次数、当前分段键和阶段都来自服务端，
 * 页面只在字段存在时展示，不从 RowsRead/RowsWritten 反推缺失的进度。
 */
  Progress?: {
    confirmedRows: number
    runRows: number
    totalRows?: number | null
    estimated: boolean
    percent?: number | null
    averageRowsPerSecond: number
    etaSeconds?: number | null
    retryCount: number
    /** 服务端计划的下一次自动重试时间（Unix 毫秒），用于避免等待期间误判为卡死。 */
    nextRetryAtMs?: number | null
    currentKey?: string | null
    updatedAtMs?: number | null
    sourceType?: string | null
    phase?: string | null
    currentObject?: string | null
    unit?: string | null
    completedUnits?: number | null
    totalUnits?: number | null
  } | null
  ID: number
  TaskID: number
  Status: 'queued' | 'running' | 'success' | 'failed'
  StartedAt: string
  FinishedAt: string
  RowsRead: number
  RowsWritten: number
  Error: string
  /** 运行提示(非失败信息,如 SFTP 目录同步跳过/重建提示) */
  Note: string
  /** 本次是否从断点续传；同时记录起始键和当时已确认的行数。 */
  Resumed?: boolean
  /** 续传的起始分段键 */
  CheckpointFrom?: string
  /** 续传开始时已确认的行数 */
  CheckpointRows?: number
}

/** 执行日志分页结果 */
export interface SyncRunPage {
  rows: SyncRun[]
  total: number
  page: number
  size: number
}

/** SFTP 目录条目(目录/文件) */
export interface SftpEntry {
  name: string
  path: string
  isDir: boolean
  size: number
  mtime: string
  isHidden?: boolean
}

/** SFTP 目录内单个文件的 schema 情况 */
export interface SftpFileSchema {
  csvEncoding?: string
  csvDelimiter?: string
  path: string
  size: number
  mtime: string
  columns: string[]
  columnCount: number
  sameSchema: boolean
  error?: string
}

/** SFTP 目录内按列结构分类的一类 */
export interface SftpColGroup {
  columns: { name: string; type: string }[]
  colNames: string[]
  fileCount: number
  memberFiles: string[]
  error?: string
}

/** SFTP 目录预览:结构分类清单 + 参考列 */
export interface SftpDirPreview {
  root: string
  pattern: string
  /** 结构基准文件:取第一个匹配到的文件,下面的 columns 由它解析而来 */
  reference: string
  /** 基准文件的列(表头);其它文件按它比对是否同构 */
  referenceColumns: string[]
  columns: { name: string; type: string }[]
  files: SftpFileSchema[]
  groups: SftpColGroup[]
  total: number
  preview?: any
}

/** 系统资源占用快照 */
export interface SystemStats {
  time: string
  uptimeSec: number
  cpuCores: number
  cpuPercent: number
  allocMB: number
  sysMB: number
  heapInuseMB: number
  workingSetMB: number
  goroutines: number
  numGC: number
  lastGCPauseMs: number
  dbFileMB: number
  duckdbFileMB: number
  uploadsMB: number
}

/** 目标库服务的单个挂载点容量 */
export interface DiskStat {
  name: string
  path: string
  freeGB: number
  /** 0 = 该版本没暴露总量,此时 usedPct 为 -1,前端只展示剩余容量 */
  totalGB: number
  /** 已用百分比;-1 = 总量未知(不要当 0% 显示) */
  usedPct: number
}

/**
 * 目标库服务(ClickHouse)的运行指标。
 *
 * ⚠️ 约定:凡「0 等于未知」的项一律是 -1(cpuPercent / memOfLimitPct / memOfOSTotalPct / DiskStat.usedPct),
 * 显示成 `--` 而不是 0 —— 0 会被读成「服务很空闲」。
 */
export interface ServerStats {
  time: string
  /** 权限不足或版本缺少监控表时的提示;未知指标为 -1。 */
  warnings?: string[]
  uptimeSec: number
  memResidentMB: number
  /** 服务生效的内存上限,0 = 未知 */
  memLimitMB: number
  memOfLimitPct: number
  osMemTotalMB: number
  osMemAvailMB: number
  memOfOSTotalPct: number
  cpuPercent: number
  disks: DiskStat[]
  totalConnections: number
  tcpConnections: number
  httpConnections: number
  isectConnections: number
  runningQueries: number
  runningMerges: number
}

/** 数据源详情(配置摘要弹窗展示)。本地文件含列/类型/行数/大小;连接类含连接信息 */
export interface DataSourceDetail {
  id: number
  code: string
  name: string
  type: DataSourceType
  // 本地文件(CSV/JSON)
  path?: string
  size?: number
  columns?: { name: string; type: string }[]
  /** 文件原始列名+类型(未应用字段映射),供「原始列名 vs 字段映射」对照展示 */
  rawColumns?: { name: string; type: string }[]
  /** 读取字段名到原始文件表头的对应关系，用作自动建表的默认列注释。 */
  columnComments?: Record<string, string>
  total?: number
  hasHeader?: boolean
  names?: string[]
  // 连接类(SFTP/数据库)
  host?: string
  port?: number
  database?: string
  table?: string
  collection?: string
  remotePath?: string
  username?: string
}

/** 连接健康状态(端口通 + SELECT 1 成功) */
export interface ConnStatus {
  id: number
  ok: boolean
  latencyMs: number
  error?: string
}

/** 连接配置(视图,不含密码)。高级字段为空表示继承全局默认 */
export interface Connection {
  id: number
  name: string
  host: string
  port: number
  database: string
  username: string
  hasPassword: boolean
  /** 连接协议:'native'(原生 TCP,默认)或 'http'(HTTP 接口) */
  protocol: string
  secure: boolean
  description: string
  isDefault: boolean
  createdAt: string
  updatedAt: string
  dialTimeoutMs?: number | null
  queryTimeoutMs?: number | null
  connMaxLifetimeMin?: number | null
  readOnly?: boolean | null
  paramsJson?: string
}

/** 连接新增/更新入参(更新时 password 留空表示保持原密码) */
export interface ConnectionInput {
  name: string
  host: string
  port: number
  database: string
  username: string
  password: string
  /** 连接协议:'native'(原生 TCP,默认)或 'http'(HTTP 接口);留空按 native */
  protocol?: string
  secure: boolean
  description: string
  /** 高级配置作用域:"connection"(仅当前连接)或 "global"(清除覆盖用全局) */
  advancedScope?: string
  dialTimeoutMs?: number | null
  queryTimeoutMs?: number | null
  connMaxLifetimeMin?: number | null
  readOnly?: boolean | null
  paramsJson?: string
}

/** 全局默认高级配置 */
export interface GlobalSettings {
  dialTimeoutMs: number
  queryTimeoutMs: number
  connMaxLifetimeMin: number
  readOnly: boolean
  paramsJson: string

  /** 手机号字典:全局默认“手机号列字段名”;新建贴源/映射自动用它作 mdn 首列(置灰主键) */
  dimPhoneField?: string
  /** 该手机号列的“展示形态”(plain 明文 / md5 / sha256 / 3des / aes / sm4 / sm3 / other) */
  dimPhoneType?: string
}

/** 单个数据单元格的值类型。ClickHouse 返回的标量都收敛到这里;
 * 数组/Map/Tuple 在 DataEditor 中支持有限,展示为字符串。 */
export type CellValue = string | number | boolean | null | Date

/** 查询结果集 */
export interface QueryResult {
  columns: string[]
  types: string[]
  rows: CellValue[][]
  total: number
  /**
   * 列名 → 该列在 ClickHouse 里的表字段注释(只含有注释的列)。
   *
   * 后端按【列名】从 system.columns 取(执行完才知道结果列,
   * "结果列来自哪张表"靠解析 SQL 不可靠),同名时优先当前库。
   * 仅用于界面展示,不作为数据依据。
   */
  columnComments?: Record<string, string>
}

/** 表数据分页(total 为精确总数;hasMore 用于无 total 时判断下一页) */
export interface DataPage {
  columns: string[]
  types: string[]
  rows: CellValue[][]
  total: number
  offset: number
  size: number
  hasMore: boolean
}

export interface SortSpec {
  column: string
  desc: boolean
  numeric?: boolean
}

/** 查询过滤条件树:叶子为条件,分组为 children 非空(组内按 logic 组合并加括号) */
export interface FilterNode {
  /**
   * 数值比较标记:目标表里仲裁列都是 String,区间/大小比较按字典序会出错
   * (例如 age 的 '9' 与 '40' 比大小)。置 true 时后端把该列转成数值再比。
   */
  numeric?: boolean
  logic?: 'and' | 'or'
  column?: string
  /** distinct：普通条件筛选后按字段组合保留一条完整记录，无需 value */
  op?: string
  /** 叶子值。数字列收到非数字会由后端拒绝(返回 400);日期类型为字符串 */
  value?: CellValue
  children?: FilterNode[]
}

/** SQL 执行响应(支持多条语句,results 为各只读语句结果) */
export interface QueryResponse {
  readOnly: boolean
  results?: QueryResult[]
  messages?: string[]
  /** 与 results 一一对应的原始语句文本(多语句时用于单条导出/复现) */
  resultStatements?: string[]
  truncated: boolean
  /** 列名 → 表字段注释(响应级;前端会挂到每个结果上供结果表格展示) */
  columnComments?: Record<string, string>
}

/** 数据库分组(目标库表（dwd_data）中用于组织多个数据库) */
export interface DBGroup {
  id?: number
  connectionId?: number
  name: string
  remark?: string
  /** 分组成员数据库名 */
  dbs?: string[]
}

export interface DatabaseInfo {
  name: string
  remark?: string
}

/** 表分组:把某库内的表归入自定义分组 */
export interface TableGroup {
  id: number
  connectionId?: number
  db?: string
  name: string
  remark?: string
  count?: number
  tables?: string[]
}

export interface TableInfo {
  name: string
  engine: string
  remark?: string
  /** 平台表资产 ID;行操作按资产定位 */
  assetId?: number
  ownerUserId?: number
  creatorUserId?: number | null
  /** 创建者显示名;历史未知为「历史数据」 */
  creatorName?: string
  /** owner=本人拥有, read=共享只读 */
  accessMode?: 'owner' | 'read' | 'admin'
  canRead?: boolean
  canWrite?: boolean
  canManage?: boolean
  canDrop?: boolean
  canShare?: boolean
  permissionStatus?: string
}

/** 某库下表的统计(列数/行数/磁盘大小),后端带缓存 */
export interface TableStat {
  name: string
  cols: number
  rows: number | null
  bytes: number | null
}

/** 表的主键/分区键/排序键 */
export interface TableKeys {
  primaryKeyExpr: string
  partitionKeyExpr: string
  sortingKeyExpr: string
  primaryKey: string[]
  partitionKey: string[]
  sortingKey: string[]
}

/** 单行更新:keys 定位行,values 为修改的列 */
export interface RowUpdate {
  keys: Record<string, CellValue>
  values: Record<string, CellValue>
}

/** 一条列结构变更指令 */
export interface ColumnAlter {
  action: 'rename' | 'modify' | 'add' | 'drop'
  oldName: string
  newName: string
  type: string
  nullable: boolean
  defaultValue: string
  comment: string
}

/** 修改表结构请求(newTableName 非空表示同时改表名) */
export interface TableAlterRequest {
  newTableName: string
  /** 不传则保持原值，空字符串表示清空表注释。 */
  tableComment?: string
  columns: ColumnAlter[]
}

/** 数据增删改请求 */
export interface DataEditRequest {
  keyColumns: string[]
  inserts: Record<string, CellValue>[]
  updates: RowUpdate[]
  deletes: Record<string, CellValue>[]
}

export interface DataEditResult {
  inserted: number
  updated: number
  deleted: number
}

/** 一条授权规则 */
export interface GrantRule {
  database: string
  table: string
  access: 'select' | 'readwrite' | 'all'
}

/** ClickHouse 用户 */
export interface UserInfo {
  name: string
  authType: string
  hosts: string[]
  remark?: string
}

/** 创建/更新用户入参 */
export interface UserInput {
  name: string
  password: string
  hosts: string[]
  grants: GrantRule[]
  clearGrants?: boolean
  remark?: string
}

// ---- 查询工作流(4 步向导:多表查询,只 SELECT 不落库) ----

export interface QueryWorkflowSource {
  connectionId: number
  database: string
  table: string
  columns?: string[]
  rename?: Record<string, string>
  whereFilter?: string
}

export interface QueryWorkflowMdnRule {
  sourceIdx: number
  sourceCol: string
  alias: string
  /** 旧:无参算子链(向后兼容) */
  pipeline?: string[]
  /** 带参处理链,非空时优先于 pipeline */
  steps?: QueryPipelineStep[]
}

/** 处理链算子的一个参数(后端 QueryPipelineArg;kind 决定渲染方式,防注入) */
export interface QueryPipelineArg {
  kind: 'number' | 'string' | 'ident'
  value: string
}

/** 处理链的单步(后端 QueryPipelineStep)。输入值恒为第一个参数,args 为其余参数。 */
export interface QueryPipelineStep {
  fn: string
  args?: QueryPipelineArg[]
}

/** 一条程序算子后处理计划:对结果集第 index 列应用 ops */
export interface ProgColumn {
  index: number
  alias: string
  ops: QueryPipelineStep[]
}

export interface QueryWorkflowFieldRule {
  alias: string
  sourceIdx: number
  sourceCol: string
  /** 旧:无参算子链(向后兼容) */
  pipeline?: string[]
  /** 带参处理链,非空时优先于 pipeline */
  steps?: QueryPipelineStep[]
  /**
   * 程序算子链:由 el-to-ck(Go)在结果返回后计算,ClickHouse 不参与。
   * ⚠️ 该列的值 ClickHouse 不知道 → 不能用于过滤/分组/排序(后端会校验拒绝)。
   */
  prog?: QueryPipelineStep[]
  agg?: string
  /**
   * 只参与查询、不输出到结果。
   *
   * 用途:按某列过滤/排序,但结果里不要这一列。
   * 后端会把它从外层 SELECT 里去掉,并把过滤/排序的引用改写成
   * CTE 侧的带别名表达式(因为它的别名不在 SELECT 里)。
   */
  hidden?: boolean
}

export interface QueryWorkflowJoin {
  type: string
  /** 全局 ON(所有关联表共用);两侧留空 = 按各表的 mdn_norm 自动关联 */
  onLeft?: string
  onRight?: string
  /** 逐表覆盖:第三张表起各表关联键不同时用它(不填的项回退到上面的全局值) */
  ons?: QueryWorkflowJoinOn[]
}

/** 单张关联表的 ON 条件 */
export interface QueryWorkflowJoinOn {
  /** 作用在哪张源表(从 1 开始;0 是主表) */
  sourceIdx: number
  onLeft?: string
  onRight?: string
}

export interface QueryWorkflowWhere {
  column: string
  op: string
  value: string
  /** 仅 between 用:区间上界 */
  value2?: string
  /** 值渲染方式:string(默认,自动加引号转义) | number | raw */
  valueKind?: 'string' | 'number' | 'raw'
  /** 时间预设(仅日期/时间列):today|thisWeek|thisMonth|last7d|last30d|custom */
  preset?: string
}

export interface QueryWorkflowOrder {
  column: string
  desc: boolean
}

export interface QueryWorkflowConfig {
  sources: QueryWorkflowSource[]
  /** 单表查询可传空数组;多表 JOIN 时每张源表必须各配一条 */
  mdnRules: QueryWorkflowMdnRule[]
  fields: QueryWorkflowFieldRule[]
  join: QueryWorkflowJoin
  where?: QueryWorkflowWhere[]
  /**
   * 聚合后过滤(HAVING),形状与 where 相同。
   * ⚠️ 作用在 GROUP BY 之后,因此要求配置里至少有一个带 agg 的字段
   *    (否则后端会拒绝:HAVING 挂在明细查询上没有意义)。
   */
  having?: QueryWorkflowWhere[]
  orderBy?: QueryWorkflowOrder[]
  limit: number
  /** 已弃用:仅兼容旧配置,等价于 groupBy=['mdn_norm'] */
  groupByMdn?: boolean
  /** 按【输出字段别名】分组,如 ['city','status'];空 = 不分组 */
  groupBy?: string[]  /**
   * 去重键(输出字段别名):非空时按这些列各保留一行,其它列原样带出来 → LIMIT 1 BY。
   * 与 groupBy 的区别:groupBy 把其它列折叠成聚合值,去重是保留一行明细。
   * 保留哪一条由 orderBy 决定。
   */
  distinctKeys?: string[]
}

export interface QueryWorkflow {
  id: number
  name: string
  description: string
  enabled: boolean
  config: QueryWorkflowConfig
  createdAt: string
  updatedAt: string
}

export interface QueryWorkflowInput {
  name: string
  description: string
  enabled: boolean
  config: QueryWorkflowConfig
}

export interface QueryWorkflowRun {
  id: number
  workflowId: number
  status: 'running' | 'success' | 'failed'
  startedAt: string
  finishedAt: string
  rowsRead: number
  generatedSql: string
  error: string
}

// ---- 已保存的 SQL 查询(数据探索页) ----

export interface SQLQuery {
  id: number
  name: string
  description: string
  database: string
  sqlText: string
  /** 可视化构建器配置(QueryWorkflowConfig 的 JSON);空 = 非可视化创建 */
  visualConfig?: string
  groupId: number
  createdAt: string
  updatedAt: string
}

export interface SQLQueryInput {
  name: string
  description: string
  database: string
  sqlText: string
  visualConfig?: string
  groupId: number
}

export interface SQLQueryGroup {
  id: number
  name: string
  remark: string
  count?: number
  createdAt: string
  updatedAt: string
}

// ---- 宽表加工任务(多表关联 → 全量重建一张宽表,落到 dwd_data) ----

/**
 * 一个宽表任务 = 一份多表关联配置 + 一个目标表。
 *
 * 与「已保存 SQL 查询」的区别:那个只查询、不落库;宽表任务会**重建一张真实的表**,
 * 而且是全量重建(每次重算所有标签),所以运行是写操作、不可逆(旧版本会被替换)。
 */
export interface WideTableTask {
  id: number
  name: string
  description: string
  enabled: boolean
  /** 多表关联配置(与数据探索向导同一个结构) */
  config: QueryWorkflowConfig
  targetDb: string
  targetTable: string
  /** MergeTree 排序键,必须是输出字段之一;空 = 取第一个输出字段 */
  orderKey?: string
  lastRunAt?: string
  lastStatus?: string
  lastRows?: number
  lastError?: string
  createdAt?: string
  updatedAt?: string
}

/** 新建/更新宽表任务的入参 */
export interface WideTableInput {
  name: string
  description?: string
  enabled?: boolean
  config: QueryWorkflowConfig
  targetDb: string
  targetTable: string
  orderKey?: string
}

/** 一次宽表重建的记录 */
export interface WideTableRun {
  id: number
  taskId: number
  status: 'running' | 'success' | 'failed'
  rows: number
  startedAt: string
  finishedAt?: string
  error?: string
  /** 本次实际执行的 SELECT(失败时是唯一的排查线索) */
  sql?: string
}

/** 保存前的 SQL 预览(select = 来源查询;create = 完整建表语句) */
export interface WideTablePreview {
  select: string
  create: string
  orderKey: string
  targetDb: string
  tempTable: string
  /** 体检:预计行数 / 每张源表的放大倍数 */
  check?: WideTableCheck
  /** 体检本身失败的原因(表被删/权限不足) —— 不影响看 SQL */
  checkError?: string
}

/**
 * 宽表落库前的“放大倍数”体检。
 *
 * 为什么需要它:宽表的粒度是一个手机号一行,但只要有一张“一个键多行”的表
 * 被直接关联进来,结果就是【乘法放大】且【不会报错】—— 表建成了、行数变多、
 * 数据变乱,可能很久才发现。
 */
export interface WideTableCheck {
  select: string
  /** 预计产出行数 */
  rows: number
  /** 关联键去重后的个数 —— 宽表“正常”应该等于它 */
  distinctKeys: number
  /** rows / distinctKeys。> 1 说明在放大 */
  ratio: number
  keyColumn: string
  sources: WideTableSourceCheck[]
  /** 人话警告(界面直接显示) */
  warnings: string[]
}

export interface HashToolItem {
  /** 原始值(回传是为了界面上能对照与导出) */
  value: string
  /** 十六进制摘要 */
  hash: string
}

export interface HashToolResult {
  items: HashToolItem[]
  algo: string
  /** 是否大写输出(平台口径:默认大写) */
  upper: boolean
  count: number
}

// ─────────────── 维度分布统计(画像查询) ───────────────

/** 按维度分组统计的入参(过滤条件与数据页同源,保证「看到的行」与「统计的人」口径一致) */
export interface GroupByRequest {
  filter: FilterNode
  /** 去重保留记录的排序，与数据列表一致 */
  orders?: SortSpec[]
  /** 按哪个字段分组 */
  column: string
  /** 最多返回多少个取值(默认 50,上限 500),按条数从多到少 */
  limit?: number
  /**
   * 分组方式:
   *   raw(默认)   原值分组 —— 分类列用,按条数从多到少
   *   number      数值分桶 —— 年龄段 / 消费区间;步长由 bucketSize 定(默认 10)
   *   day/month/year 时间分桶 —— 注册月份 / 活跃年月
   *
   * 为什么需要:画像里数值/时间维度不能按原值分组 ——
   * age 会出 100 行(18..99)、reg_date 出 365 行,根本没法看。
   */
  bucket?: 'raw' | 'number' | 'day' | 'month' | 'year'
  /** 数值分桶步长(如 10 → 10 一档) */
  bucketSize?: number
}

export interface GroupBucket {
  value: string
  count: number
}

export interface GroupByResult {
  column: string
  buckets: GroupBucket[]
  /** 满足过滤条件的【条数】—— 占比的分母(注意它与各桶之和不等,因为取了前 N 个) */
  total: number
  /** 实际执行的 SQL(界面可以直接展示"这条分布是怎么算的") */
  sql: string
}

/** 把圈选结果落成一张表(数据包)的请求 */
export interface MaterializeRequest {
  /** 落到哪个库;空 = 与源表同库 */
  targetDb?: string
  /** 数据包表名 */
  targetTable: string
  /** 圈选条件(与数据页同一套渲染,所以「看到的行」=「存进去的行」) */
  filter: FilterNode
  /** 去重保留记录的排序 */
  orders?: SortSpec[]
  /** 存哪些列;空 = 全部列 */
  columns: string[]
  /** 排序键列;空 = 自动取号码列 */
  orderBy?: string
  /** 同名表已存在时先删除 */
  dropIfExists?: boolean
  /** 只生成 SQL 不执行(预览) */
  dryRun?: boolean
}

export interface MaterializeResult {
  /** 结果表名(只有表名 —— 它是给界面提示用的,前台不显示物理库名) */
  table: string
  /** 写入了多少行 */
  rows: number
  /** 实际执行的建表语句 */
  sql: string
  /** 同名表是否已存在 */
  exists: boolean
}

export interface WideTableSourceCheck {
  database: string
  table: string
  rows: number
  keys: number
  ratio: number
  /** 该表里用作关联键的表达式(核对是不是拿错了列) */
  keyExpr: string
  /** 该表选中的字段是否都已聚合(= 已压成“一键一行”) */
  aggregated: boolean
  /** 还没聚合、会把结果撑开的输出字段别名 */
  pendingFields: string[]
}

// ─────────────── 工作流(多源仲裁 → 聚合层) ───────────────
//
// 与 WideTableTask(JOIN 型宽表)是两套东西:这里是 UNION ALL + 按列仲裁,
// 产物是一张 AggregatingMergeTree 聚合表 + 每源一个物化视图。

/** 目标列的取值方式 */
export type ArbMode = 'arbitrate' | 'sum' | 'max' | 'min'
/** 仲裁规则:非空 > 优先级 > 时间(默认)/ 只看优先级 / 只看时间 */
export type ArbRule = 'pri_time' | 'pri' | 'time'

/** 一张贴源表 */
export interface ArbSource {
  /** 前端生成的稳定标识,字段靠它挂在源上(用下标会在删源时错位) */
  id: string
  database: string
  table: string
  /** 号码列(各源可不同名,归一化后统一落到聚合表的 mdn) */
  mdnCol: string
  /** 号码列的处理链(多源号码格式不一致时靠它对齐) */
  mdnSteps?: QueryPipelineStep[]
  /** 更新时间列;空 = 该源不比较时间 */
  timeCol?: string
  /** 来源优先级,越大越权威。挂在源上而不是字段上 */
  pri: number
  /** 源内行过滤(可选) */
  where?: string
  enabled: boolean
}

/** 对接层的一行:某个源的一列 → 某个目标列 */
export interface ArbField {
  sourceId: string
  col: string
  /** 目标列名(默认 = 源列名;多源列名不一致时靠它挂到同一个目标列) */
  outName: string
  /** 源列类型(生成器用它推导聚合列类型) */
  colType?: string
  /**
   * 这一列的业务说明 —— 会作为 COMMENT 落到聚合表/服务表上(DESCRIBE 时看得到)。
   * 默认取源列注释;同一个目标列有多条映射时取第一条非空的。
   */
  comment?: string
  steps?: QueryPipelineStep[]
}

/** 一个目标列的取值方案 */
export interface ArbColumn {
  name: string
  mode: ArbMode
  rule?: ArbRule
}

export interface ArbWorkflow {
  id: number
  name: string
  description: string
  enabled: boolean
  connectionId: number
  targetDb: string
  /** 聚合表基础名;真实表名 = 此后加 _agg */
  targetTable: string
  /** 标记为「宽表」:全平台汇总的那一张 —— 宽表页签直接显示它的服务表 */
  isWide?: boolean
  sources: ArbSource[]
  fields: ArbField[]
  columns: ArbColumn[]
  lastRunAt?: string
  lastStatus?: string
  lastRows?: number
  lastError?: string
  createdAt?: string
  updatedAt?: string
}

export interface ArbWorkflowInput {
  name: string
  description?: string
  enabled?: boolean
  connectionId: number
  targetDb: string
  targetTable: string
  /** 标记为「宽表」:全平台汇总的那一张(同一时刻只有一个) */
  isWide?: boolean
  sources: ArbSource[]
  fields: ArbField[]
  columns: ArbColumn[]
}

export interface ArbWorkflowRun {
/** 工作流运行进度是字符串；它和 SyncRun.Progress 的结构化对象不是同一契约，不能按对象字段读取。 */
  progress?: string
  id: number
  workflowId: number
  action: 'setup' | 'rebuild' | 'flatten'
  status: 'running' | 'success' | 'failed'
  rows: number
  startedAt: string
  finishedAt?: string
  error?: string
  /** 本次实际执行的全部语句,失败时是唯一的排查线索 */
  sql?: string
}

/** 一条待执行的语句 */
export interface ArbStmt {
  source: string
  sql: string
}

/** 目标列在聚合层里的形态(界面用它说明「怎么读」) */
export interface ArbPlanColumn {
  name: string
  mode: ArbMode
  stateType: string
  read: string
}

/** 预览:一次工作流的全部 SQL */
export interface ArbPlan {
  aggTable: string
  aggDdl: string
  mvs: ArbStmt[]
  backfills: ArbStmt[]
  columns: ArbPlanColumn[]
  warnings?: string[]
  /** 服务表(摊平后的普通列表,业务只查这张) */
  svcTable: string
  svcDdl: string
  svcSelect: string
}

/** 聚合层现状 */
export interface ArbProbe {
  aggExists: boolean
  /** 聚合表物理行数(未合并时多于号码数,正常) */
  rows: number
  /** 聚合表去重号码数 = 收敛后的行数 */
  mdns: number
  /** 库里实际存在的物化视图名(= 当前配置里那些且已建好) */
  mvs: string[]
  /** 配置要求、但库里还没建的视图(点「生成」会补上) */
  missingMvs: string[]
  /**
   * 配置外的物化视图(删掉一个源之后它还在库里,继续往聚合层灌数据)。
   * 后端只按配置里的名字比对,所以这些不报出来界面上就永远看不见。
   */
  orphanMvs?: string[]
  /**
   * 聚合表里「现有列类型 ≠ 配置要的类型」的列(改过某列的取值方式)。
   * 建表只在首次按配置生成 DDL,老列的类型不会跟着变 —— 不报出来就永远是静默的错。
   */
  drift?: string[]
  /** 逐源的运行状态(拓扑图用) */
  sources: ArbSourceProbe[]
  /** 服务表(摊平层):业务只查它 */
  svcExists: boolean
  svcRows: number
  /** 聚合表 / 服务表最后一次写入时间 —— 用来判「服务表跟上了没」 */
  aggModified?: string
  svcModified?: string
}

/** 单个源的运行状态 */
export interface ArbSourceProbe {
  sourceId: string
  table: string
  rows: number
  /** 该表去重号码数;rows/mdns > 1 说明一个号码多行,靠仲裁去重 */
  mdns: number
  mvName: string
  mvExists: boolean
  /** 探测失败原因(表被删/号码列改名) —— 只影响这一个源 */
  error?: string
}

/** 一个宽表目标列(由同步任务的字段映射推导) */
export interface WideColumnSpec {
  name: string
  comment?: string
  mode: string
  rule?: string
  sources: string[]
}

export interface WideConflict {
  column: string
  /** mode_mismatch | rule_mismatch | mdn_norm_mismatch | no_phone_column */
  kind: string
  detail: string
  fix: string
}

/** 宽表口径检查结果(只推导不改库) */
export interface WideCheckResult {
  sources: Record<string, string>
  columns: WideColumnSpec[]
  conflicts: WideConflict[]
  warnings: string[]
  mdnNorm: string
}

/** 表体检(DDL 体检)的一条结论 */
export interface DDLCheckFinding {
  /** error = 平台上这一列改不了;warn = 不符合平台规则(表可能不由本平台写入) */
  level: 'error' | 'warn'
  title: string
  /** 涉及哪一列(列级结论) */
  column?: string
  /** 为什么 */
  detail: string
  /** 怎么改 */
  fix?: string
}

/** 体检报告里的一列(事实,不做判断) */
export interface DDLCheckColumn {
  name: string
  type: string
  defaultType?: string
  default?: string
  comment?: string
  codec?: string
  ttl?: string
}

/** 表体检报告 */
export interface TableCheckResult {
  database: string
  table: string
  engine: string
  partitionKeyExpr: string
  sortingKeyExpr: string
  primaryKeyExpr: string
  columns: DDLCheckColumn[]
  findings: DDLCheckFinding[]
  /** 本次【只查了】这几条 —— 0 条结论 ≠ 这张表没问题 */
  rules: string[]
}

/** 加密解密工具:一个算法规格 */
export interface CryptoToolSpec {
  id: string
  name: string
  keyLen: number
  ivLen: number
  aead: boolean
  legacy: boolean
  hint?: string
}

/** 加密解密工具:生成的密钥 */
export interface CryptoToolKeyPair {
  keyHex: string
  keyB64: string
  ivHex?: string
  ivB64?: string
  keyLen: number
  ivLen: number
  forAlgo: string
  algoName: string
  keyNotice?: string
}

/** 加密解密工具:一行结果(Result 与 Error 互斥 —— 一行失败不影响其它行) */
export interface CryptoToolItem {
  value: string
  result?: string
  error?: string
}

/** 加密解密工具:一次批量执行的结果 */
export interface CryptoToolResult {
  items: CryptoToolItem[]
  algo: string
  direction: 'encrypt' | 'decrypt'
  outFormat: string
  inFormat: string
  count: number
  okCount: number
  failCount: number
}

/** 平台登录用户的角色:管理员看全部,普通用户按白名单限权 */
export type AppRole = 'admin' | 'user'

/** 当前登录者身份(登录接口与 /me 的返回体) */
export interface AuthMe {
  capabilities?: { sqlExecution: boolean }
  userId?: number
  enabled?: boolean
  mustChangePassword?: boolean
  username: string
  role: AppRole
}
