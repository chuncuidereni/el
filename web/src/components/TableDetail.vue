<!--
  TableDetail.vue

  职责：表详情组件：展示字段、索引、DDL 与相关操作。
  边界：按连接、库、表加载字段、DDL 和索引，并发出编辑、删除、刷新等表级命令；详情加载与父组件导航解耦。
-->

<template>
  <DetailPageTemplate class="table-detail">
    <template #header>
      <!-- 只读:共享表 / 工作流产物。刷新保留,写操作与结构编辑收起。 -->
      <div class="toolbar">
        <el-button size="small" :icon="Refresh" @click="loadAll">刷新</el-button>
        <template v-if="!readonly">
          <el-divider direction="vertical" />
          <el-button size="small" type="warning" plain @click="onTruncate">清空数据</el-button>
          <el-button v-if="(canDrop ?? canManage) !== false" size="small" type="danger" plain @click="onDrop">删除表</el-button>
        </template>
      </div>
    </template>

    <el-tabs v-model="tab">
      <el-tab-pane label="数据" name="data">
        <DataEditor :key="dataKey" :conn-id="connId" :db="db" :table="table" :readonly="readonly" />
      </el-tab-pane>

      <el-tab-pane label="表结构" name="schema">
        <template v-if="!editing">
          <div class="schema-toolbar">
            <el-button v-if="!readonly" size="small" type="primary" plain :icon="EditPen" :loading="loadingEdit" @click="startEdit">编辑结构</el-button>
            <span class="schema-hint">修改字段名 / 长度 / 备注，新增或删除字段，修改表名 / 表注释</span>
          </div>
          <ResultGrid center-header fill-height :result="schemaResult" :show-type="false" :cell-tags="keyTags" />
        </template>
        <template v-else>
          <div class="alter-box">
            <div class="alter-row">
              <span class="alter-label">表名</span>
              <el-input v-model="editTableName" :disabled="canManage === false" size="small" style="width: 240px" />
              <el-button size="small" type="primary" :loading="altering" @click="previewAlter">保存修改</el-button>
              <el-button size="small" @click="cancelEdit">取消</el-button>
            </div>
            <div class="alter-row">
              <span class="alter-label">表注释</span>
              <el-input v-model="editTableComment" type="textarea" :rows="2" :disabled="canManage === false"
                placeholder="填写表说明，留空可清除已有表注释" />
            </div>
            <el-table
              :data="editCols"
              size="small"
              border
              height="100%"
              class="alter-table"
              :header-cell-style="{ textAlign: 'center' }"
            >
              <el-table-column label="字段名" min-width="130">
                <template #default="{ row }">
                  <el-input v-model="row.name" size="small" placeholder="字段名" />
                </template>
              </el-table-column>
              <el-table-column label="类型(仅可调长度)" min-width="170">
                <template #default="{ row }">
                  <el-input v-model="row.type" size="small" :title="row.origType" placeholder="如 String / FixedString(32)" />
                </template>
              </el-table-column>
              <el-table-column label="可空" width="60" align="center">
                <template #default="{ row }">
                  <el-switch v-model="row.nullable" size="small" />
                </template>
              </el-table-column>
              <el-table-column label="默认值" min-width="130">
                <template #default="{ row }">
                  <el-input v-model="row.defaultValue" size="small" placeholder="如 0 / now()" />
                </template>
              </el-table-column>
              <el-table-column label="列注释" min-width="160">
                <template #default="{ row }">
                  <el-input v-model="row.comment" size="small" placeholder="可选" />
                </template>
              </el-table-column>
              <el-table-column width="52" align="center">
                <template #default="{ $index }">
                  <el-button link type="danger" :icon="Delete" @click="removeEditCol($index)" />
                </template>
              </el-table-column>
            </el-table>
            <el-button size="small" :icon="Plus" class="add-col" @click="addEditCol">添加字段</el-button>
          </div>
        </template>
      </el-tab-pane>

      <el-tab-pane label="建表语句 (DDL)" name="ddl">
        <!--
          DDL 页签自身也使用纵向 Flex：编辑器吃掉全部剩余高度，复制按钮固定在下方。
          这样编辑器始终铺到工作区底部，长 DDL 只在编辑器内部滚动。
        -->
        <div class="ddl-pane">
          <div class="ddl-box">
            <!-- 只读展示:单向绑 :model-value,编辑器在 readonly 下不会回抛 update:modelValue -->
            <CodeMirrorEditor :model-value="ddlText" readonly />
          </div>
          <el-button size="small" type="primary" plain class="copy-btn" @click="copyDdl">复制 DDL</el-button>
        </div>
      </el-tab-pane>
    </el-tabs>
  </DetailPageTemplate>
