<!--
  SystemView.vue

  职责：系统监控页面：展示进程资源、数据库运行指标和健康状态。
  边界：每 2 秒刷新进程资源、每 10 秒刷新目标库指标；页面隐藏或卸载时暂停清理定时器，刷新失败保留上次采样。
-->

<template>
  <div class="system-view">
    <div class="page-head">
      <div>
        <h2>系统监控</h2>
        <p class="page-sub">
          平台进程资源占用(内存 / CPU / 并发 / GC / 数据文件,每 2 秒)与目标库服务的运行指标(每 10 秒)。
        </p>      </div>
      <div class="actions">
        <!-- 采样时刻:数字是"某一瞬间"的快照,不看时间就分不清是不是已经停止刷新了 -->
        <el-tag type="primary" effect="plain" class="sample-time">采样 {{ stats?.time ?? '--' }}</el-tag>
        <el-button :icon="Refresh" @click="refresh" :loading="loading">刷新</el-button>
      </div>
    </div>

    <!-- 四张卡自适应排布:窄屏自动折成 2 列 / 1 列,不写死列数 -->
    <div v-loading="loading" class="stats-grid">
      <!-- 内存:同时给进程视角与堆视角,只看一个都会误判。
           注意 Java 版是 JVM 口径,与 Go 版不是同一个量,标签已按 JVM 改写(见后端 SystemStats 注释) -->
      <el-card shadow="never" class="stat-card">
        <template #header><span class="card-title">内存</span></template>
        <div class="stat-big mono">{{ fmtMB(stats?.workingSetMB) }}</div>
        <div class="stat-sub">已提交虚拟内存(JVM 无「工作集」接口,与 Go 口径不同)</div>
        <el-divider />
        <!-- 三个子项都是堆的口径:已用是当前存活对象,已提交含尚未使用的空页,已申请是堆+非堆向 OS 要的总量 -->
        <div class="stat-rows">
          <div class="stat-row"><span>堆已用</span><span class="mono">{{ fmtMB(stats?.allocMB) }}</span></div>
          <div class="stat-row"><span>堆已提交</span><span class="mono">{{ fmtMB(stats?.heapInuseMB) }}</span></div>
          <div class="stat-row"><span>JVM 已申请(堆+非堆)</span><span class="mono">{{ fmtMB(stats?.sysMB) }}</span></div>
        </div>
      </el-card>

      <!-- CPU -->
      <el-card shadow="never" class="stat-card">
        <template #header><span class="card-title">CPU</span></template>
        <div class="stat-big mono">{{ stats?.cpuPercent?.toFixed(1) ?? '--' }}%</div>
        <!-- 必须带上核数:同样 200%,8 核机器和 32 核机器的含义完全不同 -->
        <div class="stat-sub">进程 CPU 使用率({{ stats?.cpuCores ?? '--' }} 核)</div>
        <!-- 进度条只作视觉刻度用,数字已在上面给足,所以关掉条内文字避免重复 -->
        <el-progress :percentage="cpuPct" :stroke-width="8" :show-text="false" :color="cpuColor" class="cpu-bar" />
        <el-divider />
        <div class="stat-rows">
          <div class="stat-row"><span>运行时长</span><span class="mono">{{ uptime }}</span></div>
        </div>
      </el-card>

      <!-- 并发 & GC -->
      <el-card shadow="never" class="stat-card">
        <template #header><span class="card-title">并发 & GC</span></template>
        <div class="stat-big mono">{{ stats?.goroutines ?? '--' }}</div>
        <!-- 线程数持续上涨(不是随请求起伏)通常意味着有地方在泄漏,所以把它放在最大的字号上。
             接口字段名仍是 Go 的 goroutines(前端契约未改),值是 JVM 的线程数 ThreadMXBean#getThreadCount -->
        <div class="stat-sub">当前线程数 threads</div>
        <el-divider />
        <div class="stat-rows">
          <div class="stat-row"><span>GC 次数</span><span class="mono">{{ stats?.numGC ?? 0 }}</span></div>
          <!-- 暂停时间是"最近一次"而不是累计:累计值只会一直变大,看不出当前是否卡顿 -->
          <div class="stat-row"><span>最近 GC 暂停</span><span class="mono">{{ stats ? stats.lastGCPauseMs.toFixed(1) : '--' }} ms</span></div>
        </div>
      </el-card>

      <!-- 文件占用:三个子项相加 = 数据目录总量,拆开是为了定位"是谁把盘吃满了" -->
      <el-card shadow="never" class="stat-card">
        <template #header><span class="card-title">数据文件</span></template>
        <div class="stat-big mono">{{ fmtMB(totalFiles) }}</div>
        <div class="stat-sub">数据目录总占用(DB + DuckDB + uploads)</div>
        <el-divider />
        <div class="stat-rows">
          <div class="stat-row"><span>MySQL 元数据库（估算）</span><span class="mono">{{ fmtMB(stats?.dbFileMB) }}</span></div>
          <div class="stat-row"><span>DuckDB 文件库</span><span class="mono">{{ fmtMB(stats?.duckdbFileMB) }}</span></div>
          <div class="stat-row"><span>uploads 上传</span><span class="mono">{{ fmtMB(stats?.uploadsMB) }}</span></div>
        </div>
      </el-card>
    </div>

    <!-- 目标库服务:上面四张卡都是【本进程】的数据,这一块才是被监控的那个 ClickHouse -->
    <el-card shadow="never" class="db-card">
      <template #header>
        <div class="db-head">
          <span class="card-title">目标库服务</span>
          <span v-if="conn" class="db-conn">{{ conn.name }}</span>
          <span class="db-spacer" />
          <el-tag v-if="!conn" type="info" effect="plain" size="small">未选择连接</el-tag>
          <el-tag v-else-if="dbErr" type="danger" effect="plain" size="small">读取失败</el-tag>
          <el-tag v-else-if="dbStats?.warnings?.length" type="warning" effect="plain" size="small">指标受限</el-tag>
          <el-tag v-else-if="dbStats" type="success" effect="plain" size="small">在线</el-tag>
          <span v-if="dbStats" class="db-time mono">采样 {{ dbStats.time }}</span>
        </div>
      </template>

      <div v-if="!conn" class="db-empty">
        尚未选择目标库连接。先在「数据库」页打开一个连接,这里就会显示它的服务运行指标。
      </div>
      <div v-else-if="dbErr" class="db-empty db-err">{{ dbErr }}</div>

      <el-alert v-if="conn && !dbErr && dbStats?.warnings?.length" type="warning" :closable="false"
        title="部分监控指标无法读取" :description="dbStats.warnings.join('；')" class="db-warning" />

      <div v-if="conn && dbStats" class="db-grid">
        <!-- 内存 -->
        <div class="db-block">
          <div class="db-label">内存 · 常驻</div>
          <div class="stat-big mono">{{ fmtMB(dbStats.memResidentMB) }}</div>
          <el-progress :percentage="barPct(dbStats.memOfLimitPct)" :stroke-width="8" :show-text="false"
            :color="barColor(dbStats.memOfLimitPct)" class="cpu-bar" />
          <!-- -1 是「上限未知」而不是 0%:0 会被读成「内存很闲」 -->
          <div class="db-note">
            {{ dbStats.memOfLimitPct >= 0 ? `已用上限的 ${dbStats.memOfLimitPct.toFixed(1)}%` : dbStats.memResidentMB < 0 ? '指标不可用' : '上限未知' }}
          </div>
          <el-divider />
          <div class="stat-rows">
            <div class="stat-row"><span>生效上限</span><span class="mono">{{ fmtLimit(dbStats.memLimitMB) }}</span></div>
            <div class="stat-row"><span>机器总内存</span><span class="mono">{{ fmtMB(dbStats.osMemTotalMB) }}</span></div>
            <div class="stat-row"><span>机器可用</span><span class="mono">{{ fmtMB(dbStats.osMemAvailMB) }}</span></div>
          </div>
        </div>

        <!-- CPU:整机口径,与上面那张「进程 CPU」不是一回事 -->
        <div class="db-block">
          <div class="db-label">整机 CPU</div>
          <div class="stat-big mono">{{ fmtPct(dbStats.cpuPercent) }}</div>
          <el-progress :percentage="barPct(dbStats.cpuPercent)" :stroke-width="8" :show-text="false"
            :color="barColor(dbStats.cpuPercent)" class="cpu-bar" />
          <div class="db-note">
            {{ dbStats.cpuPercent >= 0 ? '忙碌时间 / 总 CPU 时间' : '该库未暴露 CPU 时间指标' }}
          </div>
          <el-divider />
          <div class="stat-rows">
            <div class="stat-row"><span>运行时长</span><span class="mono">{{ fmtUptime(dbStats.uptimeSec) }}</span></div>
            <div class="stat-row"><span>运行中查询</span><span class="mono">{{ fmtCount(dbStats.runningQueries) }}</span></div>
            <div class="stat-row"><span>后台合并</span><span class="mono">{{ fmtCount(dbStats.runningMerges) }}</span></div>
          </div>
        </div>

        <!-- 磁盘 -->
        <div class="db-block">
          <div class="db-label">磁盘</div>
          <div v-if="!dbStats.disks.length" class="db-note">未获取到挂载点</div>
          <div v-for="d in dbStats.disks" :key="d.name" class="disk-row">
            <div class="stat-row">
              <span class="disk-name" :title="d.path">{{ d.name }}</span>
              <span class="mono">{{ fmtDisk(d) }}</span>
            </div>
            <!-- 总量未知(旧版本视图缺列)时没有百分比可画,只留上面那行剩余容量 -->
            <el-progress v-if="d.usedPct >= 0" :percentage="barPct(d.usedPct)" :stroke-width="6"
              :show-text="false" :color="barColor(d.usedPct)" />
          </div>
        </div>

        <!-- 连接 -->
        <div class="db-block">
          <div class="db-label">连接数</div>
          <div class="stat-big mono">{{ fmtCount(dbStats.totalConnections) }}</div>
          <div class="db-note">当前客户端连接合计</div>
          <el-divider />
          <div class="stat-rows">
            <div class="stat-row"><span>原生协议</span><span class="mono">{{ fmtCount(dbStats.tcpConnections) }}</span></div>
            <div class="stat-row"><span>HTTP</span><span class="mono">{{ fmtCount(dbStats.httpConnections) }}</span></div>
            <div class="stat-row"><span>集群互联</span><span class="mono">{{ fmtCount(dbStats.isectConnections) }}</span></div>
          </div>
        </div>
      </div>
    </el-card>

    <!-- 划清边界:上面四张卡是本进程,这一块是目标库;两张都看才够定位问题 -->
    <el-alert type="info" :closable="false" class="sys-hint"
      title="提示:「平台服务」是同步平台进程自身占用;「目标库服务」是它连接的目标库统计。需要整机磁盘 IO 之类的系统级指标时,再用资源监视器。" />
  </div>
