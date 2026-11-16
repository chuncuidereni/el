/**
 * index.ts
 *
 * 职责：后端 API 聚合层：按业务域封装参数、返回类型、分页、导出和长任务轮询。
 * 边界：所有端点必须与后端 DTO 对齐；长任务调用支持 AbortSignal，轮询中的预期失败由调用方降级处理。
 */

import { get, post, postBlob, put, del, http, EXPORT_TIMEOUT_MS, type ApiResponse } from './http'
import type {
  GroupByRequest,
  GroupByResult,
  MaterializeResult,
  MaterializeRequest,
  ProgColumn,
  ArbPlan,
  ArbProbe,
  ArbWorkflow,
  ArbWorkflowInput,
  ArbWorkflowRun,
  Connection,
  ConnectionInput,
  ConnStatus,
  DataPage,
  DataSource,
  DataSourceDetail,
  DataSourceInput,
  DatabaseInfo,
  DBGroup,
  DSGroup,
  DataEditRequest,
  DataEditResult,
  FilterNode,
  GlobalSettings,
  GrantRule,
  HashToolResult,
  QueryResponse,
  QueryResult,
  QueryWorkflow,
  QueryWorkflowConfig,
  QueryWorkflowInput,
  QueryWorkflowRun,
  SQLQuery,
  WideTableTask,
  WideTableRun,
  WideTableInput,
  WideTablePreview,
  WideTableCheck,
  SQLQueryGroup,
  SQLQueryInput,
  SftpDirPreview,
  SftpEntry,
  SyncRun,
  SyncRunPage,
  SyncTask,
  WideCheckResult,
  SyncTaskInput,
  TableAlterRequest,
  TableGroup,
  TableInfo,
  TableKeys,
  TableStat,
  UserInfo,
  UserInput,
  SystemStats,
  ServerStats,
  CryptoToolKeyPair,
  CryptoToolResult,
  CryptoToolSpec,
  DataSourceStatus,
  TableCheckResult,
  AuthMe
} from '@/types'

/** 登录 / 身份接口(HTTP Basic 传输,登录页与路由守卫用) */
export const authApi = {
  /**
   * 登录:后端校验请求体里的用户名/密码,返回 {username, role}。
   * _skipAuth —— 登录时还没有(或不该用旧的)Basic 头;silent —— 401 由登录页自己提示,不劫持导航。
   */
  login: (username: string, password: string) =>
    http
      .post('/login', { username, password }, { _skipAuth: true, silent: true } as never)
      .then((r) => (r.data as ApiResponse<AuthMe>).data),
  /** 当前登录者(刷新后据此恢复角色);silent 避免 401 触发全局跳转,由 store 处理。 */
  me: () => get<AuthMe>('/me', undefined, true),
  logout: () => http.post('/logout', undefined, { silent: true, _skipAuth: true })
    .then((r) => (r.data as ApiResponse).data),
  logoutAll: () => post('/me/logout-all'),
  /** 修改自己的密码。 */
  changePassword: (oldPassword: string, newPassword: string) =>
    put<{ ok: boolean }>('/me/password', { oldPassword, newPassword })
}

/** 连接管理接口 */
export const connectionsApi = {  list: () => get<Connection[]>('/connections'),
  status: () => get<ConnStatus[]>('/connections/status'),
  create: (data: ConnectionInput) => post<Connection>('/connections', data),
  update: (id: number, data: ConnectionInput) => put<Connection>(`/connections/${id}`, data),
  /** 全局默认与连接编辑一起提交，服务端保证全部成功或全部回滚。 */
  updateWithSettings: (id: number, connection: ConnectionInput, globalSettings: GlobalSettings) =>
    put<Connection>(`/connections/${id}/settings`, { connection, globalSettings }),
  remove: (id: number) => del<{ id: number }>(`/connections/${id}`),
  test: (data: ConnectionInput) => post<{ ok: boolean }>('/connections/test', data),
  /** 编辑测试复用服务器保存的密码，同时验证本次修改的地址、协议和高级参数。 */
  testConfig: (id: number, data: ConnectionInput) => post<{ ok: boolean }>(`/connections/${id}/test-config`, data),
  testById: (id: number) => post<{ ok: boolean }>(`/connections/${id}/test`)
}

// 路径参数一律编码:库名/表名/用户名可能含 `.`、空格、中文等,直接拼会把 URL 拼坏
const enc = encodeURIComponent

/** 全局默认高级配置接口 */
export const settingsApi = {
  get: () => get<GlobalSettings>('/settings'),
  update: (data: GlobalSettings) => put<{ ok: boolean }>('/settings', data)
}