</template>

<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { confirmDestructive } from '@/utils/confirm'
import { escapeSqlString, isSafeColumnType, isSafeDefaultExpr } from '@/utils/ddlGuard'
import { Delete, EditPen, Plus, Refresh } from '@element-plus/icons-vue'
import { workspaceApi } from '@/api'
import DetailPageTemplate from '@/components/DetailPageTemplate.vue'
import type { ColumnAlter, QueryResult, TableAlterRequest } from '@/types'
import ResultGrid from '@/components/ResultGrid.vue'
import DataEditor from '@/components/DataEditor.vue'
import CodeMirrorEditor from '@/components/CodeMirrorEditor.vue'

const props = withDefaults(defineProps<{
  connId: number
  db: string
  table: string
  /** 只读模式:隐藏 刷新/清空数据/删除表 + 编辑结构,并把只读传给 DataEditor */
  readonly?: boolean
  /** 平台资产管理能力(如改名)。 */
  canManage?: boolean
  /** 删除能力独立判断,支持管理员删除未登记的 CK 表。 */
  canDrop?: boolean
}>(), { canManage: undefined, canDrop: undefined })
const emit = defineEmits<{ (e: 'deleted'): void; (e: 'renamed', newName: string): void; (e: 'updated'): void }>()

const tab = ref('data')
const dataKey = ref(0)
const schemaResult = ref<QueryResult | null>(null)
const ddlText = ref('')
/** 字段名 → 键标签(主键/分区/排序) */
const keyTags = ref<Record<string, string[]>>({})

// ---- 编辑模式 ----
interface EditCol {
  name: string
  type: string
  nullable: boolean
  defaultValue: string
  comment: string
  /** 原始字段名(定位旧列) */
  origName: string
  /** 原始类型(判断长度是否变化) */
  origType: string
  /** 原始默认值 */
  origDefault: string
  /** 原始备注 */
  origComment: string
  /** 是否新增行 */
  isNew: boolean
}
const editing = ref(false)
const loadingEdit = ref(false)
const editTableName = ref('')
const editTableComment = ref('')
const originalTableComment = ref('')
const editCols = ref<EditCol[]>([])
const altering = ref(false)

// DESCRIBE TABLE 返回列的中文表头
const SCHEMA_COL_LABELS: Record<string, string> = {
  name: '字段名',
  type: '类型',
  default_type: '默认值类型',
  default_expression: '默认表达式',
  comment: '注释',
  codec_expression: '压缩编码',
  ttl_expression: 'TTL 表达式',
}

async function loadSchema(tableName = props.table) {
  try {
    const res = await workspaceApi.schema(props.connId, props.db, tableName)
    // 后端按 DESCRIBE 原样返回英文列名;映射表只登记已知列,未知列保留原名,避免表头出现空白
    res.columns = res.columns.map((c) => SCHEMA_COL_LABELS[c] ?? c)
    schemaResult.value = res
  } catch {
    /* 拦截器已提示 */
  }
}

async function loadDdl() {
  try {
    const res = await workspaceApi.ddl(props.connId, props.db, props.table)
    // SHOW CREATE TABLE 的结果是"一行一列":内容在 [0][0]。表刚被删或无权限时行可能为空,统一收敛成空串交给编辑器
    const first = res.rows?.[0]?.[0]
    ddlText.value = first == null ? '' : String(first)
  } catch {
    /* 拦截器已提示 */
  }
}

// 加载主键/分区键/排序键,构建 字段名→标签 映射
async function loadKeys(tableName = props.table) {
  try {
    const k = await workspaceApi.keys(props.connId, props.db, tableName)
    const tags: Record<string, string[]> = {}
    // 三个键各自追加而不是赋值:同一列可能同时是主键和排序键,标签需要并列显示
    k.primaryKey.forEach((c) => {
      tags[c] = [...(tags[c] ?? []), '主键']
    })
    k.partitionKey.forEach((c) => {
      tags[c] = [...(tags[c] ?? []), '分区']
    })
    k.sortingKey.forEach((c) => {
      tags[c] = [...(tags[c] ?? []), '排序']
    })
    keyTags.value = tags
  } catch {
    /* 拦截器已提示 */
  }
}

