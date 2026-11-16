<!--
  ConnectionsView.vue

  职责：数据库连接管理页面：新增、编辑、测试和删除共享连接配置。
  边界：加载并筛选数据库连接；新增、编辑、测试和删除经弹窗与 API 完成，成功后保持当前筛选并刷新列表。
-->

<template>
  <div class="connections-view">
    <div class="page-head">
      <div>
        <h2>目标库表</h2>
        <p class="page-sub">管理数据同步的目标数据库连接。内置一个默认连接,点击「打开」进入该库的数据库/表进行管理。</p>
      </div>
    </div>

    <ListQueryBar
      v-model:keyword="query.draftKeyword.value"
      keyword-placeholder="搜索连接名称、地址或数据库"
      @search="query.search"
      @reset="resetListQuery"
    />

    <div class="list-table-ops">
      <div class="list-table-ops-start">
        <el-button type="primary" :icon="Plus" @click="openCreate">新增连接</el-button>
      </div>
    </div>

    <el-table :data="shownConnections" v-loading="loading" border stripe scrollbar-always-on>
      <!--
        状态列:健康检查是【异步】的(见 loadStatus),所以三种形态都要有明确呈现 ——
        还没结果时是「检测中…」,不是空白(空白会被读成"检查失败")。
      -->
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <template v-if="statusMap[row.id]">
            <el-tag v-if="statusMap[row.id].ok" size="small" type="success" class="status-tag">
              <span class="dot dot-ok"></span>正常
            </el-tag>
            <!-- 失败原因挂在 tooltip 上:表格里放不下,但排查时又必须有 -->
            <el-tooltip v-else :content="statusMap[row.id].error || '连接失败'" placement="top">
              <el-tag size="small" type="danger" class="status-tag">
                <span class="dot dot-bad"></span>异常
              </el-tag>
            </el-tooltip>
          </template>
          <span v-else class="muted">检测中…</span>
        </template>
      </el-table-column>
      <el-table-column prop="name" label="名称" min-width="140">
        <template #default="{ row }">
          <span>{{ row.name }}</span>
          <el-tag v-if="row.isDefault" size="small" type="success" style="margin-left: 6px">默认</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="地址" min-width="150">
        <template #default="{ row }">{{ row.host }}:{{ row.port }}</template>
      </el-table-column>
      <el-table-column prop="database" label="数据库" min-width="110" />
      <el-table-column label="协议" width="90">
        <template #default="{ row }">{{ row.protocol === 'http' ? 'HTTP' : 'Native' }}</template>
      </el-table-column>
      <el-table-column prop="username" label="用户名" min-width="110" />
      <!-- 密码只显示"有没有":接口从不回传明文,界面也就不该假装能显示 -->
      <el-table-column label="密码" width="80">
        <template #default="{ row }">
          <el-tag v-if="row.hasPassword" size="small" type="success">已设置</el-tag>
          <el-tag v-else size="small" type="info">未设置</el-tag>
        </template>
      </el-table-column>
      <!-- 安全:只有开了 TLS 才打标,普通连接不占视觉(默认态不需要强调) -->
      <el-table-column label="安全" width="70">
        <template #default="{ row }">
          <el-tag v-if="row.secure" size="small" type="warning">TLS</el-tag>
          <span v-else class="muted">—</span>
        </template>
      </el-table-column>
      <!-- 响应耗时:探活顺带测一次往返,用来区分"通但是很慢"与"根本不通" -->
      <el-table-column label="响应" width="80">
        <template #default="{ row }">
          <span v-if="statusMap[row.id]?.ok" class="latency">{{ statusMap[row.id].latencyMs }}ms</span>
          <span v-else class="muted">—</span>
        </template>
      </el-table-column>
      <el-table-column prop="description" label="描述" min-width="160" show-overflow-tooltip />
      <el-table-column label="操作" width="160" align="center">
        <template #default="{ row }">
          <!-- 下拉而非常排按钮:四个动作平铺会占掉表格一半宽度,这里低频动作收起来 -->
          <ActionIconBar :actions="[
            { command: 'open', label: '打开', primary: true },
            { command: 'edit', label: '编辑' },
            { command: 'test', label: '测试连接' },
            { command: 'remove', label: '删除', danger: true, disabled: row.isDefault },
          ]" @command="(cmd: string) => onCommand(cmd, row)" />
        </template>
      </el-table-column>
    </el-table>

    <ConnectionFormDialog v-model="dialogVisible" :connection="editing" @saved="onSaved" />
  </div>
</template>

<script setup lang="ts">
/**
 * 目标库表(连接管理)。
 *
 * 这一页管的是"连到哪套 ClickHouse":内置一个默认连接(不可删),其余可增删改测。
 * 需要注意的两条约束:
 *  1. 默认连接是内置的 —— 删除项对它是禁用的,后端也会拒绝(界面与后端各挡一道)。
 *  2. 健康状态是【列表到位之后再异步探】的:状态按 id 关联,没有行就没有落脚点。
 */
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { confirmDestructive } from '@/utils/confirm'
import { Plus } from '@element-plus/icons-vue'
import { connectionsApi } from '@/api'
import { useConnectionStore } from '@/stores/connection'
import type { Connection, ConnStatus } from '@/types'
import ConnectionFormDialog from '@/components/ConnectionFormDialog.vue'
import ListQueryBar from '@/components/ListQueryBar.vue'
import { useListQuery } from '@/composables/useListQuery'

