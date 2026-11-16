<!--
  DdlCheckView.vue

  职责：DDL 检查页面：校验建表语句并展示风险或语法结果。
  边界：选择连接和表或输入 DDL 后调用后端生成检查报告；选表状态与报告结果分离，请求失败时保留可重试上下文。
-->

<template>
  <DetailPageTemplate class="ddl-check-view">
    <template #header>
      <div class="page-head">
        <div>
          <h2>表体检</h2>
          <p class="page-sub">
            把平台已经写好的几条护栏问出来:列类型 / 默认值 / 分区键会不会出事。
            只报告、不阻断 —— 它不改动任何东西(面对的多半是已经存在的表);建表与改列的拦截仍在原处。
          </p>
        </div>
      </div>
    </template>

    <div class="ddl-check-content">
      <el-card shadow="never" class="chk-card">
        <div class="chk-form">
          <!--
            连接不再让用户选:体检只针对目标数据源,默认就是它。
            名字保留为纯文字 —— 不然不知道这一眼是在查哪套集群(它只是个展示,不是选项)。
          -->
          <!-- 级联禁用:没连接就选不了库,没库就选不了表 —— 把"顺序"用禁用态表达,而不是点完再报错 -->
          <span class="chk-conn">{{ connName || '目标数据源' }}</span>
          <el-select
            v-model="db"
            placeholder="数据库"
            class="chk-sel"
            :disabled="connId == null"
            @change="onDbChange"
          >
            <el-option v-for="d in dbs" :key="d.name" :label="d.name" :value="d.name" />
          </el-select>
          <!-- 选表变了要清掉上一份报告(见 reset),所以这里不用防抖,直接 change 即处理 -->
          <el-select
            v-model="table"
            placeholder="表"
            class="chk-sel"
            filterable
            :disabled="!db"
            @change="reset"
          >
            <el-option v-for="t in tables" :key="t.name" :label="t.name" :value="t.name" />
          </el-select>
          <el-button type="primary" :loading="loading" :disabled="!table" @click="run">体检</el-button>
        </div>
      </el-card>

      <el-card v-if="result" shadow="never" class="chk-card chk-result-card">
        <div class="chk-facts">
          <span class="chk-name">{{ result.database }}.{{ result.table }}</span>
          <span>引擎 <b>{{ result.engine || '?' }}</b></span>
          <span>分区键 <b>{{ result.partitionKeyExpr || '无' }}</b></span>
          <span>排序键 <b>{{ result.sortingKeyExpr || '无' }}</b></span>
          <span>共 <b>{{ result.columns.length }}</b> 列</span>
        </div>

        <!-- 每条结论:级别徽章 + 标题 + 命中列名(有的话),长说明收进 details -->
        <div v-if="result.findings.length" class="chk-findings">
          <div v-for="(f, i) in result.findings" :key="i" :class="['chk-finding', f.level]">
            <div class="chk-finding-title">
              <span class="chk-badge">{{ f.level === 'error' ? '问题' : '注意' }}</span>
              <b>{{ f.title }}</b>
              <span v-if="f.column" class="chk-col">{{ f.column }}</span>
            </div>
            <!--
              长说明收进 <details>:分区键那条的 detail 是给"保存时报错"写的长文
              (整整一段),直接铺开会把列表变成一堵墙 —— 10 条结论就没法扫了。
              用原生 details 而不是自己写展开状态:零 JS、键盘可达、默认收起。
            -->
            <details class="chk-why">
              <summary>为什么 / 怎么改</summary>
              <p class="chk-detail">{{ f.detail }}</p>
              <p v-if="f.fix" class="chk-fix">改法:{{ f.fix }}</p>
            </details>
          </div>
        </div>
        <div v-else class="chk-ok">按下面这几条规则检查,没有发现问题。</div>

        <!--
          覆盖面必须写在脸上:「0 条结论」不是「这张表没问题」——
          本工具只跑 rules 里那几条,别的(引擎/排序键/TTL/编码)一概不判断。
        -->
        <div class="chk-rules">
          <div class="chk-rules-title">本次只检查了这几条(其余不判断):</div>
          <ul>
            <li v-for="(r, i) in result.rules" :key="i">{{ r }}</li>
          </ul>
        </div>

        <!--
          表格由外层 1fr 区域拿到确定高度，并用 height="100%" 一直铺到卡片底部；
          几十列时仍保持表头固定、数据区内部滚动。
          show-overflow-tooltip 用于 ClickHouse 很长的类型名，避免每行被折成多行。
        -->
        <div class="chk-table-wrap">
          <el-table :data="result.columns" border stripe size="small" height="100%">
            <el-table-column prop="name" label="列" min-width="150" show-overflow-tooltip />
            <el-table-column prop="type" label="类型" min-width="260" show-overflow-tooltip />
            <el-table-column label="默认值" min-width="180" show-overflow-tooltip>
              <template #default="{ row }">
                <span v-if="row.defaultType">{{ row.defaultType }} {{ row.default }}</span>
                <span v-else class="chk-muted">—</span>
              </template>
            </el-table-column>
            <el-table-column prop="comment" label="注释" min-width="120" show-overflow-tooltip />
            <el-table-column prop="codec" label="编码" width="120" show-overflow-tooltip />
            <el-table-column prop="ttl" label="TTL" width="120" show-overflow-tooltip />
          </el-table>
        </div>
      </el-card>

      <el-card v-else shadow="never" class="chk-card chk-idle">
        选一张表,点「体检」。体检是只读的,不会改这张表。
      </el-card>
    </div>
  </DetailPageTemplate>