// 「刷新」只重载当前页签:三个页签的数据是按需拉取的,没必要为一次刷新把三份请求都打出去
function loadAll() {
  if (tab.value === 'schema') loadSchema()
  else if (tab.value === 'ddl') loadDdl()
  else if (tab.value === 'data') dataKey.value++ // 数据页签:强制数据网格重载
}

// 懒加载:仅当对应缓存为空时请求,来回切页签不会重复打接口;数据页签由 DataEditor 自己管生命周期,不在这里拉
watch(tab, () => {
  if (tab.value === 'schema' && !schemaResult.value) loadSchema()
  else if (tab.value === 'ddl' && !ddlText.value) loadDdl()
})

// ---- 编辑结构 ----
async function startEdit() {
  // 编辑表单直接复用已拉取的结构结果,不再请求一次;结构还没加载过时没有可编辑的基线,直接放弃
  if (!schemaResult.value || loadingEdit.value) return
  // 每次进入编辑都读取单表当前注释；读取失败时中止编辑，避免误将原注释清空。
  loadingEdit.value = true
  try {
    const result = await workspaceApi.tableComment(props.connId, props.db, props.table)
    originalTableComment.value = result.comment
    editTableComment.value = result.comment
  } catch {
    return
  } finally {
    loadingEdit.value = false
  }
  // 从 schemaResult 的 rows 重建编辑行:name=0, type=1, default_type=2, default_expression=3, comment=4
  editCols.value = schemaResult.value.rows.map((r) => ({
    name: String(r[0] ?? ''),
    type: String(r[1] ?? ''),
    // 可空信息被 DESCRIBE 编码在类型字符串里(Nullable(String)),拆出来才能用开关表示
    nullable: String(r[1] ?? '').toUpperCase().startsWith('NULLABLE('),
    // 老版本 CK 的 DESCRIBE 列数可能不足 5 列,越界取值会让输入框显示出字面量 undefined
    defaultValue: r.length > 3 ? String(r[3] ?? '') : '',
    comment: r.length > 4 ? String(r[4] ?? '') : '',
    origName: String(r[0] ?? ''),
    origType: String(r[1] ?? ''),
    origDefault: r.length > 3 ? String(r[3] ?? '') : '',
    origComment: r.length > 4 ? String(r[4] ?? '') : '',
    isNew: false,
  }))
  editTableName.value = props.table
  editing.value = true
}

function cancelEdit() {
  editing.value = false
}

function addEditCol() {
  // isNew 是新增行与已有行的唯一区分:buildAlters 只对它走 add 分支,不参与改名/改类型比对
  editCols.value.push({
    name: '',
    type: 'String',
    nullable: false,
    defaultValue: '',
    comment: '',
    origName: '',
    origType: '',
    origDefault: '',
    origComment: '',
    isNew: true,
  })
}

function removeEditCol(index: number) {
  editCols.value.splice(index, 1)
}

/** 规范化类型:去掉外层 Nullable(),返回 (内层类型, nullable) */
function splitNullable(type: string): { inner: string; nullable: boolean } {
  const t = type.trim()
  if (/^NULLABLE\(.*\)$/i.test(t)) {
    // 9 = 'Nullable('.length;剥掉最外层包裹,只留 CK 真正认的内层类型
    return { inner: t.slice(9, -1).trim(), nullable: true }
  }
  return { inner: t, nullable: false }
}

/** 提取类型大类(去掉参数),用于判断"类型是否被改动" */
function typeKind(t: string): string {
  // 去掉括号参数后大写比较,使 String(10) 与 String 归为同一大类(改长度不算换类型)
  return t.replace(/\(.*\)$/, '').trim().toUpperCase()
}

