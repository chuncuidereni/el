<!--
  OdsLayerView.vue

  职责：ODS 标准源表层页面：按连接和分组浏览源表并进入数据预览。
  边界：按连接、分组和关键词浏览 ODS 表；路由 query 与导航 tick 驱动定位和刷新，打开表时按需加载数据预览。
-->

<template>
  <div class="ods-view">
    <!-- 右:当前分组下表列表 -->
    <main class="ods-main">
      <ListPageTemplate v-model:page="page" v-model:page-size="pageSize" :total="shownTables.length">
      <template #header>
      <div class="ods-head">
        <div>
          <h2>标准源表（ods_data）</h2>
          <p class="ods-sub">同步任务的目标落库处;点表行打开数据。</p>
        </div>
      </div>

      <ListQueryBar
        v-model:group="query.draftGroup.value"
        v-model:keyword="query.draftKeyword.value"
        all-label="全部表"
        :all-count="tables.length"
        special-label="默认分组"
        special-value="ungrouped"
        :special-count="ungroupedTables.length"
        :groups="groups"
        group-prefix="g:"
        keyword-placeholder="搜索表名或表注释"
        @search="query.search"
        @reset="resetListQuery"
        @new="gmgNew"
        @edit="gmgSelect"
        @remove="gmgRemove"
      />

      <div class="list-table-ops">
        <div class="list-table-ops-start">
          <el-button type="primary" :icon="Plus" @click="openCreateTable">新建标准源表（ods_data）</el-button>
        </div>
      </div>

      </template>
      <div class="ods-tbl-wrap" v-loading="loadingTables">
        <!-- height="100%" + 外层固定高度 = 表头固定、表体内部滚动(分页器不会被顶出屏幕) -->
        <el-table :data="pagedTables" border stripe size="small" height="100%" scrollbar-always-on class="ods-table">
          <el-table-column type="index" :index="globalIndex" width="60" align="center" label="序号" />
          <!-- 表名:整行只有它可点开,所以表注释做成 tooltip 而不是另开一列(tooltip 用 show-after 延迟,免得划过整列时到处弹) -->
          <el-table-column prop="name" label="表名" min-width="230">
            <template #default="{ row }">
              <el-tooltip
                :content="row.remark"
                placement="top-start"
                :show-after="300"
                :disabled="!row.remark"
              >
                <span class="mono tbl-link" @click="openTable(row.name)">{{ row.name }}</span>
              </el-tooltip>
            </template>
          </el-table-column>
          <el-table-column prop="engine" label="引擎" min-width="140">
            <template #default="{ row }">
              <el-tooltip :content="engineDesc(row.engine)" placement="top" :show-after="250" :disabled="!engineDesc(row.engine)">
                <span class="mono tbl-engine">{{ row.engine }}</span>
              </el-tooltip>
            </template>
          </el-table-column>
          <el-table-column prop="creatorName" label="创建者" width="140" show-overflow-tooltip>
            <template #default="{ row }">
              <span :class="{ dim: !row.creatorName }">{{ row.creatorName || '历史数据' }}</span>
              <el-tag v-if="row.accessMode === 'read'" size="small" type="info" class="tbl-kind">只读</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="remark" label="表注释" min-width="210" show-overflow-tooltip>
            <template #default="{ row }">
              <span v-if="row.remark" :title="row.remark">{{ row.remark }}</span>
              <span v-else class="dim">—</span>
            </template>
          </el-table-column>
          <el-table-column label="列数" width="80" align="right">
            <!-- 统计是另一个接口(带缓存),还没回来时显示 '-' 而不是 0:0 列的表不存在,会被当成 bug -->
            <template #default="{ row }">{{ statOf(row.name)?.cols ?? '-' }}</template>
          </el-table-column>
          <el-table-column label="行数" width="120" align="right">
            <template #default="{ row }">{{ fmtNum(statOf(row.name)?.rows) }}</template>
          </el-table-column>
          <el-table-column label="磁盘大小" width="110" align="right">
            <template #default="{ row }">{{ fmtBytes(statOf(row.name)?.bytes) }}</template>
          </el-table-column>
          <el-table-column label="所属分组" min-width="160">
            <template #default="{ row }">
              <!-- 0 = 默认分组(未归组);选中即提交,不做"保存"步骤 -->
              <el-select
                :model-value="groupOf(row.name)"
                size="small"
                :disabled="row.canManage === false"
                placeholder="默认分组"
                style="width: 100%"
                @change="(v: any) => moveTable(row.name, Number(v))"
              >
                <el-option :value="0" label="默认分组" />
                <el-option v-for="g in groups" :key="g.id" :value="g.id" :label="g.name" />
              </el-select>
            </template>
          </el-table-column>
          <!-- 操作列放的是 3 个 32px 图标按钮(打开/整理/共享),90px 会把最后一个裁掉 -->
          <el-table-column label="操作" width="130" align="center">
            <template #default="{ row }">
              <ActionIconBar :actions="tableActions.filter(a => a.command === 'open' || (a.command === 'share' ? row.canShare : row.canWrite !== false))" @command="(cmd: string) => onTableCommand(cmd, row)" />
            </template>
          </el-table-column>
          <template #empty>
            <!-- 空态分两种:整库确实没表 / 只是当前分类被筛空了 —— 给同一句话会让人以为表丢了 -->
            <div class="ods-empty-hint">{{ filter === 'all' ? '暂无表,点上方 新建表 或经映射任务同步生成' : '该分类下暂无表' }}</div>
          </template>
        </el-table>
      </div>
      </ListPageTemplate>
    </main>

    <!-- 查看表数据(留在本页,不跳转) -->
    <!-- 头部自带「关闭」按钮 → 关掉默认的 ×(否则两个关闭入口) -->
    <el-drawer v-model="viewOpen" :title="`数据 · ${viewTable}`" size="86%" :with-header="true" :show-close="false" destroy-on-close>
      <template #header>
        <div class="ods-drawer-head">
          <span class="ods-drawer-title">表数据 · <span class="mono">{{ viewTable }}</span>
            <span v-if="viewMeta?.creatorName" class="dim"> · 创建者 {{ viewMeta.creatorName }}</span>
            <el-tag v-if="viewMeta?.accessMode === 'read'" size="small" type="info">只读共享</el-tag>
          </span>
          <el-button size="small" :icon="Close" @click="viewOpen = false">关闭</el-button>
        </div>
      </template>
      <TableDetail
        v-if="connReady && viewTable"
        :key="`${connId}:${viewTable}`"
        :conn-id="connId"
        :db="db"
        :table="viewTable"
        :readonly="viewMeta ? viewMeta.canWrite === false : false"
        :can-manage="viewMeta?.canManage"
        :can-drop="viewMeta?.canDrop"
        @deleted="onViewDeleted"
        @renamed="onViewRenamed"
        @updated="loadTables"
      />
    </el-drawer>

    <!-- 分组管理对话框(新建/编辑共用:gmgEditing 为 null 即新建) -->
    <el-dialog v-model="gmgVisible" :title="gmgEditing ? '编辑分组' : '新建分组'" width="420px" :close-on-click-modal="false">
      <el-form label-width="64px" @submit.prevent>
        <el-form-item label="名称">
          <el-input v-model="gmgName" maxlength="50" placeholder="分组名称" @keyup.enter="gmgSave" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="gmgRemark" maxlength="100" placeholder="可选" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="gmgVisible = false">取消</el-button>
        <el-button type="primary" :loading="gmgSaving" @click="gmgSave">保存</el-button>
      </template>
    </el-dialog>

    <!-- as-ods:同一张建表对话框按"贴源表"形态渲染(自动补号码列、不选层级) -->
    <CreateTableDialog v-model="createVisible" :conn-id="connId" :db="db" as-ods @created="onTableCreated" />
    <ShareTableDialog v-model="shareVisible" :asset-id="shareAssetId" :table-name="shareTableName" @changed="loadTables" />
  </div>
