<!--
  CronHint.vue

  职责：Cron 表达式提示组件：把常见调度表达式转换为易读说明。
  边界：随 taskId/expr 变化即时重取调度说明；组件位于表格 tooltip 中，不能只在首次挂载加载，失败时需要可读降级。
-->

<script setup lang="ts">
import { ref, watch } from 'vue'
import { syncTasksApi } from '@/api'

const props = defineProps<{ taskId: number; expr: string }>()
const times = ref<string[]>([])
const loading = ref(false)
const err = ref('')

// 请求序号:并发请求返回顺序不保证,靠它判断响应是否已过期(旧响应必须丢弃)
let seq = 0

/**
 * 拉取「未来 5 次执行时间」。
 *
 * ⚠️ 必须 watch,不能只在 onMounted 取一次 —— 本组件被放在每行的 tooltip 内容里,
 *    行重排/分页/编辑表达式后实例会被复用:表达式文字(props)是新的,
 *    而执行时间还是上一个任务算出来的,自相矛盾。
 */
async function load() {
  // 进入本次调用即占号,后续所有赋值前都要比对:否则先发的慢请求晚回来会盖掉新结果
  const my = ++seq
  // 先清错误再置 loading:重试时不能残留上一轮的"无法计算"
  loading.value = true
  err.value = ''
  try {
    const r = await syncTasksApi.cronNext(props.taskId, 5)
    if (my !== seq) return // 归属校验:快速切换时旧响应不能覆盖新的
    // 后端可能返回 null 而非空数组,统一成数组供 v-if="times.length" 判断
    times.value = r.next ?? []
  } catch {
    if (my !== seq) return
    // 出错清空旧数据:留着上一次的时间会让人以为本次也算出来了
    times.value = []
    err.value = '无法计算执行时间'
  } finally {
    // 只有最新一次请求才负责收起 loading,过期的结束不该影响当前状态
    if (my === seq) loading.value = false
  }
}

// 依赖数组写成 getter 返回数组:taskId 或表达式任一变化都要重新计算(表达式改了往往直接算不出时间)
watch(() => [props.taskId, props.expr], load, { immediate: true })
</script>

<template>
  <div class="cron-hint-box">
    <div class="ch-title">Cron 表达式 <code class="mono ch-expr">{{ expr }}</code></div>
    <div v-if="loading" class="ch-loading">计算中…</div>
    <!-- 错误优先于数据展示:算不出时间时不能停在上一轮的列表上 -->
    <div v-else-if="err" class="ch-err">{{ err }}</div>
    <template v-else>
      <div class="ch-sub">未来 5 次执行时间:</div>
      <ol v-if="times.length" class="ch-list">
        <li v-for="(t, i) in times" :key="i" class="mono">{{ t }}</li>
      </ol>
      <!-- 请求成功但列表为空:多为任务不是 Cron 触发(手动/间隔),因此提示的是原因而不是"出错" -->
      <div v-else class="ch-err">暂无(任务非定时?)</div>
    </template>
  </div>
</template>

<style scoped>
.cron-hint-box { line-height: 1.7; }
.ch-title { font-size: 13px; color: var(--el-text-color-primary); margin-bottom: 6px; }
.ch-expr { color: var(--brand-strong); background: var(--fill-soft); padding: 1px 6px; border-radius: 4px; }
.ch-sub { font-size: 12px; color: var(--el-text-color-secondary); margin-bottom: 2px; }
.ch-list { margin: 0; padding-left: 20px; }
.ch-list li { font-size: 12px; color: var(--el-text-color-regular); }
.ch-loading, .ch-err { font-size: 12px; color: var(--el-text-color-secondary); }
.ch-err { color: var(--el-color-danger); }
</style>
