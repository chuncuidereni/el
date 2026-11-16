<!--
  DatabaseDetail.vue

  职责：数据库详情组件：展示元数据并提供表级操作入口。
  边界：按 connId/db 加载元数据并发出 createTable/tableOpened/deleted；表操作由父页面执行，本组件维护当前库的加载与空态。
-->

<template>
  <div class="database-detail">
    <!--
      数据库页签的正文:本库的表清单 + 建表/删库入口。
      由 WorkspaceView 用 lazy 页签承载,一个页签一个实例;切换连接时父级会清空页签列表,
      所以 props(connId / db)在实例生命周期内不变 —— 拉一次数据即可,不需要 watch 重取。
    -->
    <div class="toolbar">
      <!-- 标题走 dbLabel:前台只暴露层级名(标准源表（ods_data） / 目标库表（dwd_data）),物理库名是实现细节,不进 UI -->
      <span class="db-title">{{ dbLabel(db) }}</span>
      <!-- 备注只占一行(见 .db-remark 的 max-width + ellipsis),完整内容交给原生 title 悬浮提示 -->
      <span v-if="remark" class="db-remark" :title="remark">{{ remark }}</span>
      <el-button size="small" :icon="Refresh" @click="load">刷新</el-button>
      <el-button size="small" type="primary" :icon="Plus" @click="emit('createTable', db)">新建表</el-button>
      <!-- default 是 CK 内置库,后端 DatabaseService.drop 明确拒绝删除,这里先隐藏入口,免得用户点了才被拒 -->
      <el-button v-if="db !== 'default'" size="small" type="danger" plain @click="onDrop">删除数据库</el-button>
    </div>

    <!-- 只列表名/引擎/操作三项:列结构、行数这类重元数据留到「打开」后的表页签里再拉,避免列表接口变重 -->
    <el-table :data="tables" v-loading="loading" border size="small">
      <el-table-column prop="name" label="表名" min-width="160" />
      <el-table-column prop="engine" label="引擎" min-width="160" />
      <el-table-column label="操作" width="120">
        <template #default="{ row }">
          <!-- 「打开」只上报,不自己路由:开页签、去重、切激活页都是父级的职责(见 WorkspaceView.openTableTab) -->
          <span v-if="row.engine === 'MaterializedView'" class="pipe-hint">写入管道</span>
          <el-button v-else size="small" type="primary" link class="op-act" @click="emit('tableOpened', db, row.name)">打开</el-button>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Plus, Refresh } from '@element-plus/icons-vue'
import { workspaceApi } from '@/api'
import { dbLabel } from '@/utils/layer'
import type { TableInfo } from '@/types'
import { confirmDestructive } from '@/utils/confirm'

/** connId 与 db 由父级固定传入(db 是物理库名,展示时再经 dbLabel 转层级名);remark 来自库列表,可能没有 */
const props = defineProps<{ connId: number; db: string; remark?: string }>()
// 三种副作用都交给父级:tableOpened(开表页签)、createTable(开建表弹窗)、deleted(关掉本页签)
const emit = defineEmits<{
  (e: 'tableOpened', db: string, table: string): void
  (e: 'createTable', db: string): void
  (e: 'deleted'): void
}>()

const loading = ref(false)
// 只存表清单:行数/列信息不在列表接口里,想看得先「打开」表页签
const tables = ref<TableInfo[]>([])

async function load() {
  loading.value = true
  try {
    // 直接整体替换列表:库里的表可能被其它会话改过,增量合并反而会留下已删除的表
    tables.value = await workspaceApi.tables(props.connId, props.db)
  } catch {
    /* 拦截器已提示;这里不清空 tables —— 加载失败(如瞬时断连)时保留上一次结果比显示空列表更有用 */
  } finally {
    // loading 必须收在 finally:失败路径同样要解锁,否则刷新按钮会一直转
    loading.value = false
  }
}

async function onDrop() {
  // 确认框展示层级名,不暴露物理库名(见 utils/layer.ts)
  if (!(await confirmDestructive('删除数据库', dbLabel(props.db), '删除'))) return
  await workspaceApi.dropDatabase(props.connId, props.db)
  ElMessage.success('数据库已删除')
  // 只上报结果:关页签由父级做,本组件不自行销毁(页签的增删统一归 WorkspaceView 管)
  emit('deleted')
}

// 挂载即拉取:页签是 lazy 的,首次激活时父级才创建本实例,正好等价于"打开时加载"
onMounted(load)
</script>

<style scoped>
/* 撑满页签容器并自行滚动:表多时滚的是内容区,页签条本身保持可见 */
.database-detail {
  height: 100%;
  overflow: auto;
}
.toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
}
.db-title {
  font-weight: 600;
  margin-right: 4px;
}
/* flex:none 让备注不参与收缩,220px 上限 + 省略号才有确定宽度(否则会被标题挤没) */
.db-remark {
  flex: none;
  max-width: 220px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 13px;
  color: var(--el-text-color-placeholder);
  margin-right: 8px;
}
</style>