</template>

<script setup lang="ts">
/**
 * 标准源表（ods_data）(ODS)列表 —— 同步任务的落库处。
 *
 * 这一页是"表"的入口,不是"数据"的入口:点表名在抽屉里看数据(留在本页,不跳转),
 * 免得看完几张表就得靠浏览器后退回来。
 *
 * 三条贯穿全页的约定:
 *  1. 库名写死 ods_data,且【不展示给用户】—— 层级对用户是隐藏概念(见 utils/layer.ts)。
 *  2. 表清单 / 分组 / 统计是三个独立接口,各自失败互不阻塞。
 *  3. 三者都可能被连点刷新覆盖,所以各带一个自增序号做竞态守卫。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { Close, Plus } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import CreateTableDialog from '@/components/CreateTableDialog.vue'
import TableDetail from '@/components/TableDetail.vue'
import ShareTableDialog from '@/components/ShareTableDialog.vue'
import { connectionsApi, workspaceApi } from '@/api'
import type { TableGroup, TableInfo, TableStat } from '@/types'
import ListQueryBar from '@/components/ListQueryBar.vue'
import ListPageTemplate from '@/components/ListPageTemplate.vue'
import { useListQuery } from '@/composables/useListQuery'
import ActionIconBar from '@/components/ActionIconBar.vue'
import { confirmAction, confirmDestructive } from '@/utils/confirm'
import { useNavReset } from '@/composables/useNavReset'

// 库名写死:整页只服务 ODS 一层,做成可选既没意义也会让"当前在看哪层"变模糊
const db = 'ods_data'
const route = useRoute()
const tableActions = [
  { command: 'open', label: '打开数据', primary: true },
  { command: 'optimize', label: '整理表（OPTIMIZE 合并数据片段）' },
  { command: 'share', label: '共享 / 撤销共享' },
]
const shareVisible = ref(false)
const shareAssetId = ref<number | null>(null)
const shareTableName = ref('')
function openShare(row: TableInfo) {
  if (!row.assetId || row.canShare === false) return
  shareAssetId.value = row.assetId
  shareTableName.value = row.name
  shareVisible.value = true
}
function onTableCommand(command: string, row: TableInfo) {
  if (command === 'open') openTable(row.name)
  else if (command === 'optimize') void optimizeTable(row.name)
  else if (command === 'share') openShare(row)
}

/** 行数带千分位(大表动辄百万行,不加分隔符根本数不清位数) */
function fmtNum(n: number | null | undefined): string {
  if (n === undefined || n === null) return '-'
  return n.toLocaleString('en-US')
}
/** 字节 → 人类可读。1024 进制逐级降;超过 TB 就停在 TB(不再引入 PB,表数据不会到那个量级) */
function fmtBytes(b: number | null | undefined): string {
  if (b === undefined || b === null) return '-'
  if (b < 1024) return `${b} B`
  const units = ['KB', 'MB', 'GB', 'TB']
  let v = b / 1024
  let i = 0
  while (v >= 1024 && i < units.length - 1) {
    v /= 1024
    i++
  }
  return `${v.toFixed(1)} ${units[i]}`
}