/** 用户管理接口 */
export const usersApi = {
  list: (connId: number) => get<UserInfo[]>(`/connections/${connId}/users`),
  grants: (connId: number, name: string) => get<GrantRule[]>(`/connections/${connId}/users/${enc(name)}/grants`),
  create: (connId: number, data: UserInput) => post<{ name: string }>(`/connections/${connId}/users`, data),
  update: (connId: number, name: string, data: UserInput) => put(`/connections/${connId}/users/${enc(name)}`, data),
  remove: (connId: number, name: string) => del(`/connections/${connId}/users/${enc(name)}`)
}

/** 数据源管理接口(同步输入端) */
export const dataSourcesApi = {
  list: () => get<DataSource[]>('/datasources'),
  /**
   * 批量数据源状态:本地文件=已上传/已删除;连接型=在线/离线。
   *
   * force=true 绕过后端 15s 缓存(点「刷新」时用 —— 手动刷新必须看到当下状态)。
   */
  status: () => get<DataSourceStatus[]>('/datasources/status'),
  create: (data: DataSourceInput) => post<DataSource>('/datasources', data),
  update: (id: number, data: DataSourceInput) => put<DataSource>(`/datasources/${id}`, data),
  remove: (id: number, silent = false) => del<{ id: number }>(`/datasources/${id}`, silent),
  test: (data: DataSourceInput) => post<{ ok: boolean }>('/datasources/test', data),
  testById: (id: number) => post<{ ok: boolean }>(`/datasources/${id}/test`),
  testConfig: (id: number, input: DataSourceInput) => post<{ ok: boolean }>(`/datasources/${id}/test-config`, input),
  checkObject: (id: number, object: string) => post<{ exists: boolean }>(`/datasources/${id}/check-object`, { object }),
  /** 上传本地 CSV 到服务端(仅存储;预览/解析时由 DuckDB 按需读取) */
  upload: (file: File) => {
    const fd = new FormData()
    fd.append('file', file)
    return http
      .post('/datasources/upload', fd, {
        headers: { 'Content-Type': 'multipart/form-data' },
        timeout: 300_000 // 大文件上传放宽
      })
      .then((r) => (r.data as ApiResponse<{ path: string; name: string; size: number }>).data)
  },

  /** CSV 数据源预览(DuckDB 解析:字段 + 前 N 行 + 总行数);支持无行头 + 手动列名映射 */
  csvPreview: (
    id: number,
    opts?: {
      limit?: number
      header?: boolean
      names?: string[]
      save?: boolean
    }
  ) =>
    post<{
      columns: { name: string; type: string }[]
      rows: { columns: string[]; types: string[]; rows: string[][] }
      total: number
    }>(`/datasources/${id}/csv-preview`, {
      limit: opts?.limit ?? 50,
      header: opts?.header,
      names: opts?.names,
      save: opts?.save
    }),

  /** 按路径直接预览本地文件(新增向导用,无需先保存数据源) */
  previewFile: (
    type: 'csv' | 'json',
    path: string,
    opts?: {
      limit?: number
      header?: boolean
      names?: string[]
      keep?: string[]
    }
  ) =>
    post<{
      columns: { name: string; type: string }[]
      rows: { columns: string[]; types: string[]; rows: string[][] }
      total: number
      has_header?: boolean
    }>(`/datasources/preview-file`, {
      type,
      path,
      limit: opts?.limit ?? 100,
      header: opts?.header,
      names: opts?.names,
      keep: opts?.keep
    }),

  /** 数据源详情(配置摘要弹窗) */
  detail: (id: number) => get<DataSourceDetail>(`/datasources/${id}/detail`),

  /** SFTP:列目录(起点=登录家目录;支持按已保存源 id 或直连参数) */
  sftpBrowse: (p: { id?: number; host?: string; port?: number; username?: string; password?: string; path?: string }) =>
    post<{ path: string; entries: SftpEntry[] }>(`/datasources/sftp/browse`, p),

  /** SFTP:CSV 流式预览，其他格式保留临时文件读取 */
  sftpPreview: (p: {
    csvEncoding?: string
    header?: boolean
    ignoreExtraCsvColumns?: boolean
    id?: number
    host?: string
    port?: number
    username?: string
    password?: string
    path: string
    limit?: number
  }) =>
    post<{
      columns: { name: string; type: string }[]
      rows: { columns: string[]; types: string[]; rows: string[][] }
      total?: number | null
      csvEncoding?: string
    }>(`/datasources/sftp/preview`, p),

  /** SFTP:用未保存连接参数分析目录匹配文件一致性(配置向导阶段用) */
  sftpDirPreview: (p: {
    csvEncoding?: string
    host: string
    port?: number
    username: string
    password: string
    remotePath?: string
    filePattern?: string
  }) => post<SftpDirPreview>(`/datasources/sftp/dir-preview`, p),

  /** SFTP:按已保存数据源 id 分析目录文件结构分组(同步向导选结构类用) */
  sftpPreviewById: (id: number, p?: { remotePath?: string; filePattern?: string; hasHeader?: boolean; ignoreExtraCsvColumns?: boolean; csvEncoding?: string }) =>
    get<SftpDirPreview>(`/datasources/${id}/sftp-preview`, p ?? {}),

  /** SQL 数据源(config.database/table):返回源表列结构(名+类型),同步映射取源列用。table=可选源表覆盖 */
  sqlColumns: (id: number, table?: string, refresh = false) => {
    // 不传 table 时完全不带 query,让后端回落到数据源配置里保存的那张表
    const params = new URLSearchParams()
    if (table) params.set('table', table)
    if (refresh) params.set('refresh', 'true')
    const q = params.size ? `?${params}` : ''
    return get<{ columns: { name: string; type: string }[] }>(`/datasources/${id}/sql-columns${q}`)
  },

  /** SQL 数据源(config.database):返回库下表名列表,选表用 */
  sqlTables: (id: number, refresh = false) => get<{ tables: string[] }>(`/datasources/${id}/sql-tables${refresh ? '?refresh=true' : ''}`),

  sourceObjects: (id: number, refresh = false) => get<{ name: string; kind: string }[]>(`/datasources/${id}/objects${refresh ? '?refresh=true' : ''}`),
  sourceColumns: (id: number, object: string, refresh = false) =>
    post<{ name: string; type: string }[]>(`/datasources/${id}/columns${refresh ? '?refresh=true' : ''}`, { object }),

  /** 设置数据源所属分组(0=默认分组) */
  setGroup: (id: number, groupId: number) => put(`/datasources/${id}/group`, { groupId })
}

