<!--
  HomeView.vue

  职责：首页：汇总数据同步与加工链路入口及平台能力概览。
  边界：挂载时加载实时概览和健康数据；架构图节点按路由跳转，接口失败时以空态或降级说明保留其他入口。
-->

<template>
  <div class="home">
    <header class="home-head">
      <img class="hh-mark" :src="'/favicon.svg?v=4'" alt="" aria-hidden="true" />
      <div class="hh-main">
        <h1>数据同步与加工架构</h1>
        <p class="hh-sub">
          本地文件 / 远程 SFTP / 关系数据库 → <b>标准源表（ods_data）</b> → <b>目标库表（dwd_data）</b>,再随手查询探索。
        </p>
      </div>
      <el-button
        class="hh-refresh"
        size="small"
        :icon="Refresh"
        :loading="loading"
        @click="load"
      >刷新概览</el-button>
    </header>

    <div class="home-body">
      <!-- ═══ 左:流程架构图 ═══ -->
      <div class="home-flow">
        <!-- ① 数据接入 -->
        <section class="stage">
          <div class="stage-head">
            <span class="stage-no" aria-hidden="true">①</span>
            <span class="stage-title">数据接入</span>
            <span class="stage-note">多种来源统一同步进标准源表（ods_data）</span>
          </div>

          <div class="flow">
            <!--
              包裹层必需:汇合线的端点要落在「首/末卡片中心」,
              所以它必须相对【卡片行】定位,而不是相对整条容器。
            -->
            <div class="sources-wrap">
              <div class="sources">
                <div
                  v-for="s in sources"
                  :key="s.label"
                  class="node node-src"
                  :style="{ '--accent': s.accent }"
                  @click="go('/datasources')"
                >
                  <span class="node-tile" aria-hidden="true">{{ s.icon }}</span>
                  <span class="node-name">{{ s.label }}</span>
                </div>
              </div>

              <div class="merge">
                <span class="merge-line" />
                <span class="merge-bar" />
                <span class="arrow-down" />
              </div>
            </div>

            <button
              type="button"
              class="node node-card"
              style="--accent: #60a5fa"
              @click="go('/sync-tasks')"
            >
              <span class="node-tile" aria-hidden="true">⇄</span>
              <span class="node-body">
                <span class="node-title-row">
                  <span class="node-name">映射同步</span>
                  <span v-if="stat.tasks" class="node-chip"> {{ stat.tasks }} 个任务 </span>
                </span>
                <span class="node-desc">字段映射 · 增量同步 · 自动建表</span>
              </span>
            </button>

            <span class="rail rail-sync">
              <span class="rail-label">同步落库</span>
            </span>

            <button
              type="button"
              class="node node-card node-store"
              style="--accent: var(--layer-ods)"
              @click="go('/raw')"
            >
              <span class="node-tile" aria-hidden="true">▤</span>
              <span class="node-body">
                <span class="node-title-row">
                  <span class="node-name">标准源表（ods_data）</span>
                  <span v-if="stat.ods" class="node-chip"> {{ stat.ods }} 张表 </span>
                </span>
                <span class="node-desc">同步落库后的源表 · 手机号(mdn)主键</span>
              </span>
            </button>
          </div>
        </section>

        <!-- ② 数据加工 -->
        <section class="stage">
          <div class="stage-head">
            <span class="stage-no" aria-hidden="true">②</span>
            <span class="stage-title">数据加工</span>
            <span class="stage-note">用工作流把标准源表（ods_data）加工成目标库表（dwd_data）的表</span>
          </div>

          <div class="flow">
            <button
              type="button"
              class="node node-card"
              style="--accent: #6366f1"
              @click="go('/workflows')"
            >
              <span class="node-tile" aria-hidden="true">⚙</span>
              <span class="node-body">
                <span class="node-title-row">
                  <span class="node-name">工作流</span>
                  <span v-if="stat.wf" class="node-chip"> {{ stat.wf }} 个流程 </span>
                </span>
                <span class="node-desc">DAG 节点 · 定时调度 · 手动触发</span>
              </span>
            </button>

            <span class="rail rail-build">
              <span class="rail-label">加工产出</span>
            </span>

            <button
              type="button"
              class="node node-card node-store"
              style="--accent: var(--layer-dwd)"
              @click="go('/dwd')"
            >
              <span class="node-tile" aria-hidden="true">▦</span>
              <span class="node-body">
                <span class="node-title-row">
                  <span class="node-name">目标库表（dwd_data）</span>
                  <span v-if="stat.dwd" class="node-chip"> {{ stat.dwd }} 张表 </span>
                </span>
                <span class="node-desc">加工后的明细表 / 汇总表</span>
              </span>
            </button>
          </div>
        </section>

        <!-- ③ 数据探索(旁路,虚线表「随时可用」) -->
        <section class="stage stage-last">
          <div class="stage-head">
            <span class="stage-no" aria-hidden="true">③</span>
            <span class="stage-title">数据探索</span>
            <span class="stage-note">旁路能力:两个层级都能随时查,不用等加工</span>
          </div>

          <div class="flow">
            <button
              type="button"
              class="node node-card node-explore"
              style="--accent: #22d3ee"
              @click="go('/sql-query')"
            >
              <span class="node-tile" aria-hidden="true">⌨</span>
              <span class="node-body">
                <span class="node-title-row">
                  <span class="node-name">数据探索</span>
                </span>
                <span class="node-desc">可视化构建 · SQL 查询 · 导出</span>
              </span>
            </button>
          </div>
        </section>
      </div>

      <!-- ═══ 右:实时概览 ═══ -->
      <aside class="home-side">
        <div class="ov-card">
          <div class="ov-head">
            <span class="ov-title">实时概览</span>
            <span v-if="loading" class="ov-loading">加载中…</span>
          </div>

          <div class="ov-list">
            <button type="button" class="ov-row" @click="go('/datasources')">
              <span class="ov-label">数据源</span>
              <span class="ov-value">
                {{ stat.ds ?? '—' }}
                <span v-if="stat.dsBad" class="ov-bad">⚠ {{ stat.dsBad }}</span>
              </span>
            </button>

            <button type="button" class="ov-row" @click="go('/sync-tasks')">
              <span class="ov-label">同步任务</span>
              <span class="ov-value">
                {{ stat.tasks ?? '—' }}
                <span v-if="stat.tasksOn !== null" class="ov-sub">{{ stat.tasksOn }} 启用</span>
              </span>
            </button>

            <button type="button" class="ov-row" @click="go('/raw')">
              <span class="ov-label">标准源表（ods_data）</span>
              <span class="ov-value">{{ stat.ods ?? '—' }}<span class="ov-unit">表</span></span>
            </button>

            <button type="button" class="ov-row" @click="go('/dwd')">
              <span class="ov-label">目标库表（dwd_data）</span>
              <span class="ov-value">{{ stat.dwd ?? '—' }}<span class="ov-unit">表</span></span>
            </button>

            <button type="button" class="ov-row" @click="go('/workflows')">
              <span class="ov-label">工作流</span>
              <span class="ov-value">{{ stat.wf ?? '—' }}<span class="ov-unit">个</span></span>
            </button>
          </div>
        </div>

        <!-- 最近一次同步:直接看得到「数据有没有进来」 -->
        <div class="ov-card">
          <div class="ov-head">
            <span class="ov-title">最近同步</span>
            <el-button v-if="lastRun" size="small" link type="primary" @click="go('/sync-tasks')">
              全部记录
            </el-button>
          </div>

          <div v-if="!lastRun" class="ov-empty">还没有同步记录</div>
          <div v-else class="run-box">
            <div class="run-line">
              <span class="run-dot" :class="lastRun.status" />
              <span class="run-status">{{ runStatusText(lastRun.status) }}</span>
              <span class="run-time">{{ fmtDt(lastRun.startedAt) }}</span>
            </div>
            <div class="run-task" :title="lastRunTask">{{ lastRunTask }}</div>
            <div class="run-nums">
              <span>读 <b>{{ fmtNum(lastRun.rowsRead) }}</b></span>
              <span>写 <b>{{ fmtNum(lastRun.rowsWritten) }}</b></span>
            </div>
            <div v-if="lastRun.error" class="run-err" :title="lastRun.error">{{ lastRun.error }}</div>
          </div>
        </div>

        <!-- 运行中的任务:一眼看到在跑什么 -->
        <div v-if="running.length" class="ov-card">
          <div class="ov-head">
            <span class="ov-title">正在运行</span>
            <span class="ov-loading">{{ running.length }} 个</span>
          </div>
          <div v-for="t in running" :key="t.id" class="running-item">
            <span class="run-dot running" />
            <span class="running-name" :title="t.name">{{ t.name }}</span>
          </div>
        </div>
      </aside>
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * 首页 = 架构图 + 实时概览。
 *
 * 设计说明:
 * - 图仍然以「架构」为主(用户第一眼要理解数据怎么流),但加了实时数字徽标与跳转,
 *   让首页既是说明也是入口。
 * - 所有取数都【互不阻塞且各自兜底】:任一接口失败只让对应数字显示「—」,
 *   不能让整页空白(首页挂了会很吓人)。
 */
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Refresh } from '@element-plus/icons-vue'
import { connectionsApi, dataSourcesApi, syncTasksApi, arbWorkflowsApi, workspaceApi } from '@/api'
import type { SyncTask } from '@/types'
import { fmtDt } from '@/utils/format'

