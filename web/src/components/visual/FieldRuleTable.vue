<!--
  FieldRuleTable.vue

  职责：可视化字段规则表：编辑来源字段、别名、聚合与处理流水线。
  边界：编辑输出字段、聚合与处理链；collapseSignal 触发面板收起，字段类型和注释由父级提供的查询函数决定。
-->

<template>
  <div class="ft-wrap">
    <!--
      按表分组直接勾选输出列(单表也要显示 —— 以前只在多表时渲染,
      导致单表探索的「输出字段」根本没有勾选框,只能看到空状态按钮)。
    -->
    <div v-if="sources?.length" class="ft-groups">
      <!-- 折叠头:默认收起(常驻展开会占掉半个屏幕)。收起时也把关键信息露出来 -->
      <div class="ft-groups-head" @click="groupsOpen = !groupsOpen">
        <el-icon class="ft-groups-caret">
          <ArrowDown v-if="groupsOpen" />
          <ArrowRight v-else />
        </el-icon>
        <span class="ft-groups-title">字段选择</span>
        <span class="muted">{{ sources?.length ?? 0 }} 张表 · 已选 {{ fields.length }} 列</span>
        <!-- 收起时也要能看到「有表列没读到」,不然会以为没得选 -->
        <!-- 三态互斥且失败优先:一张表已确定读挂了就该报警,不必等其它表转完再显示 -->
        <el-tag v-if="errCount" size="small" type="danger" effect="plain">
          {{ errCount }} 张表列读取失败
        </el-tag>
        <el-icon v-else-if="anyLoading" class="is-loading"><Loading /></el-icon>
        <span class="ft-groups-toggle">{{ groupsOpen ? '收起' : '展开' }}</span>
      </div>

      <!-- 用 v-show 不用 v-if:收起只是隐藏,勾选状态与滚动位置都还在,展开时不重新渲染、不闪 -->
      <div v-show="groupsOpen" class="ft-groups-body">
        <div class="ft-hint">
          {{ multiTable ? '' : '勾选要输出的字段;' }}
          下方表格可继续配聚合 / 处理链 / 别名。
        </div>
        <div class="ft-group-toolbar">
          <el-input v-model="groupKw" size="small" clearable placeholder="搜索列名 / 注释" class="ft-group-kw">
            <template #prefix><el-icon><Search /></el-icon></template>
          </el-input>
          <span class="muted">已选 {{ fields.length }} 列</span>
        </div>
        <div class="ft-group-scroll">
          <!-- si === 0 恒为主表(与 useVisualQuery 的约定一致),其余是关联表;
               主表单独标色,因为下面的「来源表」下拉很容易把主表也换掉,需要一眼可辨 -->
          <div v-for="(s, si) in sources ?? []" :key="si" class="ft-group">
            <div class="ft-group-head">
              <el-tag size="small" :type="si === 0 ? 'warning' : 'info'" effect="plain">
                {{ si === 0 ? '主表 · ' : '' }}{{ s.table }}
              </el-tag>
              <!--
                列没读到时要【说清楚并能重试】:
                以前只显示「已选 1 列 / 可选 0 列」,用户既没法选字段也不知道为什么。
              -->
              <!-- 读列三态:加载中 / 失败(原因 + 重试入口) / 正常(带全选、全不选)。
                   失败态必须给重试按钮,否则用户既选不了字段也不知道为什么 -->
              <template v-if="s.loading">
                <span class="muted">加载列中…</span>
              </template>
              <template v-else-if="s.colErr">
                <el-tag size="small" type="danger" effect="plain" :title="s.colErr">
                  {{ s.colErr }}
                </el-tag>
                <el-button size="small" link type="primary" @click="emit('retry-cols', si)">重试</el-button>
              </template>
              <template v-else>
                <!-- 两个按钮的禁用条件不对称:全选看「有没有列」,全不选看「有没有已选」,
                     否则会出现点了毫无反应、又看不出原因的按钮 -->
                <span class="muted">{{ checkedCountOf(si) }}/{{ s.columns.length }}</span>
                <el-button size="small" link type="primary" :disabled="!s.columns.length" @click="emit('toggle-all', si, true)">全选</el-button>
                <el-button size="small" link type="danger" :disabled="!checkedCountOf(si)" @click="emit('toggle-all', si, false)">全不选</el-button>
              </template>
            </div>
            <div class="ft-group-grid">
              <!-- 受控勾选(model-value + emit):勾选状态的唯一来源是 fields 数组,
                   不在这里存本地副本,免得出现「勾了但没进字段表」的两份状态 -->
              <!-- key 必须带上表下标:不同表可以有同名列,只用列名会撞 key 导致勾选串行 -->
              <el-checkbox
                v-for="c in filteredCols(si, s.columns)"
                :key="si + '.' + c.name"
                :model-value="isOn(si, c.name)"
                @update:model-value="(v: boolean) => emit('toggle-col', si, c.name, v)"
              >
                <span class="mono">{{ c.name }}</span>
                <span class="ft-opt-type">{{ c.type }}</span>
                <span v-if="c.comment" class="ft-opt-cmt" :title="c.comment">{{ c.comment }}</span>
              </el-checkbox>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- 一张表都没选:给出明确指引,不然这个页签看起来像坏的 -->
    <div v-else class="ft-no-table">
      <el-icon><Warning /></el-icon>
      <span>还没有选择数据表,点上方「<b>探索向导</b>」选表后即可在这里勾选输出字段。</span>
    </div>

    <!-- ⚠️ 空态留在折叠【外面】:否则收起后就找不到「添加第一个字段」了 -->
    <div v-if="!fields.length" class="ft-empty" @click="emit('add-first')">
      <el-icon><Plus /></el-icon>
      <span>添加第一个字段</span>
    </div>

    <!--
      与「字段选择」用【同一个卡片容器】:两条折叠头才会左右对齐、底色/边框一致。
      (之前我把这条放在卡片外面,结果缩进差 12px、还多了一条虚线 —— 看着就是两个东西。)
    -->
    <div v-if="fields.length" class="ft-groups">
      <div class="ft-groups-head" @click="rulesOpen = !rulesOpen">
        <el-icon class="ft-groups-caret">
          <ArrowDown v-if="rulesOpen" />
          <ArrowRight v-else />
        </el-icon>
        <span class="ft-groups-title">字段配置</span>
        <span class="muted">聚合 · 处理链 · 别名 · 输出</span>
        <span class="ft-groups-toggle">{{ rulesOpen ? '收起' : '展开' }}</span>
      </div>

      <div v-show="rulesOpen" class="ft-rules-body">
      <!-- 标题/编号已在选项卡上,这里只留操作提示 -->
      <div class="ft-head">
        <span class="ft-hint">拖动行首 ≡ 可调整顺序;也可在上方分组区直接勾选</span>
      </div>

      <table class="ft-table">
      <thead>
        <tr>
          <th class="ft-c-idx"></th>
          <th v-if="multiTable" class="ft-c-src">来源表</th>
          <th class="ft-c-col">字段</th>
          <th class="ft-c-comment">注释</th>
          <th class="ft-c-agg">聚合</th>
          <th class="ft-c-pipe">处理链</th>
          <th class="ft-c-alias">别名</th>
          <th class="ft-c-out" title="取消勾选后该列只参与过滤/排序,不出现在结果里">输出</th>
          <th class="ft-c-act"></th>
        </tr>
      </thead>
      <tbody>
        <!-- 行状态语义:grouped(有聚合但本行未配聚合 → 自动成为分组依据)、
             agg(本行是聚合)、is-hidden(整行降透明度)、拖拽中的行与插入位置指示线 -->
        <tr
          v-for="(f, i) in fields"
          :key="i"
          :class="{
            grouped: !f.agg && hasAgg,
            agg: !!f.agg,
            /* 不输出的字段整行变淡:一眼能看出它不进结果 */
            'is-hidden': !!f.hidden,
            'ft-dragging': dragFrom === i,
            'ft-drop-before': dropAt === i,
            'ft-drop-after': dropAt === fields.length && i === fields.length - 1,
          }"
          @dragover="onRowDragOver($event, i)"
          @dragleave="onRowDragLeave"
          @drop="onRowDrop"
        >
          <td class="ft-c-idx">
            <span
              class="ft-handle"
              draggable="true"
              title="按住拖动可调整顺序"
              @dragstart="onHandleDragStart($event, i)"
              @dragend="onHandleDragEnd"
            >≡</span>
          </td>

          <!-- 这里只下发下标,不动 sourceCol:换表后原列名在新表未必存在,
               由 useVisualQuery 决定保留还是清空(清空后由右边的「字段」下拉重选) -->
          <td v-if="multiTable" class="ft-c-src">
            <el-select
              :model-value="f.sourceIdx ?? 0"
              size="small"
              class="ft-src-sel"
              @update:model-value="(v: number) => emit('set-source', i, v)"
            >
              <el-option
                v-for="(s, si) in sources"
                :key="si"
                :value="si"
                :label="si === 0 ? `${s.table}(主表)` : s.table"
              />
            </el-select>
          </td>

          <td v-if="multiTable" class="ft-c-col" :title="colTip(f.sourceCol, f.sourceIdx)">
            <!--
              多表时把「字段」做成可选下拉:改过「来源表」后原列可能在新表里不存在,
              以前 sourceCol 会被清空而这一格是只读文本 → 用户无从重选(死状态)。
              单表时不渲染下拉,保持原来的紧凑文本。
            -->
            <el-select
              v-if="multiTable"
              :model-value="f.sourceCol"
              size="small"
              filterable
              class="ft-col-sel"
              placeholder="选列"
              @update:model-value="(v: string) => emit('set-col', i, v)"
            >
              <el-option
                v-for="c in colsOf(f.sourceIdx)"
                :key="c.name"
                :value="c.name"
                :label="c.name"
              >
                <!-- 选项里展示类型/注释,选中后只回显列名,避免与外部类型标签重复 -->
                <span class="mono">{{ c.name }}</span>
                <span class="ft-opt-type">{{ c.type }}</span>
                <span v-if="c.comment" class="ft-opt-cmt">{{ c.comment }}</span>
              </el-option>
            </el-select>
          </td>

          <td v-else class="ft-c-col" :title="colTip(f.sourceCol, f.sourceIdx)">
            <!-- 单表:只显字段名 + 类型;注释已单独成列(以前挤在这里,长注释会盖住类型) -->
            <span class="ft-colname mono">{{ f.sourceCol }}</span>
            <span class="ft-coltype" :style="{ color: kindColor(f.sourceCol, f.sourceIdx) }">
              {{ colType(f.sourceCol, f.sourceIdx) }}
            </span>
          </td>

          <!-- 注释单独一列:来源列的注释过长时截断,悬停看全文 -->
          <td class="ft-c-comment" :title="colComment(f.sourceCol, f.sourceIdx)">
            <span v-if="colComment(f.sourceCol, f.sourceIdx)" class="ft-comment-text">
              {{ colComment(f.sourceCol, f.sourceIdx) }}
            </span>
            <span v-else class="dim">—</span>
          </td>

          <td class="ft-c-agg">
            <!-- agg 用空串表示「不聚合」,所以要 f.agg || '' 兜底:undefined 会让下拉回显不出当前值 -->
            <el-select
              :model-value="f.agg || ''"
              size="small"
              class="ft-agg-sel"
              @update:model-value="(v: string) => emit('set-agg', i, v)"
            >
              <el-option v-for="o in AGG_OPTIONS" :key="o.key" :label="o.label" :value="o.key" />
            </el-select>
          </td>

          <td class="ft-c-pipe">
            <div class="ft-pipe-box" title="点击编辑处理链" @click="openPipe(i)">
              <!-- bad = 必填参数没填(与 useVisualQuery 的告警共用同一份 stepArgsFilled 判定):
                   把「跑不起来」的原因提前暴露在字段表里,不用点进弹窗才发现 -->
              <template v-if="(f.steps || []).length">
                <span
                  v-for="(st, pi) in f.steps"
                  :key="'s' + pi"
                  class="ft-pipe-chip mono"
                  :class="{ bad: !stepFilled(st) }"
                >
                  {{ opShortName(st.fn) }}<template v-if="st.args?.length">({{ argSummary(st) }})</template>
                </span>
              </template>
              <span v-else-if="!(f.prog || []).length" class="ft-pipe-none">未配置</span>
              <!-- 程序算子:由本程序计算,单独配色 + 标记 -->
              <span
                v-for="(st, pi) in f.prog || []"
                :key="'p' + pi"
                class="ft-pipe-chip prog mono"
                :class="{ bad: !stepFilled(st) }"
                :title="`程序算子:${opShortName(st.fn)} —— 由 el-to-ck 计算,不能用于过滤/分组/排序`"
              >
                ⚙{{ opShortName(st.fn) }}<template v-if="st.args?.length">({{ argSummary(st) }})</template>
              </span>
              <el-icon class="ft-pipe-edit"><EditPen /></el-icon>
            </div>
          </td>

          <!-- 别名最终会作为结果列名被过滤/排序条件引用:非法标识符会让那些引用全部失效,故描红提示 -->
          <td class="ft-c-alias">
            <el-input
              :model-value="f.alias"
              size="small"
              class="ft-alias-input"
              :class="{ bad: !validAlias(f.alias), custom: isCustomAlias(f) }"
              :title="isCustomAlias(f) ? `${f.sourceCol} 的输出列名已改为 ${f.alias}` : '输出列名(可双击修改)'"
              @update:model-value="(v: string) => emit('set-alias', i, v)"
            />
          </td>

          <!-- 输出开关:取消勾选 = 只参与查询、不输出 -->
          <!-- 勾选框语义与存储字段相反:hidden 为真表示「不输出」,而勾选的直觉是「输出」,
               在这一层统一翻一次,免得 useVisualQuery 侧到处记反语义 -->
          <td class="ft-c-out">
            <el-tooltip
              :content="f.hidden
                ? '当前【不输出】—— 仍可用于过滤/排序,但不出现在结果里。点击恢复输出'
                : '点击设为【不输出】—— 保留在字段列表里用于过滤/排序,但结果不返回这一列'"
              placement="top"
            >
              <el-checkbox
                :model-value="!f.hidden"
                @update:model-value="(v: boolean) => emit('set-hidden', i, !v)"
              />
            </el-tooltip>
          </td>

          <!-- 首/末行禁用而非隐藏:按钮位置固定,连点「下移」时鼠标不用追着按钮跑 -->
          <td class="ft-c-act">
            <el-button link size="small" :disabled="i === 0" @click="emit('move', i, i - 1)" title="上移">
              <el-icon><Top /></el-icon>
            </el-button>
            <el-button
              link
              size="small"
              :disabled="i === fields.length - 1"
              @click="emit('move', i, i + 1)"
              title="下移"
            >
              <el-icon><Bottom /></el-icon>
            </el-button>
            <el-button link size="small" type="danger" @click="emit('remove', i)" title="移除">
              <el-icon><Close /></el-icon>
            </el-button>
          </td>
        </tr>
      </tbody>
    </table>
      </div>
    </div>

    <!-- 处理链编辑弹窗:内容较长,用 dialog 而非浮层,避免底部被裁切 -->
    <el-dialog
      v-model="pipeVisible"
      :title="pipeTitle"
      class="ft-pipe-dlg"
      width="min(760px, calc(100vw - 32px))"
      top="5vh"
      append-to-body
      destroy-on-close
    >
      <div v-if="pipeField" class="ft-pipe-dlg-body">
        <FieldPipelineEditor
          :col="pipeField.sourceCol"
          :kind="kindOf(pipeField.sourceCol, pipeField.sourceIdx)"
          :steps="pipeField.steps || []"
          :prog="pipeField.prog || []"
          @update:steps="setPipeSteps"
          @update:prog="setPipeProg"
        />
      </div>
      <template #footer>
        <el-button @click="pipeVisible = false">完成</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch} from 'vue'