/** 数据源分组接口 */
export const dsGroupsApi = {
  list: () => get<DSGroup[]>('/datasource-groups'),
  create: (name: string, remark = '') => post<DSGroup>('/datasource-groups', { name, remark }),
  update: (id: number, name: string, remark = '') => put<DSGroup>(`/datasource-groups/${id}`, { name, remark }),
  remove: (id: number) => del(`/datasource-groups/${id}`)
}

/** 同步任务接口(数据源 → ClickHouse) */
export const syncTasksApi = {
  previewMysqlDateTables: (data: Pick<SyncTaskInput, 'dataSourceId' | 'mysqlDateTables' | 'sourceObject'>) =>
    post<{ tables: string[]; columns: { name: string; type: string }[]; primaryKey?: string;
      referenceTable?: string; effectiveEndDate: string; timeZone: string; missingCount: number; allDates: boolean }>('/sync-tasks/mysql-date-tables/preview', data),
  list: (silent = false) => get<SyncTask[]>('/sync-tasks', undefined, silent),
  page: (params: { page: number; size: number; keyword?: string }, silent = false) =>
    get<{ rows: SyncTask[]; total: number; page: number; size: number }>('/sync-tasks/page', params, silent),
  detail: (id: number) => get<SyncTask>(`/sync-tasks/${id}`),
  statuses: (ids: number[]) => get<Pick<SyncTask, 'id' | 'queued' | 'lastRun'>[]>('/sync-tasks/statuses', { ids: ids.join(',') }, true),
  /** 宽表口径检查:由「纳入宽表」的同步任务推导宽表结构并报冲突(不改库) */
  wideCheck: () => get<WideCheckResult>('/wide-table/check'),
  create: (data: SyncTaskInput) => post<SyncTask>('/sync-tasks', data),
  update: (id: number, data: SyncTaskInput) => put<SyncTask>(`/sync-tasks/${id}`, data),
  setEnabled: (id: number, enabled: boolean) => put<SyncTask>(`/sync-tasks/${id}/enabled`, { enabled }),
  remove: (id: number, silent = false) => del(`/sync-tasks/${id}`, silent),
  /** 手动触发同步，默认恢复未完成轮次；异步返回 runId。 */
  run: (id: number) =>
    post<{ runId: number; status: string }>(`/sync-tasks/${id}/run`, {}),
  /** 清除旧断点、重建目标内容并重新上传全部源数据。 */
  restart: (id: number) =>
    post<{ runId: number; status: string }>(`/sync-tasks/${id}/restart`, {}),
  baselinePreview: (id: number) => get<{
    taskId: number; target: string; sourceCode: string; incrementalColumn: string;
    watermark: string; targetRows: number; confirmationToken: string
  }>(`/sync-tasks/${id}/baseline-preview`),
  adoptBaseline: (id: number, confirmationToken: string) =>
    post(`/sync-tasks/${id}/baseline-adoption`, { confirmationToken, confirmComplete: true }),
  /** 取消某任务正在运行的同步 */
  cancel: (id: number) => post<{ cancelled: boolean }>(`/sync-tasks/${id}/cancel`, {}),
  cancelQueued: (id: number) => post<{ cancelled: boolean }>(`/sync-tasks/${id}/cancel-queued`, {}),
  /** 运行历史 */
  runs: (id: number, silent = false) => get<SyncRun[]>(`/sync-tasks/${id}/runs`, undefined, silent),
  deleteRunLog: (id: number, runId: number) => del<{ deleted: number }>(`/sync-tasks/${id}/runs/${runId}`),
  /**
   * 分页查询执行日志。silent 透传给 HTTP 层：加载指示由页面控制，轮询式刷新也不会触发全局错误弹窗。
   */
  runLogs: (id: number, params: {
    page: number
    size: number
    status?: string
    resumed?: boolean
    days?: number
    keyword?: string
  }, silent = false) => get<SyncRunPage>(`/sync-tasks/${id}/run-logs`, params, silent),
/**
 * 单次运行查询固定 silent：同步页会每 2 秒轮询，不能反复弹全局错误。
 * silent 仍会得到带服务端消息和 HTTP status 的 Error，调用方据此区分 404 等特殊情况。
 */
  runStatus: (id: number, runId: number) => get<SyncRun>(`/sync-tasks/${id}/runs/${runId}`, undefined, true),
  /** 定时任务未来 N 次执行时间 */
  cronNext: (id: number, count = 5) => get<{ next: string[] }>(`/sync-tasks/${id}/cron-next`, { count })
}

