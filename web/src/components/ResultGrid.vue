<!--
  ResultGrid.vue

  职责：查询结果网格：渲染分页数据、列元数据和交互事件。
  边界：渲染 data/columns、本地列宽和分页序号；排序方向通过 sort 事件交回父级，列宽监听在卸载时停止。
-->

<template>
  <div class="result-grid" :class="{ 'center-header': centerHeader, 'fill-height': fillHeight }">
    <!-- 没有列就算不出列宽和表头高度,继续渲染只会得到一个 0 宽的空表,直接给空态 -->
    <el-empty v-if="!result || !result.columns.length" description="暂无结果" :image-size="80" />
    <template v-else>
      <div class="vg">
        <!-- 表头:本身不滚动,靠 translateX 跟随下面的横向滚动 -->
        <div class="vg-head" :class="{ 'no-type': !withType }" :style="{ height: headH + 'px' }">
          <div class="vg-head-inner" :style="{ width: totalW + 'px', transform: `translateX(${-scrollLeft}px)` }">
            <div class="vg-th vg-index-th" :style="{ width: INDEX_W + 'px' }">序号</div>
            <!-- key 里带上列下标:SQL 允许选出同名列(如 SELECT a, a),只用列名会撞 key -->
            <div
              v-for="(col, i) in result.columns"
              :key="`${i}:${col}`"
              class="vg-th"
              :style="{ width: colW[i] + 'px' }"
              :title="colTitle(col)"
            >
              <span v-if="commentOf(col)" class="col-cmt">{{ commentOf(col) }}</span>
              <span class="col-head-line">
                <span class="col-name" :class="{ 'with-comment': !!commentOf(col) }">{{ col }}</span>
                <span v-if="sortable" class="col-sort-arrows">
                  <button type="button" :class="{ active: sortColumn === col && !sortDescending }" :title="`${col} 升序`" :aria-label="`${col} 升序`" @click.stop="emit('sort', { column: col, desc: false })">▲</button>
                  <button type="button" :class="{ active: sortColumn === col && sortDescending }" :title="`${col} 降序`" :aria-label="`${col} 降序`" @click.stop="emit('sort', { column: col, desc: true })">▼</button>
                </span>
              </span>
              <!-- 类型按列下标取(后端列的返回顺序与 types 一一对应);缺失时渲染空串,而不是 'undefined' -->
              <span v-if="withType" class="col-type">{{ result.types?.[i] ?? '' }}</span>
              <!-- 表字段注释:贴源表的列名常是 mdn / wls_16 这类缩写,光看名字猜不出含义;
                   单元格里会截断,悬停 title 能看全。
                   逐列判断(而不是整表判断后渲染空白 span),只有真有注释的列才多这一行 -->
              <span
                v-if="resizable"
                class="vg-resize"
                role="separator"
                :aria-label="`拖动调整 ${col} 列宽`"
                :title="`拖动调整 ${col} 列宽`"
                @pointerdown.stop.prevent="startResize($event, col, i)"
              />
            </div>
          </div>
        </div>

        <!-- 表体:唯一的滚动容器(横竖都由它承担) -->
        <div ref="bodyRef" class="vg-body" :style="fillHeight ? undefined : { height: bodyH + 'px' }" @scroll.passive="onScroll">
          <div class="vg-canvas" :style="{ height: totalH + 'px', width: totalW + 'px' }">
            <!-- canvas 用完整高度/宽度撑出正确的滚动条,切片则只渲染可视行并整体下移 -->
            <div class="vg-slice" :style="{ transform: `translateY(${offsetY}px)` }">
              <!-- ri 是数据行的真实下标(而非可见序号):斑马纹与取数都按它算,滚动时行的颜色不会跳 -->
              <div
                v-for="ri in visibleRows"
                :key="ri"
                class="vg-row"
                :class="{ stripe: ri % 2 === 1 }"
                :style="{ height: ROW_H + 'px' }"
              >
                <div class="vg-td vg-index-td" :style="{ width: INDEX_W + 'px' }">{{ indexStart + ri }}</div>
                <div
                  v-for="(col, ci) in result.columns"
                  :key="ci"
                  class="vg-td"
                  :style="{ width: colW[ci] + 'px' }"
                >
                  <!-- 只对严格等于 null 的值加灰斜体:undefined(行/列越界的读取)是渲染中间态,不该被当成数据里的 NULL -->
                  <span
                    class="cell-value"
                    :class="{ 'cell-null': result.rows[ri]?.[ci] === null }"
                    :title="cellAt(ri, ci)"
                  >{{ cellAt(ri, ci) }}</span>
                  <!-- 标签按单元格的显示文本查表,而不是按行列坐标:调用方(如表结构页)直接给出 值 → 标签 的映射 -->
                  <span v-if="cellTags" class="cell-badges">
                    <el-tag
                      v-for="t in cellTags[cellAt(ri, ci)] ?? []"
                      :key="t"
                      size="small"
                      effect="dark"
                      :type="tagType(t)"
                      class="cell-tag"
                    >{{ t }}</el-tag>
                  </span>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>

    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import type { QueryResult } from '@/types'