import {
  ArrowDown,
  ArrowRight,
  Bottom,
  Close,
  EditPen,
  Loading,
  Plus,
  Search,
  Top,
  Warning
} from '@element-plus/icons-vue'
import FieldPipelineEditor from './FieldPipelineEditor.vue'
import type { BuilderColumn } from '@/composables/useVisualQuery'
import { AGG_OPTIONS, SQL_IDENT_RE, opShortName, stepArgsFilled, type ValueKind } from '@/constants/queryBuilderMeta'
import type { QueryPipelineStep, QueryWorkflowFieldRule } from '@/types'

const props = defineProps<{
  /**
   * 上层下发的「全部收起」信号:数字一变就把「字段选择」「字段配置」两块一起收起。
   * 用途:点「运行」时自动收起字段面板,把高度立刻还给下面的查询结果。
   */
  collapseSignal?: number
  fields: QueryWorkflowFieldRule[]
  hasAgg: boolean
  kindOf: (col: string, sourceIdx?: number) => ValueKind
  colType: (col: string, sourceIdx?: number) => string
  /** 列注释(没有则返回空串) */
  colComment: (col: string, sourceIdx?: number) => string
  /**
   * 源表清单。长度 > 1 时才显示「来源表」列 —— 单表下这列没有信息量。
   */
  sources?: { table: string; columns: BuilderColumn[]; loading?: boolean; colErr?: string }[]
  /** 取某张源表的列清单(多表时「字段」列需要下拉换列) */
  colsOf: (sourceIdx?: number) => BuilderColumn[]
}>()

