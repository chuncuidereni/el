<!--
  DataSourcesView.vue

  职责：数据源管理页面：维护本地、SFTP、Kafka 等数据源及删除引用保护。
  边界：按分组加载数据源并在创建后定位新项；删除前确认，存在映射任务或服务端拒绝时保留原列表并展示原因。
-->

<template>
  <div class="datasources-view">
    <!-- 右侧内容 -->
    <main class="ds-main">
      <ListPageTemplate v-model:page="page" v-model:page-size="pageSize" :total="filteredList.length">
      <template #header>
      <div class="page-head">
        <div>
          <h2>数据源管理</h2>
          <p class="page-sub">列表展示最近验证结果，不自动测试连接。通信测试按需执行，同一配置 30 秒内复用结果。</p>
        </div>
      </div>

      <ListQueryBar
        v-model:group="query.draftGroup.value"
        v-model:keyword="query.draftKeyword.value"
        all-label="全部"
        :all-count="list.length"
        special-label="默认分组"
        special-value="default"
        :special-count="defaultCount"
        :groups="dsGroups"
        keyword-placeholder="搜索标识、名称或描述"
        @search="query.search"
        @reset="resetListQuery"
        @new="gmgNew"
        @edit="onGroupEdit"
        @remove="gmgRemove"
      />

      <div class="list-table-ops">
        <div class="list-table-ops-start">
          <el-button type="primary" :icon="Plus" @click="openCreate">新增数据源</el-button>
          <el-button v-if="selectedDs.length" type="danger" :icon="Delete" :loading="deleting" @click="batchRemove">批量删除({{ selectedDs.length }})</el-button>
        </div>
      </div>

      </template>
      <div class="ds-table-wrap">
      <el-table :data="pagedData" v-loading="loading" border stripe class="ds-table" height="100%" scrollbar-always-on ref="dsTableRef" @selection-change="onDsSelectionChange">
      <el-table-column type="selection" width="42" align="center" />
      <el-table-column type="index" :index="globalIndex" label="序号" width="60" align="center" />
      <el-table-column prop="code" label="唯一标识" min-width="130">
        <template #default="{ row }">
          <span class="mono">{{ row.code }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="name" label="名称" min-width="130" />
      <el-table-column label="所属用户" min-width="120" show-overflow-tooltip>
        <template #default="{ row }">{{ row.ownerUsername || (row.ownerUserId ? `用户 #${row.ownerUserId}` : '未绑定') }}</template>
      </el-table-column>
      <el-table-column label="类型" width="110">
        <template #default="{ row }">
          <el-tag size="small" :type="typeTag(row.type)" effect="plain">{{ typeLabel(row.type) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="配置摘要" min-width="120" align="center">
        <template #default="{ row }">
          <el-button size="small" type="primary" plain @click="openDetail(row)">查看详情</el-button>
        </template>
      </el-table-column>
      <el-table-column label="分组" width="110">
        <template #default="{ row }">
          <el-tag size="small" effect="plain" :type="row.groupId ? 'success' : 'info'">
            {{ groupNameOf(row.groupId) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="密码" width="80">
        <template #default="{ row }">
          <!-- 文件类没有密码概念,单列「无」;连接类再分已设置/未设置,两者含义不能混 -->
          <el-tag v-if="row.type === 'csv' || row.type === 'json'" size="small" type="info">无</el-tag>
          <el-tag v-else-if="row.hasPassword" size="small" type="success">已设置</el-tag>
          <el-tag v-else size="small" type="info">未设置</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="测试连接最新时间" width="205" align="left">
        <template #default="{ row }">
          <!-- 仅异常态带 detail(缺文件/连接失败原因),故只有有值时才包 tooltip,正常态保持轻量 -->
          <el-tooltip v-if="statusOf(row)?.detail" :content="statusOf(row)!.detail!" placement="top" :show-after="300">
            <el-tag size="small" :type="statusTagType(statusOf(row))" effect="plain" style="white-space: nowrap">{{ testingIds.has(row.id) ? '正在测试' : statusOf(row)?.checkedAt ? new Date(statusOf(row)!.checkedAt!).toLocaleString('zh-CN', { hour12: false }) : statusOf(row)?.text ?? '未验证' }}</el-tag>
          </el-tooltip>
          <el-tag v-else size="small" :type="statusTagType(statusOf(row))" effect="plain" style="white-space: nowrap">{{ testingIds.has(row.id) ? '正在测试' : statusOf(row)?.checkedAt ? new Date(statusOf(row)!.checkedAt!).toLocaleString('zh-CN', { hour12: false }) : statusOf(row)?.text ?? '未验证' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="description" label="描述" min-width="140" show-overflow-tooltip />
      <el-table-column label="预览" width="80" align="center">
        <template #default="{ row }">
          <el-button v-if="row.type === 'csv' || row.type === 'json' || row.type === 'sftp'" size="small" type="success" plain @click="openPreview(row)">预览</el-button>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="235" align="center">
        <template #default="{ row }">
          <ActionIconBar :actions="sourceActions(row)" @command="(cmd: string) => onCommand(cmd, row)" />
        </template>
      </el-table-column>
      </el-table>
      </div>
      </ListPageTemplate>
    </main>

    <DataSourceFormDialog v-model="dialogVisible" :datasource="editing" :groups="dsGroups" :default-group-id="dialogDefaultGroup" @saved="onSaved" @tested="loadStatus" @group-created="loadGroups" @created-local="onCreatedLocal" />

    <!-- SFTP 文件浏览 + 预览(从家目录进入/下载预览) -->
    <SftpFilePreview v-if="sftpPvRow" v-model="sftpPvVisible" :id="sftpPvRow.id" :name="sftpPvRow.name" />

    <!-- 分组管理弹窗 -->
    <el-dialog v-model="groupMgrVisible" title="数据源分组管理" width="520px" :close-on-click-modal="false">
      <div class="dsgm">
        <!-- 左:分组列表(点选即进入编辑态);右:同一个表单兼做新建与修改 -->
        <div class="dsgm-list">
          <div v-for="g in dsGroups" :key="g.id" class="dsgm-row" :class="{ active: gmgEditting?.id === g.id }" @click="gmgSelect(g)">
            <el-icon class="dsgm-folder"><Folder /></el-icon>
            <span class="dsgm-name" :title="g.name">{{ g.name }}</span>
            <span class="dsgm-count">{{ g.count ?? 0 }}</span>
            <el-button link type="danger" size="small" :icon="Delete" class="dsgm-del" @click.stop="gmgRemove(g)" />
          </div>
          <el-empty v-if="!dsGroups.length" description="暂无分组" :image-size="40" />
        </div>
        <el-form label-width="70px" class="dsgm-form">
          <el-form-item label="名称">
            <el-input v-model="gmgName" placeholder="如:业务库 / 日志 / 测试" />
          </el-form-item>
          <el-form-item label="备注">
            <el-input v-model="gmgRemark" placeholder="可选" />
          </el-form-item>
          <div class="dsgm-actions">
            <el-button v-if="gmgEditting" type="danger" plain size="small" @click="gmgDeleteEdit">删除</el-button>
            <el-button type="primary" size="small" :loading="gmgSaving" @click="gmgSave">
              {{ gmgEditting ? '保存修改' : '新建分组' }}
            </el-button>
          </div>
        </el-form>
      </div>
    </el-dialog>

    <!-- 移动到分组弹窗 -->
    <el-dialog v-model="moveVisible" title="移动到分组" width="380px" :close-on-click-modal="false">
      <p class="move-hint">将「<b>{{ moveRow?.name }}</b>」移动到:</p>
      <el-radio-group v-model="moveGroupVal" class="move-opts">
        <el-radio value="0">默认分组</el-radio>
        <el-radio v-for="g in dsGroups" :key="g.id" :value="String(g.id)">{{ g.name }}</el-radio>
      </el-radio-group>
      <template #footer>
        <el-button @click="moveVisible = false">取消</el-button>
        <el-button type="primary" @click="onMoveConfirm">确定</el-button>
      </template>
    </el-dialog>

    <!-- 配置摘要详情弹窗 -->
    <el-dialog v-model="detailVisible" :title="`配置摘要 · ${detail?.name ?? ''}`" width="820px" top="8vh" :close-on-click-modal="false">
      <div v-loading="detailLoading" class="detail-body">
        <template v-if="detail">
          <el-descriptions :column="2" border>
            <el-descriptions-item label="名称">{{ detail.name }}</el-descriptions-item>
            <el-descriptions-item label="唯一标识">{{ detail.code }}</el-descriptions-item>
            <el-descriptions-item label="类型">
              <el-tag size="small" :type="typeTag(detail.type)" effect="plain">{{ typeLabel(detail.type) }}</el-tag>
            </el-descriptions-item>
            <!-- 本地文件 -->
            <template v-if="detail.type === 'csv' || detail.type === 'json'">
              <el-descriptions-item label="文件路径">{{ detail.path || '—' }}</el-descriptions-item>
              <el-descriptions-item label="文件大小">{{ formatSize(detail.size) }}</el-descriptions-item>
              <el-descriptions-item label="列数">{{ detail.columns?.length ?? 0 }}</el-descriptions-item>
              <el-descriptions-item label="总行数">{{ detail.total ?? 0 }}</el-descriptions-item>
              <el-descriptions-item v-if="detail.type === 'csv'" label="行头">{{ detail.hasHeader ? '有' : '无' }}</el-descriptions-item>
            </template>
            <!-- 连接类 -->
            <template v-else>
              <el-descriptions-item label="主机">{{ detail.host || '—' }}</el-descriptions-item>
              <el-descriptions-item label="端口">{{ detail.port ?? '—' }}</el-descriptions-item>
              <template v-if="detail.type === 'clickhouse'">
                <el-descriptions-item label="协议">{{ detailCkConfig.protocol === 'http' ? 'HTTP' : 'Native' }}</el-descriptions-item>
                <el-descriptions-item label="TLS">{{ detailCkConfig.secure === true || detailCkConfig.secure === 'true' ? '已启用' : '未启用' }}</el-descriptions-item>
              </template>
              <el-descriptions-item label="用户名">{{ detail.username || '—' }}</el-descriptions-item>
              <el-descriptions-item v-if="detail.database" label="数据库">{{ detail.database }}</el-descriptions-item>
              <el-descriptions-item v-if="detail.table" label="表">{{ detail.table }}</el-descriptions-item>
              <el-descriptions-item v-if="detail.collection" label="集合">{{ detail.collection }}</el-descriptions-item>
              <el-descriptions-item v-if="detail.remotePath" label="远程路径">{{ detail.remotePath }}</el-descriptions-item>
            </template>
          </el-descriptions>

          <!-- 字段映射 + 各列类型(本地文件)。以全部原始列为基线展示,标注保留/剔除 -->
          <template v-if="detail.type === 'csv' || detail.type === 'json'">
            <div class="detail-section-title">字段映射与列类型 ({{ detailColRows.length }} 列)</div>
            <el-table :data="detailColRows" border size="small" max-height="360" class="detail-table">
              <el-table-column type="index" label="序号" width="60" align="center" />
              <el-table-column label="原始列名" min-width="160" show-overflow-tooltip>
                <template #default="{ row }"><span class="mono">{{ row.rawName }}</span></template>
              </el-table-column>
              <el-table-column label="字段映射" min-width="160">
                <template #default="{ row }">
                  <span v-if="row.renamed" class="mono renamed" :title="`${row.rawName} → ${row.mapped}`">
                    {{ row.rawName }} → {{ row.mapped }}
                  </span>
                  <span v-else class="mono">{{ row.mapped }}</span>
                </template>
              </el-table-column>
              <el-table-column label="列类型" min-width="130">
                <template #default="{ row }"><span class="mono">{{ row.type }}</span></template>
              </el-table-column>
              <el-table-column label="状态" width="86" align="center">
                <template #default="{ row }">
                  <el-tag v-if="row.kept" size="small" type="success" effect="plain">保留</el-tag>
                  <el-tag v-else size="small" type="info" effect="plain">剔除</el-tag>
                </template>
              </el-table-column>
            </el-table>
          </template>
        </template>
      </div>
      <template #footer>
        <el-button @click="detailVisible = false">关闭</el-button>
      </template>
    </el-dialog>

    <!-- CSV 数据预览弹窗 -->
    <el-dialog v-model="previewVisible" :title="`数据预览 · ${previewRow?.name ?? ''}`" width="900px" top="5vh" :close-on-click-modal="false">
      <div v-loading="previewLoading" class="preview-body">
        <!-- 解析配置:行头开关 + 无行头时手动列名映射(仅 CSV;JSON 自带 key) -->
        <div v-if="previewRow?.type === 'csv'" class="preview-config">
          <span class="pc-label">行头</span>
          <el-switch v-model="previewHeader" @change="applyPreview" />
          <template v-if="!previewHeader && previewData">
            <span class="pc-label">列名映射</span>
            <div class="pc-names">
              <div v-for="(n, i) in previewNames" :key="i" class="pc-name-row">
                <span class="pc-col-idx">{{ i + 1 }}</span>
                <el-input v-model="previewNames[i]" size="small" :placeholder="`第 ${i + 1} 列列名`" @keyup.enter="applyPreview" />
              </div>
            </div>
            <el-button size="small" type="primary" plain @click="applyPreview">应用</el-button>
            <el-button size="small" @click="savePreviewConfig" :loading="savingConfig">保存设置</el-button>
          </template>
        </div>

        <template v-if="previewData">
          <div class="preview-meta">
            <el-tag size="small" effect="plain">预览 {{ previewData.rows.rows.length }} 条</el-tag>
            <el-tag v-if="previewRow?.type !== 'sftp'" size="small" type="success" effect="plain">共 {{ previewData.total }} 行</el-tag>
            <el-tag size="small" effect="plain">{{ previewData.columns.length }} 列</el-tag>
            <span v-if="previewRow?.type === 'json'" class="preview-hint">字段名来自 JSON 的 key</span>
            <span v-else-if="!previewHeader" class="preview-hint">无行头模式:列名使用手动映射,类型自动推断</span>
          </div>
          <el-table :data="previewRows" border size="small" max-height="420" class="preview-table">
            <el-table-column type="index" label="序号" width="60" align="center" />
            <el-table-column v-for="(col, ci) in previewData.columns" :key="ci" min-width="120">
              <template #header>
                <div class="preview-col-head">
                  <span class="preview-col-name">{{ col.name }}</span>
                  <span class="preview-col-type">{{ col.type }}</span>
                </div>
              </template>
              <template #default="{ $index }">
                <span class="mono" :title="previewData.rows.rows[$index]?.[ci] ?? ''">{{ previewData.rows.rows[$index]?.[ci] ?? '' }}</span>
              </template>
            </el-table-column>
          </el-table>
        </template>
      </div>
      <template #footer>
        <el-button @click="previewVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import ListQueryBar from '@/components/ListQueryBar.vue'
import ListPageTemplate from '@/components/ListPageTemplate.vue'
import { useListQuery } from '@/composables/useListQuery'
import { confirmAction, confirmDestructive } from '@/utils/confirm'
import {
  Box,
  Delete,
  EditPen,
  Expand,
  Fold,
  Folder,
  Grid,
  Plus,
  Setting
} from '@element-plus/icons-vue'
import { dataSourcesApi, dsGroupsApi } from '@/api'
import type { DataSource, DataSourceDetail, DataSourceStatus, DSGroup } from '@/types'
import DataSourceFormDialog from '@/components/DataSourceFormDialog.vue'
import SftpFilePreview from '@/components/SftpFilePreview.vue'

import ActionIconBar from '@/components/ActionIconBar.vue'

function sourceActions(row: DataSource) {
  const file = row.type === 'csv' || row.type === 'json' || row.type === 'sftp'
  const remote = row.type !== 'csv' && row.type !== 'json' && row.type !== 'ck_table'
  return [
    ...(file ? [{ command: 'sync', label: '同步配置', primary: true }] : []),
    ...(remote ? [{ command: 'test', label: '通信测试' }, { command: 'check', label: '对象检查' }] : []),
    { command: 'movegroup', label: '移动到分组' },
    { command: 'edit', label: '编辑' },
    { command: 'remove', label: '删除', danger: true },
  ]
}

const loading = ref(false)
const router = useRouter()
const route = useRoute()
const list = ref<DataSource[]>([])
// 状态:按数据源 id 缓存(kind/state/text)
const statuses = ref<Record<number, DataSourceStatus>>({})
const testingIds = ref(new Set<number>())
// 批量删除勾选
const dsTableRef = ref<any>(null)
const selectedDs = ref<DataSource[]>([])
const deleting = ref(false)
function onDsSelectionChange(sel: DataSource[]) {
  selectedDs.value = sel
}
// 前端分页
const page = ref(1)
const pageSize = ref(10)
const pagedData = computed(() => {
  // 前端切片而非后端分页:列表接口本就是一次性全量返回(分组计数、状态探测都要全量),
  // 这里只控制进 el-table 的行数,避免几百行同时渲染
  const start = (page.value - 1) * pageSize.value
  return filteredList.value.slice(start, start + pageSize.value)
})
/** 分页下全局序号(跨页连续) */
function globalIndex(i: number): number {
  return (page.value - 1) * pageSize.value + i + 1
}
const dialogVisible = ref(false)
const editing = ref<DataSource | null>(null)
const dialogDefaultGroup = ref(0) // 新增弹窗默认分组(当前左栏分组)
const detailVisible = ref(false)
const detailLoading = ref(false)
const detail = ref<DataSourceDetail | null>(null)
/** 协议/TLS 来自列表已返回的脱敏配置，不按端口猜测，兼容转发端口。 */
const detailCkConfig = computed(() => list.value.find(row => row.id === detail.value?.id)?.config ?? {})

/**
 * 配置摘要「字段映射与列类型」行数据。
 * 以文件原始列(rawColumns,已含被剔除列)为基线,逐列配对:
 *  - 原始列名(raw.name)
 *  - 字段映射名(names[i],同名则等于原始列名)
 *  - 类型
 *  - 是否保留(映射名在“保留后的列”集合 columns 中)
 * columns 是应用保留后的列;columns 为空(=未设保留)时全部视为保留。
 */
const detailColRows = computed(() => {
  const d = detail.value
  if (!d) return []
  // 老数据可能没有 rawColumns,退化为「保留列」当基线,保证表格不空
  const raws = d.rawColumns && d.rawColumns.length ? d.rawColumns : (d.columns ?? [])
  const names = d.names ?? []
  const keptNames = new Set((d.columns ?? []).map((c) => c.name))
  // columns 为空 = 从未设过「保留」,此时不能把整表判成剔除
  const hasKeep = !!d.columns && d.columns.length > 0
  return raws.map((raw, i) => {
    // names 可能短于 raws(只映射了前几列)或含空项,越界/空值一律回退原始列名
    const mapped = i < names.length && names[i] != null ? names[i].trim() : raw.name
    const isKept = !hasKeep || keptNames.has(mapped)
    return {
      rawName: raw.name,
      mapped,
      type: raw.type,
      kept: isKept,
      renamed: mapped !== raw.name
    }
  })
})

/**
 * 刷新列表 + 状态。
 *
 * ⚠️ seq 守卫:连点刷新 / 批量删除后重拉会并发,旧响应不能覆盖新数据
 *(状态探测比列表慢,容易后到)。
 */
let listSeq = 0
async function load() {
  const my = ++listSeq
  loading.value = true
  try {
    const rows = await dataSourcesApi.list()
    if (my !== listSeq) return
    list.value = rows
  } catch {
    /* 拦截器已提示 */
  } finally {
    if (my === listSeq) loading.value = false
  }
  // 刷新只读取历史快照，不测试来源连接。
  if (my === listSeq) loadStatus()
}

/** 拉取数据源状态(文件存在 / 连接连通),并行,不阻塞列表 */
let statusSeq = 0
async function loadStatus() {
  const seq = ++statusSeq
  try {
    const rows = await dataSourcesApi.status()
    if (seq !== statusSeq) return
    const m: Record<number, DataSourceStatus> = {}
    for (const r of rows) m[r.id] = r
    statuses.value = m
  } catch {
    /* 拦截器已提示 */
  }
}
function statusOf(row: DataSource): DataSourceStatus | undefined {
  return statuses.value[row.id]
}
function statusTagType(st?: DataSourceStatus): 'success' | 'danger' | 'info' | 'warning' {
  if (!st || st.state === 'none') return 'info'
  if (st.stale) return 'warning'
  return st.state === 'ok' ? 'success' : 'danger'
}

// ---- 分组 Tab / 过滤 ----
const query = useListQuery()
const curGroup = query.group // all / default / <groupId>
const dsGroups = ref<DSGroup[]>([])
// 切换分组时回到第一页:过滤后条目数变少,旧页码可能落到空页
watch([curGroup, query.keyword], () => {
  page.value = 1
})

/** 当前选中分组显示名 */
const curGroupName = computed(() => {
  if (curGroup.value === 'all') return '全部'
  if (curGroup.value === 'default') return '默认分组'
  return groupNameOf(Number(curGroup.value))
})

/** 当前 Tab 过滤后的列表 */
const filteredList = computed(() => {
  const base = curGroup.value === 'all'
    ? list.value
    : list.value.filter((d) => (d.groupId ?? 0) === (curGroup.value === 'default' ? 0 : Number(curGroup.value)))
  const words = query.keyword.value.toLowerCase().split(/\s+/).filter(Boolean)
  if (!words.length) return base
  return base.filter((d) => {
    const text = `${d.code} ${d.name} ${d.description ?? ''}`.toLowerCase()
    return words.every((word) => text.includes(word))
  })
})

/** 默认分组(未设分组)的数据源数 */
const defaultCount = computed(() => list.value.filter((d) => !d.groupId).length)

/** 分组名 */
function groupNameOf(gid?: number): string {
  if (!gid) return '默认分组'
  return dsGroups.value.find((g) => g.id === gid)?.name ?? '默认分组'
}

async function loadGroups() {
  try {
    dsGroups.value = await dsGroupsApi.list()
  } catch {
    dsGroups.value = []
  }
}

/** 数据源列表 + 分组计数一并刷新(增删数据源/分组后保证左栏计数准确) */
function onSaved() {
  if (!editing.value) {
    page.value = 1
    query.reset()
  }
  refreshAll()
}

function refreshAll() {
  load()
  loadGroups()
}

function resetListQuery() {
  query.reset()
  refreshAll()
}

/** 本地文件数据源新建成功:引导去创建同步任务同步到目标库表（dwd_data） */
async function onCreatedLocal(id: number) {
  try {
    await ElMessageBox.confirm('数据源已创建。是否立即去创建同步任务,把数据同步到目标库表（dwd_data）?', '去同步', {
      confirmButtonText: '去创建同步任务',
      cancelButtonText: '暂不',
      type: 'success',
      distinguishCancelAndClose: true
    })
  } catch {
    return // 取消/关闭
  }
  router.push({ path: '/sync-tasks', query: { prefillDataSource: String(id) } })
}

// ---- 分组管理弹窗 ----
const groupMgrVisible = ref(false)
const gmgEditting = ref<DSGroup | null>(null)
const gmgName = ref('')
const gmgRemark = ref('')
const gmgSaving = ref(false)

function openGroupManage() {
  groupMgrVisible.value = true
}

/** 左栏“新建分组”按钮:清空编辑态并打开管理弹窗 */
function gmgNew() {
  gmgEditting.value = null
  gmgName.value = ''
  gmgRemark.value = ''
  groupMgrVisible.value = true
}

/** 组件里点「编辑」:选中该分组并打开管理弹窗 */
function onGroupEdit(g: DSGroup) {
  gmgSelect(g)
  openGroupManage()
}

function gmgSelect(g: DSGroup) {
  gmgEditting.value = g
  gmgName.value = g.name
  gmgRemark.value = g.remark ?? ''
}

async function gmgSave() {
  if (!gmgName.value.trim()) {
    ElMessage.warning('请输入分组名称')
    return
  }
  const editing = !!gmgEditting.value?.id
  if (!(await confirmAction(editing ? '保存数据源分组' : '新建数据源分组',
    editing ? `确认保存分组「${gmgName.value.trim()}」的修改？` : `确认新建数据源分组「${gmgName.value.trim()}」？`,
    editing ? '确认保存' : '确认新建'))) return
  gmgSaving.value = true
  try {
    if (gmgEditting.value?.id) {
      await dsGroupsApi.update(gmgEditting.value.id, gmgName.value.trim(), gmgRemark.value.trim())
      ElMessage.success('已保存')
    } else {
      await dsGroupsApi.create(gmgName.value.trim(), gmgRemark.value.trim())
      ElMessage.success('已创建')
    }
    gmgEditting.value = null
    gmgName.value = ''
    gmgRemark.value = ''
    refreshAll()
  } catch {
    /* 拦截器已提示 */
  } finally {
    gmgSaving.value = false
  }
}

/**
 * 删除分组包含两条互斥路径：成员存在时由确认框区分“同时删除成员”和“仅删分组”；
 * “同时删除成员”还会再做一次不可恢复确认，随后逐个删除成员，单个成员失败不会中断循环。
 * 单条失败会收集名称和原因，保留分组并准确显示已删除数量，避免把部分成功说成全部成功。
 * “仅删分组”由服务端把成员归入默认分组；成功后刷新列表并移除当前已失效的分组筛选。
 */
async function gmgRemove(g: DSGroup) {
  // 先数出成员:有成员才需要让用户二选一,没有成员则直接走「仅删分组」流程
  const members = list.value.filter((d) => (d.groupId ?? 0) === g.id)
  let deleteMembers = false
  if (members.length) {
    // 选择是否同时删除成员:主按钮=同时删除成员;取消=仅删分组(成员归默认);关闭=放弃
    try {
      await ElMessageBox.confirm(
        `分组「${g.name}」下现有 ${members.length} 个数据源。\n\n选择「同时删除成员」将删除这些数据源及其上传的本地文件(不可恢复);选择「仅删分组」时成员自动移回默认分组。`,
        '删除分组',
        {
          type: 'warning',
          confirmButtonText: '同时删除成员',
          cancelButtonText: '仅删分组(归默认)',
          distinguishCancelAndClose: true,
          confirmButtonClass: 'el-button--danger',
          closeOnClickModal: false,
          closeOnPressEscape: false
        }
      )
      deleteMembers = true
    } catch (e: any) {
      // distinguishCancelAndClose 下:取消按钮 → 仅删分组,右上角×/ESC → 整个放弃
      if (e === 'cancel')
        deleteMembers = false // 仅删分组
      else return // close → 放弃
    }
  }
  if (deleteMembers) {
    // 删除成员前再次确认影响范围
    if (
      !(await confirmDestructive(
        '删除分组及其成员',
        g.name,
        '删除分组并删除其下所有数据源',
        `\n\n此操作将删除该分组下 ${members.length} 个数据源及其上传文件,不可恢复。`
      ))
    )
      return
    // 保留失败成员的分组归属；成功删除的成员无法回滚，必须准确汇报部分结果。
    const failures: string[] = []
    for (const m of members) {
      try {
        await dataSourcesApi.remove(m.id, true)
      } catch (error: unknown) {
        failures.push(`${m.code}：${error instanceof Error ? error.message : '删除失败，请稍后重试'}`)
      }
    }
    if (failures.length) {
      await refreshAll()
      await ElMessageBox.alert(`已删除 ${members.length - failures.length} 个数据源；以下成员未能删除，分组已保留。请先处理关联同步任务后重试。\n${failures.join('\n')}`, '分组未删除', {
        type: 'warning', confirmButtonText: '我知道了',
      }).catch(() => {})
      return
    }
  } else {
    if (!(await confirmDestructive('删除分组', g.name, '删除分组(成员将归入默认分组)'))) return
  }
  try {
    await dsGroupsApi.remove(g.id!)
    ElMessage.success(deleteMembers ? '已删除分组及其成员' : '已删除分组,成员已归入默认分组')
  } catch {
    return /* 拦截器已提示 */
  }
  refreshAll() // load + loadGroups:成员行分组置空后需刷新列表,否则计数/Tab/标签不一致
  // 正停在被删分组时跳回「全部」,否则 Tab 指向不存在的分组会显示空表
  query.removeGroup(String(g.id))
}

function gmgDeleteEdit() {
  if (gmgEditting.value) gmgRemove(gmgEditting.value)
}

// ---- 移动到分组 ----
const moveVisible = ref(false)
const moveRow = ref<DataSource | null>(null)
// 用字符串存:el-radio 的 value 统一是字符串,数字分组 id 需 String() 后才能匹配选中
const moveGroupVal = ref('0')

function openMove(row: DataSource) {
  moveRow.value = row
  moveGroupVal.value = String(row.groupId ?? 0)
  moveVisible.value = true
}

async function onMoveConfirm() {
  if (!moveRow.value) return
  if (!(await confirmAction('移动数据源分组', `确认把数据源「${moveRow.value.name}」移动到所选分组？`, '确认移动'))) return
  try {
    await dataSourcesApi.setGroup(moveRow.value.id, Number(moveGroupVal.value))
    ElMessage.success('已移动')
    moveVisible.value = false
    // 列表刷行上的分组标签,分组刷新各分组计数
    load()
    loadGroups()
  } catch {
    /* 拦截器已提示 */
  }
}

function openCreate() {
  editing.value = null
  // 默认分组 = 当前左栏选中的分组(默认分组/数字分组);“全部”时用默认分组
  const cur = curGroup.value
  const defGid = cur && cur !== 'all' && cur !== 'default' ? Number(cur) : 0
  dialogDefaultGroup.value = defGid
  dialogVisible.value = true
}

function openEdit(row: DataSource) {
  editing.value = row
  dialogVisible.value = true
}

/** 打开配置摘要详情 */
async function openDetail(row: DataSource) {
  detailVisible.value = true
  detailLoading.value = true
  detail.value = null // 先清空,避免列表中切换行时弹窗闪出上一个源的配置
  try {
    detail.value = await dataSourcesApi.detail(row.id)
  } catch {
    /* 拦截器已提示 */
    detailVisible.value = false // 拿不到详情就关弹窗,不留一个空壳
  } finally {
    detailLoading.value = false
  }
}

/** 格式化文件大小 */
function formatSize(bytes?: number): string {
  if (!bytes || bytes <= 0) return '—'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  let n = bytes
  let i = 0
  while (n >= 1024 && i < units.length - 1) {
    n /= 1024
    i++
  }
  // 整字节与三位数以上不保留小数,避免 "1.0 KB" 这类视觉噪音
  return `${n.toFixed(n >= 100 || i === 0 ? 0 : 1)} ${units[i]}`
}

/** 操作下拉分发 */
function onCommand(cmd: string, row: DataSource) {
  switch (cmd) {
    case 'sync':
      goSync(row)
      break
    case 'test':
      test(row)
      break
    case 'check':
      checkObject(row)
      break
    case 'preview':
      openPreview(row)
      break
    case 'movegroup':
      openMove(row)
      break
    case 'edit':
      openEdit(row)
      break
    case 'remove':
      remove(row)
      break
  }
}

/** 为某数据源配置同步任务:跳转同步任务新增向导并预填该源 */
function goSync(row: DataSource) {
  router.push({ path: '/sync-tasks', query: { prefillDataSource: String(row.id) } })
}

// ---- CSV 数据预览(DuckDB 解析) ----
const previewVisible = ref(false)
const sftpPvVisible = ref(false)
const sftpPvRow = ref<DataSource | null>(null)
const previewLoading = ref(false)
const previewRow = ref<DataSource | null>(null)
const previewData = ref<{
  columns: { name: string; type: string }[]
  rows: { columns: string[]; types: string[]; rows: string[][] }
  total: number
} | null>(null)
/** 解析配置:行头开关 + 无行头时的手动列名映射 */
const previewHeader = ref(true)
const previewNames = ref<string[]>([])
const savingConfig = ref(false)

const previewRows = computed(() => {
  // el-table 需要对象数组;用行索引取值
  const n = previewData.value?.rows.rows.length ?? 0
  return Array.from({ length: n }, (_, i) => ({ __i: i }))
})

async function openPreview(row: DataSource) {
  previewRow.value = row
  previewVisible.value = true
  previewLoading.value = true
  previewData.value = null
  try {
    if (row.type === 'sftp') {
      // SFTP 源:打开文件浏览器,从家目录按权限进入并预览单个文件
      previewVisible.value = false
      sftpPvRow.value = row
      sftpPvVisible.value = true
      return // 提前返回,但 finally 仍会复位 loading,CSV 弹窗不会卡在 loading 态
    }
    {
      // 从数据源 config 恢复上次设置(列名/行头随源持久化,换个人打开预览也是一致的)
      const cfgHeader = row.config?.header
      // 只认真正的 boolean:老数据里可能是 'true' 之类的字符串,一律回落默认「有行头」
      previewHeader.value = typeof cfgHeader === 'boolean' ? cfgHeader : true
      const cfgNames = row.config?.names
      // names 两种历史形态:逗号串 / 数组;逐项 trim 并丢掉空项——
      // 否则末尾逗号、占位空名会被当成有效列名传给解析器
      previewNames.value =
        typeof cfgNames === 'string' && cfgNames
          ? cfgNames
              .split(',')
              .map((s) => s.trim())
              .filter(Boolean)
          : Array.isArray(cfgNames)
            ? [...cfgNames]
            : []
      const names = previewNames.value.filter((n) => n.trim())
      previewData.value = await dataSourcesApi.csvPreview(row.id, {
        limit: 100,
        header: previewHeader.value,
        // 一个有效列名都没有时不传 names,交给后端按行头自动推断
        names: names.length ? names : undefined
      })
    }
  } catch {
    /* 拦截器已提示 */
    previewVisible.value = false
  } finally {
    previewLoading.value = false
  }
}

/** 应用当前配置重新解析(不保存) */
async function applyPreview() {
  if (!previewRow.value) return
  // 不写回 config,只覆盖当前预览数据;用户点「保存设置」才落库
  previewLoading.value = true
  try {
    const names = previewNames.value.filter((n) => n.trim())
    previewData.value = await dataSourcesApi.csvPreview(previewRow.value.id, {
      limit: 100,
      header: previewHeader.value,
      names: names.length ? names : undefined
    })
  } catch {
    /* 拦截器已提示 */
  } finally {
    previewLoading.value = false
  }
}

/** 保存映射设置到数据源(下次预览自动生效) */
async function savePreviewConfig() {
  if (!previewRow.value) return
  if (!(await confirmAction('保存映射设置', `确认把当前列名/表头映射设置保存到数据源「${previewRow.value.name}」？下次预览将自动套用。`, '确认保存'))) return
  savingConfig.value = true
  try {
    const names = previewNames.value.filter((n) => n.trim())
    // 复用同一个预览接口,多带 save:true 让后端顺手把 config 落库;
    // 返回的预览数据这里不需要(界面用的还是当前这份),故不写回 previewData
    await dataSourcesApi.csvPreview(previewRow.value.id, {
      limit: 100,
      header: previewHeader.value,
      names: names.length ? names : undefined,
      save: true
    })
    ElMessage.success('设置已保存,下次预览自动生效')
    load() // 刷新列表里的 config
  } catch {
    /* 拦截器已提示 */
  } finally {
    savingConfig.value = false
  }
}

async function test(row: DataSource) {
  if (testingIds.value.has(row.id)) return
  testingIds.value.add(row.id)
  try {
    await dataSourcesApi.testById(row.id)
    ElMessage.success(`「${row.name}」通信测试通过 ✓`)
  } catch {
    /* 拦截器已提示 */
  } finally {
    testingIds.value.delete(row.id)
    await loadStatus()
  }
}

/** 对象检查:弹框输入表名/文件路径 */
async function checkObject(row: DataSource) {
  // 同一句提示既当标题文案又当 placeholder,按源类型给出「检查什么」的说法
  const hint = row.type === 'kafka' ? '输入 Topic 名称' :
    row.type === 'csv'
      ? '输入文件完整路径'
      : row.type === 'sftp'
        ? '输入远程文件/目录路径'
        : row.type === 'mongodb'
          ? '输入集合名'
          : '输入表名'
  try {
    const { value } = await ElMessageBox.prompt(hint, `对象检查 · ${row.name}`, {
      confirmButtonText: '检查',
      cancelButtonText: '取消',
      inputPlaceholder: hint
    })
    const res = await dataSourcesApi.checkObject(row.id, value.trim())
    if (res.exists) {
      ElMessage.success(`「${value}」存在 ✓`)
    } else {
      ElMessage.warning(`「${value}」不存在`)
    }
  } catch (e) {
    // 用户点了取消/关闭弹框,不算错误,也不需要任何提示
    if (e === 'cancel' || e === 'close') return
    /* 拦截器已提示 */
  }
}

/**
 * 单条删除。CSV/JSON 的提示明确说明会同时删除上传文件；真正的关联任务检查由服务端完成，
 * 前端没有预查任务关联。remove(..., true) 使用 silent 请求，避免拦截器与本地错误弹窗重复提示，
 * 失败时把服务端返回的消息放在“无法删除数据源”弹窗中。
 */
async function remove(row: DataSource) {
  const extra = row.type === 'csv' || row.type === 'json' ? '\n\n删除后将同时删除该数据源上传的本地文件。' : ''
  try {
    await ElMessageBox.confirm(`是否删除数据源「${row.code}」？${extra}`, '删除数据源', {
      type: 'warning',
      confirmButtonText: '是',
      cancelButtonText: '取消',
      confirmButtonClass: 'el-button--danger',
      closeOnClickModal: false,
    })
  } catch {
    return // 取消或关闭确认框，不发送删除请求。
  }
  try {
    // 由服务端确认关联关系；失败时用弹窗说明原因，避免重复显示全局消息。
    await dataSourcesApi.remove(row.id, true)
    ElMessage.success('已删除')
    refreshAll()
  } catch (error: unknown) {
    const message = error instanceof Error ? error.message : '删除失败，请稍后重试。'
    await ElMessageBox.alert(message, '无法删除数据源', {
      type: 'warning',
      confirmButtonText: '我知道了',
    }).catch(() => {})
  }
}

/**
 * 批量删除逐项调用静默单删接口；循环结束一次性列出失败的数据源及原因，避免连续弹全局消息。
 * 只有至少成功一项才清空选择并刷新，全失败时保留勾选便于直接重试。
 */
async function batchRemove() {
  const rows = selectedDs.value
  if (!rows.length || deleting.value) return // deleting 期间再点会重复提交同一批
  // 批量删接口是一次一个,弹窗里要预先提示「其中有本地文件源」,否则用户不知道文件也会没
  const hasFile = rows.some((r) => r.type === 'csv' || r.type === 'json')
  const extra = hasFile ? '\n\n注意:其中包含本地文件数据源,删除后将同时删除其上传文件。' : ''
  try {
    await ElMessageBox.confirm(`是否删除数据源？将删除选中的 ${rows.length} 个数据源。${extra}`, '批量删除数据源', {
      type: 'warning', confirmButtonText: '确认删除', cancelButtonText: '取消',
      confirmButtonClass: 'el-button--danger', closeOnClickModal: false,
    })
  } catch { return }
  deleting.value = true
  try {
    let ok = 0
    const failures: string[] = []
    for (const r of rows) {
      try {
        await dataSourcesApi.remove(r.id, true)
        ok++
      } catch (error: unknown) {
        failures.push(`${r.code}：${error instanceof Error ? error.message : '删除失败，请稍后重试'}`)
      }
    }
    // 三种结果分开提示:全失败说明多半是权限/服务问题,混合成功要报出失败数
    if (failures.length) {
      await ElMessageBox.alert(`已删除 ${ok} 个，${failures.length} 个未删除：\n${failures.join('\n')}`, '删除结果', {
        type: 'warning', confirmButtonText: '我知道了',
      }).catch(() => {})
    } else {
      ElMessage.success(`已删除 ${ok} 个`)
    }
    // 一个都没删成功就不刷新,保留勾选让用户能直接重试
    if (ok > 0) {
      dsTableRef.value?.clearSelection() // 行对象刷新后已失效,不清会残留已删行
      refreshAll()
    }
  } finally {
    deleting.value = false
  }
}

/** 类型显示 */
const TYPE_LABELS: Record<string, string> = {
  kafka: 'Kafka',
  hive: 'Hive',
  csv: '本地 CSV',
  sftp: 'SFTP',
  ck_table: '库内表',
  mysql: 'MySQL',
  pgsql: 'PostgreSQL',
  clickhouse: 'ClickHouse',
  mongodb: 'MongoDB'
}
function typeLabel(t: string): string {
  return TYPE_LABELS[t] ?? t
}
function typeTag(t: string): 'success' | 'warning' | 'primary' | 'danger' | 'info' {
  switch (t) {
    case 'csv':
      return 'success'
    case 'sftp':
      return 'warning'
    case 'clickhouse':
      return 'danger'
    case 'mongodb':
      return 'primary'
    default:
      return 'info'
  }
}

/** 从同步任务跳转定位并打开某数据源详情:监听 query.dsId 变化 + 列表就绪后打开,
 *  取代旧的 setInterval 轮询(卸载时不清理、同路由 push 不响应等问题)。 */
let locateInterval: ReturnType<typeof setInterval> | null = null
let locateTimeout: ReturnType<typeof setTimeout> | null = null
function clearLocateTimer() {
  if (locateInterval !== null) {
    clearInterval(locateInterval)
    locateInterval = null
  }
  if (locateTimeout !== null) {
    clearTimeout(locateTimeout)
    locateTimeout = null
  }
}
function tryLocate(dsId: number): boolean {
  const row = list.value.find((d) => d.id === dsId)
  if (row) {
    clearLocateTimer()
    openDetail(row)
    return true
  }
  return false
}
function scheduleLocate(dsId: number) {
  if (!dsId) return
  if (tryLocate(dsId)) return // 列表已就绪,直接打开
  clearLocateTimer()
  // 列表还没回来(冷启动/刷新中):轻量轮询等待,tryLocate 成功即清定时器
  locateInterval = setInterval(() => {
    if (tryLocate(dsId)) return
  }, 200) as unknown as ReturnType<typeof setInterval>
  // 保存超时句柄，下一次 dsId 跳转或组件卸载时必须连同 interval 一起取消。
  locateTimeout = setTimeout(clearLocateTimer, 1500) as unknown as ReturnType<typeof setTimeout>
}
onMounted(() => {
  load()
  loadGroups()
})
// 列表就绪后或 query 变化时触发定位
watch(list, () => {
  const q = Number(route.query.dsId)
  if (q) scheduleLocate(q)
})
watch(
  () => route.query.dsId,
  () => scheduleLocate(Number(route.query.dsId))
)
onUnmounted(clearLocateTimer)
</script>

<style scoped>
/* 分组栏 */
.ds-group-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}
.ds-tabs .g-count {
  font-size: 11px;
  color: var(--el-text-color-secondary);
  margin-left: 2px;
}
/* 分组管理弹窗 */
.dsgm {
  display: flex;
  gap: 16px;
}
.dsgm-list {
  width: 180px;
  flex: none;
  border-right: 1px solid var(--el-border-color);
  padding-right: 12px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.dsgm-row {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 8px;
  border-radius: 6px;
  cursor: pointer;
  font-size: 13px;
}
.dsgm-row:hover { background: var(--el-fill-color); }
.dsgm-row.active { background: rgba(234,179,8,0.12); color: var(--brand-strong); }
.dsgm-folder { color: #34d399; }
.dsgm-name { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.dsgm-count { font-size: 11px; color: var(--el-text-color-placeholder); }
.dsgm-del { opacity: 0; }
.dsgm-row:hover .dsgm-del { opacity: 1; }
.dsgm-form { flex: 1; }
.dsgm-actions { display: flex; justify-content: space-between; }
.move-hint { margin: 0 0 10px; font-size: 13px; color: var(--el-text-color-regular); }
.move-opts {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.move-opts .el-radio {
  display: flex;
  align-items: center;
  margin-right: 0;
  height: 36px;
  padding: 0 10px;
  border-radius: 6px;
  width: 100%;
  box-sizing: border-box;
}
.move-opts .el-radio:hover {
  background: var(--el-fill-color);
}
.move-opts .el-radio.is-checked {
  background: rgba(234, 179, 8, 0.1);
}
.move-opts .el-radio__label {
  line-height: 1;
}

.datasources-view {
  height: 100%;
  box-sizing: border-box;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  padding: 20px 24px;
}

.ds-main {
  flex: 1;
  min-height: 0;
  min-width: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
/* 表格占剩余高度、内部滚动,分页固定在底部 */
.ds-table-wrap {
  flex: 1;
  min-height: 0;
}
.ds-table-wrap .ds-table {
  height: 100%;
}
.head-group {
  font-size: 14px;
  font-weight: 500;
  color: var(--el-text-color-secondary);
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
  gap: 8px;
}
/* 一条规则(以前写了两遍,后者覆盖 font-size:12.5→12) */
.mono {
  font-family: var(--font-mono);
  font-size: 12px;
}
.summary {
  color: var(--el-text-color-secondary);
}
.op-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.op-arrow {
  margin-left: 2px;
}
.detail-body {
  min-height: 120px;
}
.detail-section-title {
  margin: 16px 0 8px;
  font-size: 13px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}
.detail-table {
  width: 100%;
}
.renamed {
  color: var(--brand-strong);
}
.preview-body {
  min-height: 200px;
}
.preview-config {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 10px 12px;
  background: var(--fill-soft);
  border: 1px dashed var(--el-border-color);
  border-radius: 8px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}
.pc-label {
  font-size: 13px;
  color: var(--el-text-color-secondary);
  line-height: 32px;
  flex: none;
}
.pc-names {
  display: flex;
  flex-direction: column;
  gap: 4px;
  max-height: 140px;
  overflow: auto;
  min-width: 200px;
}
.pc-name-row {
  display: flex;
  align-items: center;
  gap: 6px;
}
.pc-col-idx {
  flex: none;
  width: 24px;
  text-align: center;
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--el-text-color-placeholder);
}
.preview-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
}
.preview-hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.sftp-dir-hdr { display: flex; align-items: center; gap: 8px; margin-bottom: 6px; flex-wrap: wrap; }
.sftp-dir-table { margin-bottom: 10px; }
.sftp-dir-table .small { font-size: 11px; }

.preview-table {
  width: 100%;
}
/* 预览单元格:长值单行截断,不换行撑高 */
.preview-table .mono {
  display: block;
  max-width: 320px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.preview-col-head {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  line-height: 1.3;
}
.preview-col-name {
  font-family: var(--font-mono);
  font-size: 12px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}
.preview-col-type {
  font-family: var(--font-mono);
  font-size: 10px;
  color: var(--el-text-color-secondary);
}
</style>