/** 系统监控接口 */
export const systemApi = {
  stats: () => get<SystemStats>('/system/stats'),
  /**
   * 目标库服务的运行指标。
   *
   * 第三个参数 silent:这一块失败(连不上目标库、视图读不了)不该弹全局错误 ——
   * 页面自己有错误态。而且它是 10 秒轮询的,弹窗会反复糊在屏幕上。
   */
  serverStats: (connId: number) => get<ServerStats>(`/connections/${connId}/server-stats`, undefined, true)
}

/** 数据工作台接口 */
export const workspaceApi = {
  /** 数据库分组(含成员数据库) */
  groups: (connId: number) => get<DBGroup[]>(`/connections/${connId}/groups`),
  createGroup: (connId: number, name: string, remark = '') =>
    post<DBGroup>(`/connections/${connId}/groups`, { name, remark }),
  updateGroup: (id: number, name: string, remark = '') => put<DBGroup>(`/groups/${id}`, { name, remark }),
  removeGroup: (id: number) => del(`/groups/${id}`),
  /** 批量设置分组成员数据库 */
  setGroupDbs: (id: number, dbs: string[]) => put(`/groups/${id}/dbs`, { dbs }),

  /** 表分组(某库内的表归组) */
  tableGroups: (connId: number, db: string) =>
    get<TableGroup[]>(`/connections/${connId}/databases/${enc(db)}/table-groups`),
  createTableGroup: (connId: number, db: string, name: string, remark = '') =>
    post<TableGroup>(`/connections/${connId}/databases/${enc(db)}/table-groups`, { name, remark }),
  updateTableGroup: (id: number, name: string, remark = '') => put<TableGroup>(`/table-groups/${id}`, { name, remark }),
  removeTableGroup: (id: number) => del(`/table-groups/${id}`),
  /** 把某表设到指定分组(0 = 移出到未分组) */
  setTableGroup: (connId: number, db: string, table: string, groupId: number) =>
    put(`/connections/${connId}/databases/${enc(db)}/tables/${enc(table)}/group`, { groupId }),

  databases: (connId: number) => get<DatabaseInfo[]>(`/connections/${connId}/databases`),

  createDatabase: (connId: number, name: string, remark = '') =>
    post(`/connections/${connId}/databases`, { name, remark }),

  dropDatabase: (connId: number, db: string) => del(`/connections/${connId}/databases/${enc(db)}`),

  tables: (connId: number, db: string) => get<TableInfo[]>(`/connections/${connId}/databases/${enc(db)}/tables`),
  /** 表体检(只读):把平台的护栏(列类型/默认值/分区键)一次问出来 */
  checkTable: (connId: number, db: string, table: string) =>
    get<TableCheckResult>(`/connections/${connId}/databases/${enc(db)}/tables/${enc(table)}/check`),

  /** 某库各表统计(列数/行数/磁盘大小),后端有 TTL 缓存 */
  tableStats: (connId: number, db: string) =>
    get<TableStat[]>(`/connections/${connId}/databases/${enc(db)}/table-stats`),

  /** 合并表(OPTIMIZE TABLE ... FINAL),使 MergeTree 更紧凑 */
  optimizeTable: (connId: number, db: string, table: string) =>
    post(`/connections/${connId}/databases/${enc(db)}/tables/${enc(table)}/optimize`),

  createTable: (connId: number, db: string, ddl: string) =>
    post(`/connections/${connId}/databases/${enc(db)}/tables`, { ddl }),

  schema: (connId: number, db: string, table: string) =>
    get<QueryResult>(`/connections/${connId}/databases/${enc(db)}/tables/${enc(table)}/schema`),

  /** 静默获取表结构(自动补全用,失败不弹错误) */
  schemaSilent: (connId: number, db: string, table: string) =>
    get<QueryResult>(`/connections/${connId}/databases/${enc(db)}/tables/${enc(table)}/schema`, undefined, true),

  tableComment: (connId: number, db: string, table: string) =>
    get<{ comment: string }>(`/connections/${connId}/databases/${enc(db)}/tables/${enc(table)}/comment`),

  keys: (connId: number, db: string, table: string) =>
    get<TableKeys>(`/connections/${connId}/databases/${enc(db)}/tables/${enc(table)}/keys`),

  /**
   * 按维度做分布统计(画像查询):值 + 条数 + 占比分母。
   *
   * 为什么必须后端算:分布要 GROUP BY 全表,前端只拿到当前页的行,
   * 自己数会数成「这一页的分布」—— 大表上完全错。
   */
  groupBy: (connId: number, db: string, table: string, req: GroupByRequest) =>
    post<GroupByResult>(`/connections/${connId}/databases/${enc(db)}/tables/${enc(table)}/group-by`, req),

  /**
   * 把圈选结果落成一张表 —— 就是「数据包」。
   *
   * 为什么要有它:圈出来的这批人要拿去投放 / 再分析 / 被别的工作流当源表,
   * 只导出 CSV 下次还得重新圈;落成表之后它就是一个稳定的数据产物。
   * 过滤条件由后端用同一套渲染器拼,所以「看到的行」=「存进去的行」。
   */
  materialize: (
    connId: number,
    db: string,
    table: string,
    req: MaterializeRequest
  ) => post<MaterializeResult>(`/connections/${connId}/databases/${enc(db)}/tables/${enc(table)}/materialize`, req),

  ddl: (connId: number, db: string, table: string) =>
    get<QueryResult>(`/connections/${connId}/databases/${enc(db)}/tables/${enc(table)}/ddl`),

  data: (
    connId: number,
    db: string,
    table: string,
    offset = 0,
    size = 100,
    filter?: FilterNode,
    columns: string[] = [],
    withCount = true,
    /** 排序列;numeric = 把 String 列按数值排(目标表里仲裁列是 String) */
    orders: { column: string; desc: boolean; numeric?: boolean }[] = []
  ) =>
    post<DataPage>(`/connections/${connId}/databases/${enc(db)}/tables/${enc(table)}/data`, {
      offset,
      size,
      // 传空 and 节点而不是 null:后端统一按「无过滤 = 全表」处理,前端不用再分支
      filter: filter ?? { logic: 'and', children: [] },
      columns,
      withCount,
      orders
    }),

  editData: (connId: number, db: string, table: string, data: DataEditRequest) =>
    post<DataEditResult>(`/connections/${connId}/databases/${enc(db)}/tables/${enc(table)}/data/edit`, data),

  dropTable: (connId: number, db: string, table: string) =>
    del(`/connections/${connId}/databases/${enc(db)}/tables/${enc(table)}`),

  truncateTable: (connId: number, db: string, table: string) =>
    post(`/connections/${connId}/databases/${enc(db)}/tables/${enc(table)}/truncate`),

  alterTable: (connId: number, db: string, table: string, data: TableAlterRequest) =>
    put<{ ok: boolean }>(`/connections/${connId}/databases/${enc(db)}/tables/${enc(table)}/alter`, data),

  /** 导出 CSV:按过滤条件导出全表数据(返回 CSV + 列名 + 总行数) */
  exportCsv: (
    connId: number,
    db: string,
    table: string,
    data: {
      columns: string[]
      headerNames: string[]
      filter: FilterNode
      withHeader: boolean
      delimiter: string
      limit?: number
      orders?: { column: string; desc: boolean }[]
    }
  ) =>
    postBlob(`/connections/${connId}/databases/${enc(db)}/tables/${enc(table)}/export`, data, true, EXPORT_TIMEOUT_MS),

  query: (
    connId: number,
    sql: string,
    allowWrite: boolean,
    database?: string,
    page?: number,
    pageSize?: number,
    /** 程序算子后处理计划(可视化构建器的 sm3/3des 等,由后端逐行计算) */
    progCols?: ProgColumn[]
  ) =>
    post<QueryResponse>(`/connections/${connId}/query`, {
      sql,
      allowWrite,
      database,
      page,
      pageSize,
      progCols
    }),

  /** 把 SQL 结果导出为 CSV(服务端分批查询,不截断) */
  queryExport: (
    connId: number,
    data: {
      sql: string
      database?: string
      columns?: string[]
      headerNames?: string[]
      withHeader: boolean
      delimiter: string
      limit?: number
      /** 程序算子后处理计划 —— 导出也必须应用,否则脱敏/加密列会以明文导出 */
      progCols?: ProgColumn[]
    }
  ) => postBlob(`/connections/${connId}/query/export`, data, true, EXPORT_TIMEOUT_MS)
}