</template>

<script setup lang="ts">
/**
 * 系统监控 —— 只读的进程自检页。
 *
 * 指标来自后端进程自己的运行时统计(Go 的 runtime.MemStats / NumGoroutine),
 * 所以「协程数」「GC 次数」这类概念是【进程内】的,不对应操作系统指标;
 * 页面底部的提示卡就是在说这件事,免得有人拿这里的 CPU 去当整机 CPU 用。
 *
 * 刷新策略:2 秒轮询 + 手动刷新并存 —— 监控页开着时数据必须自己动(不然会误判服务卡死),
 * 但组件卸载时一定要 clearInterval,否则路由切走后定时器仍在跑、还在打接口。
 */
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import { connectionsApi, systemApi } from '@/api'
import { useConnectionStore } from '@/stores/connection'
import type { DiskStat, ServerStats, SystemStats } from '@/types'

const stats = ref<SystemStats | null>(null)
const loading = ref(false)
/** setInterval 的句柄:非响应式即可(不参与渲染),但必须留住才能在卸载时清掉 */
let timer: ReturnType<typeof setInterval> | null = null
/** 目标库指标的轮询句柄(10 秒一次,与上面那个 2 秒的分开) */
let dbTimer: ReturnType<typeof setInterval> | null = null

/** 目标库服务指标。与 stats(本进程)是两块独立的数据,各自有错误态与刷新节奏 */
const connStore = useConnectionStore()
const conn = computed(() => connStore.active)
const dbStats = ref<ServerStats | null>(null)
const dbErr = ref('')
/** 防重入:10 秒一轮,但目标库慢的时候上一轮可能还没回来 */
let dbBusy = false
let disposed = false
let mounted = false
let statsSeq = 0
let dbSeq = 0