const props = withDefaults(defineProps<{
  result: QueryResult | null
  truncated?: boolean
  /** 是否在字段名下方小型显示类型 */
  showType?: boolean
  /** 单元格值 → 标签列表(如表结构里按字段名标注主键/分区/排序) */
  cellTags?: Record<string, string[]>
  /**
   * 表格最大高度(px)。默认 460;数据探索页会传入「结果区实际可用高度」,
   * 这样拖动分栏把结果区拉大时,表格也跟着变大(内部滚动 + 表头固定)。
   */
  maxHeight?: number | string
  /** 目标表查看器可在表头直接切换升降序；其余结果网格默认不显示。 */
  sortable?: boolean
  sortColumn?: string
  sortDescending?: boolean
  /** 允许拖动表头右边界调整列宽。 */
  resizable?: boolean
  /** 当前结果第一页第一行的序号。 */
  indexStart?: number
  /** 表结构等管理表格需要将各列标题整体水平居中。 */
  centerHeader?: boolean
  /** 让表格铺满父容器剩余高度；表头固定，仅表体在空间不足时滚动。 */
  fillHeight?: boolean
}>(), { resizable: true, indexStart: 1 })
const emit = defineEmits<{ (e: 'sort', value: { column: string; desc: boolean }): void }>()

/** 行高(px) —— 虚拟滚动要求固定行高 */
const ROW_H = 33
const INDEX_W = 60
/** 表头高度(px),带类型行时更高 */
const HEAD_H = 48
const HEAD_H_NO_TYPE = 32
/** 表头多一行"字段注释"时的额外高度 */
const HEAD_H_COMMENT = 16
/** 上下各多渲染几行,滚动时不至于露白 */
const OVERSCAN = 6
/** 虚拟滚动的触发阈值:行数少于它就直接全渲染(省掉不必要的计算) */
const VIRTUAL_MIN = 60

const bodyRef = ref<HTMLElement>()
const scrollTop = ref(0)
const scrollLeft = ref(0)
const manualWidths = ref<Record<string, number>>({})
let resizeState: { column: string; startX: number; startWidth: number } | null = null
let previousUserSelect = ''

function startResize(event: PointerEvent, column: string, index: number) {
  resizeState = { column, startX: event.clientX, startWidth: colW.value[index] }
  previousUserSelect = document.body.style.userSelect
  document.body.style.userSelect = 'none'
  window.addEventListener('pointermove', onResizeMove)
  window.addEventListener('pointerup', stopResize)
  window.addEventListener('pointercancel', stopResize)
}

function onResizeMove(event: PointerEvent) {
  if (!resizeState) return
  const width = Math.max(64, Math.min(1200, resizeState.startWidth + event.clientX - resizeState.startX))
  manualWidths.value = { ...manualWidths.value, [resizeState.column]: width }
}