/** ClickHouse 表引擎说明(供引擎名浮动提示) */
const ENGINE_DESC: Record<string, string> = {
  MergeTree: '通用日志/明细表引擎:写入后的数据按分区/排序存储,后台自动合并小片段。适合存明细与多数同步落库表。',
  ReplacingMergeTree:
    '在 MergeTree 基础上,按版本或写入顺序去重(同排序键只保留最新)。适合“同一主键只留最新”的增量场景。',
  SummingMergeTree: '在 MergeTree 基础上,合并时把同排序键的数值列自动求和。适合累加/汇总计数类数据。',
  AggregatingMergeTree: '在 MergeTree 基础上,合并时按聚合函数预聚合。适合需持续聚合统计的大表。',
  CollapsingMergeTree: '用 ±1 的行对做“折叠”:同排序键的正负行合并时抵消删除。适合需要频繁增删的实时报表。',
  VersionedCollapsingMergeTree: '在折叠引擎上加版本号,能按版本正确折叠。适合“先减旧版本再加新版本”的数据。',
  Log: '简单的日志引擎,写入快、适合小表测试/临时数据,不支持分区与修改。',
  TinyLog: '最简日志引擎,单线程写入,适合极小的临时表。',
  StripeLog: '日志引擎,把数据按列存储、便于逐列读取,适合小量日志。',
  Memory: '数据只存内存,速度极快但不持久,重启即丢,适合临时/测试。',
  Distributed: '分布式表引擎:本身不存数据,把读写分发到后端的多个分片表。',
  Null: '写入即丢弃(空引擎),常用于测试/数据管道占位。',
  Buffer: '缓冲引擎:先把写入攒到内存,再批量刷到目标表,降低写入压力。',
  Set: '集合引擎:只保存去重后的键,常用于 WHERE ... IN 判断。',
  Dictionary: '字典引擎:把外部字典源读为本地内存字典,查询时用 dictGet 快速取值。',
  Kafka: 'Kafka 引擎:直接把 Kafka 主题当表读/写,配合物化视图做实时流接入。',
  MySQL: 'MySQL 引擎:把远端 MySQL 表映射为本地可查表。',
  PostgreSQL: 'PostgreSQL 引擎:把远端 PostgreSQL 表映射为本地可查表。',
  File: 'File 引擎:把数据按文件读/写(CSV/TabSeparated 等格式)。',
  URL: 'URL 引擎:按 HTTP(S) 地址读/写远端文件数据。',
  View: '普通视图引擎(只读映射一条 SELECT)。',
  MaterializedView: '物化视图:插入源表时自动把结果写入它,常用于实时聚合/转发。',
  ReplicatedMergeTree: 'MergeTree 的复制版:多副本自动同步,提供高可用。',
  ReplicatedReplacingMergeTree: 'ReplacingMergeTree 的复制版:支持多副本高可用。',
  ReplicatedSummingMergeTree: 'SummingMergeTree 的复制版:支持多副本高可用。',
  ReplicatedAggregatingMergeTree: 'AggregatingMergeTree 的复制版:支持多副本高可用。',
  ReplicatedCollapsingMergeTree: 'CollapsingMergeTree 的复制版:支持多副本高可用。'
}