const emit = defineEmits<{
  (e: 'add-first'): void
  (e: 'remove', i: number): void
  (e: 'move', from: number, to: number): void
  (e: 'set-agg', i: number, agg: string): void
  (e: 'set-steps', i: number, steps: QueryPipelineStep[]): void
  (e: 'set-prog', i: number, prog: QueryPipelineStep[]): void
  (e: 'set-alias', i: number, alias: string): void
  (e: 'set-source', i: number, sourceIdx: number): void
  /** 换列(多表时「字段」列的下拉) */
  (e: 'set-col', i: number, col: string): void
  /** 只参与查询、不输出(勾选状态取反后下发) */
  (e: 'set-hidden', i: number, hidden: boolean): void
  /** 分组勾选:勾/取消某源表的某列 */
  (e: 'toggle-col', sourceIdx: number, col: string, checked: boolean): void
  /** 分组勾选:某源表全选/全不选 */
  (e: 'toggle-all', sourceIdx: number, checked: boolean): void
  /** 列清单读取失败后的重试 */
  (e: 'retry-cols', sourceIdx: number): void
  /** 字段面板折叠状态:是否有块展开着(收起时页面把高度让给结果区) */
  (e: 'collapse-change', anyOpen: boolean): void
}>()

/** 单表时「来源表」列的信息量恒为零(永远是主表),所以整列(表头 + 单元格)一起隐藏 */
const multiTable = computed(() => (props.sources?.length ?? 0) > 1)