/** 查询工作流(4 步向导:多表查询,只 SELECT 不落库) */
export const queryWorkflowsApi = {
  list: () => get<QueryWorkflow[]>('/query-workflows'),
  get: (id: number) => get<QueryWorkflow>(`/query-workflows/${id}`),
  create: (data: QueryWorkflowInput) => post<QueryWorkflow>('/query-workflows', data),
  update: (id: number, data: QueryWorkflowInput) => put<QueryWorkflow>(`/query-workflows/${id}`, data),
  remove: (id: number) => del<{ id: number }>(`/query-workflows/${id}`),
  /** 仅生成 SQL,不执行 */
  previewSql: (id: number) => post<{ sql: string }>(`/query-workflows/${id}/preview-sql`, {}),
  /** 预览(不需保存的查询配置直接预览 SQL/试跑) */
  preview: (data: { config: QueryWorkflowConfig; run?: boolean; limit?: number }) =>
    post<{
      sql: string
      /** ⚠️ 仅 run=true 时返回:真正执行的那条 SQL(带 LIMIT),与 sql 可能不同 */
      runSql?: string
      /** 程序算子后处理计划(前端只用它做展示/判断,执行时由后端从 config 重新推导) */
      progCols?: ProgColumn[]
      results?: {
        columns: string[]
        types: string[]
        rows: any[][]
        total: number
      }[]
      rowsRead?: number
      error?: string
    }>(`/query-workflows/preview`, data),
  /**
   * 可视化专用:按配置生成 SQL(含程序算子计划)并执行。
   * ⚠️ 只传 config —— SQL 与计划由后端一起推导,前端无从漏传。
   */
  execute: (data: { config: QueryWorkflowConfig; page?: number; pageSize?: number }) =>
    post<{
      sql: string
      progCols?: ProgColumn[]
      readOnly: boolean
      results?: {
        columns: string[]
        types: string[]
        rows: any[][]
        total: number
      }[]
      messages?: string[]
      resultStatements?: string[]
      truncated: boolean
    }>(`/query-workflows/execute`, data),
  /** 可视化专用:按配置导出 CSV(程序算子一并应用,不会漏出原始值) */
  exportCsv: (data: {
    config: QueryWorkflowConfig
    columns?: string[]
    headerNames?: string[]
    withHeader: boolean
    delimiter: string
    limit?: number
  }) => postBlob(`/query-workflows/export`, data, true, EXPORT_TIMEOUT_MS),

  /** 执行 SELECT 并返回结果；默认截断到 1000 行并回传 truncated，传 page/pageSize 时改用服务端分页。 */
  run: (id: number, page?: number, pageSize?: number) =>
    post<{
      runId: number
      sql: string
      results: {
        columns: string[]
        types: string[]
        rows: any[][]
        total: number
      }[]
      messages?: string[]
      truncated: boolean
      rowsRead: number
      resultStatements?: string[]
    }>(`/query-workflows/${id}/run`, page ? { page, pageSize } : {}),
  /** 取消一次进行中的运行 */
  cancelRun: (runId: number) => post<{ cancelled: boolean }>(`/query-workflows/runs/${runId}/cancel`, {}),
  /** 运行历史 */
  runs: (id: number, limit = 20) => get<QueryWorkflowRun[]>(`/query-workflows/${id}/runs`, { limit })
}