/** 根据引擎名返回中文说明;无匹配返回 ''(不显示提示) */
function engineDesc(engine: string): string {
  if (!engine) return ''
  const base = engine.replace(/\s*\(.*/, '').trim() // 去掉括号参数,如 ReplicatedMergeTree(...)
  if (ENGINE_DESC[base]) return ENGINE_DESC[base]
  // 再尝试去掉末尾路径等(如 engine 带路径)后取已知键;未知则返回通用说明
  return `表引擎:${base}。决定数据如何存储与合并。`
}

/** 当前连接 id;0 = 还没解析出来(统计/表清单都靠它,所以下面所有 loader 都先看 connReady) */
const connId = ref(0)
const connReady = ref(false)
const tables = ref<TableInfo[]>([])
const groups = ref<TableGroup[]>([])
/** 表名 → 列数/行数/字节(单独接口返回,可能比表清单晚到,所以按名字查而不是按下标对应) */
const stats = ref<Record<string, TableStat>>({})
const loadingTables = ref(false)
const query = useListQuery()
const kw = query.keyword
const filter = query.group // all | ungrouped | g:id
const createVisible = ref(false)
const viewTable = ref('') // 在弹层内打开查看数据的表;非空即抽屉打开
const viewMeta = ref<TableInfo | null>(null) // 当前表的服务端能力(共享表只读)

// ── 分组编辑对话框的状态(gmg = group manage) ──
const gmgVisible = ref(false)
/** null = 新建;非空 = 编辑该分组 */
const gmgEditing = ref<TableGroup | null>(null)
const gmgName = ref('')
const gmgRemark = ref('')
const gmgSaving = ref(false)

/** 供组件栏显示当前分类名(f:'g:3' → 该分组的名字) */
const curFilterLabel = computed(() => {
  if (filter.value === 'ungrouped') return '默认分组'
  if (filter.value.startsWith('g:'))
    return groups.value.find((g) => g.id === Number(filter.value.slice(2)))?.name ?? '分组'
  return '全部表'
})

// 表名 → 分组 id 的反查表。分组接口给的是"分组 → 表",而表格是按行(表)渲染的,
// 每行都要问一次"它在哪个组",先摊平成 map 免得每行都去遍历分组。
const tableGroupMap = computed<Record<string, number>>(() => {
  const m: Record<string, number> = {}
  for (const g of groups.value) for (const t of g.tables ?? []) m[t] = g.id
  return m
})
/** 未归组 = 不在任何分组里;用 >0 判断是因为 id 从 1 开始,0 是"默认分组"的伪 id */
const ungroupedTables = computed(() => tables.value.filter((t) => !(tableGroupMap.value[t.name] > 0)))

/**
 * 列表的最终数据源 = 分组筛选 → 身份筛选 → 关键词搜索。
 * 三级都是纯函数式的链式过滤,顺序固定:先按分类缩小,再按关键词收窄。
 */
const shownTables = computed(() => {
  const k = kw.value.trim().toLowerCase()
  let base = tables.value
  if (filter.value === 'ungrouped') base = ungroupedTables.value
  else if (filter.value.startsWith('g:')) {
    const gid = Number(filter.value.slice(2))
    const set = new Set(groups.value.find((g) => g.id === gid)?.tables ?? [])
    base = tables.value.filter((t) => set.has(t.name))
  }
  if (!k) return base
  // 支持按【表名 + 表注释】搜索,空格分隔多关键词(全部命中)
  const words = k.split(/\s+/).filter(Boolean)
  return base.filter((t) => {
    // 拼成一段文本再逐词匹配:多关键词是 AND 语义,「用户 手机」能定位到注释里同时含这两词的表
    const hay = `${t.name}\n${t.remark ?? ''}`.toLowerCase()
    return words.every((w) => hay.includes(w))
  })
})
function groupOf(name: string): number {
  return tableGroupMap.value[name] ?? 0
}

// ---- 前端分页(与数据源管理一致) ----
// 分页在前端做:表清单本来就是一次性拉回来的(ClickHouse 的库表数量有限),
// 再走一趟服务端分页只会让搜索/切分组时多一次往返。
const page = ref(1)
const pageSize = ref(10)
const pagedTables = computed(() => {
  const start = (page.value - 1) * pageSize.value
  return shownTables.value.slice(start, start + pageSize.value)
})
/** 分页下全局序号(跨页连续) */
function globalIndex(i: number): number {
  return (page.value - 1) * pageSize.value + i + 1
}
// 切换分组/搜索时回到第一页
watch([filter, kw], () => {
  page.value = 1
})

/**
 * 解析用哪个连接。顺序:默认连接 → 当前已选 → 列表第一个。
 * 拿不到连接时【不报错】:connReady 保持 false,三个 loader 会直接返回,
 * 页面显示成空列表而不是弹一串错误(没配连接时看空表是合理的第一印象)。
 */
async function resolveConn() {
  const conns = (await connectionsApi.list().catch(() => [])) as any[]
  const requested = Number(route.query.conn)
  const def = requested
    ? conns.find((c: any) => c.id === requested)
    : (conns.find((c: any) => c.id === connId.value) ?? conns.find((c: any) => c.isDefault) ?? conns[0])
  connReady.value = false
  if (requested && !def) ElMessage.warning('指定连接不存在，请检查任务的目标连接')
  if (def) {
    connId.value = def.id
    connReady.value = true
  }
}
// ⚠️ seq 守卫:连点刷新 / 切连接时请求会并发,旧响应不能覆盖新数据。
//    与「数据探索」的 sqlSeq 同一套做法。
let groupsSeq = 0
let tablesSeq = 0
let statsSeq = 0

async function loadGroups() {
  if (!connReady.value) return
  const my = ++groupsSeq
  try {
    const rows = (await workspaceApi.tableGroups(connId.value, db)) as unknown as TableGroup[]
    if (my !== groupsSeq) return
    groups.value = rows
  } catch {
    /* 拦截器已提示 */
  }
}
async function loadTables() {
  if (!connReady.value) return
  const my = ++tablesSeq
  loadingTables.value = true
  try {
    const rows = await workspaceApi.tables(connId.value, db)
    if (my !== tablesSeq) return
    tables.value = rows
  } catch {
    /* 拦截器已提示 */
  } finally {
    if (my === tablesSeq) loadingTables.value = false
  }
}
/** 拉取各表统计(列数/行数/磁盘大小)。后端带缓存,不会每次阻塞列表;手动刷新或 OPTIMIZE 后强制更新。 */
async function loadStats() {
  if (!connReady.value) return
  const my = ++statsSeq
  try {
    const rows = await workspaceApi.tableStats(connId.value, db)
    if (my !== statsSeq) return
    const m: Record<string, TableStat> = {}
    for (const r of rows) m[r.name] = r
    stats.value = m
  } catch {
    /* 拦截器已提示 */
  }
}
/** 按名字取统计(模板里每行调一次) */
function statOf(name: string) {
  return stats.value[name]
}
/** 三个接口并发拉;用 Promise.all 是因为它们互不依赖,串行会让刷新明显变慢 */
async function reloadAll() {
  await Promise.all([loadGroups(), loadTables(), loadStats()])
}

function resetListQuery() {
  query.reset()
  void reloadAll()
}

/** 整理表:OPTIMIZE TABLE ... FINAL */
async function optimizeTable(name: string) {
  // 只报表名:物理库名对用户是隐藏概念(见 utils/layer.ts 的约定)
  if (!(await confirmDestructive('整理表', name, '整理'))) return
  const tbl = name
  try {
    await workspaceApi.optimizeTable(connId.value, db, tbl)
    ElMessage.success('已发起整理')
    await loadStats()
  } catch {
    /* 拦截器已提示 */
  }
}

// ---- 分组 CRUD ----
// 三处打开对话框的入口都做同一件事:重置三个字段再打开。
// 不重置的话上一次编辑过的名字会留在框里,点"新建"却看到旧内容。
function gmgNew() {
  gmgEditing.value = null
  gmgName.value = ''
  gmgRemark.value = ''
  gmgVisible.value = true
}
function gmgSelect(g: TableGroup) {
  gmgEditing.value = g
  gmgName.value = g.name
  gmgRemark.value = g.remark ?? ''
  gmgVisible.value = true
}
function gmgManage() {
  gmgEditing.value = null
  gmgName.value = ''
  gmgRemark.value = ''
  gmgVisible.value = true
}
async function gmgSave() {
  const name = gmgName.value.trim()
  if (!name) {
    ElMessage.warning('请输入分组名称')
    return
  }
  if (!(await confirmAction(gmgEditing.value ? '保存表分组' : '新建表分组',
    gmgEditing.value ? `确认保存分组「${name}」的修改？` : `确认新建表分组「${name}」？`,
    gmgEditing.value ? '确认保存' : '确认新建'))) return
  gmgSaving.value = true
  try {
    if (gmgEditing.value) {
      await workspaceApi.updateTableGroup(gmgEditing.value.id, name, gmgRemark.value.trim())
      ElMessage.success('分组已更新')
    } else {
      await workspaceApi.createTableGroup(connId.value, db, name, gmgRemark.value.trim())
      ElMessage.success('分组已创建')
    }
    gmgVisible.value = false
    await loadGroups()
  } catch {
    /* 拦截器已提示 */
  } finally {
    gmgSaving.value = false
  }
}
async function gmgRemove(g: TableGroup) {
  if (!(await confirmDestructive('删除分组', g.name, '删除'))) return
  try {
    await workspaceApi.removeTableGroup(g.id)
    ElMessage.success('分组已删除')
    query.removeGroup(`g:${g.id}`)
    await loadGroups()
  } catch {
    /* 拦截器已提示 */
  }
}

// ---- 表归组 / 打开 ----
/** 归组只改分组关系(接口按"表 → 组"单点提交),回来重拉分组即可,不用刷整表清单 */
async function moveTable(name: string, groupId: number) {
  if (!connReady.value) return
  const groupName = groupId ? (groups.value.find((g) => g.id === groupId)?.name ?? `#${groupId}`) : '默认分组'
  if (!(await confirmAction('移动表分组', `确认把表「${name}」移动到「${groupName}」？`, '确认移动'))) return
  try {
    await workspaceApi.setTableGroup(connId.value, db, name, groupId)
    await loadGroups()
  } catch {
    /* 拦截器已提示,失败时下拉会自己弹回旧值(值来自 computed,没有本地副本) */
  }
}
function openTable(name: string) {
  viewMeta.value = tables.value.find((t) => t.name === name) ?? null
  viewTable.value = name
}
/** 抽屉的开合由 viewTable 派生:非空即开,关闭时清空(单一数据源,不会出现"关了但表名还留着") */
const viewOpen = computed({
  get: () => !!viewTable.value,
  set: (v: boolean) => {
    if (!v) { viewTable.value = ''; viewMeta.value = null }
  }
})

// 关闭数据抽屉后刷新统计(编辑可能改了行数),避免行数长期过期
watch(viewOpen, (open) => {
  if (!open) loadStats()
})

/** 点侧边栏当前菜单 → 关闭表数据抽屉,回到表列表 */
const navResetTick = useNavReset()
watch(navResetTick, () => {
  if (viewTable.value) { viewTable.value = ''; viewMeta.value = null }
})
function onViewDeleted() {
  viewTable.value = ''
  loadTables()
  loadGroups()
  loadStats()
}
/** 表被改名:抽屉里 TableDetail 用 :key="viewTable" 挂载,更新 viewTable 即自动重建指向新表;
 *  同时旧名可能在自定义分组里,重拉分组/表以剔除死成员(O3 依赖此处刷新)。 */
function onViewRenamed(newName: string) {
  if (newName && viewTable.value !== newName) {
    viewTable.value = newName
    if (viewMeta.value) viewMeta.value = { ...viewMeta.value, name: newName }
  }
  loadTables()
  loadGroups()
  loadStats()
}

async function openCreateTable() {
  createVisible.value = true
}
async function onTableCreated() {
  createVisible.value = false
  await loadTables()
  await loadGroups()
  await loadStats() // 新建表也应即时刷新统计(否则 列数/行数/磁盘 恒为 -)
}

let routeSeq = 0
async function openRouteTarget() {
  const seq = ++routeSeq
  viewTable.value = ''
  ++groupsSeq
  ++tablesSeq
  ++statsSeq
  tables.value = []
  groups.value = []
  stats.value = {}
  await resolveConn()
  if (seq !== routeSeq || !connReady.value) return
  await reloadAll()
  if (seq !== routeSeq) return
  // 从映射同步任务跳转(?table=目标贴源表):加载后直接打开该表数据
  const want = typeof route.query.table === 'string' ? route.query.table : ''
  if (want && tables.value.some((t) => t.name === want)) openTable(want)
  else if (want) ElMessage.warning(`目标表 ${want} 不存在`)
}
onMounted(openRouteTarget)
watch(() => [route.query.conn, route.query.table], openRouteTarget)
</script>

<style scoped>
/*
  overflow: hidden(不是 auto)+ flex 纵向布局:本页是"表头固定 + 列表内部滚动"的形态,
  整页滚动会让表头跟着走、分页器被推到屏幕外。滚动交给 .ods-tbl-wrap 里面的 el-table。
*/
.ods-view {
  height: 100%;
  box-sizing: border-box;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  padding: 20px 24px;
}
/* 左:分组栏 */
/* min-width: 0 是必需的:flex 子项默认 min-width:auto,长表名会把容器撑破导致横向溢出 */
.ods-main {
  flex: 1;
  min-height: 0;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 12px;
  overflow: hidden;
}
.ods-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}
/* 工具按钮固定一行,不换行;标题区可收缩 */
.ods-head > div:first-child {
  min-width: 0;
}
.ods-head .actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: none;
  flex-wrap: nowrap;
}
.ods-head h2 {
  margin: 0;
  font-size: 18px;
}
.ods-head-group {
  font-size: 14px;
  color: var(--el-text-color-secondary);
  font-family: var(--el-font-family-mono, monospace);
}
.ods-sub {
  margin: 4px 0 0;
  font-size: 14px;
  color: var(--el-text-color-secondary);
}
/*
  表格容器:flex:1 + min-height:0 才能让 el-table 的 height="100%" 算出真实高度
  (父级不定高时 100% 会退化成内容高,内部滚动就失效了)。
*/
.ods-tbl-wrap {
  flex: 1;
  min-height: 0;
  /* 外框由 el-table--border 统一提供(见 theme.css),这里不再套第二层边框 */
  overflow: hidden;
}
.ods-table {
  width: 100%;
}
/* 可点开的表名:链接色 + hover 下划线,与普通文本区分开 */
.tbl-link {
  cursor: pointer;
  color: var(--el-color-primary);
}
.tbl-link:hover {
  text-decoration: underline;
}
.ods-empty-hint {
  padding: 14px;
  color: var(--el-text-color-secondary);
}
.ods-drawer-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  gap: 8px;
}
.ods-drawer-title {
  font-weight: 600;
  font-size: 14px;
}
/*
  抽屉头部与内边距压扁 —— 与「目标库表（dwd_data）」页保持一致。
  Element Plus 默认 header 20px 内边距 + 30px 下边距、body 20px 内边距,
  一屏里白吃掉 70 多像素:标题离内容老远,数据区还被挤小。
  body 不留白且不滚动,滚动交给里面的结果表格(表头固定)。
*/
.ods-view :deep(.el-drawer__header) {
  padding: 8px 14px;
  margin-bottom: 0;
  border-bottom: 1px solid var(--el-border-color-lighter);
}
.ods-view :deep(.el-drawer__body) {
  padding: 8px 14px;
  overflow: hidden;
}
.mono {
  font-family: var(--el-font-family-mono, monospace);
}
/* .dim 见全局主题(assets/theme.css) */
/* 引擎名可停靠查看说明(cursor: help + 虚线下划线是"这里可以悬停"的通用暗示) */
.tbl-engine {
  cursor: help;
  color: var(--el-text-color-primary);
  white-space: nowrap; /* 引擎名带括号参数时会很长,折行会让行高参差 */
}
.tbl-engine:hover {
  color: var(--el-color-primary);
  text-decoration: underline dotted;
}
</style>
