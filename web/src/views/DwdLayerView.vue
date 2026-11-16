<!--
  DwdLayerView.vue

  职责：DWD 明细层页面：浏览目标表、触发摊平并观察后台任务状态。
  边界：浏览 DWD 目标表并触发摊平或重算；后台观察使用 AbortController，导航变化和组件卸载都会停止旧轮询。
-->

<template>
  <div class="dwd-view">
    <!-- 右:当前分组下表列表 -->
    <main class="dwd-main">
      <div class="dwd-head">
        <div>
          <!-- 只写表名不写库名:库(dwd_data)对用户是隐藏概念,见 utils/layer.ts 的约定 -->
          <h2>目标库表（dwd_data）</h2>
        </div>
      </div>

      <el-tabs v-model="tab" class="dwd-tabs">
        <!--
          ═══ 页1:宽表(默认)═══
          全平台汇总的那一张:所有贴源表并成一个大宽表,一个号码一行。
          这里不放列表 —— 直接就是数据(圈选/画像/导出都在),因为这张表只有一张,
          给它做一层列表等于让用户每天多点一次。
          数据来自被标记为「宽表」的那个工作流(口径在向导里看得见、改得动)。
        -->
        <el-tab-pane v-if="WIDE_TABLE_ENABLED" name="wide" label="宽表">
          <template v-if="wideWf && wideReady">
            <div class="dwd-wide-bar">
              <span class="dim">
                来自工作流「{{ wideWf.name }}」· {{ wideWf.sources?.length || 0 }} 个源 ·
                {{ wideWf.columns?.length || 0 }} 个字段
              </span>
              <span v-if="wideWf.lastRunAt" class="dim">· 最近执行 {{ fmtDt(wideWf.lastRunAt) }}</span>
              <span v-else class="dwd-wide-warn">· 还没执行过</span>
              <span class="ttp-gap"></span>
              <el-button size="small" link type="primary" @click="goWizard">改口径</el-button>
            </div>
            <div class="dwd-wide-body">
              <TargetTablePanel
                :key="`wide-${wideSvc}`"
                :conn-id="connId"
                :db="db"
                :table="wideSvc"
              />
            </div>
          </template>
          <div v-else class="dwd-wide-empty">
            <template v-if="wideWf">
              <h3>宽表已配好，但还没有数据</h3>
              <p>
                工作流「{{ wideWf.name }}」已标记为宽表，但它的服务表还没建出来 ——
                去工作流里点「保存并执行」，建好后这个页签就直接显示数据了。
              </p>
              <el-button type="primary" @click="goWizard">去执行</el-button>
            </template>
            <template v-else>
              <h3>还没有宽表</h3>
              <p>
                宽表 = 把所有贴源表并成一张大宽表（一个号码一行，字段按"非空 &gt; 优先级 &gt; 时间"仲裁）。
                它本质上就是一个工作流，只是被标记成了「宽表」，由这个页签直接显示。
              </p>
              <div class="dwd-wide-actions">
                <el-button type="primary" :loading="drafting" @click="makeWideDraft">
                  一键生成汇总草稿
                </el-button>
                <el-button @click="goWizard">我自己建</el-button>
              </div>
              <p class="dim">
                草稿会把所有带号码列的贴源表预填进去（列按同名列自动对齐），
                并【先不启用】—— 你确认列名与取值方式后再保存执行。
              </p>
            </template>
          </div>
        </el-tab-pane>

        <!-- ═══ 页2:工作流产出(一行 = 一个目标表) ═══ -->
        <el-tab-pane name="outputs">
          <template #label>
            <span>工作流产出 <span class="dim">{{ workflows.length }}</span></span>
          </template>
          <ListPageTemplate v-model:page="outputPage" v-model:page-size="outputPageSize" :total="shownWorkflows.length">
          <template #header>
          <ListQueryBar
            v-model:keyword="outputQuery.draftKeyword.value"
            keyword-placeholder="搜索工作流或目标表"
            @search="outputQuery.search"
            @reset="resetOutputQuery"
          />
          </template>
          <el-table v-loading="wfLoading" :data="pagedWorkflows" height="100%" border stripe size="small" scrollbar-always-on class="out-table">
            <!-- 目标表 = 服务表(*_svc);点它打开的就是业务查的那张表,工作流名只作副标题 -->
            <el-table-column label="目标表" min-width="230">
              <template #default="{ row }">
                <span class="mono tbl-link" @click="openTable(svcNameOf(row), 'target')">{{ svcNameOf(row) }}</span>
                <div class="dim">{{ row.name }}</div>
              </template>
            </el-table-column>
            <el-table-column label="工作流" width="150">
              <template #default="{ row }">
                <span class="tbl-link" :title="`查看目标表 ${svcNameOf(row)}`" @click="openTable(svcNameOf(row), 'target')">{{ row.name }}</span>
                <el-switch
                  class="out-sw"
                  size="small"
                  :model-value="row.enabled"
                  @change="toggleWf(row)"
                />
              </template>
            </el-table-column>
            <!-- 行内简链:源 N → 聚合表 → 目标表 -->
            <el-table-column label="拓扑" min-width="190">
              <template #default="{ row }">
                <!-- 整块可点:点了直接弹拓扑图(带行数/MV 状态/服务表落后提示) -->
                <div class="out-chain out-click" title="点开看完整拓扑" @click="showTopo(row)">
                  <span class="out-node">源 {{ row.sources?.length || 0 }}</span>
                  <span class="out-arr">→</span>
                  <span class="out-node agg">聚合</span>
                  <span class="out-arr">→</span>
                  <span class="out-node svc">目标</span>
                  <span class="out-more">查看</span>
                </div>
                <div class="dim out-srcs">
                  {{ (row.sources || []).map((x: ArbSource) => x.table).join('、') || '—' }}
                </div>
              </template>
            </el-table-column>
            <!-- 最近执行:只截 40 字展示错误首段(完整内容在 title 里),行高保持一行 -->
            <el-table-column label="最近执行" width="200">
              <template #default="{ row }">
                <div v-if="row.lastRunAt">
                  <el-tag size="small" :type="wfStatusType(row.lastStatus)">
                    {{ wfStatusLabel(row.lastStatus) }}
                  </el-tag>
                  <span class="dim"> {{ fmtNum(row.lastRows) }} 行</span>
                  <div class="dim">{{ fmtDt(row.lastRunAt) }}</div>
                </div>
                <span v-else class="dim">未执行</span>
                <div v-if="row.lastError" class="out-err" :title="row.lastError">
                  {{ String(row.lastError).slice(0, 40) }}…
                </div>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="160" align="center">
              <template #default="{ row }">
                <!-- 四个动作按"看" → "动库"排:打开数据/拓扑/聚合表都是只读,刷新服务表才真的写 -->
                <ActionIconBar :actions="outputActions" :busy="busyIds.has(row.id)" @command="(c: string) => onOutputCommand(c, row)" />
              </template>
            </el-table-column>
            <template #empty>
              <div class="dwd-empty-hint">还没有工作流产出。去「工作台 → 工作流」建一个。</div>
            </template>
          </el-table>
          </ListPageTemplate>
        </el-tab-pane>

        <!--
          ═══ 页3:数据包(圈选落下来的表) ═══
          圈选是「找出这批人」,数据包是「把这批人留下来」——
          留下来了就该有个地方一眼看到:包多大、从哪张目标表圈的、什么条件、什么时候圈的。
          它同时也是普通表(在「全部表」里也在),那张页是兜底,这张页是常用视图。
        -->
        <el-tab-pane name="crowd">
          <template #label>
            <span>数据包 <span class="dim">{{ crowdRows.length }}</span></span>
          </template>
          <ListPageTemplate v-model:page="crowdPage" v-model:page-size="crowdPageSize" :total="shownCrowds.length">
          <template #header>
          <ListQueryBar
            v-model:keyword="crowdQuery.draftKeyword.value"
            keyword-placeholder="搜索数据包、来源或条件"
            @search="crowdQuery.search"
            @reset="resetCrowdQuery"
          />
          </template>
          <el-table v-loading="loadingTables" :data="pagedCrowds" height="100%" border stripe size="small" scrollbar-always-on>
            <el-table-column label="数据包" min-width="220">
              <template #default="{ row }">
                <span class="mono tbl-link" @click="openTable(row.name, 'crowd')">{{ row.name }}</span>
              </template>
            </el-table-column>
            <el-table-column label="行数" width="110" align="right">
              <template #default="{ row }">{{ fmtNum(statOf(row.name)?.rows) }}</template>
            </el-table-column>
            <!-- 来源与圈选条件都从【表注释】解析(见脚本里 crowdRows 的说明);早期建的包没有这两段,给一句解释而不是空白 -->
            <el-table-column label="来源目标表" min-width="180">
              <template #default="{ row }">
                <span v-if="row.source" class="mono">{{ row.source }}</span>
                <span v-else class="dim">—（早期建的，注释里没记来源）</span>
              </template>
            </el-table-column>
            <el-table-column label="圈选条件" min-width="240" show-overflow-tooltip>
              <template #default="{ row }">
                <span v-if="row.cond" class="mono">{{ row.cond }}</span>
                <span v-else class="dim">—</span>
              </template>
            </el-table-column>
            <el-table-column label="创建时间" width="160" align="center">
              <template #default="{ row }">
                <span v-if="row.createdAt">{{ row.createdAt }}</span>
                <span v-else class="dim">—</span>
              </template>
            </el-table-column>
            <!-- 数据包是普通表,删除走二次确认(建包可能花了很久,误删很痛) -->
            <el-table-column label="操作" width="150" align="center">
              <template #default="{ row }">
                <el-button size="small" type="primary" link @click="openTable(row.name, 'crowd')">
                  打开
                </el-button>
                <el-button size="small" type="danger" link @click="dropCrowd(row.name)">删除</el-button>
              </template>
            </el-table-column>
            <template #empty>
              <div class="dwd-empty-hint">
                还没有数据包。到「工作流产出」打开目标表，圈选一批人后点右上角「存为数据包」。
              </div>
            </template>
          </el-table>
          </ListPageTemplate>
        </el-tab-pane>

        <!-- ═══ 页4:全部表(裸表浏览器) ═══ -->
        <el-tab-pane name="all" label="全部表">
      <ListPageTemplate v-model:page="page" v-model:page-size="pageSize" :total="shownTables.length">
      <template #header>
      <!--
        身份分类:库里混着三种东西 —— 服务表(给业务查)、聚合表(工作流的中间层)、
        物化视图(管道,没人需要看)。默认把物化视图收起来,26 个对象立刻只剩 11 个。
      -->
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
        @search="applyTableQuery"
        @reset="resetTableQuery"
        @new="gmgNew"
        @edit="gmgSelect"
        @remove="gmgRemove"
      >
        <template #extra>
          <span class="dwd-kind-label">表类型</span>
          <el-select v-model="queryKindFilter" class="dwd-kind-select">
            <el-option value="all" label="全部" />
            <el-option value="svc" :label="`服务表 · ${counts.svc}`" />
            <el-option value="agg" :label="`聚合表 · ${counts.agg}`" />
            <el-option value="plain" :label="`其他 · ${counts.plain}`" />
            <el-option value="mv" :label="`物化视图 · ${counts.mv}`" />
          </el-select>
        </template>
      </ListQueryBar>

      <div class="list-table-ops">
        <div class="list-table-ops-start">
          <el-button type="primary" :icon="Plus" @click="openCreateTable">新建目标表</el-button>
        </div>
      </div>

      </template>
      <div class="dwd-tbl-wrap" v-loading="loadingTables">
        <el-table :data="pagedTables" border stripe size="small" height="100%" scrollbar-always-on class="dwd-table">
          <el-table-column type="index" :index="globalIndex" width="60" align="center" label="序号" />
          <el-table-column prop="name" label="表名" min-width="160">
            <template #default="{ row }">
              <el-tooltip
                :content="row.remark"
                placement="top-start"
                :show-after="300"
                :disabled="!row.remark"
              >
                <span v-if="isMv(row)" class="mono" title="物化视图是写入管道；请在工作流产出中查看服务表数据">{{ row.name }}</span>
                <span v-else class="mono tbl-link" @click="openTable(row.name)">{{ row.name }}</span>
              </el-tooltip>
              <el-tag
                v-if="kindLabel(row)"
                size="small"
                effect="plain"
                :type="kindTagType(row)"
                class="tbl-kind"
              >{{ kindLabel(row) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="engine" label="引擎" min-width="120">
            <template #default="{ row }">
              <el-tooltip :content="engineDesc(row.engine)" placement="top" :show-after="250" :disabled="!engineDesc(row.engine)">
                <span class="mono tbl-engine">{{ row.engine }}</span>
              </el-tooltip>
            </template>
          </el-table-column>
          <el-table-column prop="remark" label="表注释" min-width="190" show-overflow-tooltip>
            <template #default="{ row }">
              <span v-if="row.remark" :title="row.remark">{{ row.remark }}</span>
              <span v-else class="dim">—</span>
            </template>
          </el-table-column>
          <el-table-column prop="creatorName" label="创建者" width="120" show-overflow-tooltip>
            <template #default="{ row }">
              <span :class="{ dim: !row.creatorName }">{{ row.creatorName || '历史数据' }}</span>
              <el-tag v-if="row.accessMode === 'read'" size="small" type="info" class="tbl-kind">只读</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="列数" width="70" align="right">
            <template #default="{ row }">{{ statOf(row.name)?.cols ?? '-' }}</template>
          </el-table-column>
          <el-table-column label="行数" width="110" align="right">
            <template #default="{ row }">{{ fmtNum(statOf(row.name)?.rows) }}</template>
          </el-table-column>
          <el-table-column label="磁盘大小" width="100" align="right">
            <template #default="{ row }">{{ fmtBytes(statOf(row.name)?.bytes) }}</template>
          </el-table-column>
          <el-table-column label="所属分组" min-width="120">
            <template #default="{ row }">
              <el-select
                :model-value="groupOf(row.name)"
                size="small"
                :disabled="row.canManage === false"
                placeholder="默认分组"
                style="width: 120px"
                @change="(v: any) => moveTable(row.name, Number(v))"
              >
                <el-option :value="0" label="默认分组" />
                <el-option v-for="g in groups" :key="g.id" :value="g.id" :label="g.name" />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="150" align="center">
            <template #default="{ row }">
              <!-- 物化视图是管道:它自己不存数据(SELECT 出来是空的),也不能 OPTIMIZE -->
              <template v-if="isMv(row)">
                <span class="dim">管道，由工作流维护</span>
              </template>
              <template v-else>
                <el-button size="small" type="primary" link @click="openTable(row.name)">打开数据</el-button>
                <el-button v-if="row.canShare" size="small" link type="primary" @click="openShare(row)">共享</el-button>
              </template>
            </template>
          </el-table-column>
          <template #empty>
            <div class="dwd-empty-hint">{{ kindFilter === 'all' && counts.mv ? `暂无表（已收起 ${counts.mv} 个物化视图，点上方「物化视图」可看）` : '该分类下暂无表' }}</div>
          </template>
        </el-table>
      </div>
      </ListPageTemplate>
        </el-tab-pane>
      </el-tabs>
    </main>

    <!-- 查看表数据(留在本页,不跳转) -->
    <!-- 工作流拓扑(带运行状态):行内只给简链,完整图在这里 -->
    <el-dialog v-model="topoVisible" title="工作流拓扑" width="860px" top="5vh">
      <ArbTopology
        v-if="topoRow"
        :sources="topoRow.sources || []"
        :columns="topoRow.columns || []"
        :agg-table-name="topoRow.targetTable + '_agg'"
        :svc-table-name="topoRow.targetTable + '_svc'"
        :probe="topoProbe"
      />
    </el-dialog>

    <!-- 头部自带「关闭」按钮 → 关掉默认的 ×(否则两个关闭入口) -->
    <el-drawer v-model="viewOpen" :title="`数据 · ${viewTable}`" size="86%" :with-header="true" :show-close="false" destroy-on-close>
      <template #header>
        <div class="dwd-drawer-head">
          <!-- 只显示表名:库的概念对用户隐藏(见 utils/layer.ts 的约定) -->
          <span class="dwd-drawer-title">
            {{ viewMode === 'target' ? '目标表' : viewMode === 'crowd' ? '数据包' : viewMode === 'agg' ? '聚合表' : '表数据' }} ·
            <span class="mono">{{ viewTable }}</span>
          </span>
          <el-button size="small" :icon="Close" @click="viewOpen = false">关闭</el-button>
        </div>
      </template>
      <!--
        两条路用两个面板:
          · 工作流产出 / 数据包 → 给「圈选 + 画像」用(TargetTablePanel,只读)
            —— 数据包走的也是它:可以再圈一次做出子数据包,这比导出 CSV 再导回来实用
          · 全部表 → 通用表浏览器(TableDetail,可增删改/看结构/DDL)
      -->
      <TargetTablePanel
        v-if="connReady && viewTable && (viewMode === 'target' || viewMode === 'crowd')"
        :key="`ttp-${viewTable}`"
        :conn-id="connId"
        :db="db"
        :table="viewTable"
      />
      <TableDetail
        v-else-if="connReady && viewTable"
        :key="viewTable"
        :conn-id="connId"
        :db="db"
        :table="viewTable"
        :readonly="viewReadonly || viewMeta?.canWrite === false"
        :can-manage="viewMeta?.canManage"
        :can-drop="viewMeta?.canDrop"
        @deleted="onViewDeleted"
        @renamed="onViewRenamed"
        @updated="loadTables"
      />
    </el-drawer>

    <!-- 分组管理对话框(新建/编辑) -->
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

    <CreateTableDialog v-model="createVisible" :conn-id="connId" :db="db" @created="onTableCreated" />
    <ShareTableDialog v-model="shareVisible" :asset-id="shareAssetId" :table-name="shareTableName" @changed="loadTables" />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Close, Plus } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import CreateTableDialog from '@/components/CreateTableDialog.vue'
import TableDetail from '@/components/TableDetail.vue'
import { arbWorkflowsApi, connectionsApi, workspaceApi } from '@/api'
import ArbTopology from '@/components/arb/ArbTopology.vue'
import TargetTablePanel from '@/components/arb/TargetTablePanel.vue'
import ShareTableDialog from '@/components/ShareTableDialog.vue'
import type { TableInfo,  ArbProbe, ArbSource, ArbWorkflow, TableGroup, TableStat } from '@/types'
import ListQueryBar from '@/components/ListQueryBar.vue'
import ListPageTemplate from '@/components/ListPageTemplate.vue'
import { useListQuery } from '@/composables/useListQuery'
import { fmtDt } from '@/utils/format'
import { arbParseCrowd, arbWideDraft, type WideCandidate } from '@/utils/arbFields'
import { WIDE_TABLE_ENABLED } from '@/utils/features'
import { confirmAction, confirmDestructive } from '@/utils/confirm'
import { useNavReset } from '@/composables/useNavReset'

import ActionIconBar from '@/components/ActionIconBar.vue'

const outputActions = [
  { command: 'data', label: '打开数据（圈选 / 画像）', primary: true },
  { command: 'topology', label: '查看拓扑' },
  { command: 'agg', label: '查看聚合表' },
  { command: 'flatten', label: '刷新服务表' },
]

const db = 'dwd_data'
const route = useRoute()
const router = useRouter()

/**
 * 页1 = 宽表(默认,直接看数据);页2 = 工作流产出;页3 = 数据包;页4 = 全部表。
 *
 * 为什么宽表排最前且默认打开:它是一张表 —— 每天进来看数据的人最多,
 * 不该先越过一层列表。
 */
// 宽表归档后默认落在「工作流产出」——否则第一个页签是关着的,打开就是空白
const tab = ref<'wide' | 'outputs' | 'crowd' | 'all'>(WIDE_TABLE_ENABLED ? 'wide' : 'outputs')

// ─────────── 宽表(被标记为「宽表」的那个工作流的服务表) ───────────
const wideWf = computed(() => workflows.value.find((w) => w.isWide))
const wideSvc = computed(() => (wideWf.value ? `${wideWf.value.targetTable}_svc` : ''))
/** 服务表已经建出来了才显示数据 —— 光有配置没执行过的话点了也是报错 */
const wideReady = computed(() => !!wideSvc.value && tables.value.some((t) => t.name === wideSvc.value))
const drafting = ref(false)

function goWizard() {
  router.push('/workflows')
}

/**
 * 一键生成汇总草稿:把所有带号码列的贴源表预填进一个新工作流。
 *
 * 只做到"草稿"为止 —— 草稿是【不启用】的,列名与取值方式都要人确认。
 * 取值方式一律先给仲裁:计数/金额类该不该 sum 是业务判断,猜错会静默算错。
 */
async function makeWideDraft() {
  drafting.value = true
  try {
    const tbs = await workspaceApi.tables(connId.value, 'ods_data')
    const cands: WideCandidate[] = []
    for (const t of tbs) {
      if (isMv(t)) continue
      try {
        const sch = await workspaceApi.schema(connId.value, 'ods_data', t.name)
        const iName = sch.columns.indexOf('name')
        const iType = sch.columns.indexOf('type')
        const iCmt = sch.columns.indexOf('comment')
        cands.push({
          database: 'ods_data',
          table: t.name,
          cols: sch.rows.map((r) => ({
            name: String(r[iName >= 0 ? iName : 0] ?? ''),
            type: String(r[iType >= 0 ? iType : 1] ?? ''),
            comment: iCmt >= 0 ? String(r[iCmt] ?? '') : '',
          })),
        })
      } catch {
        /* 单张表读不到就跳过,不影响整份草稿 */
      }
    }
    const draft = arbWideDraft(connId.value, cands)
    if (!draft) {
      ElMessage.warning('没有找到带号码列的贴源表，没法自动汇总')
      return
    }
    if (!(await confirmAction('生成汇总草稿', `确认按 ${draft.sources.length} 个贴源表生成工作流草稿「${draft.name}」？草稿默认不启用。`, '确认生成'))) return
    const created = await arbWorkflowsApi.create(draft)
    ElMessage.success(`已生成草稿「${created.name}」（未启用，${draft.sources.length} 个源）—— 去工作流确认字段与取值方式`)
    await loadWorkflows()
  } catch (e: any) {
    ElMessage.error(e?.message || '生成草稿失败')
  } finally {
    drafting.value = false
  }
}

// ─────────── 数据包(圈选落下来的表) ───────────
//
// 认包靠【表注释】(建包时后端写进去的:数据包｜来源 …｜时间｜条件 …),
// 名字以 crowd_ 开头的老表兜底认。所以这里不需要任何自己的存储 ——
// 表本身就是记录,在「全部表」里被删掉,这里自然也就没了。
const crowdRows = computed(() =>
  tables.value
    .map((t) => ({ name: t.name, ...arbParseCrowd(t.name, t.remark) }))
    .filter((x) => x.isCrowd)
    // 新包排前面(按创建时间倒序;没有时间的按名字排)
    .sort((a, b) => (b.createdAt || '').localeCompare(a.createdAt || '') || a.name.localeCompare(b.name))
)
const crowdQuery = useListQuery()
const shownCrowds = computed(() => {
  const words = crowdQuery.keyword.value.toLowerCase().split(/\s+/).filter(Boolean)
  if (!words.length) return crowdRows.value
  return crowdRows.value.filter((row) => {
    const text = `${row.name} ${row.source ?? ''} ${row.cond ?? ''}`.toLowerCase()
    return words.every((word) => text.includes(word))
  })
})

const crowdPage = ref(1)
const crowdPageSize = ref(10)
const pagedCrowds = computed(() => shownCrowds.value.slice((crowdPage.value - 1) * crowdPageSize.value, crowdPage.value * crowdPageSize.value))
watch(crowdQuery.keyword, () => { crowdPage.value = 1 })

async function reloadCrowds() {
  await loadTables()
  await loadStats()
}

function resetCrowdQuery() {
  crowdQuery.reset()
  crowdPage.value = 1
  void reloadCrowds()
}

/** 删数据包 —— 破坏性操作,按项目惯例走二次确认 */
async function dropCrowd(name: string) {
  // 只报表名:物理库名对用户是隐藏概念(见 utils/layer.ts 的约定)
  if (!(await confirmDestructive('删除数据包', name, '删除'))) return
  try {
    await workspaceApi.dropTable(connId.value, db, name)
    ElMessage.success(`已删除 ${name}`)
    await reloadCrowds()
  } catch (e: any) {
    ElMessage.error(e?.message || '删除失败')
  }
}

// ─────────── 工作流产出 ───────────
const workflows = ref<ArbWorkflow[]>([])
const outputQuery = useListQuery()
const shownWorkflows = computed(() => {
  const words = outputQuery.keyword.value.toLowerCase().split(/\s+/).filter(Boolean)
  if (!words.length) return workflows.value
  return workflows.value.filter((row) => {
    const text = `${row.name} ${row.targetTable} ${(row.sources ?? []).map((source) => source.table).join(' ')}`.toLowerCase()
    return words.every((word) => text.includes(word))
  })
})
const outputPage = ref(1)
const outputPageSize = ref(10)
const pagedWorkflows = computed(() => shownWorkflows.value.slice((outputPage.value - 1) * outputPageSize.value, outputPage.value * outputPageSize.value))
watch(outputQuery.keyword, () => { outputPage.value = 1 })
const wfLoading = ref(false)
/** 当前正在执行摊平观察的工作流 id；它驱动该行忙碌态，不代表后端任务的全局锁。 */
const busyIds = ref(new Set<number>())
const topoVisible = ref(false)
const topoRow = ref<ArbWorkflow | null>(null)
const topoProbe = ref<ArbProbe | null>(null)

/** 目标表(业务查的那张)= 摊平后的普通列 */
function svcNameOf(w: ArbWorkflow) {
  return `${w.targetTable}_svc`
}
function wfStatusType(s?: string) {
  return s === 'success' ? 'success' : s === 'failed' ? 'danger' : s === 'running' ? 'warning' : 'info'
}
function wfStatusLabel(s?: string) {
  return s === 'success' ? '成功' : s === 'failed' ? '失败' : s === 'running' ? '执行中' : '未执行'
}

async function loadWorkflows() {
  wfLoading.value = true
  try {
    workflows.value = await arbWorkflowsApi.list()
  } catch (e: any) {
    ElMessage.error(e?.message || '加载工作流失败')
  } finally {
    wfLoading.value = false
  }
}

function resetOutputQuery() {
  outputQuery.reset()
  outputPage.value = 1
  void loadWorkflows()
}

/** 工作流产出那一行的操作下拉 */
function onOutputCommand(cmd: string, row: ArbWorkflow) {
  if (cmd === 'data') openTable(svcNameOf(row), 'target')
  else if (cmd === 'topology') void showTopo(row)
  else if (cmd === 'flatten') void flatten(row)
  else if (cmd === 'agg') openTable(`${row.targetTable}_agg`, 'agg')
}

async function showTopo(row: ArbWorkflow) {
  topoRow.value = row
  topoProbe.value = null
  topoVisible.value = true
  try {
    topoProbe.value = await arbWorkflowsApi.probe(row.id)
  } catch {
    topoProbe.value = null
  }
}

/**
 * 每次摊平都建立一个 AbortController 并加入集合，组件卸载时统一 abort。
 * AbortSignal 只让启动/轮询请求退出，不能撤销服务端已经开始的摊平任务。
 */
const flattenObservers = new Set<AbortController>()
onUnmounted(() => { for (const observer of flattenObservers) observer.abort() })
/**
 * 用户确认后按 ID 登记 busyIds，再启动后台摊平；API 层按 runId 轮询到终态。
 * 成功后刷新工作流和统计；后台失败展示实际原因，只有页面离开的观察取消不提示。
 * finally 只移除本次 controller 和 ID，其他工作流仍保持忙碌状态。
 */
async function flatten(row: ArbWorkflow) {
  if (busyIds.value.has(row.id)) return
  if (!(await confirmAction('刷新服务表', `确认对工作流「${row.name}」执行摊平？会按聚合层当前数据整体覆盖服务表 ${row.targetTable}_svc。`, '确认刷新'))) return
  if (busyIds.value.has(row.id)) return
  busyIds.value.add(row.id)
  const observer = new AbortController()
  flattenObservers.add(observer)
  try {
    const r = await arbWorkflowsApi.flatten(row.id, observer.signal)
    ElMessage.success(`服务表已刷新：${fmtNum(r.rows)} 行`)
    await loadWorkflows()
    await loadStats()
  } catch (e: any) {
    if (!observer.signal.aborted) ElMessage.error(e?.message || '启动或查询任务失败')
  } finally {
    flattenObservers.delete(observer)
    busyIds.value.delete(row.id)
  }
}

async function toggleWf(row: ArbWorkflow) {
  const next = !row.enabled
  if (!(await confirmAction('启停工作流', `确认${next ? '启用' : '停用'}工作流「${row.name}」？`, next ? '确认启用' : '确认停用'))) return
  try {
    await arbWorkflowsApi.update(row.id, {
      name: row.name,
      description: row.description,
      enabled: !row.enabled,
      connectionId: row.connectionId,
      targetDb: row.targetDb,
      targetTable: row.targetTable,
      sources: row.sources,
      fields: row.fields,
      columns: row.columns,
    })
    await loadWorkflows()
  } catch (e: any) {
    ElMessage.error(e?.message || '切换失败')
  }
}

/** 行数千分位(宽表动辄千万行) */
function fmtNum(n: number | null | undefined): string {
  if (n === undefined || n === null) return '-'
  return n.toLocaleString('en-US')
}
/** 字节 → 人类可读;1024 进制逐级降,到 TB 封顶(表数据不会到 PB 量级) */
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
    '在 MergeTree 基础上,按版本或写入顺序去重(同排序键只保留最新)。适合"同一主键只留最新"的增量场景。',
  SummingMergeTree: '在 MergeTree 基础上,合并时把同排序键的数值列自动求和。适合累加/汇总计数类数据。',
  AggregatingMergeTree: '在 MergeTree 基础上,合并时按聚合函数预聚合。适合需持续聚合统计的大表。',
  CollapsingMergeTree: '用 ±1 的行对做"折叠":同排序键的正负行合并时抵消删除。适合需要频繁增删的实时报表。',
  VersionedCollapsingMergeTree: '在折叠引擎上加版本号,能按版本正确折叠。适合"先减旧版本再加新版本"的数据。',
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

/** 引擎名带括号参数(如 ReplicatedMergeTree(...))时,去掉参数再查表;查不到给一句通用说明 */
function engineDesc(engine: string): string {
  if (!engine) return ''
  const base = engine.replace(/\s*\(.*/, '').trim()
  if (ENGINE_DESC[base]) return ENGINE_DESC[base]
  return `表引擎:${base}。决定数据如何存储与合并。`
}

/** 表清单/统计都靠它;0 = 还没解析出来,此时所有 loader 直接返回(见 connReady 判断) */
const connId = ref(0)
const connReady = ref(false)
const tables = ref<TableInfo[]>([])
const groups = ref<TableGroup[]>([])
const stats = ref<Record<string, TableStat>>({})
const loadingTables = ref(false)
const query = useListQuery()
const kw = query.keyword
const filter = query.group
/** 身份筛选;默认「全部」但物化视图要单独点(它们是管道,平时不用看) */
const kindFilter = ref<'all' | 'svc' | 'agg' | 'plain' | 'mv'>('all')
const queryKindFilter = ref<typeof kindFilter.value>('all')
function applyTableQuery() {
  query.search()
  kindFilter.value = queryKindFilter.value
}
function resetTableQuery() {
  query.reset()
  queryKindFilter.value = 'all'
  kindFilter.value = 'all'
  void reloadAll()
}

/**
 * 表身份 —— 库里混着三种东西,不给标签用户分不清哪个能查:
 *
 *   svc   * _svc 结尾 = 摊平后的普通表,【业务只查这个】
 *   agg   AggregatingMergeTree 或 _agg 结尾 = 工作流中间层,必须 max(列).4 + GROUP BY
 *   mv    MaterializedView = 管道(源表→聚合表),自己不存数据
 *   plain 其它(手工建的表)
 */
function kindOf(t: { name: string; engine?: string }): 'svc' | 'agg' | 'mv' | 'plain' {
  const eng = t.engine ?? ''
  if (eng === 'MaterializedView') return 'mv'
  if (t.name.endsWith('_svc')) return 'svc'
  if (eng === 'AggregatingMergeTree' || t.name.endsWith('_agg')) return 'agg'
  return 'plain'
}
const isMv = (t: { name: string; engine?: string }) => kindOf(t) === 'mv'
function kindLabel(t: { name: string; engine?: string }): string {
  switch (kindOf(t)) {
    case 'svc':
      return '服务表'
    case 'agg':
      return '聚合表'
    case 'mv':
      return '视图'
    default:
      return ''
  }
}
function kindTagType(t: { name: string; engine?: string }): 'success' | 'warning' | 'info' {
  switch (kindOf(t)) {
    case 'svc':
      return 'success'
    case 'agg':
      return 'warning'
    default:
      return 'info'
  }
}
const counts = computed(() => {
  const c = { svc: 0, agg: 0, mv: 0, plain: 0 }
  for (const t of tables.value) c[kindOf(t)]++
  return c
})
const createVisible = ref(false)
const viewTable = ref('')
const viewMeta = ref<TableInfo | null>(null)
const shareVisible = ref(false)
const shareAssetId = ref<number | null>(null)
const shareTableName = ref('')
function openShare(row: TableInfo) {
  if (!row.assetId || row.canShare === false) return
  shareAssetId.value = row.assetId
  shareTableName.value = row.name
  shareVisible.value = true
}
/** 抽屉的打开模式(见 openTable) */
const viewMode = ref<'edit' | 'target' | 'agg' | 'crowd'>('edit')
const viewReadonly = computed(() => viewMode.value !== 'edit')

const gmgVisible = ref(false)
const gmgEditing = ref<TableGroup | null>(null)
const gmgName = ref('')
const gmgRemark = ref('')
const gmgSaving = ref(false)

const curFilterLabel = computed(() => {
  if (filter.value === 'ungrouped') return '默认分组'
  if (filter.value.startsWith('g:'))
    return groups.value.find((g) => g.id === Number(filter.value.slice(2)))?.name ?? '分组'
  return '全部表'
})

const tableGroupMap = computed<Record<string, number>>(() => {
  const m: Record<string, number> = {}
  for (const g of groups.value) for (const t of g.tables ?? []) m[t] = g.id
  return m
})
const ungroupedTables = computed(() => tables.value.filter((t) => !(tableGroupMap.value[t.name] > 0)))

const shownTables = computed(() => {
  const k = kw.value.trim().toLowerCase()
  let base = tables.value
  if (filter.value === 'ungrouped') base = ungroupedTables.value
  else if (filter.value.startsWith('g:')) {
    const gid = Number(filter.value.slice(2))
    const set = new Set(groups.value.find((g) => g.id === gid)?.tables ?? [])
    base = tables.value.filter((t) => set.has(t.name))
  }
  // 身份筛选:选「全部」时把物化视图去掉(它们是管道,会把列表淹掉)
  if (kindFilter.value === 'all') base = base.filter((t) => !isMv(t))
  else base = base.filter((t) => kindOf(t) === kindFilter.value)
  if (!k) return base
  // 支持按【表名 + 表注释】搜索,空格分隔多关键词(全部命中)
  const words = k.split(/\s+/).filter(Boolean)
  return base.filter((t) => {
    const hay = `${t.name}\n${t.remark ?? ''}`.toLowerCase()
    return words.every((w) => hay.includes(w))
  })
})
function groupOf(name: string): number {
  return tableGroupMap.value[name] ?? 0
}

// ---- 前端分页(与数据源管理一致) ----
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
watch([filter, kw, kindFilter], () => {
  page.value = 1
})

/** 解析用哪个连接:默认连接 → 当前 → 第一个;拿不到就保持 connReady=false,页面显示空列表而不是弹错 */
async function resolveConn() {
  const conns = (await connectionsApi.list().catch(() => [])) as any[]
  const requestedId = Number(route.query.conn)
  const requested = Number.isSafeInteger(requestedId) && requestedId > 0
    ? conns.find((c: any) => c.id === requestedId)
    : null
  const def = (requested ?? conns.find((c: any) => c.isDefault) ?? conns.find((c: any) => c.id === connId.value) ?? conns[0]) || null
  if (def) {
    connId.value = def.id
    connReady.value = true
  }
}
// ⚠️ seq 守卫:连点刷新 / 切连接时请求会并发,旧响应不能覆盖新数据。
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
/** 拉各表统计(列数/行数/磁盘)。后端带缓存,所以切页签时调它不会每次都全表扫描 */
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
function statOf(name: string) {
  return stats.value[name]
}
async function reloadAll() {
  await Promise.all([loadGroups(), loadTables(), loadStats()])
}

// ---- 分组 CRUD ----
// 三个入口都先重置字段再打开:不重置会把上一次编辑过的内容留在"新建"框里
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
/** 归组是单点提交(表 → 组),回来只需重拉分组关系 */
async function moveTable(name: string, groupId: number) {
  if (!connReady.value) return
  const groupName = groupId ? (groups.value.find((g) => g.id === groupId)?.name ?? `#${groupId}`) : '默认分组'
  if (!(await confirmAction('移动表分组', `确认把表「${name}」移动到「${groupName}」？`, '确认移动'))) return
  try {
    await workspaceApi.setTableGroup(connId.value, db, name, groupId)
    await loadGroups()
  } catch {
    /* 拦截器已提示 */
  }
}
/**
 * 打开表数据抽屉。三种模式:
 *
 *   edit    通用可编辑浏览器(「全部表」里的手工表)
 *   target  目标表:圈选 / 画像(只读 —— 改了下次摊平会覆盖)
 *   agg     聚合表(中间层):通用浏览器但只读(看数据/结构/DDL,不能改)
 */
function openTable(name: string, mode: 'edit' | 'target' | 'agg' | 'crowd' = 'edit') {
  viewMeta.value = tables.value.find((t) => t.name === name) ?? null
  viewMode.value = mode
  viewTable.value = name
}
const viewOpen = computed({
  get: () => !!viewTable.value,
  set: (v: boolean) => {
    if (!v) { viewTable.value = ''; viewMeta.value = null }
  }
})
// 抽屉里可能「存为数据包」建了新表(也可能删了表)——关掉抽屉时把表清单与统计一起刷,
// 否则回列表看不到新建的包,得整页重载。
watch(viewOpen, (open) => {
  if (!open) void reloadCrowds()
})

/** 点侧边栏当前菜单 → 关闭表数据弹层,回到表列表 */
const navResetTick = useNavReset()
watch(navResetTick, () => {
  if (viewTable.value) { viewTable.value = ''; viewMeta.value = null }
})
function onViewDeleted() {
  viewTable.value = ''
  viewMeta.value = null
  loadTables()
  loadGroups()
  loadStats()
}
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
  // 三个都要刷:新表出现在列表里、可能落在某个分组、统计也从 '-' 变成真实值
  await loadTables()
  await loadGroups()
  await loadStats()
}

onMounted(async () => {
  void loadWorkflows()
  await resolveConn()
  await reloadAll()
  const want = typeof route.query.table === 'string' ? route.query.table : ''
  if (want) openTable(want, route.query.view === 'target' ? 'target' : 'edit')
})
</script>

<style scoped>
/* 与 ODS 页同一套骨架:整页不滚动,滚动交给页签里的内容区 */
.dwd-view {
  height: 100%;
  box-sizing: border-box;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  padding: 20px 24px;
}
/* dwd 层配色:紫色 —— 区别于 ods 橙黄品牌色。
   ⚠️ 色值改从 theme.css 的 --layer-dwd 取:它按亮/暗模式各给一档,
   硬编码 #6366f1 在暗底上只有 3.4:1(当文字用不达标)。 */
.dwd-view {
  --layer-accent: var(--layer-dwd);
  --layer-accent-soft: color-mix(in srgb, var(--layer-dwd) 12%, transparent);
}
/* 左:分组栏 */
/* min-width:0 必须有:flex 子项默认 min-width:auto,长表名会撑破容器导致横向溢出 */
.dwd-main {
  flex: 1;
  min-height: 0;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 12px;
  overflow: hidden;
}
.dwd-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}
/* 与 ODS 页一致:工具按钮固定一行不换行,标题区可收缩 */
.dwd-head > div:first-child {
  min-width: 0;
}
.dwd-head .actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: none;
  flex-wrap: nowrap;
}
.dwd-head h2 {
  margin: 0;
  font-size: 18px;
}
.dwd-head-group {
  font-size: 14px;
  color: var(--layer-accent);
  font-family: var(--el-font-family-mono, monospace);
  font-weight: 600;
}
.dwd-sub {
  margin: 4px 0 0;
  font-size: 14px;
  color: var(--el-text-color-secondary);
}
.dwd-kind-label {
  color: var(--el-text-color-secondary);
  font-size: 13px;
  white-space: nowrap;
}
.dwd-kind-select { width: 150px; }
.tbl-kind {
  margin-left: 6px;
  transform: scale(0.92);
}
.dwd-tabs {
  margin-top: 4px;
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.dwd-tabs :deep(.el-tabs__header) { flex: none; }
/* 页签名与抽屉里的面板都要能吃掉剩余高度(否则宽表页签里表格高度会塌成 0) */
.dwd-tabs :deep(.el-tabs__content) {
  flex: 1;
  min-height: 0;
  overflow: hidden;
}
/* 每个页签内部再撑一层 flex:页签里的表格靠它拿到确定高度 */
.dwd-tabs :deep(.el-tab-pane) {
  height: 100%;
  display: flex;
  flex-direction: column;
  min-height: 0;
}
/* 宽表页:一行说明 + 数据面板 */
/* 说明行可能很长(源数/字段数/最近执行 + 改口径按钮),允许折行 */
.dwd-wide-bar {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  padding: 0 2px 6px;
  font-size: 13px;
}
/* flex:1 把「改口径」按钮推到最右 */
.dwd-wide-bar .ttp-gap {
  flex: 1;
}
.dwd-wide-warn {
  color: var(--el-color-warning);
}
/* flex:1 + min-height:0:让 TargetTablePanel 内部的表格能算出高度并自己滚 */
.dwd-wide-body {
  flex: 1;
  min-height: 0;
}
/* 空态限宽:说明文字铺满整屏会很难读(一行太长) */
.dwd-wide-empty {
  max-width: 640px;
  padding: 40px 8px;
}
.dwd-wide-empty h3 {
  margin: 0 0 8px;
  font-size: 16px;
}
.dwd-wide-empty p {
  margin: 0 0 14px;
  line-height: 1.7;
  color: var(--el-text-color-secondary);
}
.dwd-wide-actions {
  display: flex;
  gap: 8px;
  margin-bottom: 14px;
}
.out-table {
  width: 100%;
}
.out-sw {
  margin-left: 8px;
}
/* 行内简链:源 N → 聚合 → 目标 */
.out-chain {
  display: flex;
  align-items: center;
  gap: 4px;
}
.out-node {
  padding: 1px 7px;
  border-radius: 4px;
  font-size: 13px;
  background: var(--el-fill-color);
}
.out-node.agg {
  background: rgba(217, 119, 6, 0.14);
  color: var(--brand, #d97706);
}
.out-node.svc {
  background: rgba(64, 158, 255, 0.14);
  color: var(--el-color-primary);
}
.out-arr {
  color: var(--el-text-color-placeholder);
}
/* 拓扑块可点:整块给 hover 反馈 + 右侧一个「查看」 */
.out-click {
  cursor: pointer;
  border-radius: 4px;
  padding: 2px 4px;
  transition: background 0.15s;
}
.out-click:hover {
  background: var(--el-fill-color-light);
}
.out-more {
  margin-left: 6px;
  font-size: 13px;
  color: var(--el-color-primary);
  opacity: 0;
  transition: opacity 0.15s;
}
.out-click:hover .out-more {
  opacity: 1;
}
.out-caret {
  margin-left: 2px;
}
.out-srcs {
  margin-top: 2px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.out-err {
  font-size: 13px;
  color: var(--el-color-danger);
}
.dwd-tbl-wrap {
  flex: 1;
  min-height: 0;
  /* 外框由 el-table--border 统一提供(见 theme.css),这里不再套第二层边框 */
  overflow: hidden;
}
.dwd-table {
  width: 100%;
}
.out-table :deep(td .cell div:not(.action-icon-bar)) {
  display: inline;
  margin-right: 6px;
}
.tbl-link {
  cursor: pointer;
  color: var(--layer-accent);
}
.tbl-link:hover {
  text-decoration: underline;
}
.dwd-empty-hint {
  padding: 14px;
  color: var(--el-text-color-secondary);
}
.dwd-drawer-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  gap: 8px;
}
.dwd-drawer-title {
  font-weight: 600;
  font-size: 14px;
}
/*
  抽屉头部与内边距压扁:Element Plus 默认 header 20px 内边距 + 30px 下边距、
  body 20px 内边距 —— 一屏里白吃掉 70 多像素,数据区就被挤小了。
  body 改成不留白且不滚动:滚动交给里面的结果表格(表头固定)。
*/.dwd-view :deep(.el-drawer__header) {
  padding: 8px 14px;
  margin-bottom: 0;
  border-bottom: 1px solid var(--el-border-color-lighter);
}
.dwd-view :deep(.el-drawer__body) {
  padding: 8px 14px;
  overflow: hidden;
}
.mono {
  font-family: var(--el-font-family-mono, monospace);
}
/* .dim 见全局主题(assets/theme.css) */
.tbl-engine {
  cursor: help;
  color: var(--el-text-color-primary);
  white-space: nowrap;
}
.tbl-engine:hover {
  color: var(--layer-accent);
  text-decoration: underline dotted;
}
</style>