</template>

<script setup lang="ts">
/**
 * 工具 · 表体检。
 *
 * 为什么要有这一页:平台的护栏(列类型片段 / 默认值 / 分区键)只在【动手时】才说话 ——
 * 建表改列被拒、同步任务保存时被拦,或者更糟:写入被 CK 拒掉(code 252)之后,
 * 而那时表已经建好、分区键还改不了,只能删表重建。这一页把同一批规则提前成"随时能问"。
 *
 * 两条刻意的取舍:
 *  1. 只报告不阻断:面对的多半是已存在的表,拿它当门没有意义(门仍在建表/改列/同步保存处)。
 *  2. 必须把【覆盖面】显示出来:只跑后端 rules 里那几条,免得把"0 条"当成"没问题"。
 *
 * ⚠️ 测试里【不要】stub teleport:这一页有 3 个 el-select,
 *    给 teleport 打桩会让 EP 的 popper 触发 "Maximum recursive updates exceeded"
 *    (ExploreWizard.test.ts 踩过,当时表现为 10 个 Unhandled Rejection)。
 */
import { computed, onMounted, ref } from 'vue'
import DetailPageTemplate from '@/components/DetailPageTemplate.vue'
import { connectionsApi, workspaceApi } from '@/api'
import type { Connection, DatabaseInfo, TableCheckResult, TableInfo } from '@/types'

const conns = ref<Connection[]>([])
const dbs = ref<DatabaseInfo[]>([])
const tables = ref<TableInfo[]>([])
const connId = ref<number | undefined>(undefined)
const db = ref('')
const table = ref('')
const loading = ref(false)
const result = ref<TableCheckResult | null>(null)

/** 当前连接名(仅展示用 —— 连接固定用默认连接,用户不再选) */
const connName = computed(() => conns.value.find((c) => c.id === connId.value)?.name ?? '')

/** 选表变了就把上一份报告清掉(否则报告与选择器对不上) */
function reset() {
  result.value = null
}

async function onConnChange() {
  // 连接一变,库与表两级都要清空并重拉:留着上一个连接的库名会指向不存在的库
  db.value = ''
  table.value = ''
  dbs.value = []
  tables.value = []
  reset()
  if (connId.value == null) return
  try {
    dbs.value = await workspaceApi.databases(connId.value)
  } catch {
    /* http 拦截器已统一提示,这里不再弹一次 */
  }
}

async function onDbChange() {
  // 同理往下级联:换库要清表清单,否则会出现"这张表不在这个库里"的假选项
  table.value = ''
  tables.value = []
  reset()
  if (connId.value == null || !db.value) return
  try {
    tables.value = await workspaceApi.tables(connId.value, db.value)
  } catch {
    /* 同上 */
  }
}

