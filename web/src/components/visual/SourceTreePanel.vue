<!--
  SourceTreePanel.vue

  职责：可视化查询源树：加载数据库/表并管理已选来源。
  边界：按 connId/database 加载表及按需列信息；分组折叠、搜索和加字段通过 emits 交给构建器，当前主表重复点击不发出选择事件。
-->

<template>
  <div class="st-wrap">
    <div class="st-head">
      <span class="st-title">数据源</span>
      <!-- 库名按层级缩写展示(title 里挂原始全名兜底,截断后鼠标悬停仍可确认真实库名) -->
      <span class="st-db" :title="database">{{ dbLabel(database) }}</span>
      <!-- 手动重拉:外部改过库结构(建表/删表)时不必重开页面 -->
      <el-button
        size="small"
        text
        :icon="Refresh"
        :loading="loadingTables"
        @click="reloadTables"
      />
    </div>
    <!--
      搜索词对表名与字段名都生效,但字段是懒加载的:只有展开过的表才有字段数据,
      所以「搜字段」实际只在已加载过列的表里命中(占位文案里已向用户说明)。
    -->
    <el-input
      v-model="kw"
      size="small"
      placeholder="搜索表名(已展开的表可搜字段)"
      :prefix-icon="Search"
      clearable
      class="st-search"
    />

    <div class="st-body" v-loading="loadingTables">
      <!-- 两种空态必须分开:库真的是空的 / 搜索没命中,否则用户会误以为表被删了 -->
      <div v-if="!tables.length" class="st-empty">该库下没有表</div>
      <div v-else-if="!filteredTables.length" class="st-empty">没有匹配的表</div>

      <!--
        按「表分组」分节,默认全部折叠。
        库里没有任何分组时 sections 只返回一个匿名节 → 自动退化成原来的扁平列表。
      -->
      <!-- 匿名节的 key 是空串,补一个占位后作为 v-for 的 key(空 key 会退化成非唯一标识) -->
      <div v-for="sec in sections" :key="sec.key || '__flat'">
        <div
          v-if="sec.key"
          class="st-group"
          :class="{ open: isGroupOpen(sec.key) }"
          :title="sec.tip"
          @click="toggleGroup(sec.key)"
        >
          <el-icon class="st-caret" :class="{ open: isGroupOpen(sec.key) }"><CaretRight /></el-icon>
          <span class="st-gname">{{ sec.name }}</span>
          <span class="st-gcount">{{ sec.tables.length }}</span>
        </div>

        <!-- 匿名节(库里一个分组都没配)没有折叠概念,恒展开,也不渲染分组头 -->
        <div v-if="!sec.key || isGroupOpen(sec.key)" :class="{ 'st-tables': !!sec.key }">
          <div v-for="t in sec.tables" :key="t.name" class="st-table-block">
            <div
              class="st-table"
              :class="{ active: t.name === activeTable }"
              :title="tableTip(t)"
              @click="toggleTable(t.name)"
            >
              <!--
                .stop 阻止冒泡:外层整行的点击是「设为主表 + 收起展开」,
                点箭头只应切换字段列表,不该再触发一次选表。
              -->
              <el-icon
                class="st-caret"
                :class="{ open: expanded === t.name }"
                title="展开/收起字段列表(不会改主表)"
                @click.stop="toggleExpand(t.name)"
              ><CaretRight /></el-icon>
              <el-icon class="st-ticon"><Grid /></el-icon>
              <span class="st-tname">{{ t.name }}</span>
              <!-- 两种标记互斥:主表判定排在前,已关联只表示它是普通源表 -->
              <el-tag v-if="isActive(t.name)" size="small" effect="dark" class="st-active-tag">主表</el-tag>
              <el-tag v-else-if="isPicked(t.name)" size="small" type="success" effect="plain" class="st-active-tag">已关联</el-tag>
              <!-- 加为关联表(JOIN):多表能力入口。已选中的表不再显示,避免重复添加 -->
              <el-icon
                v-if="activeTable && !isPicked(t.name)"
                class="st-join"
                title="加为关联表(多表 JOIN)"
                @click.stop="emit('join-table', t.name)"
              ><Plus /></el-icon>
            </div>
            <!-- 表注释:表数量少,直接展示(截断 + 悬停看全文) -->
            <div v-if="t.remark" class="st-tremark" :title="t.remark">{{ t.remark }}</div>

            <div v-if="expanded === t.name" class="st-cols" v-loading="loadingCols === t.name">
              <!--
                加载中的 colCache 项同样是空数组,必须用 loadingCols 区分,
                否则每次展开都会先闪一下「取不到列」的错误文案。
              -->
              <div v-if="!colCache[t.name]?.length && loadingCols !== t.name" class="st-empty-sm">
                取不到列
              </div>
              <!-- 一次把该表所有字段加为输出字段(已加过的自动跳过) -->
              <div v-else-if="colCache[t.name]?.length" class="st-cols-head">
                <span class="st-cols-count">{{ matchedCols(t.name).length }} 个字段</span>
                <el-button
                  size="small"
                  text
                  type="primary"
                  class="st-addall"
                  :disabled="!pendingCols(t.name).length"
                  :title="pendingTitle(t.name)"
                  @click="emitAddAll(t.name)"
                >
                  {{ pendingCols(t.name).length ? `全部添加 (${pendingCols(t.name).length})` : '已全部添加' }}
                </el-button>
              </div>
              <!-- 加字段有两条路径:点击直接 emit,拖拽则把列交给右侧字段区接管 -->
              <div
                v-for="c in matchedCols(t.name)"
                :key="c.name"
                class="st-col"
                draggable="true"
                @dragstart="onDragStart($event, c, t.name)"
                @click="emit('add-field', c, t.name)"
                :title="colTip(c)"
              >
                <span class="st-cname">{{ c.name }}</span>
                <span class="st-ctype" :style="{ color: kindColor(c.kind) }">{{ c.type }}</span>
                <el-icon class="st-add"><Plus /></el-icon>
                <!-- 字段注释:与表注释一样直接展示一行小字(截断 + 悬停看全文) -->
                <span v-if="c.comment" class="st-ccmt" :title="c.comment">{{ c.comment }}</span>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { CaretRight, Grid, Plus, Refresh, Search } from '@element-plus/icons-vue'