// —— 分组勾选区:默认【折叠】——
// 常驻展开会占掉半屏,把下方的字段明细表挤下去;收起时头部仍露出
// 「几张表 / 已选几列」与「有表列读取失败」,不丢关键信息。
const groupsOpen = ref(false)

// 字段规则表(聚合 / 处理链 / 别名 / 输出)同样默认收起 —— 把高度让给结果区,
// 需要细配时再展开(空态按钮留在折叠外,不会点不到)。
/*
  「字段配置 / 字段选择」默认都收起(用户定稿)。
  ⚠️ 别把它单独改成默认展开 —— 那样打开会看到两行折叠头 + 一大片空白。
  现在配套的是"折叠状态驱动配置区高度":都收起 → 配置区 28%,空间自动还给下面的查询结果;
  展开任一块 → 配置区 62%(够编辑)。两个行为是一对,改一个要连着看另一个。
*/
const rulesOpen = ref(false)

/**
 * 折叠状态变化时上报给上层：两个面板都收起后，父级把高度让给查询结果区。
 * immediate 确保首次挂载也同步一次，避免父级沿用旧布局状态。
 */
watch([groupsOpen, rulesOpen], ([g, r]) => emit('collapse-change', g || r), { immediate: true })

// 收到「全部收起」信号(点运行时下发)→ 两块一起收起。
// 数字变化才触发;已经收起时不产生变化,也就不会重复 emit。
watch(
  () => props.collapseSignal,
  () => {
    groupsOpen.value = false
    rulesOpen.value = false
  },
)