function stopResize() {
  if (!resizeState) return
  resizeState = null
  document.body.style.userSelect = previousUserSelect
  window.removeEventListener('pointermove', onResizeMove)
  window.removeEventListener('pointerup', stopResize)
  window.removeEventListener('pointercancel', stopResize)
}
onBeforeUnmount(stopResize)

// 调用方开了 showType 也未必真有类型:整列都为空时不值得多占一行表头
const withType = computed(() => !!props.showType && !!(props.result?.types ?? []).some(Boolean))

/**
 * 是否有任一列带表字段注释 —— 有的话表头要多一行。
 *
 * 为什么要显式算出来:表头高度同时被 CSS(样式)与 JS(bodyH = 可视高 - headH)使用,
 * 两者必须一致,否则表头会遮住第一行数据。所以高度改成绑定 headH 的动态值。
 */
const withComment = computed(() => {
  const cm = props.result?.columnComments
  if (!cm) return false
  return (props.result?.columns ?? []).some((c) => !!cm[c])
})

const headH = computed(
  () => (withType.value ? HEAD_H : HEAD_H_NO_TYPE) + (withComment.value ? HEAD_H_COMMENT : 0),
)

/** 某列的字段注释(没有则空串) */
function commentOf(col: string): string {
  return props.result?.columnComments?.[col] ?? ''
}

/** 表头悬停提示:列名 + 注释(列宽窄时注释会被截断,悬停看全) */
function colTitle(col: string): string {
  const c = commentOf(col)
  return c ? `${col} — ${c}` : col
}

const maxH = computed(() => {
  // 允许传 520 或 '520px' 两种写法,统一归一成 px 数值;0/NaN/负数一律退回默认高度
  const v = props.maxHeight
  if (typeof v === 'number' && v > 0) return v
  if (typeof v === 'string') {
    const n = parseFloat(v)
    if (Number.isFinite(n) && n > 0) return n
  }
  return 460
})
// 保底 120px:容器极矮时也要留出可滚动的区域,否则表体会被压成 0 高
const bodyH = computed(() => Math.max(120, Math.round(maxH.value - headH.value)))

// 行数超过阈值才启用虚拟滚动:几十行时全量渲染的 DOM 开销本来就小,走简单路径更省心
const rowCount = computed(() => props.result?.rows.length ?? 0)
const virtualized = computed(() => rowCount.value > VIRTUAL_MIN)
const totalH = computed(() => rowCount.value * ROW_H)

/**
 * 单元格文本缓存(按行懒算)。
 *
 * ⚠️ 以前是 `rows.map(r => r.map(fmt))` —— 一次性把「全部行 × 全部列」都格式化,
 *    1000 行 × 60 列就是 6 万次 fmt + 6 万个字符串。虚拟滚动下一屏只有几十行,
 *    没必要为看不见的行付费。
 */
const cellCache = new Map<number, string[]>()
watch(
  () => props.result,
  () => {
    // 缓存键是行下标,换了结果必须整清:否则新结果会拿到上一批的行文本
    cellCache.clear()
    // 偏移量跟着真实滚动条一起归零,避免新结果停在旧滚动位置上(行数往往已不同)
    scrollTop.value = 0
    scrollLeft.value = 0
    bodyRef.value?.scrollTo(0, 0)
  },
)

function rowCells(r: number): string[] {
  // 懒算并缓存:同一行会被列宽估算和单元格取值反复访问,格式化只做一次
  let v = cellCache.get(r)
  if (!v) {
    v = (props.result?.rows[r] ?? []).map(fmt)
    cellCache.set(r, v)
  }
  return v
}

function cellAt(r: number, c: number): string {
  return rowCells(r)[c] ?? ''
}

function fmt(v: unknown): string {
  // 显式渲染 NULL 而不是空串:空字符串也是合法数据,两者在界面上必须能区分
  if (v === null || v === undefined) return 'NULL'
  // 数组/嵌套类型会以对象形式返回,JSON 化避免显示成 [object Object]
  if (typeof v === 'object') return JSON.stringify(v)
  return String(v)
}