/** 已保存的 SQL 查询(数据探索页) */
export const sqlQueriesApi = {
  // groupId=undefined 表示「不过滤,返回全部」;传 0 是有效分组,不能混为一谈
  list: (groupId?: number) => get<SQLQuery[]>('/sql-queries', groupId === undefined ? undefined : { groupId }),
  get: (id: number) => get<SQLQuery>(`/sql-queries/${id}`),
  create: (data: SQLQueryInput) => post<SQLQuery>('/sql-queries', data),
  update: (id: number, data: SQLQueryInput) => put<SQLQuery>(`/sql-queries/${id}`, data),
  remove: (id: number, silent = false) => del<{ id: number }>(`/sql-queries/${id}`, silent),
  setGroup: (id: number, groupId: number) => put(`/sql-queries/${id}/group`, { groupId })
}

/** 数据探索 · 查询分组 */
export const sqlQueryGroupsApi = {
  list: () => get<SQLQueryGroup[]>('/sql-query-groups'),
  create: (name: string, remark = '') => post<SQLQueryGroup>('/sql-query-groups', { name, remark }),
  update: (id: number, name: string, remark = '') => put<SQLQueryGroup>(`/sql-query-groups/${id}`, { name, remark }),
  remove: (id: number) => del<{ id: number }>(`/sql-query-groups/${id}`)
}

/**
 * 宽表加工任务(多表关联 → 全量重建一张宽表,落到 dwd_data)。
 *
 * 与「已保存的 SQL 查询」的区别:那个只查询、不落库;这里是**写操作** ——
 * 会真实建表并替换旧版本的表。
 */