/** 列读取失败的表数(收起时也要能一眼看到) */
const errCount = computed(() => (props.sources ?? []).filter((s) => !!s.colErr).length)
/** 是否有表还在拉列 */
const anyLoading = computed(() => (props.sources ?? []).some((s) => s.loading))

// —— 分组勾选面板 ——
const groupKw = ref('')
function filteredCols(si: number, cols: BuilderColumn[]): BuilderColumn[] {
  const k = groupKw.value.trim().toLowerCase()
  if (!k) return cols
  // 注释也参与搜索:按中文含义找列比记列名方便
  return cols.filter((c) => c.name.toLowerCase().includes(k) || (c.comment ?? '').toLowerCase().includes(k))
}
/** 必须 sourceIdx + 列名 双匹配:多表下同名列属于不同字段,只比列名会把两张表的勾选串起来 */
function isOn(si: number, col: string): boolean {
  return props.fields.some((f) => f.sourceIdx === si && f.sourceCol === col)
}
/** 按字段列表计数,含 hidden(不输出)的行 —— 它们仍在参与过滤/排序,不算「没选」 */
function checkedCountOf(si: number): number {
  return props.fields.filter((f) => f.sourceIdx === si).length
}

// ─────────────── 拖动行首 ≡ 调整输出字段顺序 ───────────────
//
// 只有手柄是 draggable,行只作为放置目标 —— 若整行 draggable,在「别名」输入框里
// 划选文字会变成拖行,几乎没法改名。
/** 正在拖动的行下标,-1 = 没在拖 */
const dragFrom = ref(-1)
/** 插入位置(0..fields.length,表示插到第 N 个元素【之前】),-1 = 无 */
const dropAt = ref(-1)

function onHandleDragStart(e: DragEvent, i: number) {
  dragFrom.value = i
  dropAt.value = -1
  if (e.dataTransfer) {
    e.dataTransfer.effectAllowed = 'move'
    // ⚠️ Firefox 必须 setData,否则后续的 dragover / drop 根本不触发
    e.dataTransfer.setData('text/plain', String(i))
  }
}

function onHandleDragEnd() {
  dragFrom.value = -1
  dropAt.value = -1
}

function onRowDragOver(e: DragEvent, i: number) {
  // 只认从手柄发起的拖动:否则从别处拖东西经过表格也会冒出一条插入指示线
  if (dragFrom.value < 0) return
  // 必须 preventDefault,否则浏览器不把这里当作可放置区域(不会触发 drop)
  e.preventDefault()
  if (e.dataTransfer) e.dataTransfer.dropEffect = 'move'
  const r = (e.currentTarget as HTMLElement).getBoundingClientRect()
  // 落点在行的上半 → 插到本行之前;下半 → 之后
  dropAt.value = e.clientY < r.top + r.height / 2 ? i : i + 1
}

function onRowDragLeave(e: DragEvent) {
  // ⚠️ 只在【离开整张表】时清指示线。行与行之间移动同样会触发 dragleave,
  //    而且顺序是 dragenter(新行) → dragleave(旧行):按行清会把刚设好的位置
  //    又擦掉,指示线在相邻行之间反复闪。
  const to = e.relatedTarget as Node | null
  const table = (e.currentTarget as HTMLElement).closest('table')
  if (to && table?.contains(to)) return
  dropAt.value = -1
}