import { workspaceApi } from '@/api'
import { dbLabel } from '@/utils/layer'
import { chTypeToKind, type ValueKind } from '@/constants/queryBuilderMeta'
import type { BuilderColumn } from '@/composables/useVisualQuery'
import type { TableGroup } from '@/types'

const props = defineProps<{
  connId: number
  database: string
  activeTable: string
  /** 已经被加入查询的源表(主表 + 关联表),用来标记状态、避免重复添加 */
  selectedTables?: string[]
  /**
   * 已经加为输出字段的列,按「表名 → 列名清单」。
   * 只用于算「还剩几个没加」与禁用「全部添加」按钮。
   */
  addedCols?: Record<string, string[]>
}>()

const emit = defineEmits<{
  (e: 'select-table', name: string): void
  /** 加为关联表(JOIN) */
  (e: 'join-table', name: string): void
  (e: 'add-field', col: BuilderColumn, table: string): void
  /** 批量添加:把某张表的这些列一次全部加为输出字段 */
  (e: 'add-fields', table: string, cols: BuilderColumn[]): void
}>()

/** 该表是否已在查询的源表清单里 */
function isPicked(name: string): boolean {
  return (props.selectedTables ?? []).includes(name)
}
/** 是否是当前主/活动表 */
function isActive(name: string): boolean {
  return name === props.activeTable
}

interface TableItem {
  name: string
  engine?: string
  remark?: string
}
/** 当前库的表清单;只有名称/引擎/注释,字段要展开时才按需拉 */
const tables = ref<TableItem[]>([])
const loadingTables = ref(false)
/** 当前展开了字段列表的表名。用单值而非集合:同时只展开一张,列区域高度才可控 */
const expanded = ref('')
/** 表名 → 列清单的懒加载缓存:命中即短路,失败刻意不写入以便下次重试 */
const colCache = ref<Record<string, BuilderColumn[]>>({})
/** 正在拉字段的表名;模板靠它区分「加载中」与「真的取不到列」 */
const loadingCols = ref('')
/** 搜索词:对表名与已缓存字段名同时生效 */
const kw = ref('')
/** 该库的表分组(来自「表分组」配置,成员表在 tables 里) */
const groups = ref<TableGroup[]>([])
/**
 * 已展开的分组 key。默认【空 = 全部折叠】——
 * 要的就是“先看到有哪几组”,而不是一上来就把几十张表铺开。
 */
const openGroups = ref<Set<string>>(new Set())

/** 关键词过滤:命中表名则保留整表;否则展开字段看是否命中 */
const filteredTables = computed(() => {
  const k = kw.value.trim().toLowerCase()
  if (!k) return tables.value
  return tables.value.filter((t) => {
    if (t.name.toLowerCase().includes(k)) return true
    return (colCache.value[t.name] || []).some((c) => c.name.toLowerCase().includes(k))
  })
})