/**
 * 列宽:按「表头 + 前若干行」的文本长度估算(不扫全表,否则又变成 O(全量))。
 * 上限 320px,避免超长单元格把表格撑得没法看。
 */
const MIN_W = 90
const MAX_W = 320
const SAMPLE_ROWS = 200

const colW = computed(() => {
  const cols = props.result?.columns ?? []
  // 只采样前 SAMPLE_ROWS 行:列宽本就是估算,扫全表等于又退回 O(行数 × 列数)
  const n = Math.min(rowCount.value, SAMPLE_ROWS)
  return cols.map((c, i) => {
    // 表头:UI 字体,约 8.5px/字符
    let w = c.length * 8.5 + 18
    for (let r = 0; r < n; r++) {
      const t = rowCells(r)[i]
      // 空单元格不参与比较:列宽应由真正有内容的格子决定
      if (!t) continue
      // 单元格:等宽字体约 7.4px/字符;超过 48 字符的部分反正会被省略号截断
      w = Math.max(w, Math.min(t.length, 48) * 7.4 + 22)
    }
    // 上下限夹逼:太窄连表头都放不下,太宽一屏显示不了几列
    return manualWidths.value[c] ?? Math.max(MIN_W, Math.min(MAX_W, Math.round(w)))
  })
})

const totalW = computed(() => INDEX_W + colW.value.reduce((a, b) => a + b, 0))

/** 当前需要渲染的行下标 */
const visibleRows = computed(() => {
  const total = rowCount.value
  if (total === 0) return [] as number[]
  // 未启用虚拟滚动时返回全部下标,等价于整表渲染
  if (!virtualized.value) return Array.from({ length: total }, (_, i) => i)
  // 上下各多放 OVERSCAN 行:滚动时的亚像素误差不至于在边缘露出白条
  const first = Math.max(0, Math.floor(scrollTop.value / ROW_H) - OVERSCAN)
  // 末尾用 ceil:底部往往只露出半行,少算这一行会在快速滚动时闪白;再夹到总行数以内
  const last = Math.min(total, Math.ceil((scrollTop.value + bodyH.value) / ROW_H) + OVERSCAN)
  const out: number[] = []
  for (let i = first; i < last; i++) out.push(i)
  return out
})

/** 渲染切片整体位移(而不是把每行都算一次 translate) */
const offsetY = computed(() => {
  // 只对整片切片做一次位移:逐行各写一个 translate 会在滚动时产生大量样式重算
  const first = visibleRows.value[0]
  return first === undefined ? 0 : first * ROW_H
})

function onScroll(e: Event) {
  const el = e.target as HTMLElement
  // 横竖两个偏移共用这一次滚动事件:行切片与表头都从这两个 ref 取渲染位置
  scrollTop.value = el.scrollTop
  // 表头是独立元素、没有自己的滚动条,横向偏移只能靠这个变量转成 translateX 同步
  scrollLeft.value = el.scrollLeft
}

// 标签到样式的映射集中在此:调用方只传中文字符串,不必关心用了哪个主题色
function tagType(tag: string): 'warning' | 'success' | 'info' {
  if (tag === '主键') return 'warning'
  if (tag === '分区') return 'success'
  return 'info'
}
</script>

<style scoped>
.result-grid {
  width: 100%;
}

/*
 * 铺满模式用于表详情等有明确剩余空间的工作区。
 * 外层和表格都保持纵向 Flex，表头不参与压缩，表体吃掉剩余高度。
 */