function onRowDrop(e: DragEvent) {
  e.preventDefault()
  const from = dragFrom.value
  const at = dropAt.value
  dragFrom.value = -1
  dropAt.value = -1
  if (from < 0 || at < 0) return
  // dropAt 是「在原始数组里插到 at 之前」;元素被移除后,at 之后的项整体前移一位,
  // 而 moveField 的 to 是【移除后】的下标 —— 这里必须换算。
  const to = at > from ? at - 1 : at
  // 落回原位(拖起又放回同一处)直接丢弃:不值得让上层为一次无效拖动重建字段
  if (to === from) return
  // dropAt 允许等于 fields.length(拖到末行之后),换算后仍可能越界,这里兜底
  if (to < 0 || to >= props.fields.length) return
  emit('move', from, to)
}

/** 颜色编码的是「值类型」而不是来源表:类型标签与处理链的输出类型对不上时,颜色能一眼看出异常 */
const KIND_COLORS: Record<ValueKind, string> = {
  number: '#34d399',
  string: '#60a5fa',
  date: '#fb923c',
  bool: '#c084fc',
  other: '#94a3b8'
}
function kindColor(col: string, sourceIdx = 0) {
  return KIND_COLORS[props.kindOf(col, sourceIdx)]
}

/** 字段列的悬停提示:列名 : 类型 + 注释 */
function colTip(col: string, sourceIdx = 0): string {
  const parts = [`${col} : ${props.colType(col, sourceIdx)}`]
  const c = props.colComment(col, sourceIdx)
  if (c) parts.push(`注释: ${c}`)
  return parts.join('\n')
}

/** chip 是否缺必填参数(与 useVisualQuery 的告警共用同一份判定) */
function stepFilled(st: QueryPipelineStep): boolean {
  return stepArgsFilled(st)
}

/** chip 上的参数摘要 */
function argSummary(st: QueryPipelineStep): string {
  // 空值显式写成 ?:留白会让 chip 看着只是「参数比较少」,与红底提示对不上
  return (st.args ?? []).map((a) => (a.value === '' ? '?' : a.value)).join(', ')
}

// 存下标而不是行对象:父组件增删行时会重建对象,持有旧引用会把改动写到已删除的行上
/** 处理链弹窗:当前编辑的行下标(null = 关闭) */
const pipeIdx = ref<number | null>(null)
const pipeVisible = computed({
  get: () => pipeIdx.value !== null,
  set: (v: boolean) => {
    if (!v) pipeIdx.value = null
  }
})
const pipeField = computed(() => (pipeIdx.value === null ? null : (props.fields[pipeIdx.value] ?? null)))
/** 弹窗标题:处理链 · 字段名(字段注释);无注释时省略括号 */
const pipeTitle = computed(() => {
  const f = pipeField.value
  if (!f) return '处理链'
  const c = props.colComment(f.sourceCol, f.sourceIdx)
  return c ? `处理链 · ${f.sourceCol}（${c}）` : `处理链 · ${f.sourceCol}`
})

function openPipe(i: number) {
  pipeIdx.value = i
}
function setPipeSteps(v: QueryPipelineStep[]) {
  if (pipeIdx.value !== null) emit('set-steps', pipeIdx.value, v)
}
function setPipeProg(v: QueryPipelineStep[]) {
  if (pipeIdx.value !== null) emit('set-prog', pipeIdx.value, v)
}

/** 与后端列名规则共用同一条正则(constants 里的 SQL_IDENT_RE):两边判定必须一致,否则前端放行、后端报错 */
function validAlias(a: string) {
  return !!a && SQL_IDENT_RE.test(a)
}

/** 别名是否被改过(用于高亮,让用户一眼看出生效了) */
function isCustomAlias(f: QueryWorkflowFieldRule) {
  // 只跟 sourceCol 比,不把聚合自动生成的别名算进来:否则配了聚合的行会整片亮起,高亮就失去提示作用
  return !!f.alias && f.alias !== f.sourceCol
}
</script>

<style scoped>
/* 弹窗体:内容高时在弹窗内滚动,底部「完成」按钮始终可见 */
.ft-pipe-dlg-body {
  max-height: 66vh;
  overflow: auto;
  overscroll-behavior: contain;
  padding-right: 4px;
}

.ft-wrap {
  margin-bottom: 14px;
}
/* 未选表时的指引 */
.ft-no-table {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 14px 16px;
  margin-bottom: 8px;
  font-size: 14px;
  color: var(--el-text-color-secondary);
  background: var(--el-fill-color-light);
  border: 1px dashed var(--el-border-color);
  border-radius: 8px;
}
/* —— 多表分组勾选面板 —— */
.ft-groups {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-bottom: 12px;
  padding: 8px 12px;
  background: var(--el-fill-color-light);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
}
/*
 * 「字段配置」表现在也在这张卡片里(7 列:来源表/字段/注释/聚合/处理链/别名/输出)。
 * 卡片有 12px 内边距,面板一变窄表格就会比卡片宽 —— 没有横向滚动的话表格会直接
 * 顶出圆角边框(看着像样式被破坏了),所以这里必须给一条滚动。
 */