/** 统一的 MB 显示;取不到值(undefined/null)给 '--' 而不是 0 —— 0 会被读成"真的没占用" */
function fmtMB(v?: number): string {
  if (v === undefined || v === null || v < 0) return '--'
  return `${v.toFixed(1)} MB`
}

// el-progress 的 percentage 只接受 0..100:多核机器上进程 CPU 可以超过 100%,
// 不夹住会画出越界的进度条(el-progress 会告警且视觉错乱)
const cpuPct = computed(() => Math.min(stats.value?.cpuPercent ?? 0, 100))
/** 阈值配色:>80 红、>50 橙、其余绿;与"看颜色就知道要不要管"的直觉一致 */
const cpuColor = computed(() => {
  const v = stats.value?.cpuPercent ?? 0
  if (v > 80) return 'var(--el-color-danger)'
  if (v > 50) return '#f59e0b'
  return 'var(--el-color-success)'
})

/** 数据目录总占用 = 三块相加;某项缺失按 0 计(单项行里仍会显示 '--',这里只求和) */
const totalFiles = computed(() =>
  (stats.value?.dbFileMB ?? 0) + (stats.value?.duckdbFileMB ?? 0) + (stats.value?.uploadsMB ?? 0),
)

/** 秒 → 「x时 y分 z秒」;小时为 0 就不显示小时段,重启不久时不会看到一长串 0 */
const uptime = computed(() => {
  const s = stats.value?.uptimeSec ?? 0
  const h = Math.floor(s / 3600)
  const m = Math.floor((s % 3600) / 60)
  const sec = s % 60
  if (h > 0) return `${h}时 ${m}分 ${sec}秒`
  if (m > 0) return `${m}分 ${sec}秒`
  return `${sec}秒`
})