export const wideTablesApi = {
  list: () => get<WideTableTask[]>('/wide-table-tasks'),
  get: (id: number) => get<WideTableTask>(`/wide-table-tasks/${id}`),
  create: (data: WideTableInput) => post<WideTableTask>('/wide-table-tasks', data),
  update: (id: number, data: WideTableInput) => put<WideTableTask>(`/wide-table-tasks/${id}`, data),
  remove: (id: number) => del<{ id: number }>(`/wide-table-tasks/${id}`),
  /**
   * 运行一次(全量重建)。
   *
   * ⚠️ 重建是同步执行的(建临时表 → 灌数 → 原子换名),数据量大时会超过默认 120s。
   *    所以这里单独放宽超时:若沿用默认值,界面会先报超时、而后台仍在跑完并换表 ——
   *    用户以为失败去重试,反而制造并发。
   */
  // 下面几处直接调 http 实例(而不是 post()/get() 包装器):包装器不接受自定义超时
  run: (id: number) =>
    http
      .post(`/wide-table-tasks/${id}/run`, {}, { timeout: WIDE_TABLE_RUN_TIMEOUT_MS })
      .then((r) => (r.data as ApiResponse<WideTableRun>).data),
  runs: (id: number, limit = 20) => get<WideTableRun[]>(`/wide-table-tasks/${id}/runs`, { limit }),
  /** 保存前预览将执行的 SQL(不建表、不写库),同时返回体检结果 */
  // ⚠️ 与 :id/check 是同一个 CheckWideTable(真打库):服务端已进 30m 组,
  //    前端也必须等 30m,否则浏览器先放弃(评审 r6 #12:上一次只改了服务端)
  preview: (data: WideTableInput) =>
    http
      .post('/wide-table-tasks/preview', data, { timeout: WIDE_TABLE_RUN_TIMEOUT_MS })
      .then((r) => (r.data as ApiResponse<WideTablePreview>).data),
  /**
   * 运行前体检:预计行数 + 哪张源表会放大。
   *
   * 单独一步(而不是揉进 run):界面要能【先问再跑】——
   * 否则用户在看到警告之前,表已经被重建了。
   */
  check: (id: number) =>
    http
      .post(`/wide-table-tasks/${id}/check`, {}, { timeout: WIDE_TABLE_RUN_TIMEOUT_MS })
      .then((r) => (r.data as ApiResponse<WideTableCheck>).data)
}

/** 宽表重建的超时上限(与后端运行路由的 exportTimeout 对齐)。 */
// 常量声明在使用点之后无妨:箭头函数体要到真正发请求时才求值,不会踩 TDZ
const WIDE_TABLE_RUN_TIMEOUT_MS = 30 * 60 * 1000

/**
 * 工作流(多源仲裁 → 聚合层)。
 *
 * 与 wideTablesApi 的区别:那套是 JOIN 打平(同名字段只能挑一侧),
 * 这套是 UNION ALL + 按 (非空, 优先级, 时间) 仲裁;产物是聚合表 + 每源物化视图。
 *
 * ⚠️ setup / rebuild 会真实建表、建物化视图、回填历史,是写操作。
 *    回填是分钟级(取决于源表行数),所以单独放宽超时。
 */
export const arbWorkflowsApi = {
  list: () => get<ArbWorkflow[]>('/arb-workflows'),
  get: (id: number) => get<ArbWorkflow>(`/arb-workflows/${id}`),
  create: (data: ArbWorkflowInput) => post<ArbWorkflow>('/arb-workflows', data),
  update: (id: number, data: ArbWorkflowInput) => put<ArbWorkflow>(`/arb-workflows/${id}`, data),
  /** 只删配置。ClickHouse 里的聚合表与物化视图要另外调 dropObjects */
  remove: (id: number) => del<{ id: number }>(`/arb-workflows/${id}`),
  /** 保存前预览全部 SQL(不连库、不建表) */
  preview: (data: ArbWorkflowInput) => post<ArbPlan>('/arb-workflows/preview', data),
  /** 生成 / 更新:建聚合表 + 每源 MV + 回填历史(全量重算,幂等) */
  setup: (id: number, signal?: AbortSignal) => runArbInBackground(id, 'setup', signal),
  /** 重建聚合层:清空重算。修 sum 翻倍 / 源表删号码留下的幽灵行 */
  rebuild: (id: number, signal?: AbortSignal) => runArbInBackground(id, 'rebuild', signal),
  /**
   * 只刷新服务表(摊平)。
   *
   * 聚合层由物化视图实时更新,服务表必须全表 GROUP BY 才能摊 ——
   * MV 做不到(它只看得到刚插入的那批),所以服务表要单独刷。
   */
  flatten: (id: number, signal?: AbortSignal) => runArbInBackground(id, 'flatten', signal),
  /** 现状:聚合表/服务表在不在、视图齐不齐、多少行、最后写入时间 */
  probe: (id: number) => get<ArbProbe>(`/arb-workflows/${id}/probe`),
  /** 删除 ClickHouse 里的产物(聚合表 + 所有 MV)。不可逆 */
  dropObjects: (id: number) =>
    http
      .post(`/arb-workflows/${id}/drop-objects`, {}, { timeout: ARB_RUN_TIMEOUT_MS })
      .then((r) => (r.data as ApiResponse<{ id: number }>).data),
  runs: (id: number, limit = 20, silent = false) => get<ArbWorkflowRun[]>(`/arb-workflows/${id}/runs`, { limit }, silent)
}