/** 有关键词时,字段列表也按关键词过滤 */
function matchedCols(table: string): BuilderColumn[] {
  const cols = colCache.value[table] || []
  const k = kw.value.trim().toLowerCase()
  // 表名自己命中时不再下钻过滤字段 —— 否则会出现「表留下了、下面的字段却全空」的自相矛盾
  if (!k || table.toLowerCase().includes(k)) return cols
  return cols.filter((c) => c.name.toLowerCase().includes(k))
}

/**
 * 该表「当前可见、但还没加为输出字段」的列。
 *
 * ⚠️ 基于 matchedCols(可见列)而不是全量列 —— 搜索过滤后点「全部添加」,
 *    用户的预期是「把屏幕上这几列都加上」,而不是把 50 列都塞进来。
 */
function pendingCols(table: string): BuilderColumn[] {
  const added = new Set(props.addedCols?.[table] ?? [])
  return matchedCols(table).filter((c) => !added.has(c.name))
}

function pendingTitle(table: string): string {
  const n = pendingCols(table).length
  if (!n) return '该表的字段都已加为输出字段'
  return `把 ${table} 剩余的 ${n} 个字段一次全部加为输出字段`
}

/** 批量提交可见但未添加的列;空列表直接短路,省掉一次无意义的父组件更新 */
function emitAddAll(table: string) {
  const cols = pendingCols(table)
  if (!cols.length) return
  emit('add-fields', table, cols)
}

// ─────────────── 按表分组分节 ───────────────
/** 表名 → 所属分组 */
const groupOfTable = computed(() => {
  const m = new Map<string, TableGroup>()
  for (const g of groups.value) for (const t of g.tables ?? []) m.set(t, g)
  return m
})

interface TableSection {
  /** 分组 key;空串 = 匿名节(库里没有任何分组时的扁平列表) */
  key: string
  name: string
  tip: string
  tables: TableItem[]
}

/**
 * 分节后的表清单。
 *
 * - 库里配了分组  → 按分组分节,外加一节「未分组」兜底
 * - 一个分组都没有 → 只返回一个匿名节,UI 退化成原来的扁平列表
 *   (否则没建过分组的库会被强行塞进「未分组」,反而凭空多一层)
 */
const sections = computed<TableSection[]>(() => {
  const list = filteredTables.value
  if (!groups.value.length) return [{ key: '', name: '', tip: '', tables: list }]
  const byGroup = new Map<number, TableItem[]>()
  const rest: TableItem[] = []
  for (const t of list) {
    const g = groupOfTable.value.get(t.name)
    if (!g) {
      rest.push(t)
      continue
    }
    const arr = byGroup.get(g.id)
    if (arr) arr.push(t)
    else byGroup.set(g.id, [t])
  }
  const out: TableSection[] = []
  for (const g of groups.value) {
    const arr = byGroup.get(g.id)
    // ⚠️ 分组还在、但成员表全被删/改名了 → 不渲染空节(否则一片空气占着位置)
    if (arr?.length) {
      out.push({ key: `g${g.id}`, name: g.name, tip: g.remark || g.name, tables: arr })
    }
  }
  if (rest.length) out.push({ key: 'ungrouped', name: '未分组', tip: '没有归入任何分组的表', tables: rest })
  return out
})

function isGroupOpen(key: string): boolean {
  return openGroups.value.has(key)
}
function toggleGroup(key: string) {
  // ⚠️ 必须整体替换 —— 直接 mutate Set 不会触发 Vue 的响应式更新
  const next = new Set(openGroups.value)
  if (next.has(key)) next.delete(key)
  else next.add(key)
  openGroups.value = next
}

/**
 * 搜索时自动展开命中的分组。
 * ⚠️ 不展开的话:命中的表藏在折叠的分组里,用户搜了半天只看到分组名,
 *    看不出到底搜到没有。
 */
watch(kw, (v) => {
  if (!v.trim()) return
  const next = new Set(openGroups.value)
  for (const sec of sections.value) if (sec.key) next.add(sec.key)
  openGroups.value = next
})

async function reloadTables() {
  // 连接/库尚未就绪时清空而非报错:面板在初始化与切库的中间态都会渲染到这里
  if (!props.connId || !props.database) {
    tables.value = []
    groups.value = []
    return
  }
  loadingTables.value = true
  try {
    // 并行拉;分组取不到时降级为扁平列表(不该连表都看不了)
    const [tbs, gs] = await Promise.all([
      workspaceApi.tables(props.connId, props.database),
      workspaceApi.tableGroups(props.connId, props.database).catch(() => [] as TableGroup[]),
    ])
    tables.value = tbs
    groups.value = gs ?? []
  } catch {
    // 拉表失败也清空:留着上一次库的表名会让人在错的树上选表,空态提示更诚实
    tables.value = []
    groups.value = []
  } finally {
    loadingTables.value = false
  }
}