/** 对比前后差异,生成 ColumnAlter 列表 */
function buildAlters(): ColumnAlter[] {
  const alters: ColumnAlter[] = []
  const existing = new Map<string, EditCol>()

  for (const col of editCols.value) {
    const name = col.name.trim()
    // 用户新增后没填名字的空行:直接跳过,既不生成语句也不参与重名校验
    if (!name) continue
    if (col.isNew) {
      // 新增列
      const { inner, nullable } = splitNullable(col.type)
      alters.push({
        action: 'add',
        oldName: '',
        newName: name,
        type: inner,
        nullable,
        defaultValue: col.defaultValue.trim(),
        comment: col.comment.trim(),
      })
      continue
    }
    existing.set(col.origName, col)

    // 改列名
    // 改名单独成句:RENAME COLUMN 不接受同句的其它动作,且改名后同批 MODIFY 需用新名定位
    if (name !== col.origName) {
      alters.push({
        action: 'rename',
        oldName: col.origName,
        newName: name,
        type: '',
        nullable: false,
        defaultValue: '',
        comment: '',
      })
    }
    // 改类型参数(长度)/默认值/备注 — 类型大类不变才允许
    const { inner: newInner, nullable } = splitNullable(col.type)
    const { inner: origInner } = splitNullable(col.origType)
    // 大类不同(如 String→Int32)时 CK 通常直接拒绝,这里不生成 modify,改由 kindChangedNames 在预览处告知用户"改动被忽略"
    if (
      typeKind(newInner) === typeKind(origInner) &&
      (newInner !== origInner || nullable !== splitNullable(col.origType).nullable || col.defaultValue.trim() !== (col.origDefault ?? '') || col.comment.trim() !== (col.origComment ?? ''))
    ) {
      alters.push({
        action: 'modify',
        oldName: col.origName,
        newName: name,
        type: newInner,
        nullable,
        defaultValue: col.defaultValue.trim(),
        comment: col.comment.trim(),
      })
    }
  }

  // 删除的列(原表有,编辑列表没有)
  // 用「原始字段名」而不是当前名做基准:改过名的列新名不在原表里,按新名比对会把它误判成已删除
  const kept = new Set(editCols.value.filter((c) => !c.isNew).map((c) => c.origName))
  for (const col of schemaResult.value?.rows ?? []) {
    const origName = String(col[0] ?? '')
    if (origName && !kept.has(origName)) {
      alters.push({
        action: 'drop',
        oldName: origName,
        newName: '',
        type: '',
        nullable: false,
        defaultValue: '',
        comment: '',
      })
    }
  }
  return alters
}

/** 生成 ALTER 语句预览文本 */
function alterPreview(alters: ColumnAlter[], newTableName: string): string {
  const lines: string[] = []
  // RENAME TABLE 必须排在最前:它一执行,后续 ALTER 就只能认新表名(下面统一用 newTableName || props.table)
  if (newTableName && newTableName !== props.table) {
    lines.push(`RENAME TABLE \`${props.db}\`.\`${props.table}\` TO \`${props.db}\`.\`${newTableName}\``)
  }
  for (const a of alters) {
    switch (a.action) {
      case 'rename':
        lines.push(`ALTER TABLE \`${props.db}\`.\`${newTableName || props.table}\` RENAME COLUMN \`${a.oldName}\` TO \`${a.newName}\``)
        break
      case 'modify': {
        // 可空在前端是独立开关,拼 SQL 时再包回 Nullable(...);已被包过就把开关还原回去,避免出现 Nullable(Nullable(…))
        let t = a.type
        if (a.nullable && !t.toUpperCase().startsWith('NULLABLE(')) t = `Nullable(${t})`
        let s = `ALTER TABLE \`${props.db}\`.\`${newTableName || props.table}\` MODIFY COLUMN \`${a.newName || a.oldName}\` ${t}`
        if (a.defaultValue) s += ` DEFAULT ${a.defaultValue}`
        if (a.comment) s += ` COMMENT '${escapeSqlString(a.comment)}'`
        lines.push(s)
        break
      }
      case 'add': {
        let t = a.type
        if (a.nullable && !t.toUpperCase().startsWith('NULLABLE(')) t = `Nullable(${t})`
        let s = `ALTER TABLE \`${props.db}\`.\`${newTableName || props.table}\` ADD COLUMN \`${a.newName}\` ${t}`
        if (a.defaultValue) s += ` DEFAULT ${a.defaultValue}`
        if (a.comment) s += ` COMMENT '${escapeSqlString(a.comment)}'`
        lines.push(s)
        break
      }
      case 'drop':
        lines.push(`ALTER TABLE \`${props.db}\`.\`${newTableName || props.table}\` DROP COLUMN \`${a.oldName}\``)
        break
    }
  }
  if (editTableComment.value !== originalTableComment.value) {
    lines.push(`ALTER TABLE \`${props.db}\`.\`${newTableName || props.table}\` MODIFY COMMENT '${escapeSqlString(editTableComment.value)}'`)
  }
  return lines.join('\n')
}