async function run() {
  // 三个都选齐才发请求(按钮虽已 disabled,但键盘/程序触发仍需挡一道)
  if (connId.value == null || !db.value || !table.value) return
  loading.value = true
  try {
    result.value = await workspaceApi.checkTable(connId.value, db.value, table.value)
  } catch {
    // 不清掉上一份:失败原因由拦截器提示,清空反而让人以为"没查到问题"
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  try {
    conns.value = await connectionsApi.list()
    // 默认连接先选上:绝大多数情况就是它
    const def = conns.value.find((c) => c.isDefault) ?? conns.value[0]
    if (def) {
      connId.value = def.id
      await onConnChange()
    }
  } catch {
    /* 拉不到连接就让人自己选 */
  }
})
</script>

<style scoped>
/* 页面骨架由 DetailPageTemplate 统一提供；这里只保留本页外边距和内容布局。 */
.ddl-check-view {
  height: 100%;
  min-height: 0;
  padding: 20px 24px;
  box-sizing: border-box;
}
/* 头部固定在模板顶部，查询卡也固定；只有结果卡随剩余高度伸展。 */
.ddl-check-content {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.page-head {
  flex: none;
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
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
.chk-form {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.chk-sel {
  width: 200px;
}
/* 固定的连接名:与下拉同一行,但是纯文字(不可点) */
.chk-conn {
  font-size: 13px;
  color: var(--el-text-color-regular);
  padding: 0 4px;
}
.chk-card {
  flex: none;
}
.chk-card :deep(.el-card__body) {
  padding: 14px 16px;
}
/*
  结果卡占满查询卡下方的剩余高度；卡体自身成为纵向 Flex 容器。
  列清单表格放在 1fr 区域中，并显式使用 height="100%"，
  因此无论窗口多高，表格底边都会贴合工作区底部，数据行在表格内部滚动。
*/
.chk-card.chk-result-card {
  flex: 1;
  min-height: 0;
  display: flex;
  overflow: hidden;
}
.chk-card.chk-result-card :deep(.el-card__body) {
  height: 100%;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.chk-table-wrap {
  flex: 1;
  min-height: 180px;
}
.chk-idle {
  color: var(--el-text-color-secondary);
}
.chk-facts {
  display: flex;
  align-items: center;
  gap: 16px;
  flex-wrap: wrap;
  margin-bottom: 12px;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
.chk-name {
  font-weight: 600;
  color: var(--el-text-color-primary);
}
.chk-findings {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-bottom: 12px;
}
.chk-finding {
  border-left: 3px solid var(--el-color-warning);
  background: var(--el-fill-color-light);
  padding: 8px 12px;
  border-radius: 4px;
}
.chk-finding.error {
  border-left-color: var(--el-color-danger);
}
.chk-finding-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13.5px;
  flex-wrap: wrap;
}
.chk-badge {
  font-size: 12px;
  padding: 1px 6px;
  border-radius: 3px;
  background: var(--el-color-warning-light-9);
  color: var(--el-color-warning);
}
.chk-finding.error .chk-badge {
  background: var(--el-color-danger-light-9);
  color: var(--el-color-danger);
}
.chk-col {
  font-family: var(--el-font-family-mono, monospace);
  font-size: 12.5px;
  color: var(--el-text-color-secondary);
}
/* 展开项:默认收起,点了才铺开长说明 */
.chk-why {
  margin-top: 4px;
}
.chk-why > summary {
  cursor: pointer;
  font-size: 12.5px;
  color: var(--el-text-color-secondary);
  user-select: none;
}
.chk-why > summary:hover {
  color: var(--el-color-primary);
}
.chk-detail,
.chk-fix {
  margin: 6px 0 0;
  font-size: 13px;
  line-height: 1.6;
  white-space: pre-wrap;
}
.chk-fix {
  color: var(--el-text-color-regular);
}
.chk-ok {
  margin-bottom: 12px;
  padding: 8px 12px;
  border-radius: 4px;
  background: var(--el-color-success-light-9);
  color: var(--el-color-success);
  font-size: 13.5px;
}
.chk-rules {
  margin-bottom: 12px;
  font-size: 12.5px;
  color: var(--el-text-color-secondary);
}
.chk-rules ul {
  margin: 4px 0 0;
  padding-left: 20px;
}
.chk-muted {
  color: var(--el-text-color-placeholder);
}
</style>
