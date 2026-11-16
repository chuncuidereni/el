<!--
  WideTablesView.vue

  职责：宽表加工页面：按关联键圈选多张源表并生成后续加工输入。
  边界：选择多个源表、关联键和输出配置以生成宽表任务；页面仅维护加载和预览状态，入口可见性由 feature 开关控制。
-->

<template>
  <div class="wt-view">
    <div class="page-head">
      <div>
        <h2>工作流</h2>
        <p class="page-sub">
          把多张贴源表按关联键(通常是手机号)关联成<b>一张宽表</b>,落到
          <span class="mono">{{ defaultDb }}</span> 库,供后续按条件圈选号码。
          每次运行都是<b>全量重建</b>:先建临时表,成功后再原子换名 ——
          所以运行失败时,宽表仍然是上一次成功的那份数据。
        </p>
      </div>
      <div class="actions">
        <el-button :icon="Refresh" @click="load">刷新</el-button>
      </div>
    </div>

    <!--
      空态直接给"下一步该去哪":这一页没有任何新建入口(工作流由「数据探索」另存而来),
      只放一句"还没有工作流"会让人在这一页里找不到出路。
      #description 插槽会覆盖 description 属性,所以正文以插槽为准。
    -->
    <el-empty
      v-if="!loading && !list.length"
      description="还没有工作流"
    >
      <template #description>
        <p>还没有工作流。</p>
        <p class="hint">
          到「数据探索」里配置好多表关联(选表 → 配关联键 → 选标签),再点
          <b>保存为工作流</b> 就能在这里运行。
        </p>
      </template>
    </el-empty>

    <el-table v-else v-loading="loading" :data="list" border stripe scrollbar-always-on class="wt-table">
      <el-table-column label="任务" min-width="220">
        <template #default="{ row }">
          <div class="name">{{ row.name }}</div>
          <div v-if="row.description" class="desc">{{ row.description }}</div>
        </template>
      </el-table-column>

      <!-- 目标表:只给表名(库固定为 dwd_data,副标题里带出排序键/源数/标签数,省一次点开) -->
      <el-table-column label="目标表" min-width="200">
        <template #default="{ row }">
          <span class="mono">{{ row.targetDb }}.{{ row.targetTable }}</span>
          <div class="desc">
            排序键 <span class="mono">{{ row.orderKey || '—' }}</span>
            · 源表 {{ (row.config?.sources || []).length }} 张
            · 标签 {{ visibleFieldCount(row) }} 个
          </div>
        </template>
      </el-table-column>

      <!-- 关联键:一行一个源列 → 目标列别名,多源时能一眼看出是往哪个列上并 -->
      <el-table-column label="关联键" min-width="150">
        <template #default="{ row }">
          <div v-for="r in row.config?.mdnRules || []" :key="r.sourceIdx" class="join-rule">
            <span class="mono">{{ r.sourceCol }}</span>
            <span class="muted">→ {{ r.alias }}</span>
          </div>
          <span v-if="!(row.config?.mdnRules || []).length" class="muted">—</span>
        </template>
      </el-table-column>

      <!-- 最近运行:行数 + 时间 + 失败原因截断展示(错误可能很长,完整内容在浮动提示里) -->
      <el-table-column label="最近运行" width="190">
        <template #default="{ row }">
          <template v-if="row.lastRunAt">
            <el-tag :type="statusType(row.lastStatus)" size="small" effect="light">
              {{ statusText(row.lastStatus) }}
            </el-tag>
            <span class="muted"> {{ row.lastRows || 0 }} 行</span>
            <div class="desc">{{ fmtDt(row.lastRunAt) }}</div>
            <el-tooltip v-if="row.lastError" :content="row.lastError" placement="top">
              <div class="err-line">{{ row.lastError }}</div>
            </el-tooltip>
          </template>
          <span v-else class="muted">未运行</span>
        </template>
      </el-table-column>

      <!-- 启用开关直接落库(改完即生效),按统一规范做二次确认(取消时开关保持原值) -->
      <el-table-column label="启用" width="80" align="center">
        <template #default="{ row }">
          <el-switch
            :model-value="row.enabled"
            size="small"
            @change="(v: boolean | string | number) => toggleEnabled(row, Boolean(v))"
          />
        </template>
      </el-table-column>

      <el-table-column label="操作" width="200" align="center">
        <template #default="{ row }">
          <!-- 停用的任务不让运行(与后端一致);loading 只按当前行,别的行仍可操作 -->
          <el-button
            type="primary"
            size="small"
            :loading="runningId === row.id"
            :disabled="!row.enabled"
            @click="run(row)"
          >
            运行
          </el-button>
          <el-button size="small" @click="openRuns(row)">历史</el-button>
          <!-- text 样式:删除是低频且需要确认的操作,不给它同等的视觉重量 -->
          <el-button type="danger" size="small" text @click="removeTask(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 运行历史:抽屉而不是新页面,看完即关,不打断列表上下文 -->
    <el-drawer v-model="runsOpen" :title="`运行历史 · ${current?.name || ''}`" size="56%">
      <el-table v-loading="runsLoading" :data="runs" border size="small">
        <el-table-column label="时间" width="170">
          <template #default="{ row }">
            <div>{{ fmtDt(row.startedAt) }}</div>
            <div class="desc">{{ row.finishedAt ? fmtDt(row.finishedAt) : '进行中' }}</div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="statusType(row.status)" size="small" effect="light">
              {{ statusText(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="行数" width="90" prop="rows" />
        <el-table-column label="说明">
          <template #default="{ row }">
            <span v-if="row.error" class="err-text">{{ row.error }}</span>
            <span v-else class="muted">成功</span>
            <!-- 本次执行的 SQL 默认收起:它是排障用的,平时展开会淹没错误信息 -->
            <el-collapse v-if="row.sql" class="sql-box">
              <el-collapse-item title="本次执行的 SQL" name="sql">
                <pre class="sql">{{ row.sql }}</pre>
              </el-collapse-item>
            </el-collapse>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!runsLoading && !runs.length" description="暂无运行记录" />
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
/**
 * 工作流(宽表)列表 —— 「数据探索」里另存出来的多表关联任务的运行入口。
 *
 * 这一页只读列表 + 触发运行,不提供编辑:
 * 口径(选表 / 关联键 / 标签)在探索向导里配,这里改会分叉出两套配置。
 *
 * 运行的核心保证是【全量重建 + 原子换名】:先灌临时表,成功后再换名,
 * 所以中途失败时生产宽表仍是上一次成功的那份 —— 文案与提示都围绕这条写。
 */
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { wideTablesApi } from '@/api'
import type { WideTableRun, WideTableTask } from '@/types'
import { fmtDt } from '@/utils/format'

/** 宽表固定落在这个库(库名对用户隐藏,见 utils/layer.ts 的约定) */
const defaultDb = 'dwd_data'

const loading = ref(false)
const list = ref<WideTableTask[]>([])
/** 正在运行的任务 id(用于按行显示 loading,避免把整页都转圈) */
const runningId = ref<number | null>(null)

const runsOpen = ref(false)
const runsLoading = ref(false)
const runs = ref<WideTableRun[]>([])
/** 抽屉的归属任务:标题与拉取都按它,避免"开着 A 的历史又点了 B"时串数据 */
const current = ref<WideTableTask | null>(null)

/** 「不输出」的字段不落进宽表,计数时要排除(否则界面数字与表的列数对不上)。 */
function visibleFieldCount(row: WideTableTask) {
  return (row.config?.fields || []).filter((f) => !f.hidden).length
}

/** 执行状态 → Element Plus 的 tag 类型;未知状态兜 info 而不是留空,免得标签没颜色 */
function statusType(s?: string) {
  if (s === 'success') return 'success'
  if (s === 'failed') return 'danger'
  if (s === 'running') return 'warning'
  return 'info'
}

/** 执行状态 → 中文;空(从未运行)与未知都落到「未运行」 */
function statusText(s?: string) {
  if (s === 'success') return '成功'
  if (s === 'failed') return '失败'
  if (s === 'running') return '运行中'
  return '未运行'
}

async function load() {
  loading.value = true
  try {
    list.value = await wideTablesApi.list()
  } catch (e) {
    // 这里自己提示而不是靠拦截器:列表是唯一内容,失败了必须说话,否则只剩一个空表格
    ElMessage.error('加载工作流失败:' + (e as Error).message)
  } finally {
    loading.value = false
  }
}

async function run(row: WideTableTask) {
  // ⚠️ 先体检再跑:放大【不会报错】,只会让宽表行数翻很多倍、数据变乱,
  //    可能很久才发现。所以必须先把"会变成多少行"说清楚,再让用户决定。
  if (!(await confirmIfAmplified(row))) return
  // 运行会重建目标宽表,按统一规范再确认一次
  try {
    await ElMessageBox.confirm(`确认运行宽表任务「${row.name}」？目标表「${row.targetTable}」会按当前配置重建。`, '运行宽表任务', {
      type: 'warning', confirmButtonText: '确认运行', cancelButtonText: '取消',
    })
  } catch { return }
  runningId.value = row.id
  try {
    const r = await wideTablesApi.run(row.id)
    ElMessage.success(`重建完成:${r.rows} 行`)
    await load()
  } catch (e) {
    // ⚠️ 失败时**不能**只说"运行失败":要给出原因(表不存在/字段改名/权限…),
    //    否则用户唯一能做的就是瞎试。后端把 CK 的原始错误带在 message 里。
    ElMessage({
      type: 'error',
      duration: 8000,
      showClose: true,
      message: '重建失败:' + (e as Error).message,
    })
    await load()
  } finally {
    runningId.value = null
  }
}

/**
 * 运行前体检;有放大风险时让用户确认。返回 false = 用户选择先去调整。
 *
 * 体检本身失败(权限不足/表临时读不到)【不能】阻止运行 ——
 * 那会把一个辅助功能变成硬阻断;提示一句后继续跑。
 */
async function confirmIfAmplified(row: WideTableTask): Promise<boolean> {
  try {
    const c = await wideTablesApi.check(row.id)
    if (!c.warnings?.length) return true
    await ElMessageBox.confirm(c.warnings.join('\n\n'), '运行前体检', {
      type: 'warning',
      confirmButtonText: '仍然运行',
      cancelButtonText: '我去调整',
      // 放大是"数据会不对"而不是"操作有危险",所以文案说清后果而不是吓人
    })
    return true
  } catch (e) {
    if (e === 'cancel' || e === 'close') return false // 用户在确认框里选了"我去调整"
    ElMessage.warning('体检未完成,继续运行:' + (e as Error).message)
    return true
  }
}

/**
 * 开关启用状态。提交的是【整份配置】(接口按覆盖语义处理),
 * 所以 name / config / target 这些没改的字段也要原样带上,漏一个就会被清掉。
 */
async function toggleEnabled(row: WideTableTask, v: boolean) {
  try {
    await ElMessageBox.confirm(`确认${v ? '启用' : '停用'}宽表任务「${row.name}」？`, '启停宽表任务', {
      type: 'warning', confirmButtonText: v ? '确认启用' : '确认停用', cancelButtonText: '取消',
    })
  } catch { return }
  try {
    await wideTablesApi.update(row.id, {
      name: row.name,
      description: row.description,
      enabled: v,
      config: row.config,
      targetDb: row.targetDb,
      targetTable: row.targetTable,
      orderKey: row.orderKey,
    })
    // 成功后只改本地这一行的开关;整表重拉会丢掉当前滚动位置
    row.enabled = v
  } catch (e) {
    ElMessage.error('更新失败:' + (e as Error).message)
  }
}

/**
 * 删除任务。这里不走 confirmDestructive:那个文案是给"数据会没"的操作用的,
 * 而本操作只删配置与运行记录,宽表本身留着 —— 文案必须说清这一点,否则用户不敢删。
 */
async function removeTask(row: WideTableTask) {
  try {
    await ElMessageBox.confirm(
      `删除任务「${row.name}」?` + '\n\n只删除任务与运行记录,已生成的宽表不会被删除。',
      '删除确认',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch {
    return // 用户取消
  }
  try {
    await wideTablesApi.remove(row.id)
    ElMessage.success('已删除')
    await load()
  } catch (e) {
    ElMessage.error('删除失败:' + (e as Error).message)
  }
}

async function openRuns(row: WideTableTask) {
  current.value = row
  runsOpen.value = true
  runsLoading.value = true
  // 先清空再拉:上一次任务的历史不能停在新任务的抽屉里(哪怕只停一瞬)
  runs.value = []
  try {
    runs.value = await wideTablesApi.runs(row.id)
  } catch (e) {
    ElMessage.error('加载运行历史失败:' + (e as Error).message)
  } finally {
    runsLoading.value = false
  }
}

onMounted(load)
</script>

<style scoped>
/* 本页被嵌在工作台的容器里(外层已有内边距),所以自带留白要小,否则会明显内缩 */
.wt-view {
  padding: 4px 2px;
}
.page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 14px;
}
.page-head h2 {
  margin: 0 0 6px;
  font-size: 18px;
}
/* 页头说明比较长(要讲清全量重建与原子换名),限宽 + 放大行距才读得下去 */
.page-sub {
  margin: 0;
  max-width: 760px;
  line-height: 1.7;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
.wt-table {
  margin-top: 4px;
}
.name {
  font-weight: 600;
}
/* 所有副标题/补充信息共用:小一号、灰一档,保证主信息(表名)始终最突出 */
.desc {
  margin-top: 2px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.muted {
  color: var(--el-text-color-secondary);
}
/* 表名 / 列名必须是等宽:库表名里下划线和大小写都要能看清 */
.mono {
  font-family: var(--el-font-family-mono, ui-monospace, monospace);
  font-size: 12px;
}
.join-rule {
  font-size: 12px;
  line-height: 1.6;
}
/* 失败原因单行截断:表格行高不能被一段长报错撑起来,完整内容在 tooltip 里 */
.err-line {
  margin-top: 2px;
  max-width: 180px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--el-color-danger);
  font-size: 12px;
}
.err-text {
  color: var(--el-color-danger);
}
.sql-box {
  margin-top: 6px;
}
/*
  SQL 展示:pre-wrap + break-all —— SQL 里有很长的列名与函数调用,
  不折行的话横向滚动条会一直拖,排障时很难读;限高避免一条 SQL 撑满抽屉。
*/
.sql {
  margin: 0;
  max-height: 240px;
  overflow: auto;
  font-size: 12px;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-all;
}
.hint {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
</style>