/** 校验编辑结果,返回错误消息;通过返回 null */
function validateAlters(alters: ColumnAlter[], newTableName: string): string | null {
  // 表名/字段名无法参数化,只能拼进 SQL,所以在本地先按标识符白名单挡一道(与后端同规则)
  if (newTableName && !/^[A-Za-z_][A-Za-z0-9_]*$/.test(newTableName)) return '表名仅允许字母/数字/下划线,且不能以数字开头'
  for (const a of alters) {
    if (a.action === 'rename' && !/^[A-Za-z_][A-Za-z0-9_]*$/.test(a.newName)) return `新字段名非法:${a.newName}`
    if (a.action === 'add' && !/^[A-Za-z_][A-Za-z0-9_]*$/.test(a.newName)) return `新字段名非法:${a.newName}`
    /*
      ⚠️ 类型校验以前只挂在 modify 上 → 新增列时用户输入的类型字符串原样拼进 ALTER。
      评审 security·high。后来我改成"放宽字符集 + 挡注释符",仍不完整:字符集里有逗号,
      而 ClickHouse 允许一条 ALTER 里逗号分隔多个动作 → `Int32, DROP COLUMN y` 照样通过
      (评审 r4 再次打回,已核实)。现在统一走 utils/ddlGuard(与服务端 ddl_guard.go 同规则)。
    */
    if (a.type && !isSafeColumnType(a.type)) return `非法类型:${a.type}`
    // defaultValue 以前完全没校验,而它同样被拼进 ALTER(`DEFAULT ${...}`)
    if (a.defaultValue && !isSafeDefaultExpr(a.defaultValue)) return `非法默认值:${a.defaultValue}`
  }
  // 检查字段名重复
  // 重名时后端逐条执行,第一条成功、第二条才因列已存在报 502;提前拦下才能给出"哪个字段重复"的可读提示
  const names = new Set<string>()
  for (const col of editCols.value) {
    const n = col.name.trim()
    if (!n) continue
    if (names.has(n)) return `字段名重复:${n}`
    names.add(n)
  }
  return null
}

/** 存在“基础类型大类”被改动的已有字段(buildAlters 不会为其生成 modify,CK 大多禁止)。
 *  返回这些字段名,用于在预览时明确提示该部分未生效,避免“没有需要修改的内容”的误导。 */
function kindChangedNames(): string[] {
  const out: string[] = []
  for (const col of editCols.value) {
    const n = col.name.trim()
    if (!n || col.isNew || !col.origType) continue
    const cur = splitNullable(col.type).inner
    const orig = splitNullable(col.origType).inner
    if (cur && typeKind(cur) !== typeKind(orig)) out.push(n)
  }
  return out
}