async function loadCols(name: string) {
  // ⚠️ 用 `in` 判断而不是取值判断:失败时不再写 []（空数组是 truthy,
  //    会把一次瞬时失败永久缓存住 —— 那张表再也取不到列,且刷新按钮也救不回来）
  if (name in colCache.value) return
  loadingCols.value = name
  try {
    const res = await workspaceApi.schemaSilent(props.connId, props.database, name)
    // 后端返回的列顺序不保证,按表头名定位下标;缺列时退回固定位次,保证 name/type 至少有值
    const iName = res.columns.indexOf('name')
    const iType = res.columns.indexOf('type')
    const iCmt = res.columns.indexOf('comment')
    colCache.value[name] = res.rows.map((r) => {
      const n = String(r[iName >= 0 ? iName : 0] ?? '')
      const ty = String(r[iType >= 0 ? iType : 1] ?? '')
      const cmt = iCmt >= 0 && r[iCmt] != null ? String(r[iCmt]) : ''
      return { name: n, type: ty, kind: chTypeToKind(ty), comment: cmt }
    })
  } catch {
    delete colCache.value[name] // 失败不入缓存,下次展开可重试
  } finally {
    loadingCols.value = ''
  }
}

function toggleTable(name: string) {
  // ⚠️ 点的就是当前主表 → 【只】收起/展开字段列表,不再重复「设为主表」。
  //    以前无条件 emit('select-table') → loadTable 会把 fields/where/having/orderBy
  //    全部清空(实测 3 个字段 + 1 条条件 → 0 / 0,且无确认弹窗),
  //    而用户点第二下往往只是想收起列表 —— 最容易误触、损失最大的一条路径。
  if (name !== props.activeTable) emit('select-table', name)
  toggleExpand(name)
}

/**
 * 只展开/收起该表的字段列表,【不动主表】。
 *
 * 用途:多表 JOIN 时想输出关联表的列 —— 以前展开任何表都意味着「把它设为主表」,
 * 连带把整份配置清空,于是关联表的列根本加不进来。
 */
function toggleExpand(name: string) {
  // 再点同一张表 → 收起,不发任何请求(列已缓存在 colCache 里,下次展开即刻可用)
  if (expanded.value === name) {
    expanded.value = ''
    return
  }
  expanded.value = name
  void loadCols(name)
}

function onDragStart(e: DragEvent, c: BuilderColumn, table: string) {
  e.dataTransfer?.setData('application/x-el2ck-col', JSON.stringify(c))
  // ⚠️ 必须把所属表也带上:多表时同一个列名可能属于不同的表,
  //    只传列名会让下落方默认落到主表(关联表的列就永远加不进来)。
  e.dataTransfer?.setData('application/x-el2ck-table', table)
  e.dataTransfer?.setData('text/plain', c.name)
  if (e.dataTransfer) e.dataTransfer.effectAllowed = 'copy'
}

/** 表的悬停提示:名称 + 引擎 + 注释 */
function tableTip(t: TableItem): string {
  const parts = [t.name]
  if (t.engine) parts.push(`引擎: ${t.engine}`)
  if (t.remark) parts.push(`注释: ${t.remark}`)
  return parts.join('\n')
}

/** 列的悬停提示:名称 : 类型 + 注释 */
function colTip(c: BuilderColumn): string {
  const parts = [`${c.name} : ${c.type}`]
  if (c.comment) parts.push(`注释: ${c.comment}`)
  parts.push('点击或拖拽到右侧添加为输出字段')
  return parts.join('\n')
}

/** 字段类型 → 颜色:纯视觉聚类,让人扫一眼就能看出该表的类型分布 */
const KIND_COLORS: Record<ValueKind, string> = {
  number: '#34d399',
  string: '#60a5fa',
  date: '#fb923c',
  bool: '#c084fc',
  other: '#94a3b8',
}
function kindColor(k: ValueKind) {
  return KIND_COLORS[k]
}

onMounted(reloadTables)
// 换连接/换库:同名表的结构可能完全不同,列缓存、展开态、分组展开态必须整体作废重来
watch(() => [props.connId, props.database], () => {
  expanded.value = ''
  colCache.value = {}
  openGroups.value = new Set() // 换库 → 回到全折叠
  void reloadTables()
})