.result-grid.fill-height {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.result-grid.fill-height .vg {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.result-grid.fill-height .vg-body {
  flex: 1;
  min-height: 0;
}

.result-grid.fill-height .vg-head {
  flex: none;
}

.vg {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 0;
  overflow: hidden;
  background: transparent;
}
/* 表头:不滚动,内部靠 translateX 跟随 */
.vg-head {
  height: 48px;
  overflow: hidden;
  background: var(--bg-panel);
  border-bottom: 1px solid var(--el-border-color-lighter);
}
.vg-head.no-type {
  height: 32px;
}
.vg-head-inner {
  /* 占满固定高度的表头行，子单元格才能把内容真正放到上下正中。 */
  height: 100%;
  display: flex;
  will-change: transform;
}
.vg-th {
  position: relative;
  flex: none;
  display: flex;
  flex-direction: column;
  justify-content: center;
  padding: 0 8px;
  line-height: 1.25;
  border-right: 1px solid var(--el-border-color-lighter);
  overflow: hidden;
}
/* 仅供显式传入 center-header 的管理表格使用，不影响普通查询结果。 */
.result-grid.center-header .vg-th:not(.vg-index-th) {
  align-items: center;
}

.result-grid.center-header .col-head-line {
  justify-content: center;
}

.result-grid.center-header .col-name {
  flex: 0 1 auto;
  text-align: center;
}

.result-grid.center-header .col-type,
.result-grid.center-header .col-cmt {
  text-align: center;
}

.vg-index-th {
  align-items: center;
  color: var(--el-text-color-secondary);
  font-size: 13px;
  font-weight: 600;
}
.vg-resize {
  position: absolute;
  top: 0;
  right: 0;
  bottom: 0;
  width: 7px;
  cursor: col-resize;
  touch-action: none;
}
.vg-resize:hover,
.vg-resize:active {
  background: var(--el-color-primary-light-7);
}
.col-name {
  flex: 1;
  min-width: 0;
  font-family: var(--font-ui);
  font-weight: 600;
  font-size: 13px;
  color: var(--el-text-color-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.col-name.with-comment {
  font-size: 12px;
  font-weight: 400;
  color: var(--el-text-color-secondary);
}
.col-head-line {
  display: flex;
  align-items: center;
  min-width: 0;
  width: 100%;
}
.col-sort-arrows {
  display: inline-flex;
  flex: none;
  flex-direction: column;
  margin-left: 2px;
}
.col-sort-arrows button {
  appearance: none;
  border: 0;
  background: transparent;
  width: 13px;
  height: 9px;
  padding: 0;
  line-height: 9px;
  font-size: 8px;
  color: var(--el-text-color-placeholder);
  opacity: 0.55;
  cursor: pointer;
}
.col-sort-arrows button:hover,
.col-sort-arrows button.active {
  color: var(--el-color-primary);
  opacity: 0.9;
}
.col-type {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--el-text-color-secondary);
  opacity: 0.85;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
/* 与表详情一致:注释为主标题,物理字段名在下一行作辅助说明。 */
.col-cmt {
  font-family: var(--font-mono);
  font-size: 14px;
  font-weight: 700;
  color: var(--el-text-color-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.vg-body {
  overflow: auto;
  overscroll-behavior: contain;
}
.vg-canvas {
  position: relative;
}
.vg-slice {
  position: absolute;
  top: 0;
  left: 0;
  will-change: transform;
}
.vg-row {
  display: flex;
}
.vg-row.stripe {
  background: var(--el-fill-color-lighter);
}
.vg-row:hover,
.vg-row.stripe:hover {
  background: var(--brand-dim);
}
.vg-td {
  flex: none;
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 0 8px;
  border-right: 1px solid var(--el-border-color-lighter);
  border-bottom: 1px solid var(--el-border-color-lighter);
  overflow: hidden;
  box-sizing: border-box;
  height: 100%;
}
.vg-index-td {
  justify-content: center;
  color: var(--el-text-color-placeholder);
  font-family: var(--font-mono);
  font-size: 13px;
}
.cell-value {
  flex: 1;
  min-width: 0;
  font-family: var(--font-mono);
  font-size: 13.5px;
  color: var(--el-text-color-regular);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.cell-null {
  color: var(--el-text-color-placeholder);
  font-style: italic;
}
.cell-badges {
  display: inline-flex;
  gap: 4px;
  flex: none;
}
.cell-tag {
  font-family: var(--font-ui);
  font-size: 12px;
  height: 18px;
  line-height: 18px;
  padding: 0 5px;
  border-radius: 4px;
}
</style>
