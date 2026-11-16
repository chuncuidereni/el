<!-- 同步列表和执行历史共用的进度视图。未知总量用活动条表示运行，不能伪造百分比。 -->
<template>
  <div class="sync-progress-panel" :data-state="status">
    <div v-if="announce" class="sync-progress-live" aria-live="polite" aria-atomic="true">{{ liveText }}</div>
    <div class="sync-progress-phase"><span>{{ phase }}</span><span v-if="percent != null">{{ stageLabel ? `${stageLabel} ` : '' }}{{ percent }}%</span></div>
    <div v-if="running && retryText" class="sync-progress-detail sync-progress-retry">{{ retryText }}</div>
    <div v-if="percent != null" class="sync-progress-determinate" role="progressbar" :aria-label="stageLabel || '传输进度'"
      aria-valuemin="0" aria-valuemax="100" :aria-valuenow="percent" :aria-valuetext="`${phase}，${percent}%`">
      <el-progress :percentage="percent" :stroke-width="7" :show-text="false"
        :status="status === 'failed' ? 'exception' : status === 'success' ? 'success' : undefined" />
    </div>
    <div v-else-if="running" class="sync-progress-activity" role="progressbar"
      aria-label="传输进行中" aria-valuetext="总量未知，正在传输" />
    <div class="sync-progress-counts">已读取 {{ count(read) }} 行 · 已确认写入 {{ count(written) }} 行</div>
    <div v-if="progress?.unit === '表' && progress.currentObject" class="sync-progress-detail">当前表：{{ progress.currentObject }}</div>
    <div v-if="progress?.completedUnits != null" class="sync-progress-detail">{{ unitText }}</div>
    <div v-if="progress?.totalRows != null" class="sync-progress-detail">
      总行数：{{ progress.estimated ? '约 ' : '' }}{{ count(progress.totalRows) }} 行
    </div>
    <div v-else-if="running" class="sync-progress-detail">总行数未知，按已处理数量展示</div>
    <div v-if="running && !progress" class="sync-progress-detail">等待进度上报，正在查询运行状态</div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { SyncRun } from '@/types'

const props = withDefaults(defineProps<{
  progress?: SyncRun['Progress']
  status: string
  rowsRead?: number
  rowsWritten?: number
  /** 仅聚焦单条任务时开启；列表中的多个悬停卡片不并发播报。 */
  announce?: boolean
}>(), { rowsRead: 0, rowsWritten: 0, announce: false })

const finite = (value: number | null | undefined) => Number.isFinite(value) ? Math.max(0, value!) : 0
const count = (value: number | null | undefined) => finite(value).toLocaleString('zh-CN')
const running = computed(() => props.status === 'running')
const written = computed(() => Math.max(finite(props.rowsWritten), finite(props.progress?.confirmedRows)))
const read = computed(() => Math.max(finite(props.rowsRead), written.value))
const phase = computed(() => props.status === 'success' ? '已完成'
  : props.status === 'failed' ? '已停止' : props.progress?.phase || '准备传输')
// 下载/定位的 100% 仅表示该阶段结束，不能暗示整轮同步已完成。
const stageLabel = computed(() => !running.value ? '' : props.progress?.unit === '字节' ? '当前文件下载进度'
  : props.progress?.unit === '表' ? '日期分表完成进度'
  : props.progress?.phase === '定位文件行号' ? '断点定位进度' : '')
const retryText = computed(() => {
  const p = props.progress
  if (!p || p.retryCount <= 0) return ''
  if (p.nextRetryAtMs == null) return `已自动重试 ${p.retryCount} 次`
  const retryAt = new Date(p.nextRetryAtMs)
  if (!Number.isFinite(retryAt.getTime())) return `已自动重试 ${p.retryCount} 次`
  return `等待自动重试（第 ${p.retryCount} 次），计划 ${retryAt.toLocaleTimeString('zh-CN', { hour12: false })}`
})
const percent = computed(() => {
  if (props.status === 'success') return 100
  const value = props.progress?.percent
  if (value == null || !Number.isFinite(value)) return null
  return Math.round(Math.min(100, Math.max(0, value)) * 10) / 10
})
/** 仅播报阶段或终态；行数和百分比变化不进入 live region，避免高频轮询反复打断读屏。 */
const liveText = computed(() => {
  const text = phase.value
  return running.value && retryText.value ? `${text}，${retryText.value}` : text
})
const unitText = computed(() => {
  const p = props.progress!
  const label = p.phase === '定位文件行号' ? '已定位' : p.unit === '字节' ? '已下载' : p.unit === '位点' ? '已扫描' : '已处理'
  const amount = (value: number) => {
    if (p.unit !== '字节') return `${count(value)} ${p.unit || ''}`.trim()
    const bytes = finite(value)
    if (bytes < 1024) return `${count(bytes)} B`
    const index = Math.min(3, Math.floor(Math.log(bytes) / Math.log(1024)))
    return `${(bytes / 1024 ** index).toLocaleString('zh-CN', { maximumFractionDigits: 1 })} ${['B', 'KiB', 'MiB', 'GiB'][index]}`
  }
  return `${label} ${amount(p.completedUnits ?? 0)} / ${p.totalUnits == null ? '总量未知' : amount(p.totalUnits)}`
})
</script>

<style scoped>
.sync-progress-panel { position: relative; padding-top: 6px; min-width: 230px; line-height: 1.6; }
.sync-progress-live { position: absolute; width: 1px; height: 1px; padding: 0; margin: -1px; overflow: hidden; clip: rect(0, 0, 0, 0); clip-path: inset(50%); white-space: nowrap; border: 0; }
.sync-progress-phase { display: flex; justify-content: space-between; gap: 8px; font-size: 12px; font-weight: 600; color: var(--el-text-color-primary); }
.sync-progress-counts { font-size: 12px; color: var(--el-text-color-regular); font-variant-numeric: tabular-nums; }
.sync-progress-detail { font-size: 11px; color: var(--el-text-color-secondary); }
.sync-progress-activity { position: relative; overflow: hidden; height: 7px; margin: 7px 0; border-radius: 4px; background: var(--el-fill-color); }
.sync-progress-activity::after { content: ''; position: absolute; width: 35%; height: 100%; border-radius: inherit; background: var(--el-color-primary); animation: sync-transfer 1.6s linear infinite; }
@keyframes sync-transfer { from { transform: translateX(-100%); } to { transform: translateX(290%); } }
@media (prefers-reduced-motion: reduce) { .sync-progress-activity::after { animation: none; left: 30%; } }
</style>