/** 主表变化时确保其列已加载(供外部刷新) */
watch(
  () => props.activeTable,
  (t) => {
    if (t) void loadCols(t)
  },
)

/** 外部结构变更(建表/加列)后由父组件调用,强制丢弃列缓存并重拉表清单 */
defineExpose({ invalidateCache: () => { colCache.value = {}; void reloadTables() } })
</script>

<style scoped>
.st-wrap {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}
.st-head {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 2px 2px 6px;
}
.st-title {
  font-size: 14px;
  font-weight: 600;
}
.st-db {
  flex: 1;
  font-size: 12px;
  color: var(--el-text-color-placeholder);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.st-search {
  margin-bottom: 6px;
}
.st-body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding-top: 2px;
}
.st-empty,
.st-empty-sm {
  font-size: 13px;
  color: var(--el-text-color-placeholder);
  padding: 8px 6px;
}

/* ── 表分组 ─────────────────────────────────────────────
   分组头整行可点(不只是箭头),折叠状一眼可见;
   分组内的表整体缩进半格,体现层级。 */
.st-group {
  display: flex;
  align-items: center;
  gap: 5px;
  padding: 5px 6px;
  margin: 2px 0;
  border-radius: 5px;
  cursor: pointer;
  font-size: 13.5px;
  font-weight: 600;
  color: var(--el-text-color-regular);
  background: var(--el-fill-color-light);
  user-select: none;
}
.st-group:hover {
  background: var(--el-fill-color);
}
.st-group.open {
  color: var(--el-text-color-primary);
}
.st-gname {
  flex: 1;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.st-gcount {
  flex: none;
  font-size: 12px;
  font-weight: 400;
  color: var(--el-text-color-placeholder);
}
/* 分组内的表:整体缩进,和分组头形成层级 */
.st-tables {
  padding-left: 11px;
}
.st-table-block {
  margin-bottom: 2px;
}
.st-table {
  display: flex;
  align-items: center;
  gap: 5px;
  padding: 5px 6px;
  border-radius: 5px;
  cursor: pointer;
  font-size: 14px;
}
.st-table:hover {
  background: var(--el-fill-color);
}
.st-table.active {
  background: rgba(234, 179, 8, 0.12);
  font-weight: 600;
}
.st-caret {
  font-size: 12px;
  color: var(--el-text-color-placeholder);
  transition: transform 0.15s ease;
}
.st-caret.open {
  transform: rotate(90deg);
}
.st-ticon {
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
.st-tname {
  flex: 1;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
/* 表注释:缩进对齐表名,小字灰色,单行截断 */
.st-tremark {
  margin: 0 0 3px 38px;
  font-size: 11px;
  line-height: 1.5;
  color: var(--el-text-color-placeholder);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  cursor: default;
}

/* 「加为关联表」按钮:平时淡,悬停表行才明显,避免抢视觉 */
.st-join {
  margin-left: auto;
  font-size: 14px;
  color: var(--el-text-color-placeholder);
  cursor: pointer;
  flex: none;
}
.st-join:hover {
  color: var(--brand-strong);
}
.st-active-tag {
  transform: scale(0.8);
  transform-origin: right center;
}
.st-cols {
  padding-left: 16px;
}
/* 展开后的列清单顶部:字段数 + 「全部添加」 */
.st-cols-head {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 0 4px 2px 6px;
}
.st-cols-count {
  flex: 1;
  font-size: 12px;
  color: var(--el-text-color-placeholder);
}
.st-addall {
  flex: none;
  height: 20px;
  padding: 0 4px;
  font-size: 12px;
}
.st-col {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 3px 6px;
  border-radius: 4px;
  cursor: grab;
  font-size: 13px;
  flex-wrap: wrap;
}
/* 字段注释:独占一行小字 */
.st-ccmt {
  flex: 0 0 100%;
  padding-left: 2px;
  font-size: 11px;
  line-height: 1.5;
  color: var(--el-text-color-placeholder);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  cursor: default;
}
.st-col:hover {
  background: var(--el-fill-color);
}
.st-col:hover .st-add {
  opacity: 1;
}
.st-cname {
  flex: 1;
  font-family: var(--font-mono, monospace);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.st-ctype {
  font-size: 11px;
  opacity: 0.85;
  white-space: nowrap;
}
.st-add {
  opacity: 0;
  font-size: 13px;
  color: var(--brand-strong, #b45309);
  transition: opacity 0.15s ease;
}
</style>