async function previewAlter() {
  const alters = buildAlters()
  const newTableName = editTableName.value.trim()
  const renamed = !!newTableName && newTableName !== props.table
  const commentChanged = editTableComment.value !== originalTableComment.value
  const err = validateAlters(alters, newTableName)
  if (err) {
    ElMessage.warning(err)
    return
  }
  // 基础类型大类变更无法应用:若除此之外没有任何改动,直接提示并不继续(避免误报“没有需要修改的内容”);
  // 若还有其它改动,则继续并顺带提示该字段的类型改动会被忽略。
  const kindChanged = kindChangedNames()
  if (kindChanged.length && !renamed && !commentChanged && alters.length === 0) {
    ElMessage.warning(`字段「${kindChanged.join('、')}」的基础类型不可变更,仅可调整长度/可空等参数;请还原类型后重试`)
    return
  }
  if (!renamed && !commentChanged && alters.length === 0) {
    // 改名与列变更都没有才走到这里;只要有一项非空就继续,避免弹出一个空确认框
    ElMessage.info('没有需要修改的内容')
    return
  }
  if (kindChanged.length) {
    ElMessage.warning(`字段「${kindChanged.join('、')}」的基础类型改动不会生效(该库仅允许调整长度/可空),已忽略类型变更`)
  }
  const preview = alterPreview(alters, newTableName)
  try {
    // 预览文本是拼出来的 SQL,已手动转义尖括号,所以这里可以放心用 dangerouslyUseHTMLString 走 <pre> 排版
    await ElMessageBox.confirm(
      `<pre class="alter-preview">${preview.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')}</pre>
      <p class="alter-warn">⚠ 删除字段不可恢复;改类型长度会触发后台数据重写(不锁表,大表可能耗时)。确认执行?</p>`,
      '确认修改表结构',
      { dangerouslyUseHTMLString: true, confirmButtonText: '执行', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return // 用户取消
  }

  const req: TableAlterRequest = { newTableName, columns: alters }
  if (commentChanged) req.tableComment = editTableComment.value
  altering.value = true
  try {
    await workspaceApi.alterTable(props.connId, props.db, props.table, req)
    ElMessage.success('表结构已修改')
    editing.value = false
    // 刷新结构 + 通知父组件表名可能变了。
    // 注意:若改了表名,此时 props.table 还是旧名,必须用新名刷新,
    // 否则 loadSchema/loadKeys 会因旧表不存在报 code 60。
    const effectiveTable = newTableName || props.table
    schemaResult.value = null
    await loadSchema(effectiveTable)
    await loadKeys(effectiveTable)
    // 结构已变:清掉旧的 DDL 预览缓存;未改名时重载数据页(列集已变),改名交给父组件按新表重挂
    ddlText.value = ''
    if (!renamed) dataKey.value++
    if (renamed) {
      emit('renamed', newTableName)
    } else {
      emit('updated')
    }
  } catch {
    /* 拦截器已提示 */
  } finally {
    altering.value = false
  }
}

async function onTruncate() {
  // 确认框要求用户手动输入表名才能通过,避免一键误清空(不可恢复)
  if (!(await confirmDestructive('清空表数据', props.table, '清空'))) return
  await workspaceApi.truncateTable(props.connId, props.db, props.table)
  ElMessage.success('已清空')
  dataKey.value++ // 强制数据网格重新加载
}

async function onDrop() {
  if (!(await confirmDestructive('删除表', props.table, '删除'))) return
  await workspaceApi.dropTable(props.connId, props.db, props.table)
  ElMessage.success('已删除')
  emit('deleted')
}

async function copyDdl() {
  try {
    // clipboard API 只在 https / localhost 下可用,其余场景会直接抛错,降级为提示手动复制
    await navigator.clipboard.writeText(ddlText.value)
    ElMessage.success('已复制到剪贴板')
  } catch {
    ElMessage.warning('复制失败,请手动选择复制')
  }
}

// 键标签只依赖表名,进表即预取,免得切到结构页签时标签要等一次请求才补上
onMounted(loadKeys)
</script>

<style scoped>
.table-detail {
  height: 100%;
  display: flex;
  flex-direction: column;
}
.table-detail :deep(.el-tabs) {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}
.table-detail :deep(.el-tabs__header) {
  flex: none;
}
.table-detail :deep(.el-tabs__content) {
  flex: 1;
  min-height: 0;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}
.table-detail :deep(.el-tab-pane) {
  height: 100%;
  display: flex;
  flex-direction: column;
}
.toolbar {
  display: flex;
  align-items: center;
  margin-bottom: 8px;
}
.schema-toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 8px;
}
.schema-hint {
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

.alter-box {
  display: flex;
  flex-direction: column;
  gap: 8px;
  flex: 1;
  min-height: 0;
}
.alter-row {
  display: flex;
  align-items: center;
  gap: 10px;
}
.alter-label {
  font-size: 13px;
  color: var(--el-text-color-secondary);
  flex: none;
}
.alter-table {
  /* 占满“添加字段”按钮以上的剩余高度；字段较多时由 Element Plus 表格主体内部滚动。 */
  flex: 1;
  min-height: 0;
}

/* 统一表头单元格对齐，避免短标题因列宽不同而出现左右错位。 */
.alter-table :deep(.el-table__header-wrapper th) {
  text-align: center;
}

.alter-table :deep(.el-table__header-wrapper th .cell) {
  width: 100%;
  text-align: center;
}
.add-col {
  flex: none;
  align-self: flex-start;
}
.alter-preview {
  font-family: var(--font-mono);
  font-size: 13px;
  line-height: 1.6;
  background: #0d1117;
  color: #c9d1d9;
  padding: 10px 12px;
  border-radius: 6px;
  max-height: 240px;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-all;
  margin: 0 0 8px;
}
.alter-warn {
  font-size: 13px;
  color: var(--el-color-warning);
  margin: 0;
}
.ddl-pane {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}
.ddl-box {
  flex: 1;
  min-height: 0;
  margin-bottom: 0;
}
.copy-btn {
  flex: none;
  align-self: flex-start;
  margin-top: 8px;
}
</style>