const router = useRouter()

/** 三种接入来源(纯展示) */
const sources = [
  { label: '本地 CSV', icon: '▤', accent: '#34d399' },
  { label: 'SFTP 源', icon: '⇅', accent: '#fb923c' },
  { label: '关系数据库', icon: '▦', accent: '#60a5fa' }
]

const loading = ref(true)
/**
 * 概览数字:null = 未知(接口失败或还没加载完)→ 界面显示「—」。
 *
 * ⚠️ 失败必须是 null,不能是 0:
 *    以前失败时兜底成空数组、当 0 展示,于是接口挂了会显示
 *    「数据源 0 个 / 标准源表（ods_data） 0 张」—— 用户会读成"我的数据没了",
 *    而不是"读取失败了"。0(确定没有)与 —(不知道)必须分开。
 */
const stat = ref<{
  ds: number | null
  dsBad: number | null
  tasks: number | null
  tasksOn: number | null
  ods: number | null
  dwd: number | null
  wf: number | null
}>({ ds: null, dsBad: null, tasks: null, tasksOn: null, ods: null, dwd: null, wf: null })
const tasks = ref<SyncTask[]>([])

/** 最近一次运行(取 startedAt 最新的那个任务) */
const lastRun = computed(() => {
  let best: SyncTask | null = null
  for (const t of tasks.value) {
    if (!t.lastRun?.startedAt) continue
    if (!best || t.lastRun.startedAt > best.lastRun!.startedAt) best = t
  }
  return best?.lastRun ?? null
})
/** 上面那条记录的归属任务(反查一次即可:lastRun 就是列表里某个任务的引用) */
const lastRunTask = computed(() => {
  const t = tasks.value.find((x) => x.lastRun === lastRun.value)
  return t ? `${t.name} → ${t.targetDb}.${t.targetTable}` : ''
})
/** 只列 running:这是"现在在跑什么",不是历史 */
const running = computed(() => tasks.value.filter((t) => t.lastRun?.status === 'running'))