import ActionIconBar from '@/components/ActionIconBar.vue'

const router = useRouter()
const store = useConnectionStore()

const loading = ref(false)
const connections = ref<Connection[]>([])
const query = useListQuery()
function resetListQuery() {
  query.reset()
  void load()
}
const shownConnections = computed(() => {
  const words = query.keyword.value.toLowerCase().split(/\s+/).filter(Boolean)
  if (!words.length) return connections.value
  return connections.value.filter((row) => {
    const text = `${row.name} ${row.host} ${row.port} ${row.database} ${row.username} ${row.description ?? ''}`.toLowerCase()
    return words.every((word) => text.includes(word))
  })
})
const dialogVisible = ref(false)
/** 正在编辑的连接;null = 新建(也是对话框判断标题的依据) */
const editing = ref<Connection | null>(null)
/** 连接 ID → 健康状态 */
const statusMap = ref<Record<number, ConnStatus>>({})

function onSaved() {
  if (!editing.value) query.reset()
  void load()
}

async function load() {
  loading.value = true
  try {
    connections.value = [...await connectionsApi.list()].sort((a, b) => b.id - a.id)
    // 列表到位后再探活：状态列按 id 关联，必须等行数据先渲染出来
    await loadStatus()
  } finally {
    loading.value = false
  }
}

/**
 * 并发获取所有连接的健康状态(端口通 + SELECT 1)。
 *
 * 探活整体失败【不】向外抛:列连接列表是主任务,探活只是附加信息,
 * 让它把整页搞成错误页不值得 —— 状态列会停在「检测中…」。
 */
async function loadStatus() {
  // 先清空：本轮未返回状态的行走模板的「检测中…」分支，而不是沿用上一轮的旧结果
  statusMap.value = {}
  try {
    const list = await connectionsApi.status()
    // 转成 map 是为了模板里能 O(1) 按 id 取(表格每行都要查一次)
    const map: Record<number, ConnStatus> = {}
    list.forEach((s) => (map[s.id] = s))
    statusMap.value = map
  } catch {
    /* 状态检查失败不阻塞列表 */
  }
}

function openCreate() {
  // editing = null 是"新建"的信号,对话框据此切换标题与提交行为
  editing.value = null
  dialogVisible.value = true
}

/** 编辑:直接传整个对象(对话框内部再拷贝一份,避免改到列表里的引用) */
function openEdit(row: Connection) {
  editing.value = row
  dialogVisible.value = true
}

function open(row: Connection) {
  // 先落到 store 再跳转：WorkspaceView 靠 store.active 决定管理哪个库
  store.setActive(row)
  router.push('/workspace')
}

/** 操作下拉分发 —— 入口收在一处,行内只留一个按钮 */
function onCommand(cmd: string, row: Connection) {
  switch (cmd) {
    case 'open':
      open(row)
      break
    case 'edit':
      openEdit(row)
      break
    case 'test':
      test(row)
      break
    case 'remove':
      remove(row)
      break
  }
}

/**
 * 手动测试连接。成功/失败都只用来提示,状态列一律以 status() 的回刷为准 ——
 * 这样"刚测过"与"页面显示"永远同源,不会出现两套口径互相打脸。
 */
async function test(row: Connection) {
  try {
    await connectionsApi.testById(row.id)
    ElMessage.success(`「${row.name}」连接成功 ✓`)
    // 成功失败都要回刷状态列：失败时错误信息得立刻反映到「异常」气泡里
    loadStatus()
  } catch {
    /* 拦截器已提示 */
    loadStatus()
  }
}

async function remove(row: Connection) {
  // 删除连接前展示对象名称并确认
  if (!(await confirmDestructive('删除连接', row.name, '删除'))) return
  try {
    await connectionsApi.remove(row.id)
    ElMessage.success('已删除')
    // 删掉的正是当前工作台所用的连接时同步清空，否则 WorkspaceView 会继续指向已不存在的库
    if (store.active?.id === row.id) store.setActive(null)
    load()
  } catch {
    /* 拦截器已提示 */
  }
}

onMounted(load)
</script>

<style scoped>
/* 与其它整页视图同一套骨架:占满高度、整页滚动、统一内边距 */
.connections-view {
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
  gap: 8px;
}
.muted {
  color: var(--el-text-color-placeholder);
}
/* 状态标签:圆点 + 文字必须基线对齐,所以标签本身用 inline-flex(EP 默认是行内元素) */
.status-tag {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-family: var(--font-ui);
}
.dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  display: inline-block;
  flex: none; /* 不参与压缩:标签变窄时圆点不能被挤扁 */
}
/*
  圆点带同色微光(不是给好看):表格里绿/红两个 7px 的点很容易被当成装饰,
  发光后一眼就能在整列里定位到异常那行。
*/
.dot-ok {
  background: #34d399;
  box-shadow: 0 0 4px rgba(52, 211, 153, 0.6);
}
.dot-bad {
  background: #f87171;
  box-shadow: 0 0 4px rgba(248, 113, 113, 0.6);
}
/* 耗时用等宽:数字长度变化时不会带着整列左右晃 */
.latency {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
/* 「操作」按钮里的文字与箭头同排 */
.op-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.op-arrow {
  margin-left: 2px;
}
</style>