async function refresh() {
  if (disposed || loading.value) return
  const seq = ++statsSeq
  loading.value = true
  try {
    const sample = await systemApi.stats()
    if (!disposed && seq === statsSeq) stats.value = sample
  } catch {
    // 拦截器已提示；定时器入口仍需捕获拒绝，保留最后一次有效采样。
  } finally {
    if (!disposed && seq === statsSeq) loading.value = false
  }
}

/** 秒 → 「x时 y分 z秒」;负数(时钟回拨之类)按 0 处理,不显示 --1秒 */
function fmtUptime(sec: number): string {
  if (sec < 0) return '--'
  const s = Math.max(sec ?? 0, 0)
  const h = Math.floor(s / 3600)
  const m = Math.floor((s % 3600) / 60)
  const rest = s % 60
  if (h > 0) return `${h}时 ${m}分 ${rest}秒`
  if (m > 0) return `${m}分 ${rest}秒`
  return `${rest}秒`
}

function fmtCount(value: number): string {
  return value < 0 ? '--' : String(value)
}

/** 百分比:-1 是「未知」(后端约定),显示 -- 而不是 0.0% */
function fmtPct(v: number): string {
  return v < 0 ? '--' : `${v.toFixed(1)}%`
}

/** 内存上限:0 表示没读到配置,显示 -- */
function fmtLimit(v: number): string {
  return v > 0 ? `${v.toFixed(0)} MB` : '--'
}

/** 磁盘一行:有总量就给「已用/总量」,没有就只给剩余(旧版本视图缺列) */
function fmtDisk(d: DiskStat): string {
  if (d.totalGB <= 0) return `剩余 ${d.freeGB.toFixed(1)} GB`
  return `${(d.totalGB - d.freeGB).toFixed(1)} / ${d.totalGB.toFixed(1)} GB`
}

// el-progress 只接受 0..100;-1(未知)按 0 画,文字那边已经说明是未知
const barPct = (v: number) => Math.min(Math.max(v, 0), 100)
/** 阈值配色与本页其它进度条一致:>80 红、>50 橙、其余绿 */
const barColor = (v: number) => {
  if (v < 0) return 'var(--el-color-info)'
  if (v > 80) return 'var(--el-color-danger)'
  if (v > 50) return '#f59e0b'
  return 'var(--el-color-success)'
}

/**
 * 拉一次目标库服务指标。
 *
 * 失败只写进 dbErr、不清空上一份数据 —— 目标是「离线时也能看见最后一次的数值」,
 * 比整块变空更有用。所以这里必须 catch(接口是 silent 的,不会弹全局错误)。
 */
async function refreshDb() {
  if (disposed || dbBusy) return
  const id = conn.value?.id
  if (!id) {
    dbStats.value = null
    dbErr.value = ''
    return
  }
  const seq = ++dbSeq
  const current = () => !disposed && seq === dbSeq && id === conn.value?.id
  dbBusy = true
  try {
    const sample = await systemApi.serverStats(id)
    if (!current()) return
    dbStats.value = sample
    dbErr.value = ''
  } catch (e) {
    if (current()) dbErr.value = e instanceof Error ? e.message : '目标库服务指标读取失败'
  } finally {
    if (current()) dbBusy = false
  }
}

/** 切连接立即作废旧采样；同一连接刷新失败时才保留它自己的上一份数据。 */
watch(() => conn.value?.id, () => {
  dbSeq++
  dbBusy = false
  dbStats.value = null
  dbErr.value = ''
  if (mounted && !document.hidden) void refreshDb()
})