.ft-rules-body {
  overflow-x: auto;
}
/* 折叠头:整行可点,悬停高亮 */
.ft-groups-head {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  user-select: none;
  min-height: 24px;
}
/* 字段配置那一层不再单加样式:它和「字段选择」共用 .ft-groups 卡片,
   这样两条折叠头的内边距、底色、边框、圆角、间距完全一致 */
.ft-groups-head:hover .ft-groups-title {
  color: var(--el-color-primary);
}
.ft-groups-caret {
  flex: none;
  font-size: 14px;
  color: var(--el-text-color-secondary);
}
.ft-groups-title {
  flex: none;
  font-size: 14px;
  font-weight: 600;
  transition: color 0.15s;
}
/* 「展开/收起」推到最右,作为可点提示 */
.ft-groups-toggle {
  flex: none;
  margin-left: auto;
  font-size: 13px;
  color: var(--el-color-primary);
}
.ft-groups-body {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.ft-group-toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
}
.ft-group-kw {
  width: 220px;
}
.ft-group-scroll {
  max-height: 300px;
  overflow: auto;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.ft-group-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 4px;
}
.ft-group-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 2px 10px;
}
/* 勾选项防溢出:内容比单元宽时会盖住相邻勾选框 */
.ft-group-grid :deep(.el-checkbox) {
  display: flex;
  align-items: baseline;
  min-width: 0;
  margin-right: 0;
  overflow: hidden;
}
.ft-group-grid :deep(.el-checkbox__input) {
  flex: none;
}
.ft-group-grid :deep(.el-checkbox__label) {
  flex: 1;
  min-width: 0;
  display: inline-flex;
  align-items: baseline;
  overflow: hidden;
}
/* 勾选项里的列名:过长截断 */
.ft-group-grid :deep(.el-checkbox__label) > span:first-child {
  flex: none;
  max-width: 45%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
/* 一条规则(以前写了两遍,后者静默覆盖前者的 margin-left:6→8) */
.ft-opt-cmt {
  flex: 1;
  min-width: 24px;
  margin-left: 8px;
  font-size: 12px;
  color: var(--el-text-color-placeholder);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.ft-head {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 8px;
}
.ft-no {
  width: 16px;
  height: 16px;
  border-radius: 50%;
  background: var(--brand, #eab308);
  color: #1f2937;
  font-size: 11px;
  line-height: 16px;
  text-align: center;
  font-weight: 700;
  flex: 0 0 16px;
}
.ft-title {
  font-size: 14px;
  font-weight: 600;
}
.ft-count {
  font-size: 11px;
  padding: 0 5px;
  border-radius: 8px;
  background: var(--el-fill-color);
  color: var(--el-text-color-secondary);
  line-height: 15px;
}
.ft-hint {
  font-size: 12px;
  color: var(--el-text-color-placeholder);
  margin-left: auto;
}
.ft-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  height: 68px;
  border: 1px dashed var(--el-border-color);
  border-radius: 8px;
  color: var(--el-text-color-placeholder);
  font-size: 13.5px;
  cursor: pointer;
  transition: all 0.15s ease;
}
.ft-empty:hover {
  border-color: var(--brand, #eab308);
  color: var(--brand-strong, #b45309);
  background: rgba(234, 179, 8, 0.05);
}
.ft-table {
  width: 100%;
  border-collapse: separate;
  border-spacing: 0;
  font-size: 13.5px;
}
.ft-table th {
  text-align: left;
  font-weight: 500;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  padding: 4px 6px;
  border-bottom: 1px solid var(--el-border-color-lighter);
}
.ft-table td {
  padding: 3px 6px;
  border-bottom: 1px solid var(--el-border-color-lighter);
  vertical-align: middle;
}
.ft-table tbody tr:hover {
  background: var(--el-fill-color-lighter);
}
/* 有聚合时,未聚合字段是「分组依据」→ 左侧加色条 */
.ft-table tbody tr.grouped td:first-child {
  box-shadow: inset 2px 0 0 var(--el-color-info);
}
.ft-table tbody tr.agg td:first-child {
  box-shadow: inset 2px 0 0 var(--brand, #eab308);
}

/* 拖拽排序:正在被拖动的行 */
.ft-table tbody tr.ft-dragging {
  opacity: 0.45;
}
/* 拖拽排序:插入位置指示线。
   ⚠️ 用 box-shadow 而不是 border —— 表格是 border-collapse: separate,
      border 会把行高撑开 2px,拖动时整张表跟着抖动。 */
.ft-table tbody tr.ft-drop-before td {
  box-shadow: inset 0 2px 0 var(--el-color-primary);
}
.ft-table tbody tr.ft-drop-after td {
  box-shadow: inset 0 -2px 0 var(--el-color-primary);
}
/* 首格还要保住「分组依据 / 聚合」的左侧色条,不能只留指示线 */
.ft-table tbody tr.ft-drop-before td:first-child {
  box-shadow: inset 2px 0 0 var(--el-color-primary), inset 0 2px 0 var(--el-color-primary);
}
.ft-table tbody tr.ft-drop-after td:first-child {
  box-shadow: inset 2px 0 0 var(--el-color-primary), inset 0 -2px 0 var(--el-color-primary);
}
.ft-c-idx {
  width: 22px;
}
.ft-handle {
  color: var(--el-text-color-placeholder);
  cursor: grab;
  font-size: 14px;
  user-select: none;
}
.ft-handle:active {
  cursor: grabbing;
  color: var(--brand-strong, #b45309);
}
.ft-c-col {
  width: 240px;
  /* 字段名 / 类型 / 注释 横排一行 */
  white-space: nowrap;
}
.ft-colname {
  display: inline-block;
  vertical-align: middle;
  max-width: 46%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
/* 多表时的换列下拉:撑满该格 */
.ft-col-sel {
  width: 100%;
  min-width: 120px;
}
/* 下拉选项里的类型/注释小字 */
.ft-opt-type {
  margin-left: 8px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.ft-coltype {
  display: inline-block;
  vertical-align: middle;
  margin-left: 6px;
  font-size: 11px;
  opacity: 0.85;
}
/* 字段注释:独立的「注释」列,过长省略、悬停看全文 */
.ft-c-comment {
  width: 180px;
  max-width: 180px;
  font-size: 13px;
  color: var(--el-text-color-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.ft-comment-text {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.ft-c-comment .dim {
  color: var(--el-text-color-placeholder);
}
/* 输出开关列 */
.ft-c-out {
  width: 48px;
  text-align: center;
}
/* 不输出的字段:整行降噪,但仍可编辑 */
.ft-table tbody tr.is-hidden .ft-colname,
.ft-table tbody tr.is-hidden .ft-alias-input .el-input__inner {
  opacity: 0.55;
  text-decoration: line-through;
}
.ft-c-agg {
  width: 132px;
}
/* 多表时才出现的「来源表」列 */
.ft-c-src {
  width: 190px;
}
.ft-src-sel {
  width: 100%;
}
.ft-agg-sel {
  width: 100%;
}
.ft-c-pipe {
  min-width: 150px;
}
.ft-pipe-box {
  display: flex;
  align-items: center;
  gap: 4px;
  flex-wrap: wrap;
  min-height: 24px;
  padding: 2px 6px;
  border: 1px solid transparent;
  border-radius: 5px;
  cursor: pointer;
}
.ft-pipe-box:hover {
  border-color: var(--el-border-color);
  background: var(--el-fill-color-lighter);
}
.ft-pipe-chip {
  font-size: 11px;
  padding: 1px 5px;
  border-radius: 3px;
  background: rgba(96, 165, 250, 0.16);
  color: #7dd3fc;
}
.ft-pipe-chip.bad {
  background: rgba(239, 68, 68, 0.16);
  color: #fca5a5;
}
/* 程序算子:琥珀色,与 CK 算子区分 */
.ft-pipe-chip.prog {
  background: rgba(245, 158, 11, 0.18);
  color: #f59e0b;
}
.ft-pipe-none {
  font-size: 12px;
  color: var(--el-text-color-placeholder);
}
.ft-pipe-edit {
  margin-left: auto;
  font-size: 12px;
  color: var(--el-text-color-placeholder);
  opacity: 0;
  transition: opacity 0.15s ease;
}
.ft-pipe-box:hover .ft-pipe-edit {
  opacity: 1;
}
.ft-c-alias {
  width: 140px;
}
.ft-alias-input :deep(.el-input__inner) {
  font-family: var(--font-mono, monospace);
  font-size: 13px;
}
.ft-alias-input.bad :deep(.el-input__wrapper) {
  box-shadow: 0 0 0 1px var(--el-color-danger) inset;
}
/* 别名被改过 → 左侧加一道品牌色,提示“输出列名已自定义” */
.ft-alias-input.custom :deep(.el-input__wrapper) {
  box-shadow: inset 2px 0 0 var(--brand, #eab308);
}
.ft-alias-input.custom.bad :deep(.el-input__wrapper) {
  box-shadow: inset 2px 0 0 var(--brand, #eab308), 0 0 0 1px var(--el-color-danger) inset;
}
.ft-c-act {
  width: 92px;
  white-space: nowrap;
}
.ft-c-act :deep(.el-button) {
  padding: 0 3px;
}
.mono {
  font-family: var(--font-mono, monospace);
}
</style>