/** 同步状态 → 中文。这里不是 tag 而是纯文字 + 圆点,所以不做颜色映射 */
function runStatusText(s: string): string {
  return s === 'success' ? '成功' : s === 'failed' ? '失败' : '运行中'
}

/**
 * 概览数字 → 展示文本。首页侧栏很窄,上万就直接用「万/亿」,
 * 免得一个数字把整行挤到换行。n == null 时给 '—' 而不是 0(见 stat 的注释)。
 */
function fmtNum(n?: number): string {
  if (n == null) return '—'
  if (n >= 1e8) return (n / 1e8).toFixed(1) + '亿'
  if (n >= 1e4) return (n / 1e4).toFixed(1) + '万'
  return String(n)
}

/** 统一跳转入口:void 掉 promise 是刻意的(导航失败无需在这里处理) */
function go(path: string) {
  void router.push(path)
}

async function load() {
  loading.value = true
  try {
    // 连接:取默认连接(拿不到连接时,层级表数算「未知」)
    const conns = (await connectionsApi.list().catch(() => null)) as
      | { id: number; isDefault?: boolean }[]
      | null
    const conn = conns?.find((c) => c.isDefault) ?? conns?.[0]
    const cid = conn?.id ?? 0
    const layerUnknown = !cid

    // ⚠️ 每项独立兜底,失败记为 null(未知),不要兜成 []:见 stat 的注释
    const [dss, statuses, sts, wfs, odsT, dwdT] = await Promise.all([
      dataSourcesApi.list().catch(() => null),
      dataSourcesApi.status().catch(() => null),
      syncTasksApi.list().catch(() => null),
      arbWorkflowsApi.list().catch(() => null),
      cid
        ? workspaceApi.tables(cid, 'ods_data').catch(() => null)
        : Promise.resolve(layerUnknown ? null : []),
      cid
        ? workspaceApi.tables(cid, 'dwd_data').catch(() => null)
        : Promise.resolve(layerUnknown ? null : [])
    ])

    tasks.value = sts ?? []
    stat.value = {
      ds: dss?.length ?? null,
      dsBad: statuses ? statuses.filter((s) => s.state === 'bad' && !s.stale).length : null,
      tasks: sts?.length ?? null,
      tasksOn: sts ? sts.filter((t) => t.enabled).length : null,
      ods: odsT?.length ?? null,
      dwd: dwdT?.length ?? null,
      wf: wfs?.length ?? null
    }
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<style scoped>
/* 首页内容高度不定(架构图 + 概览卡),整页滚动 */
.home {
  height: 100%;
  overflow: auto;
  padding: 24px 28px 36px;
}

/* 头部 */
.home-head {
  display: flex;
  align-items: center;
  gap: 14px;
  margin-bottom: 20px;
}
/* 图标块:用主色底 + 圆角,替代纯图片 logo(favicon 本身是透明底的矢量) */
.hh-mark {
  width: 46px;
  height: 46px;
  border-radius: 12px;
  background: #0052d9;
  flex: none;
}
.hh-main {
  min-width: 0;
}
.home-head h1 {
  margin: 0;
  font-size: 20px;
  font-weight: 700;
}
.hh-sub {
  margin: 4px 0 0;
  font-size: 12.5px;
  color: var(--el-text-color-secondary);
}
/* 副标题里的「标准源表（ods_data） / 目标库表（dwd_data）」是流程里的关键节点,跟着层级色走便于与图上对应 */
.hh-sub b {
  color: var(--brand-strong, #3378e8);
}
/* margin-left:auto 把它推到最右;flex:none 保证变窄时不被压扁 */
.hh-refresh {
  margin-left: auto;
  flex: none;
}

/* 两栏:左流程 + 右概览 */
.home-body {
  display: flex;
  align-items: flex-start; /* 两栏各自按内容高度,不被对方拉长 */
  gap: 20px;
}
.home-flow {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 14px;
}
/* 概览栏固定宽:它是"旁注",不该随窗口变宽而抢走架构图的空间 */
.home-side {
  flex: none;
  width: 268px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
/* 窄屏:改成上下排布并【反转顺序】—— 概览卡在上面,先看数字再看图 */
@media (max-width: 1080px) {
  .home-body {
    flex-direction: column-reverse; /* 窄屏:概览在上面(先看数字再看图) */
  }
  .home-side {
    width: 100%;
    flex-direction: row;
    flex-wrap: wrap;
  }
  .ov-card {
    flex: 1 1 240px;
  }
}

/* 阶段 */
/* 每个阶段是一张卡片:限宽 + 居中,保证三张卡在视觉上是一条竖列 */
.stage {
  width: 100%;
  max-width: 720px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 12px;
  background: var(--el-bg-color);
  padding: 14px 18px 18px;
}
/* 虚线边框 = 旁路能力(数据探索不在主链路上,随时可用但不参与加工流程) */
.stage-last {
  border-style: dashed;
}
.stage-head {
  display: flex;
  align-items: center; /* 圆标 + 文字垂直居中 */
  gap: 8px;
  margin-bottom: 16px;
}
.stage-no {
  width: 18px;
  height: 18px;
  border-radius: 50%;
  background: var(--brand, #eab308);
  color: #1f2937;
  font-size: 11px;
  line-height: 18px;
  text-align: center;
  font-weight: 700;
  flex: none;
}
.stage-title {
  font-size: 14px;
  font-weight: 600;
}
/* 阶段说明用占位色(比 secondary 更淡):它是注解,不能和标题抢注意力 */
.stage-note {
  font-size: 12px;
  color: var(--el-text-color-placeholder);
}

/* 流程:纵向排列的小节,内部元素各自居中 */
.flow {
  display: flex;
  flex-direction: column;
  align-items: center;
}

/*
 * 三张来源卡片 + 汇合线。
 *
 * ⚠️ 卡片用【三等分自适应】而不是固定宽度:
 *    以前 .node-src 写死 136px + 不换行,容器一窄于 3×136+2×12=432px
 *    就横向溢出(窗口窄 / 侧边栏展开时很容易碰到)。
 * ⚠️ 汇合线端点用 calc 表达同一套几何(不能写死 68px——
 *    那个值只在宽度恰好 432px 时才对)。
 *    三列等宽、间距 12px 时,首/末卡片中心 = (宽 - 2×12)/6。
 */
.sources-wrap {
  display: flex;
  flex-direction: column;
  align-items: stretch;
  width: 100%;
  max-width: 560px; /* 与下方节点同宽,整体成列 */
  min-width: 0;
}
.sources {
  display: flex;
  gap: 12px;
}
/* 三张来源卡等分宽度(见上方说明),纵向排图标与名称 */
.node-src {
  flex: 1 1 0;
  min-width: 0;
  flex-direction: column;
  align-items: center;
  text-align: center;
  gap: 6px;
  padding: 14px 10px;
}
/* 卡片名允许换行:极窄时「关系数据库」不会被挤出去 */
.node-src .node-name {
  font-size: 12.5px;
  line-height: 1.35;
  word-break: break-word;
}

/* 汇合线:水平线 + 竖线 + 箭头(端点跟随卡片中心,见上方说明) */
.merge {
  position: relative;
  width: 100%;
  height: 30px;
}
.merge-line {
  position: absolute;
  top: 12px;
  left: calc((100% - 24px) / 6);
  right: calc((100% - 24px) / 6);
  height: 2px;
  border-radius: 1px;
  background: linear-gradient(90deg, transparent, var(--el-border-color), transparent);
}
.merge-bar {
  position: absolute;
  top: 12px;
  left: 50%;
  width: 2px;
  height: 8px;
  background: var(--el-border-color);
}
.merge .arrow-down {
  position: absolute;
  left: 50%;
  transform: translateX(-50%);
  top: 15px;
}

/* 竖向连接轨(带标签,比裸箭头更像“管道”) */
.rail {
  position: relative;
  display: block;
  width: 2px;
  height: 34px;
  /* 渐隐:上端接上文、下端渐淡,视觉上"流"进下面的卡片 */
  background: linear-gradient(180deg, var(--el-border-color), var(--el-border-color-lighter));
}
/* 轨尾的三角箭头:纯 CSS(两个透明边框 + 一个实色上边框) */
.rail::after {
  content: '';
  position: absolute;
  left: 50%;
  bottom: -1px;
  transform: translateX(-50%);
  border-left: 5px solid transparent;
  border-right: 5px solid transparent;
  border-top: 7px solid var(--el-border-color);
}
/* 轨旁的文字标签:绝对定位在轨右侧居中(不占用轨的宽度) */
.rail-label {
  position: absolute;
  left: 10px;
  top: 50%;
  transform: translateY(-50%);
  font-size: 11px;
  color: var(--el-text-color-placeholder);
  white-space: nowrap;
}

/* 简单箭头(汇合线用) */
.arrow-down {
  width: 2px;
  height: 16px;
  position: relative;
  display: block;
  background: var(--el-border-color);
  flex: none;
}
/* 箭头尖端:与 .rail::after 同一套三角画法 */
.arrow-down::after {
  content: '';
  position: absolute;
  left: 50%;
  bottom: 0;
  transform: translateX(-50%);
  border-left: 5px solid transparent;
  border-right: 5px solid transparent;
  border-top: 7px solid var(--el-border-color);
}

/* 节点 */
.node {
  position: relative; /* 供 ::before 的左侧色条定位 */
  display: flex;
  align-items: center;
  gap: 12px;
  border: 1px solid var(--el-border-color);
  border-radius: 10px;
  background: var(--el-fill-color-lighter);
  padding: 12px 16px;
  /* 自适应宽度而不是固定 min-width:320px —— 窄容器下固定值会直接溢出 */
  width: 100%;
  min-width: 0;
  max-width: 560px;
  font: inherit; /* 按钮节点要抹掉浏览器默认按钮字体/颜色,才能和 div 节点长得一样 */
  color: inherit;
  text-align: left;
}
/* 只有按钮节点可点:给 hover / focus 反馈 */
button.node {
  cursor: pointer;
  appearance: none;
  transition: border-color 0.15s, background 0.15s, transform 0.15s;
}
button.node:hover {
  border-color: var(--accent, var(--brand));
  background: var(--hover-bg);
  transform: translateY(-1px); /* 1px 上浮 = "可以点"的轻提示 */
}
/* 键盘可达:focus-visible 只在键盘聚焦时出现,鼠标点不会留下描边 */
button.node:focus-visible {
  outline: 2px solid var(--accent, var(--brand));
  outline-offset: 2px;
}
/* 左侧色条:用渐变而不是纯色,和汇合线风格一致 */
.node::before {
  content: '';
  position: absolute;
  left: 0;
  top: 8px;
  bottom: 8px;
  width: 3px;
  border-radius: 0 3px 3px 0;
  background: linear-gradient(180deg, var(--accent, var(--brand)), transparent);
}
.node-tile {
  width: 34px;
  height: 34px;
  border-radius: 9px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 17px;
  flex: none;
  color: var(--accent, var(--brand));
  /* 用主色的淡底做图标块:比裸字符更像「图标」,且随模式自适应 */
  background: color-mix(in srgb, var(--accent, var(--brand)) 14%, transparent);
}
/* 节点右侧的文字区:flex:1 吃掉剩余宽度,min-width:0 让长名字可以省略号截断 */
.node-body {
  display: flex;
  flex-direction: column;
  gap: 3px;
  flex: 1;
  min-width: 0;
}
.node-title-row {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.node-name {
  font-size: 13.5px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}
.node-desc {
  font-size: 11.5px;
  color: var(--el-text-color-placeholder);
}
/* 实时数字徽标:淡底 + 层级色,和节点是一套配色 */
.node-chip {
  font-size: 11px;
  line-height: 1.5;
  padding: 0 6px;
  border-radius: 8px;
  color: var(--accent, var(--brand));
  background: color-mix(in srgb, var(--accent, var(--brand)) 14%, transparent);
  font-variant-numeric: tabular-nums;
}
/* 存储类节点(标准源表（ods_data） / 目标库表（dwd_data）)的名字用更深的层级色:它们是"结果",要更醒目 */
.node-store .node-name {
  color: var(--accent, var(--brand-strong));
}

/* 探索节点:居中单卡 */
.node-explore {
  justify-content: center;
  text-align: center;
}
.node-explore .node-body {
  flex: 0 1 auto;
}

/* ── 概览卡 ── */
/* 概览卡与阶段卡同一套外观(圆角/描边),但更紧凑 —— 它是旁注 */
.ov-card {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 12px;
  background: var(--el-bg-color);
  padding: 12px 14px;
  min-width: 0;
}
.ov-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}
.ov-title {
  font-size: 13px;
  font-weight: 600;
}
.ov-loading {
  margin-left: auto;
  font-size: 11.5px;
  color: var(--el-text-color-placeholder);
}
.ov-list {
  display: flex;
  flex-direction: column;
}
/* 整行可点(跳转到对应页面):所以用 button 而不是 div,键盘也能聚焦 */
.ov-row {
  display: flex;
  align-items: baseline;
  gap: 8px;
  padding: 6px 4px;
  border: none;
  background: none;
  font: inherit;
  color: inherit;
  cursor: pointer;
  border-radius: 6px;
  text-align: left;
}
.ov-row:hover {
  background: var(--hover-bg);
}
.ov-row + .ov-row {
  border-top: 1px dashed var(--el-border-color-lighter);
}
.ov-label {
  font-size: 12.5px;
  color: var(--el-text-color-secondary);
}
/* 数值靠右、等宽数字:同一列数字位数不同时不会左右跳 */
.ov-value {
  margin-left: auto;
  font-size: 17px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
  display: inline-flex;
  align-items: baseline;
  gap: 6px;
}
.ov-unit {
  font-size: 11px;
  font-weight: 400;
  color: var(--el-text-color-placeholder);
}
.ov-sub {
  font-size: 11px;
  font-weight: 400;
  color: var(--el-text-color-placeholder);
}
.ov-bad {
  font-size: 11.5px;
  font-weight: 600;
  color: var(--el-color-danger);
}
.ov-empty {
  font-size: 12px;
  color: var(--el-text-color-placeholder);
  padding: 4px 0;
}

/* 最近同步 */
.run-box {
  display: flex;
  flex-direction: column;
  gap: 5px;
}
/* 状态点 + 状态文字 + 时间同一行,时间用 margin-left:auto 推到最右 */
.run-line {
  display: flex;
  align-items: center;
  gap: 6px;
}
.run-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex: none;
  background: var(--el-text-color-placeholder);
}
.run-dot.success {
  background: var(--el-color-success);
}
.run-dot.failed {
  background: var(--el-color-danger);
}
/* 运行中的圆点做脉冲:不用刷新页面也能看出"它还在跑" */
.run-dot.running {
  background: var(--el-color-primary);
  animation: run-pulse 1.4s ease-in-out infinite;
}
@keyframes run-pulse {
  50% { opacity: 0.35; }
}
.run-status {
  font-size: 12.5px;
  font-weight: 600;
}
.run-time {
  margin-left: auto;
  font-size: 11.5px;
  color: var(--el-text-color-placeholder);
}
.run-task {
  font-size: 11.5px;
  color: var(--el-text-color-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.run-nums {
  display: flex;
  gap: 14px;
  font-size: 11.5px;
  color: var(--el-text-color-placeholder);
}
.run-nums b {
  color: var(--el-text-color-regular);
  font-variant-numeric: tabular-nums;
}
.run-err {
  font-size: 11.5px;
  color: var(--el-color-danger);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 正在运行 */
.running-item {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 3px 0;
}
.running-name {
  font-size: 12px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 尊重系统设置:开了"减少动态效果"就关掉脉冲与上浮,信息不依赖动画传达 */
@media (prefers-reduced-motion: reduce) {
  .run-dot.running { animation: none; }
  button.node { transition: none; }
  button.node:hover { transform: none; }
}
</style>