function stopTimers() {
  if (timer) clearInterval(timer)
  if (dbTimer) clearInterval(dbTimer)
  timer = null
  dbTimer = null
}

/** 两组轮询一起暂停；重新显示时先刷新，再恢复各自的采样间隔。 */
function onVisibility() {
  if (disposed || !mounted) return
  if (document.hidden) {
    stopTimers()
  } else {
    void refresh()
    void refreshDb()
    if (!timer) timer = setInterval(refresh, 2000)
    if (!dbTimer) dbTimer = setInterval(refreshDb, 10000)
  }
}

onMounted(async () => {
  // 直接进本页时连接 store 可能还是空的(它由工作台页恢复),这里补一次
  if (!connStore.active) {
    try {
      const connections = await connectionsApi.list()
      if (disposed) return
      if (!connStore.active) connStore.restore(connections)
    } catch {
      /* 连接列表读不到 → 面板显示「未选择连接」 */
    }
  }
  if (disposed) return
  mounted = true
  document.addEventListener('visibilitychange', onVisibility)
  onVisibility()
})
onBeforeUnmount(() => {
  disposed = true
  statsSeq++
  dbSeq++
  stopTimers()
  document.removeEventListener('visibilitychange', onVisibility)
})
</script>

<style scoped>
.db-warning {
  margin-bottom: 16px;
}
/* 本页内容高度不定(提示卡 + 四张卡折行),所以整页滚动而不是内部滚动 */
.system-view {
  height: 100%;
  overflow: auto;
  padding: 20px 24px;
}
.page-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
}
.page-head h2 {
  margin: 0 0 4px;
  font-size: 18px;
}
.page-sub {
  margin: 0;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
.actions {
  display: flex;
  align-items: center;
  gap: 8px;
}
/* 采样时间是时间戳字符串,等宽字体下数字跳动时宽度稳定,不会带着旁边的按钮抖 */
.sample-time {
  font-family: var(--font-mono);
}
/*
  卡片网格:auto-fit + minmax(240px, 1fr) —— 列数随宽度自动变,
  而不是写死 repeat(4, 1fr)(窄窗口下四列会被压成豆腐块)。
*/
.stats-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
  gap: 14px;
}
/* 卡片内边距压紧:四张卡要在一屏里看全 */
.stat-card :deep(.el-card__body) {
  padding: 14px 16px;
}
.stat-card :deep(.el-card__header) {
  padding: 10px 16px;
}
.card-title {
  font-size: 13px;
  font-weight: 600;
}
/* 主数字:整页的视觉焦点,用主色 + 大字号 */
.stat-big {
  font-size: 30px;
  font-weight: 700;
  color: var(--el-color-primary);
  line-height: 1.2;
}
.stat-sub {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-top: 2px;
}
.cpu-bar {
  margin-top: 12px;
}
/* 子项列表:名称靠左、数值靠右(justify-content: space-between),扫一眼就能对上 */
.stat-rows {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.stat-row {
  display: flex;
  justify-content: space-between;
  font-size: 12.5px;
  color: var(--el-text-color-regular);
}
.mono {
  font-family: var(--font-mono);
}
.sys-hint {
  margin-top: 16px;
}

/* ── 目标库服务面板 ── */
.db-card {
  margin-top: 14px;
}
.db-card :deep(.el-card__body) {
  padding: 14px 16px;
}
.db-card :deep(.el-card__header) {
  padding: 10px 16px;
}
.db-head {
  display: flex;
  align-items: center;
  gap: 10px;
}
.db-conn {
  font-size: 12.5px;
  color: var(--el-color-primary);
}
.db-spacer {
  flex: 1;
}
.db-time {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.db-empty {
  font-size: 13px;
  color: var(--el-text-color-secondary);
  padding: 8px 0;
}
.db-err {
  color: var(--el-color-danger);
}
/* 四块（内存/CPU/磁盘/连接）与上面的卡片网格同一套折行规则 */
.db-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 18px;
}
.db-label {
  font-size: 12.5px;
  font-weight: 600;
  color: var(--el-text-color-regular);
  margin-bottom: 4px;
}
/* 说明文字：既解释数字口径，也承担「-1 = 未知」这种状态的措辞 */
.db-note {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-top: 2px;
}
.disk-row + .disk-row {
  margin-top: 8px;
}
.disk-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 50%;
}
</style>