/**
 * 工作流“启动 + 轮询”协议。
 * 1. 先向 /start?action=... 提交启动请求并取得 runId；该请求只表示后台已接单，不代表工作流完成。
 * 2. 使用 runId 查询单次运行状态，单次查询超时 15 秒，正常轮询间隔 3 秒。
 * 3. success 返回最终运行结果；failed 抛出带 taskFailed 标记的错误，由页面避免重复弹提示并交给执行历史展示。
 * 4. 401/403/404 立即抛出；其余查询错误会等待后继续。轮询失败不会取消服务端任务。
 * AbortSignal 只中止前端 HTTP 观察，不能保证取消后台工作流；3 秒等待本身不监听 abort，
 * 因此离开页面时最多可能等到本轮等待结束后才抛出 AbortError。
 */
async function runArbInBackground(id: number, action: string, signal?: AbortSignal): Promise<ArbWorkflowRun> {
  const started = await http.post(`/arb-workflows/${id}/start`, {}, { params: { action }, silent: true, signal })
  const { runId } = (started.data as ApiResponse<{ runId: number }>).data
  while (!signal?.aborted) {
    let run: ArbWorkflowRun
    try {
      const response = await http.get(`/arb-workflows/${id}/runs/${runId}`, { silent: true, signal, timeout: 15000 })
      run = (response.data as ApiResponse<ArbWorkflowRun>).data
    } catch (e: any) {
      if (signal?.aborted) break
      if ([401, 403, 404].includes(e?.status)) throw e
      await new Promise<void>((resolve) => setTimeout(resolve, 3000))
      continue
    }
    if (run.status === 'success') return run
    if (run.status === 'failed') throw Object.assign(new Error(run.error || '任务已停止，断点已保留，请查看执行历史'), { taskFailed: true })
    await new Promise<void>((resolve) => setTimeout(resolve, 3000))
  }
  throw new DOMException('已停止观察后台任务', 'AbortError')
}

/** 工作流执行的超时上限(与后端一致)。回填是分钟级,不能沿用默认 120s。 */
// 同 WIDE_TABLE_RUN_TIMEOUT_MS:声明在调用点之后,靠闭包延迟求值
const ARB_RUN_TIMEOUT_MS = 30 * 60 * 1000

/**
 * 工具 · 哈希计算(明文 → MD5 / SHA256 / SM3)。
 *
 * 大小写:默认【大写】。平台里 ClickHouse 的 hex() 与 SM3 程序算子都返回大写,
 * 而本工具的产物大多是拿去与库里的列比对 —— 大小写不一致会让 join / 去重静默不中。
 */
export const cryptoToolApi = {
  /** 支持的算法清单(含长度/提示,前端据此渲染) */
  config: () => get<{ algos: CryptoToolSpec[] }>('/tools/crypto/config'),
  /** 生成随机密钥与 IV(长度由算法规格决定) */
  keygen: (algo: string) => post<CryptoToolKeyPair>('/tools/crypto/keygen', { algo }),
  /** 批量加解密(按行返回结果与错误) */
  run: (data: {
    algo: string
    direction: 'encrypt' | 'decrypt'
    key: string
    iv: string
    keyFormat: string
    inFormat: string
    outFormat: string
    values: string[]
  }) => post<CryptoToolResult>('/tools/crypto/run', data)
}

export const hashToolApi = {
  /** 算法清单 */
  config: () => get<{ algos: string[] }>('/tools/hash/config'),
  /** 批量计算摘要。值按【字节原样】算:去行尾空白是页面的事(见视图里的解析注释) */
  digest: (data: { algo: string; values: string[]; upper?: boolean }) =>
    post<HashToolResult>('/tools/hash/digest', data)
}

/** 表共享:本人拥有的表可把只读权限授予其他平台用户。 */
export interface DataAssetShare {
  userId: number
  username: string
  permission?: string
  status: string
  lastError?: string | null
  createdAt?: string
  updatedAt?: string
}

export interface DataAssetRecipient {
  userId: number
  username: string
}

export const dataAssetsApi = {
  shares: (id: number) => get<DataAssetShare[]>(`/data-assets/${id}/shares`),
  recipients: (keyword: string) => get<DataAssetRecipient[]>('/data-assets/recipients', { keyword }),
  share: (id: number, userId: number) => post(`/data-assets/${id}/shares`, { userId }),
  revoke: (id: number, userId: number) => del(`/data-assets/${id}/shares/${userId}`),
}
