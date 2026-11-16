<!--
  SqlQueryView.vue

  职责：SQL 查询页面：管理 SQL 分组、编辑器、执行、结果展示与导出。
  边界：管理查询分组、SQL/可视化模式、执行和多结果标签；自动保存与导出定时器在卸载时清理，含程序算子的构建器 SQL 必须走可视化接口。
-->

<template>
  <div class="sqlq-view">
    <!-- 右:列表 / 编辑器 -->
    <main class="sq-main">
      <el-alert v-if="!auth.sqlExecutionEnabled" class="sq-access-note" type="info" show-icon :closable="false" title="可编辑、保存和分组管理自己的 SQL 查询；当前暂未开放 SQL 运行及结果导出。查看表数据请前往「我的数据与共享」。" />
      <!-- 列表 -->
      <template v-if="!editing">
        <div class="sq-head">
          <div>
            <h2>数据探索</h2>
            <p class="sq-sub">保存自己的常用 SQL，按分组归类；点「打开」进入编辑器。</p>
          </div>
        </div>
        <ListQueryBar
          v-model:group="query.draftGroup.value"
          v-model:keyword="query.draftKeyword.value"
          all-label="全部"
          :all-count="queries.length"
          special-label="默认分组"
          special-value="default"
          :special-count="defaultCount"
          :groups="groups"
          keyword-placeholder="搜索探索名称"
          @search="query.search"
          @reset="resetListQuery"
          @new="gmgNew"
          @edit="gmgSelect"
          @remove="gmgRemove"
        />
        <div class="list-table-ops">
          <div class="list-table-ops-start">
            <el-button type="primary" :icon="Plus" @click="openCreate">新建探索</el-button>
            <el-button v-if="selectedQueries.length" type="danger" :icon="Delete" :loading="deletingBatch" :disabled="deletingQueryIds.size > 0 && !deletingBatch" @click="batchRemoveQueries">批量删除({{ selectedQueries.length }})</el-button>
          </div>
        </div>
        <div class="sq-tbl-wrap" v-loading="loading">
          <el-table
            :data="pagedQueries"
            border
            stripe
            size="small"
            height="100%"
            scrollbar-always-on
            ref="sqTableRef"
            row-key="id"
            @selection-change="onSqSelectionChange"
          >
            <el-table-column type="selection" width="42" align="center" />
            <el-table-column type="index" :index="globalIndex" width="60" align="center" label="序号" />
            <el-table-column prop="name" label="名称" min-width="150">
              <template #default="{ row }">
                <span class="mono tbl-link" @click="openEditor(row)">{{ row.name }}</span>
              </template>
            </el-table-column>
            <!-- 层级名带物理库名(如「标准源表（ods_data）」),110 会换行/截断,给足宽度 -->
            <el-table-column label="层级" width="180">
              <template #default="{ row }">
                <el-tag
                  size="small"
                  effect="plain"
                  :style="dbAccent(row.database) ? { color: dbAccent(row.database), borderColor: dbAccent(row.database) } : {}"
                >
                  {{ dbLabel(row.database) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="description" label="描述" min-width="140" show-overflow-tooltip>
              <template #default="{ row }">
                <span v-if="row.description">{{ row.description }}</span>
                <span v-else class="dim">—</span>
              </template>
            </el-table-column>
            <el-table-column label="所属分组" width="140">
              <template #default="{ row }">
                <el-select
                  :model-value="row.groupId ?? 0"
                  size="small"
                  style="width: 120px"
                  @change="(v: any) => moveQuery(row, Number(v))"
                >
                  <el-option :value="0" label="默认分组" />
                  <el-option v-for="g in groups" :key="g.id" :value="g.id" :label="g.name" />
                </el-select>
              </template>
            </el-table-column>
            <el-table-column label="更新时间" width="160">
              <template #default="{ row }"><span class="mono dim">{{ fmtDt(row.updatedAt) }}</span></template>
            </el-table-column>
            <el-table-column label="操作" width="150" align="center">
              <template #default="{ row }">
                <el-button size="small" type="primary" link @click="openEditor(row)">打开</el-button>
                <el-button size="small" type="danger" link :loading="deletingQueryIds.has(row.id)" @click="removeQuery(row)">删除</el-button>
              </template>
            </el-table-column>
            <template #empty>
              <div class="sq-empty-hint">暂无保存的探索,点上方「新建探索」开始</div>
            </template>
          </el-table>
        </div>
        <div class="sq-pager">
          <el-pagination
            background
            layout="total, sizes, prev, pager, next, jumper"
            :total="shownQueries.length"
            :current-page="listPage"
            :page-size="listPageSize"
            :page-sizes="[10, 20, 50, 100]"
            @current-change="(p: number) => listPage = p"
            @size-change="(s: number) => { listPageSize = s; listPage = 1 }"
          />
        </div>
      </template>

      <!-- 编辑器 -->
      <template v-else>
        <div class="sq-head">
          <div class="ed-head-left">
            <el-button size="small" :icon="Back" @click="backToList">返回列表</el-button>
            <el-input v-model="editing.name" size="small" class="ed-name" placeholder="查询名称" @input="dirty = true" />
            <el-tag size="small" effect="plain">{{ dbLabel(editing.database) }}</el-tag>
            <el-radio-group v-model="editorMode" size="small">
              <el-radio-button value="visual">可视化</el-radio-button>
              <el-radio-button value="sql">SQL</el-radio-button>
            </el-radio-group>
            <el-tag v-if="dirty" size="small" type="warning" effect="plain">未保存</el-tag>
            <!-- 自动保存:开启后停笔自动落库;右侧显示保存状态 -->
            <el-tooltip placement="bottom" :content="autoSaveTip">
              <el-switch
                v-model="autoSave"
                size="small"
                inline-prompt
                active-text="自动"
                inactive-text="手动"
                class="ed-autosave"
              />
            </el-tooltip>
            <span v-if="autoSave && autoSaveState" class="ed-save-state" :class="autoSaveState.cls">
              {{ autoSaveState.text }}
            </span>
          </div>
          <div class="actions">
            <el-button size="small" :icon="DocumentCopy" :loading="savingAs" :disabled="saving || autoSaving" @click="saveAs">另存为</el-button>
            <el-button size="small" type="primary" :loading="saving" :disabled="autoSaving" @click="save">保存</el-button>
          </div>
        </div>
        <div class="sql-toolbar" v-show="editorMode === 'sql'">
          <el-checkbox v-model="allowWrite" :disabled="!auth.sqlExecutionEnabled">允许写操作</el-checkbox>
          <el-button type="primary" :loading="running" :disabled="!auth.sqlExecutionEnabled" @click="runSql">运行 (Ctrl+Enter)</el-button>
          <el-button :icon="Clock" @click="historyVisible = true">历史</el-button>
          <el-button :icon="MagicStick" @click="snippetVisible = true">片段库</el-button>
          <span v-if="elapsed >= 0" class="elapsed">{{ elapsed }} ms</span>
          <span class="sql-hint">可直接写表名(默认在本库解析);跨库请写 <b class="mono">库.表名</b></span>
        </div>
        <!-- 配置区 / 结果区 可拖拽分栏(默认收窄,执行查询后展开) -->
        <div class="sq-split-wrap" ref="splitWrapRef">
        <div class="sq-config" :style="{ flexBasis: splitPct + '%' }">
        <div class="editor-box" v-show="editorMode === 'sql'">
          <CodeMirrorEditor
            ref="codeMirrorRef"
            :model-value="editing.sqlText"
            :completion-data="completionData"
            @update:model-value="(v: string) => { if (editing) { editing.sqlText = v; dirty = true } }"
            @run="runSql"
          />
        </div>
        <!-- ⚠️ 用 v-show 而非 v-if:切到 SQL 再切回来时 builder 保持挂载,配置不丢 -->
        <div class="builder-box" v-show="editorMode === 'visual'">
          <VisualQueryBuilder
            ref="visualRef"
            :conn-id="connId"
            :database="editing.database"
            :execution-enabled="auth.sqlExecutionEnabled"
            :external-sql="editing.sqlText"
            @run="onVisualRunUi"
            @open-in-sql="onOpenInSql"
            @change="onVisualChange"
            :collapse-signal="collapseSignal"
            @field-panel="onFieldPanelChange"
          />
        </div>
        </div>

        <!-- 拖拽调整配置区/结果区高度;双击复位 -->
        <div
          class="sq-divider"
          :class="{ dragging: splitDragging }"
          title="拖动调整高度 · 双击复位"
          @mousedown.prevent="onSplitDrag"
          @dblclick="resetSplit"
        >
          <span class="sq-divider-grip"></span>
        </div>

        <div class="sql-result" v-loading="running">
          <div class="sql-result-body">
          <!-- 执行失败且下方还留着旧结果:必须显著标注,否则会被误当成"配置没生效" -->
          <div v-if="queryFailed && resultTabs.length" class="stale-banner">
            <div class="sb-main">⚠ 本次执行失败:{{ queryError }}</div>
            <div class="sb-sub">下方是<b>上一次运行</b>的结果,可能与你当前的配置不一致</div>
          </div>
          <div v-if="message" class="query-msg">{{ message }}</div>
          <!--
            本次实际执行的 SQL 已挂到结果标题行的「SQL」按钮(弹窗查看)。
            原来在这里是个折叠块:即使收起也要占一条(约 34px),把结果表往下推。
          -->
          <el-tabs
            v-if="resultTabs.length"
            v-model="activeResult"
            type="card"
            size="small"
            closable
            :class="{ 'res-tabs-single': hideTabBar }"
            class="res-tabs"
            @tab-remove="closeResultTab"
          >
            <el-tab-pane
              v-for="(t, ti) in resultTabs"
              :key="t.key"
              :name="t.key"
              :label="tabLabel(t)"
            >
              <template v-if="t.kind === 'grid'">
                <div class="result-title">
                  <span v-if="queryTruncated" class="meta-warn">已截断</span>
                  <span>{{ t.result!.rows.length }} 行 × {{ t.result!.columns.length }} 列</span>
                  <!-- 结果操作放在结果旁边:可视化模式也能用(那里 SQL 工具栏被隐藏) -->
                  <span class="rt-spacer"></span>
                  <!-- 本次执行的 SQL:挂在标题行上 = 不额外占高度 -->
                  <el-button
                    size="small"
                    text
                    :icon="Tickets"
                    :disabled="!executedSql"
                    @click="sqlDialogVisible = true"
                  >
                    SQL
                  </el-button>
                  <el-button
                    size="small"
                    text
                    type="primary"
                    :icon="Download"
                    :disabled="exporting"
                    @click="openExport"
                  >
                    导出
                  </el-button>
                  <!--
                    关闭当前结果(原来叫「清空」=一次把全部结果清掉)。
                    粒度跟上面的标签页保持一致:关掉手上这个,不是全盘清。
                    关掉最后一个 = 回到空态(高度也收回去)。
                  -->
                  <el-button size="small" text :icon="Close" @click="closeActiveResult">关闭</el-button>
                </div>
                <!-- ⚠️ :key 带运行序号 → 每次执行重建,避免复用旧的表格列定义 -->
                <ResultGrid
                  :key="`grid-${t.seq}-${ti}`"
                  :result="t.result!"
                  :truncated="queryTruncated"
                  :max-height="gridMaxHeight"
                  :index-start="(page - 1) * pageSize + 1"
                />
              </template>
              <div v-else class="query-msg">✓ {{ t.message }}</div>
            </el-tab-pane>
          </el-tabs>
          <!--
            空态:结果区默认只留一条窄条(实测 74px),所以用一行居中提示,
            而不用 el-empty —— 后者图标 60px + 内边距共 110px,窄条里必然被裁掉:
            实测「编写 SQL 后运行」这行字会掉到可视区外。
          -->
          <div v-if="!resultTabs.length && !message" class="sql-empty">
            编写 SQL 后运行,结果将显示在此
          </div>
          </div>
          <!--
            分页:固定在底部,不随结果滚动。
            ⚠️ 每页上限收到 500 —— 结果表没有虚拟滚动,1000 行 × 几十列会明显掉帧。
          -->
          <div v-if="hasGrid" class="res-pager">
            <el-pagination
              background
              layout="total, sizes, prev, pager, next, jumper"
              :total="activeResultObj?.total ?? 0"
              :current-page="page"
              :page-size="pageSize"
              :page-sizes="[50, 100, 200, 500]"
              :disabled="running"
              @current-change="onPageChange"
              @size-change="onPageSizeChange"
            />
          </div>
        </div>
        </div>
      </template>
    </main>

    <!-- SQL 片段库 -->
    <el-drawer
      v-model="snippetVisible"
      :modal="false"
      :with-header="false"
      size="680px"
      class="sn-drawer"
    >
      <div class="sn-wrap">
        <div class="sn-head">
          <div class="sn-head-line">
            <span class="sn-title">SQL 片段库</span>
            <span class="sn-sub">{{ SNIPPET_TOTAL }} 条 · 已实测验证</span>
          </div>
          <el-input
            v-model="snippetKw"
            size="small"
            placeholder="搜索片段(搜全部组)…"
            :prefix-icon="Search"
            clearable
            class="sn-search"
          />
        </div>
        <div class="sn-body">
          <div class="sn-groups" :class="{ dim: !!snippetKw.trim() }">
            <div
              v-for="g in SQL_SNIPPETS"
              :key="g.key"
              class="sn-gitem"
              :class="{ active: !snippetKw.trim() && snippetGroup === g.key }"
              @click="snippetGroup = g.key; snippetKw = ''"
            >
              <span class="sn-dot" :style="{ background: g.accent }"></span>
              <span class="sn-gname">{{ g.label }}</span>
              <span class="sn-gcount">{{ g.items.length }}</span>
            </div>
          </div>
          <div class="sn-list">
            <div v-if="snippetKw.trim()" class="sn-list-hint">搜索结果(跨全部 {{ SQL_SNIPPETS.length }} 组)</div>
            <div
              v-for="s in filteredSnippets"
              :key="s.key"
              class="sn-card"
              :class="{ warn: s.warn }"
              @click="insertSnippet(s)"
            >
              <div class="sn-card-head">
                <span class="sn-label">{{ s.label }}</span>
                <span class="sn-ins">插入</span>
              </div>
              <div v-if="s.desc" class="sn-desc">{{ s.desc }}</div>
              <pre class="sn-code">{{ s.sql }}</pre>
            </div>
            <el-empty v-if="!filteredSnippets.length" description="没有匹配的片段" :image-size="50" />
          </div>
        </div>
      </div>
    </el-drawer>

    <!-- 新建探索 -->
    <el-dialog v-model="createVisible" title="新建探索" width="460px" :close-on-click-modal="false">
      <!--
        label-width 用 auto 而不是写死的 70px:
        「数据层级」四个中文字 + 必填星号比 70px 宽,写死会被挤成两行,
        auto 会按最宽的标签统一宽度,同时保证各控件的左边界对齐。
      -->
      <el-form label-width="auto" :disabled="creating">
        <el-form-item label="名称" required>
          <el-input v-model="createForm.name" placeholder="如:贴源手机号抽样" />
        </el-form-item>
        <el-form-item label="数据层级" required>
          <el-radio-group v-model="createForm.database" class="layer-opts">
            <el-radio v-for="o in LAYERS" :key="o.db" :value="o.db" class="layer-opt">
              <!-- 层级名为主、括号里附物理库名(见 utils/layer.ts 的展示约定) -->
              <div class="layer-line">
                <span class="layer-name">{{ o.label }}</span>
              </div>
              <div class="layer-desc">{{ o.desc }}</div>
            </el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="分组">
          <el-select v-model="createForm.groupId" clearable placeholder="默认分组" style="width: 100%">
            <el-option :value="0" label="默认分组" />
            <el-option v-for="g in groups" :key="g.id" :value="g.id" :label="g.name" />
          </el-select>
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="createForm.description" placeholder="可选" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="doCreate">创建并打开</el-button>
      </template>
    </el-dialog>

    <!-- 分组管理 -->
    <el-dialog v-model="groupDlgVisible" :title="gmgEditing ? '编辑分组' : '新建分组'" width="420px" :close-on-click-modal="false">
      <el-form label-width="64px" @submit.prevent>
        <el-form-item label="名称">
          <el-input v-model="gmgName" maxlength="50" placeholder="分组名称" @keyup.enter="gmgSave" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="gmgRemark" maxlength="100" placeholder="可选" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="groupDlgVisible = false">取消</el-button>
        <el-button type="primary" :loading="gmgSaving" @click="gmgSave">保存</el-button>
      </template>
    </el-dialog>

    <!-- 本次执行的 SQL:列名对不上时先看这里(原先是结果区上方的折叠块,占一条高度) -->
    <el-dialog v-model="sqlDialogVisible" title="本次执行的 SQL" width="760px" :close-on-click-modal="false">
      <div class="exec-sql-dlg">
        <p class="exec-sql-tip">
          列名对不上时,先看这里。可视化模式下这条 SQL 由当前配置生成,与「运行」执行的是同一条。
        </p>
        <SqlCode :sql="executedSql" />
      </div>
      <template #footer>
        <el-button @click="copyExecSql">复制</el-button>
        <el-button type="primary" @click="sqlDialogVisible = false">关闭</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="exportVisible" title="导出查询结果 CSV" width="540px" :close-on-click-modal="false">
      <el-alert
        v-if="progFieldCount"
        type="warning"
        :closable="false"
        show-icon
        class="exp-prog-tip"
      >
        <template #title>
          <div>本次结果含 <b>{{ progFieldCount }}</b> 个程序计算列(SM3 / 3DES / FF1 等)。</div>
          <div>导出会按<b>当前配置</b>重新计算,不会漏出原始值。</div>
        </template>
      </el-alert>
      <el-form label-width="90px">
        <el-form-item label="导出列">
          <el-checkbox-group v-model="exportCols" class="exp-cols" @change="onExportColsChange">
            <el-checkbox v-for="c in exportAllCols" :key="c" :value="c" :label="c" />
          </el-checkbox-group>
          <div class="exp-tips">
            <el-button size="small" link type="primary" @click="exportCols = [...exportAllCols]; onExportColsChange()">全选</el-button>
            <el-button size="small" link @click="exportCols = []; onExportColsChange()">清空</el-button>
          </div>
        </el-form-item>
        <el-form-item v-if="exportCols.length" label="表头重命名">
          <div v-for="(c, i) in exportCols" :key="c" class="exp-rename">
            <span class="mono exp-from">{{ c }}</span>
            <el-input size="small" v-model="exportHeaderNames[i]" :placeholder="c" style="width: 200px" @input="onHeaderNameInput(i)" />
          </div>
        </el-form-item>
        <el-form-item label="包含表头">
          <el-switch v-model="exportWithHeader" />
        </el-form-item>
        <el-form-item label="分隔符">
          <el-select v-model="exportDelimiter" style="width: 160px">
            <el-option label="逗号 ," value="," />
            <el-option label="制表符 Tab" value="\t" />
            <el-option label="分号 ;" value=";" />
            <el-option label="竖线 |" value="|" />
          </el-select>
        </el-form-item>
        <el-form-item label="行数限制">
          <el-input-number v-model="exportLimit" :min="0" :max="MAX_EXPORT_ROWS" />
          <span class="exp-tips">0 = 不限(导出全部)</span>
        </el-form-item>
        <el-form-item v-if="exporting || exportProgress > 0" label="进度">
          <el-progress :percentage="exportProgress" style="width: 100%" />
          <div class="exp-tips">{{ exportStatusText }}</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <!-- 导出中不允许关窗:请求没有中断机制,关了它照样跑完并弹下载 -->
        <el-button :disabled="exporting" @click="exportVisible = false">取消</el-button>
        <el-button type="primary" :loading="exporting" :disabled="!exportCols.length" @click="doExport">导出</el-button>
      </template>
    </el-dialog>

    <!-- 查询历史 -->
    <el-drawer v-model="historyVisible" title="查询历史" size="520px">
      <div class="sq-history">
        <div class="sq-history-top">
          <span class="dim">最近 {{ history.length }} 条(存于本地浏览器)</span>
          <el-button size="small" link type="danger" :disabled="!history.length" @click="clearHistory">清空历史</el-button>
        </div>
        <el-empty v-if="!history.length" description="暂无历史" :image-size="60" />
        <div v-for="(h, i) in history" :key="i" class="sq-hist-item">
          <div class="sq-hist-head">
            <el-tag size="small" :type="h.ok ? 'success' : 'danger'" effect="plain">{{ h.ok ? '成功' : '失败' }}</el-tag>
            <el-tag size="small" effect="plain">{{ dbLabel(h.db) }}</el-tag>
            <span class="dim mono">{{ h.at }}</span>
            <span class="dim">{{ h.ms }} ms</span>
            <el-button size="small" link type="primary" @click="applyHistory(h)">载入</el-button>
          </div>
          <div class="sq-hist-sql mono">{{ h.sql }}</div>
        </div>
      </div>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  Back,
  Clock,
  Close,
  Delete,
  DocumentCopy,
  Download,
  EditPen,
  Folder,
  FolderAdd,
  MagicStick,
  Plus,
  Search,
  Setting,
  Tickets
} from '@element-plus/icons-vue'
import CodeMirrorEditor from '@/components/CodeMirrorEditor.vue'
import { fmtDt } from '@/utils/format'
import { SQL_SNIPPETS, SNIPPET_TOTAL, type SQLSnippet } from '@/constants/sqlSnippets'
import ListQueryBar from '@/components/ListQueryBar.vue'
import { useListQuery } from '@/composables/useListQuery'
import ResultGrid from '@/components/ResultGrid.vue'
import SqlCode from '@/components/SqlCode.vue'
import VisualQueryBuilder from '@/components/visual/VisualQueryBuilder.vue'
import { connectionsApi, queryWorkflowsApi, sqlQueriesApi, sqlQueryGroupsApi, workspaceApi } from '@/api'
import type {
  QueryResult,
  QueryWorkflowConfig,
  SQLQuery,
  SQLQueryGroup,
} from '@/types'
import { confirmAction, confirmDestructive } from '@/utils/confirm'
import { MAX_EXPORT_ROWS } from '@/constants/export'
import { useNavReset } from '@/composables/useNavReset'
import { LAYERS, dbAccent, dbLabel } from '@/utils/layer'
import { privateStorage } from '@/utils/privateStorage'
import { useAuthStore } from '@/stores/auth'

const STORAGE_HD = 'el2ck.sqlHistory'
const queryStorage = privateStorage()
const auth = useAuthStore()

const connId = ref(0)
const loading = ref(false)
const saving = ref(false)
const savingAs = ref(false)
const creating = ref(false)
const queries = ref<SQLQuery[]>([])
const groups = ref<SQLQueryGroup[]>([])

const query = useListQuery()
const filter = query.group
const kw = query.keyword

// —— 编辑器 ——
const editing = ref<SQLQuery | null>(null)
const dirty = ref(false)
const allowWrite = ref(false)
const running = ref(false)
// 结果以「可关闭的 Tab」形式管理(多语句 -> 多个结果/消息 Tab)
interface ResultTab {
  key: string
  kind: 'grid' | 'msg'
  result?: QueryResult
  message?: string
  /** 产生该结果的原始语句(单条),导出时使用 */
  stmt?: string
  /**
   * 运行序号(每次执行唯一)。
   * ⚠️ key 恒为 r0 会导致结果面板跨运行被复用 —— 表格列头不会更新,必须靠它强制重建。
   */
  seq: number
}
const resultTabs = ref<ResultTab[]>([])
const queryTruncated = ref(false)
const message = ref('')
/** 本次执行是否失败(失败时结果区可能still是上一次的) */
const queryFailed = ref(false)
/** 本次执行失败的原始错误信息 */
const queryError = ref('')
/** 本次实际执行的 SQL(展示在结果区,便于和可视化配置对账) */
const executedSql = ref('')
/**
 * 当前结果集的来源。
 *
 * - `sql`    :手写 SQL(走通用 /connections/:id/query)
 * - `visual` :可视化配置(走 /query-workflows/execute,**只传 config**)
 *
 * ⚠️ 这里快照的是 **config 而不是后处理计划** —— 计划由后端每次从 config 重新推导,
 * 所以分页/导出不可能出现"计划漏传或被改配置后过期"的问题。
 */
const resultSource = ref<{ kind: 'sql' } | { kind: 'visual'; config: QueryWorkflowConfig }>({
  kind: 'sql'
})

/**
 * 构建器最近一次生成的 SQL。
 *
 * ⚠️ 用途:可视化 → SQL 模式后,编辑器内容可能仍是「构建器产物」(用户没手改)。
 *    这种 SQL 里【不含】程序算子(它们由后端在结果返回后计算),若按手写 SQL 执行/导出,
 *    SM3/3DES 列会以明文出现且零提示。所以用它来判断「能否继续按可视化执行」。
 */
const visualSqlSnapshot = ref('')

/** 当前结果里由程序算子计算的列数(仅可视化来源) */
const progFieldCount = computed(() => {
  const src = resultSource.value
  if (src.kind !== 'visual') return 0
  return (src.config.fields ?? []).filter((f) => (f.prog ?? []).length > 0).length
})

// ─────────── 配置区 / 结果区 可拖拽分栏 ───────────
/**
 * 结果区【空态】高度:配置区占满,结果区只留一条提示。
 * 刚打开页面时就是在写 SQL / 配筛选,把空间让给上半部分。
 */
const SPLIT_MAX = 78
const SPLIT_IDLE = SPLIT_MAX // 空态 = 配置区放到最大
/**
 * 执行查询后展开到的高度 —— 配置区占 28%,结果区占 72%。
 *
 * 原来是 42(结果区 58%),但结果表才是真正常看的东西(尤其是宽表/圈选场景),
 * 而且字段配置现在默认折叠,配置区不需要那么多高度。
 */
const SPLIT_DEFAULT = 28
const SPLIT_MIN = 18

/**
 * 初始固定为空态。
 *
 * ⚠️ 故意【不读 localStorage】。曾经把拖动比例存起来、初始值也读它,结果出现一个
 *    很迷惑的 bug:双击分隔条复位时把空态值(78)也写进了 localStorage,下次
 *    「运行后展开」读到的就是 78 —— 等于根本没展开。
 *    需求是「默认最低、运行后回到基准高度」,用固定值最可预期。
 */
const splitPct = ref(SPLIT_IDLE)
/** 用户本次手动拖过分栏 → 之后执行查询不再自动改高度,尊重用户拖出来的结果 */
let userAdjustedSplit = false
const splitDragging = ref(false)
const splitWrapRef = ref<HTMLElement>()
const splitWrapH = ref(0) // 供结果表格算可用高度

let ro: ResizeObserver | null = null
/** 拖拽中遗留的清理函数(组件卸载时兜底调用) */
let dragCleanup: (() => void) | null = null

/** 拖拽调整分栏(按 wrap 实际高度换算成百分比) */
function onSplitDrag(e: MouseEvent) {
  const wrap = splitWrapRef.value
  if (!wrap) return
  const rect = wrap.getBoundingClientRect()
  if (rect.height < 40) return
  const startY = e.clientY
  const startPct = splitPct.value
  splitDragging.value = true
  let moved = false

  const onMove = (ev: MouseEvent) => {
    moved = true
    const deltaPct = ((ev.clientY - startY) / rect.height) * 100
    splitPct.value = Math.min(SPLIT_MAX, Math.max(SPLIT_MIN, startPct + deltaPct))
  }
  const onUp = () => {
    document.removeEventListener('mousemove', onMove)
    document.removeEventListener('mouseup', onUp)
    window.removeEventListener('blur', onUp)
    document.body.style.cursor = ''
    document.body.style.userSelect = ''
    splitDragging.value = false
    dragCleanup = null
    if (moved) {
      // 手动拖过 → 记下来:之后执行查询不再自动展开/收回,免得跟用户抢高度
      userAdjustedSplit = true
      // 若此刻字段面板是展开的,把"收起后回到哪"更新成用户拖到的值 ——
      // 否则收起时会跳回"展开之前"的旧值,等于把用户刚调的高度丢掉
      if (fieldPanelOpen.value) splitPctBeforeTallTab = splitPct.value
    }
  }
  document.body.style.cursor = 'row-resize'
  document.body.style.userSelect = 'none'
  document.addEventListener('mousemove', onMove)
  document.addEventListener('mouseup', onUp)
  // 在窗口外松开鼠标拿不到 mouseup → 光标/监听会滞留到下一次点击;用 blur 兜底
  window.addEventListener('blur', onUp)
  dragCleanup = onUp
}

/** 双击分隔条复位:还没结果就回到空态窄条,有结果就回到默认分栏 */
function resetSplit() {
  splitPct.value = resultTabs.value.length ? SPLIT_DEFAULT : SPLIT_IDLE
  // 显式复位 → 解除「用户已手动调整」,之后执行查询可以再次自动展开
  userAdjustedSplit = false
  // ⚠️ 这里也必须套页签规则:它是第三个改高度的地方(前两个是 expandResultForRun /
  //    collapseResultIfEmpty)。漏掉它时,凡是走复位的路径都会把「输出字段」需要的
  //    高度重新压回 28% —— 用户看到的就是"点进去直接 28%,没有伸缩"。
  applyTabSplit()
}

/** 开始执行查询:展开到基准高度(用户手动拖过就不插手) */
function expandResultForRun() {
  if (userAdjustedSplit) return
  splitPct.value = SPLIT_DEFAULT
  // ⚠️ 执行完还要再套一次页签规则:否则"打开即执行"会把配置区收回 28%,
  //    而用户此时往往正停在「输出字段」上编辑 —— 于是撑高白做(实测踩过)。
  applyTabSplit()
}

/** 结果被清空:收回空态窄条(用户手动拖过就不插手) */
function collapseResultIfEmpty() {
  if (userAdjustedSplit) return
  splitPct.value = SPLIT_IDLE
}

/**
 * 结果表格的最大高度:跟随结果区实际可用高度 ——
 * 不再写死 460,结果区拉大时表格也跟着变大(内部滚动 + 表头固定)。
 */
const gridMaxHeight = computed(() => {
  const h = splitWrapH.value
  if (!h) return 460
  const resultH = (h * (100 - splitPct.value)) / 100
  // 扣掉 结果标题行 + 内边距 + 分页条。
  // 以前还要再扣一条「本次执行 SQL」条(约 34px) —— 它已挂到标题行的「SQL」按钮上,
  // 所以这里少扣一些,把省下的高度还给表格。
  //
  // ⚠️ 这里必须【往富余方向扣】:
  //    扣少了(表格比可用区高)→ 最后一行被下面的分页条压住/切掉(用户实测,浏览器量到
  //    内容 651px vs 可见 617px,正好差 50px);
  //    扣多了(表格比可用区矮)→ 只是表格自己早一点出现滚动条,无害。
  //    所以按实测的"外壳"高度来:结果标题行 31 + 内边距 16 + 分页条 45 + 页签头(多结果时 34)
  //    + 余量(表格下方原来还有一行 grid-meta,已删)≈ 128 / 162。
  /*
    ⚠️ 只扣【表格容器底下真实占掉的高度】:分页条 45 + 容器内边距 16 ≈ 61;
    多结果时还有页签内容的 padding 与页签头 ≈ 34。
    以前这里扣到 128/162,把"结果标题行""页签"也算进去了 —— 可它们在 flex 布局里
    【本来就已占位】,等于重复扣 → 表格比可用区矮一截,底部到分页条之间留出一条空隙
    (用户:".vg-body 底部离 res-pager 有点高,能不能挨着")。
    少扣一点没关系:容器本身有隐形滚动兜底(算多了也只是能滚,不会裁掉最后一行)。
  */
  const chrome = hideTabBar.value ? 85 : 119
  /*
    ⚠️ 再把高度【取整到整行】。
    el-table 是 size="small"(表头与行高都约 33px),若可视区不是整行高,
    最后一行会被表格自己的视口裁掉半行 —— 看上去就是"最后一行贴着分页条、只露一半"
    (用户反馈"到底部的分页还是有点高")。表头也在 max-height 之内,同样约 33px,
    所以直接对齐到 33 的整数倍即可。
  */
  const ROW_H = 33
  const usable = Math.max(200, Math.round(resultH - chrome))
  return Math.max(200, Math.floor(usable / ROW_H) * ROW_H)
})
const sqlDialogVisible = ref(false)

/** 复制本次执行的 SQL(与可视化构建器里的复制同一套提示) */
async function copyExecSql() {
  try {
    await navigator.clipboard.writeText(executedSql.value)
    ElMessage.success('已复制 SQL')
  } catch {
    ElMessage.warning('复制失败,请手动选择')
  }
}
const elapsed = ref(-1)
const activeResult = ref('')
const hasGrid = computed(() => resultTabs.value.some((t) => t.kind === 'grid'))
// 分页(服务端分页:不截断,按页查询)
const page = ref(1)
const pageSize = ref(100)
const lastSql = ref('')

/** 当前选中的结果 Tab(仅 grid) */
const activeGridTab = computed<ResultTab | null>(() => {
  const t = resultTabs.value.find((x) => x.key === activeResult.value)
  if (t?.kind === 'grid') return t
  return null
})

/** 当前展示的结果集(用于分页 total / 导出列) */
const activeResultObj = computed<QueryResult | null>(() => {
  const t = activeGridTab.value
  if (t) return t.result ?? null
  return resultTabs.value.find((x) => x.kind === 'grid')?.result ?? null
})

/** 关闭某个结果 Tab */
function closeResultTab(key: string) {
  const idx = resultTabs.value.findIndex((t) => t.key === key)
  if (idx < 0) return
  resultTabs.value.splice(idx, 1)
  if (activeResult.value === key || !resultTabs.value.some((t) => t.key === activeResult.value)) {
    activeResult.value = resultTabs.value[Math.min(idx, resultTabs.value.length - 1)]?.key ?? ''
  }
  // 全关完了 → 和「清空」一样收回窄条(否则结果区留一大块空框)
  if (!resultTabs.value.length) collapseResultIfEmpty()
}

/** Tab 标题:结果 N / 消息 N */
function tabLabel(t: ResultTab): string {
  const n = Number(t.key.slice(1)) + 1
  return t.kind === 'msg' ? `消息 ${n}` : `结果 ${n}`
}

/**
 * 只有一个「表格」结果时不显示标签栏。
 *
 * 那种情况标签栏只有一项(「结果 1」),纯占一行高度(卡片式标签约 34px),
 * 而结果标题行已经把「N 行 × M 列」写清了 —— 没什么可切换的。
 *
 * ⚠️ 纯消息结果(DDL 之类)【不能】这样省:它的标签上那个 × 是唯一关闭入口
 *    (消息面板里没有结果标题行,也就放不下「关闭」按钮)。
 */
const hideTabBar = computed(() => resultTabs.value.length === 1 && hasGrid.value)

/** 关闭当前结果;关掉最后一个时整体复位(等同原来的「清空」) */
function closeActiveResult() {
  const key = activeResult.value
  if (!key) return
  closeResultTab(key)
  // 全关完了再复位一次:把失败横幅 / 耗时 / 实际 SQL 一起收掉,
  // 否则标签没了、上面还挂着「上次运行失败」的提示。
  if (!resultTabs.value.length) clearResults()
}
const codeMirrorRef = ref<any>(null)
const visualRef = ref<any>(null)
let sqlSeq = 0
/** 编辑会话与请求序号分开：同一查询重新打开也算新会话，旧保存/轮询不可修改新会话。 */
let editorSession = 0
let editorDisposed = false
function isEditorSession(session: number) {
  return !editorDisposed && session === editorSession && editing.value !== null
}

// —— 编辑器模式:SQL 手写 / 可视化构建 ——
// 可视化只负责「生成 SQL」,产物写回 sqlText → 保存/运行走同一条链路。
// P1 不做 SQL → 可视化的反向解析,故可视化配置本身不持久化。
// 默认可视化(见 openEditor 的分支逻辑)
const editorMode = ref<'sql' | 'visual'>('visual')

/**
 * 模式切换:从「可视化」切到「SQL」时,把可视化【当前】生成的 SQL 同步进编辑器。
 *
 * ⚠️ 必须做这一步 —— 否则可视化里改了配置后,SQL 模式编辑器还停留在旧 SQL,
 * 在那里点「运行」会跑旧的(表现:结果列名/别名不是最新的)。
 */
watch(editorMode, async (m) => {
  if (m !== 'sql' || !editing.value) return
  // ⚠️ await 期间用户可能已「返回列表」(editing=null)或另存为别的查询 ——
  //    必须比对对象身份,否则会把【旧查询的 SQL 写进新查询】或直接抛 null。
  const w = editing.value
  const s = await visualRef.value?.refreshSQLNow?.()
  if (!w || editing.value !== w) return
  if (s) visualSqlSnapshot.value = s
  if (s && s !== w.sqlText) {
    w.sqlText = s
    dirty.value = true
  }
})

/**
 * 若编辑器里的 SQL 仍是「构建器产物」(用户没手改过),返回构建器当前配置,否则 null。
 *
 * ⚠️ 这些 SQL 里不含程序算子 —— 按下推给通用 SQL 接口会丢掉 SM3/3DES,
 *    结果里就是【明文】(已实测:同一段 SQL 走 /query-workflows/execute 得密文、
 *    走 /connections/:id/query 得 13800000000)。
 *
 * ⚠️ 判定不能只靠 visualSqlSnapshot:
 *    clearResults / applyHistory 会把它清成 '',而编辑器内容一个字没改 ——
 *    快照一失效,守卫就失效,整段 SQL 落到通用接口 → 明文。
 *    构建器【实时】产出的 SQL 才是权威依据,所以两条都比。
 */
function builderConfigIfUntouched(stmt?: string): QueryWorkflowConfig | null {
  const sql = (stmt ?? editing.value?.sqlText ?? '').trim()
  if (!sql) return null
  const hit = (a: string) => !!a.trim() && a.trim() === sql
  if (!hit(visualSqlSnapshot.value) && !hit(visualRef.value?.getSQL?.() ?? '')) return null
  return visualRef.value?.toConfig?.() ?? null
}

/** 该配置是否含程序算子列(决定要不要强制走可视化接口) */
function hasProgFields(cfg: QueryWorkflowConfig | null): boolean {
  return !!cfg && (cfg.fields ?? []).some((f) => (f.prog ?? []).length > 0)
}

// —— 可视化配置的本地草稿(未保存也不丢) ——
const DRAFT_PREFIX = 'el2ck.visualDraft.'
function readVisualDraft(id: number): string {
  try {
    return queryStorage.getItem(DRAFT_PREFIX + id) || ''
  } catch {
    return ''
  }
}
function writeVisualDraft(id: number, json: string) {
  try {
    if (json) queryStorage.setItem(DRAFT_PREFIX + id, json)
    else queryStorage.removeItem(DRAFT_PREFIX + id)
  } catch {
    /* 隐私模式等场景忽略 */
  }
}
function clearVisualDraft(id: number) {
  try {
    queryStorage.removeItem(DRAFT_PREFIX + id)
  } catch {
    /* ignore */
  }
}

// 草稿写入防抖:别名/过滤值每敲一个字符都会触发 onVisualChange,
// 全量 JSON.stringify + 同步 localStorage.setItem 会拖慢输入。
let draftTimer: ReturnType<typeof setTimeout> | null = null

/** builder 配置变化 → 落本地草稿(与已保存配置一致时不写,避免无谓的"已恢复"提示) */
function onVisualChange(json: string) {
  const w = editing.value
  if (!w) return
  if (json === (w.visualConfig || '')) {
    clearVisualDraft(w.id)
    return
  }
  // 配置与已保存的不一致 → 同样算「未保存」(以前只有 SQL 改动才算,改配置后返回列表不提示)
  dirty.value = true
  // 纯配置改动不一定改 sqlText → 靠这个计数触发自动保存
  changeTick.value++
  if (draftTimer) clearTimeout(draftTimer)
  draftTimer = setTimeout(() => {
    draftTimer = null
    writeVisualDraft(w.id, json)
  }, 500)
}

/** 可视化「在 SQL 模式打开」:把生成的 SQL 写回编辑器 */
function onOpenInSql(sql: string) {
  if (!editing.value) return
  editing.value.sqlText = sql
  visualSqlSnapshot.value = sql
  dirty.value = true
  editorMode.value = 'sql'
  ElMessage.success('已写入 SQL,可继续编辑或保存')
}

/**
 * 用户点【可视化构建器里】的「运行」。
 *
 * ⚠️ 必须与自动执行分开:自动执行那条也走 onVisualRun。
 *    如果把"收起字段面板"放进 onVisualRun,用户每改一个过滤值都会触发自动执行,
 *    面板就被收一次 —— 正在编辑的人会被反复打断(所以这里单独包一层)。
 *    收起只挂在用户显式点运行的两个入口:工具栏 runSql / 这里。
 */
function onVisualRunUi(sql: string) {
  if (!auth.sqlExecutionEnabled) return
  collapseSignal.value++
  return onVisualRun(sql)
}

/**
 * @param opts.markDirty 是否把这次执行算作「用户改动」。
 *   ⚠️ 打开任务时的自动执行【不算】:它会把 dirty 置 true → 触发自动保存 →
 *   动 updatedAt、把这条查询在列表里排到最前(无头浏览器实测过的副作用)。
 *   以前是靠"执行完再把 dirty 还原"绕过去的,而那样会在执行期间【吃掉用户的真实编辑】:
 *   用户在等待结果时改了别的东西 → dirty 被置 true → 执行结束又被还原成 false →
 *   改动不再标记为待保存(评审 high,已核实)。
 *   现在直接从源头不置 dirty,那套存取逻辑整段删掉。
 */
async function onVisualRun(sql: string, opts: { markDirty?: boolean } = {}) {
  if (!auth.sqlExecutionEnabled) return
  if (!editing.value) return
  editing.value.sqlText = sql
  visualSqlSnapshot.value = sql
  if (opts.markDirty !== false) dirty.value = true
  lastSql.value = sql
  // 快照 config(不是计划)—— 后端每次从它重新推导 SQL 与计划
  const cfg = visualRef.value?.toConfig?.()
  resultSource.value = cfg ? { kind: 'visual', config: cfg } : { kind: 'sql' }
  page.value = 1
  queryFailed.value = false
  queryError.value = ''
  const ok = await doVisualQuery(1)
  // ⚠️ 只有执行成功才清除「配置已改」标记 ——
  // 失败时必须保留,否则用户会以为旧结果就是新配置的结果
  if (ok) visualRef.value?.markFresh?.()
}

// —— SQL 片段库 ——
const snippetVisible = ref(false)
const snippetGroup = ref(SQL_SNIPPETS[0].key)
const snippetKw = ref('')

/** 有搜索词时跨全部组搜索,否则只展示当前组 */
const filteredSnippets = computed<SQLSnippet[]>(() => {
  const kw = snippetKw.value.trim().toLowerCase()
  if (kw) {
    return SQL_SNIPPETS.flatMap((g) =>
      g.items.filter((s) => `${s.label} ${s.desc ?? ''} ${s.sql}`.toLowerCase().includes(kw))
    )
  }
  return SQL_SNIPPETS.find((g) => g.key === snippetGroup.value)?.items ?? []
})

/** 把片段插入编辑器光标处(占位符 {{x}} 会自动选中,可直接改写) */
function insertSnippet(s: SQLSnippet) {
  if (!editing.value) {
    ElMessage.warning('请先打开一个查询')
    return
  }
  // ⚠️ 编辑器用 v-show 常驻挂载,可视化模式下插入会静默写进【看不见的】SQL 里 ——
  //    先切到 SQL 模式,让用户看到插入结果。
  if (editorMode.value !== 'sql') editorMode.value = 'sql'
  nextTick(() => codeMirrorRef.value?.insertSnippet?.(s.sql))
  ElMessage({ message: `已插入:${s.label}`, type: 'success', duration: 1200 })
}

// —— 新建 ——
const createVisible = ref(false)
const createForm = ref({ name: '', database: 'ods_data', groupId: 0, description: '' })

// —— 分组管理 ——
const groupDlgVisible = ref(false)
const gmgEditing = ref<SQLQueryGroup | null>(null)
const gmgName = ref('')
const gmgRemark = ref('')
const gmgSaving = ref(false)

// —— 历史 ——
interface HistItem {
  sql: string
  at: string
  ok: boolean
  ms: number
  db: string
}
const history = ref<HistItem[]>([])
const historyVisible = ref(false)

// —— 导出 CSV ——
const exportVisible = ref(false)
const exportAllCols = ref<string[]>([])
const exportCols = ref<string[]>([])
const exportHeaderNames = ref<string[]>([])
const exportNameMap = ref<Record<string, string>>({})
/** 导出完成后的自动关闭定时器(卸载时清理) */
let exportDoneTimer: ReturnType<typeof setTimeout> | null = null
const exportWithHeader = ref(true)
const exportDelimiter = ref(',')
const exportLimit = ref(0)
const exporting = ref(false)
const exportProgress = ref(0)
const exportStatusText = ref('')

function openExport() {
  const cols = activeResultObj.value?.columns ?? []
  if (!cols.length) {
    ElMessage.warning('当前无可导出的结果')
    return
  }
  exportAllCols.value = [...cols]
  exportCols.value = [...cols]
  // ⚠️ 表头重命名不跨查询重用:exportNameMap 只写不读(以前从不重置),
  //    在查询 A 把 mdn 改成「手机号」后,查询 B 的导出弹窗会预填「手机号」,
  //    直接点导出就把非预期的表头写出去了。
  exportNameMap.value = {}
  exportHeaderNames.value = [...cols]
  exportWithHeader.value = true
  exportLimit.value = 0
  exportProgress.value = 0
  exportStatusText.value = ''
  exportVisible.value = true
}
function onExportColsChange() {
  // ⚠️ el-checkbox-group 的 v-model 数组顺序 = 【勾选顺序】——
  //    取消再勾选任何一列都会把它挪到末尾,导出的 CSV 列序会无端跳动。
  //    这里统一按结果列的原顺序重排(顺带让后端 prog 下标的重定位更好理解)。
  const order = new Map(exportAllCols.value.map((c, i) => [c, i]))
  exportCols.value = [...exportCols.value].sort((a, b) => (order.get(a) ?? 0) - (order.get(b) ?? 0))
  exportHeaderNames.value = exportCols.value.map((c) => exportNameMap.value[c] ?? c)
}
function onHeaderNameInput(i: number) {
  const col = exportCols.value[i]
  if (col) exportNameMap.value[col] = exportHeaderNames.value[i]
}

/** 导出文件名:查询名_YYYYMMDD_HHMMSS.csv */
function exportFilename(): string {
  const now = new Date()
  const pad = (n: number) => String(n).padStart(2, '0')
  const ts = `${now.getFullYear()}${pad(now.getMonth() + 1)}${pad(now.getDate())}_${pad(now.getHours())}${pad(now.getMinutes())}${pad(now.getSeconds())}`
  const base = (editing.value?.name || 'query').replace(/[\\/:*?"<>|\s]+/g, '_')
  return `${base}_${ts}.csv`
}

async function doExport() {
  if (!auth.sqlExecutionEnabled) return
  const w = editing.value
  const stmt = activeGridTab.value?.stmt || lastSql.value
  if (!w || !stmt) {
    ElMessage.warning('请先运行查询')
    return
  }
  exporting.value = true
  exportProgress.value = 20
  exportStatusText.value = '正在查询并生成 CSV…'
  try {
    const cols = exportCols.value
    const headers = exportCols.value.map((_, i) => exportHeaderNames.value[i] ?? '')
    let src = resultSource.value
    // ⚠️ SQL 模式但内容仍是构建器产物(未手改)→ 仍按可视化导出。
    //    否则 SM3/3DES 列会以明文写进 CSV。
    if (src.kind === 'sql') {
      const cfg = builderConfigIfUntouched(stmt)
      if (hasProgFields(cfg)) src = { kind: 'visual', config: cfg as QueryWorkflowConfig }
    }
    // ⚠️ 可视化来源走专用导出接口:只传 config,后端连 SQL 和程序算子计划一起推导
    //    —— 结构上不存在"漏传计划导致 SM3/3DES 导出明文"的可能。
    // 服务端改成了【流式 text/csv】:直接拿 Blob,不再经 JSON 中转
    const blob =
      src.kind === 'visual'
        ? await queryWorkflowsApi.exportCsv({
            config: src.config,
            columns: cols,
            headerNames: headers,
            withHeader: exportWithHeader.value,
            delimiter: exportDelimiter.value,
            limit: exportLimit.value || undefined
          })
        : await workspaceApi.queryExport(connId.value, {
            sql: stmt,
            database: w.database,
            columns: cols,
            headerNames: headers,
            withHeader: exportWithHeader.value,
            delimiter: exportDelimiter.value,
            limit: exportLimit.value || undefined
          })
    exportProgress.value = 80
    exportStatusText.value = '正在下载…'
    downloadBlob(blob, exportFilename())
    const rows = await countCsvRows(blob, exportWithHeader.value)
    exportProgress.value = 100
    exportStatusText.value = rows >= 0 ? `完成,共导出 ${rows} 行` : '完成'
    ElMessage.success(rows >= 0 ? `已导出 ${rows} 行` : '导出完成')
    // 句柄存下来,onBeforeUnmount 要清(卸载后还去写 ref / 关弹窗是脏动作)
    exportDoneTimer = window.setTimeout(() => {
      exportDoneTimer = null
      exportVisible.value = false
      exportProgress.value = 0
      exportStatusText.value = ''
    }, 800)
  } catch (e: any) {
    exportProgress.value = 0
    exportStatusText.value = ''
    // ⚠️ 导出接口是以 silent=true 调的(响应体是文件流,拦截器解不出错误信息),
    //    所以这里必须自己提示 —— 以前注释写「拦截器已提示」,实际根本没人提示,
    //    失败时用户只看到进度条归零、弹窗没反应。
    ElMessage.error(e?.message || '导出失败(可能是超时或数据量过大,可试试减小行数限制)')
  } finally {
    exporting.value = false
  }
}

/**
 * 触发浏览器下载 CSV。
 * ⚠️ 不再自己拼 BOM —— 服务端已在流头部写过 UTF-8 BOM(Excel 打开中文才不乱码)。
 */
function downloadBlob(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  URL.revokeObjectURL(url)
}

/** 超过这个体积就不数行数了(数一遍要过完整份字节,大文件会明显拖慢) */
const COUNT_ROWS_LIMIT = 16 * 1024 * 1024

/**
 * 统计 CSV 数据行数(不含表头)。按字节流统计换行,不把整份文本读进内存。
 * 返回 -1 表示「太大,不数了」。
 */
async function countCsvRows(blob: Blob, withHeader: boolean): Promise<number> {
  if (blob.size > COUNT_ROWS_LIMIT) return -1
  const reader = blob.stream().getReader()
  let lines = 0
  for (;;) {
    const { done, value } = await reader.read()
    if (done) break
    const buf = value as Uint8Array
    for (let i = 0; i < buf.length; i++) if (buf[i] === 10) lines++
  }
  return Math.max(0, lines - (withHeader ? 1 : 0))
}

const defaultCount = computed(() => queries.value.filter((q) => !q.groupId).length)

const shownQueries = computed(() => {
  let base = queries.value
  if (filter.value === 'default') base = base.filter((q) => !q.groupId)
  else if (filter.value !== 'all') {
    const gid = Number(filter.value)
    base = base.filter((q) => q.groupId === gid)
  }
  const k = kw.value.trim().toLowerCase()
  if (!k) return base
  return base.filter((q) => q.name.toLowerCase().includes(k))
})

// ---- 列表分页(与数据源管理一致;与结果集分页 page/pageSize 区分) ----
const listPage = ref(1)
const listPageSize = ref(10)
const pagedQueries = computed(() => {
  const start = (listPage.value - 1) * listPageSize.value
  return shownQueries.value.slice(start, start + listPageSize.value)
})
/** 分页下全局序号(跨页连续) */
function globalIndex(i: number): number {
  return (listPage.value - 1) * listPageSize.value + i + 1
}

async function loadConn() {
  try {
    const conns = await connectionsApi.list()
    connId.value = conns.find((c) => c.isDefault)?.id ?? conns[0]?.id ?? 0
  } catch {
    connId.value = 0
  }
}
async function loadGroups() {
  try {
    groups.value = await sqlQueryGroupsApi.list()
  } catch {
    groups.value = []
  }
}
async function loadQueries() {
  loading.value = true
  try {
    queries.value = await sqlQueriesApi.list()
  } catch {
    /* 拦截器已提示 */
  } finally {
    loading.value = false
  }
}
async function reloadAll() {
  await Promise.all([loadGroups(), loadQueries()])
}

function resetListQuery() {
  query.reset()
  void reloadAll()
}

// —— 列表操作 ——
let createSession = 0
watch(createVisible, (visible) => {
  if (!visible) { createSession++; creating.value = false }
}, { flush: 'sync' })

function openCreate() {
  createSession++
  creating.value = false
  createForm.value = {
    name: '',
    database: 'ods_data',
    groupId: filter.value !== 'all' && filter.value !== 'default' ? Number(filter.value) : 0,
    description: ''
  }
  createVisible.value = true
}
async function doCreate() {
  if (editorDisposed || !createVisible.value || creating.value) return
  if (!createForm.value.name.trim()) {
    ElMessage.warning('请输入名称')
    return
  }
  const session = createSession
  const originEditor = editorSession
  const isCurrent = () => !editorDisposed && createVisible.value && session === createSession && originEditor === editorSession
  // 从确认阶段锁住输入并保存快照，避免重复提交或提交与确认内容不同的表单。
  const input = {
    name: createForm.value.name.trim(),
    description: createForm.value.description.trim(),
    database: createForm.value.database,
    sqlText: '',
    visualConfig: '',
    groupId: createForm.value.groupId || 0,
  }
  creating.value = true
  try {
    if (!(await confirmAction('新建探索', `确认新建查询「${input.name}」（${dbLabel(input.database)}）？`, '确认新建')) || !isCurrent()) return
    const q = await sqlQueriesApi.create(input)
    if (!isCurrent()) return
    await reloadAll()
    if (!isCurrent()) return
    query.reset()
    createVisible.value = false
    ElMessage.success('已创建')
    openEditor(q)
    const openedSession = editorSession
    // 新建的探索是空配置 → 直接弹探索向导开始配置(不必等用户找入口)
    nextTick(() => { if (isEditorSession(openedSession)) visualRef.value?.openWizardIfEmpty?.() })
  } catch {
    /* 拦截器已提示 */
  } finally {
    if (isCurrent()) creating.value = false
  }
}

/**
 * 打开任务后自动执行一次(用户要求:打开即出结果)。
 *
 * ⚠️ 可视化模式下必须先 refreshSQLNow 拿到【当前配置】的 SQL:SQL 生成走的是 300ms 防抖
 *    (scheduleRefresh),直接跑会拿到空 SQL 或上一份配置的 SQL。
 * 失败不弹窗 —— 原因本来就显示在 SQL 框与结果区,再弹一个只会挡视线。
 */
async function autoRunAfterOpen(session: number) {
  if (!auth.sqlExecutionEnabled) return
  await nextTick()
  if (!isEditorSession(session)) return

  // SQL 模式:语句就在记录里(editing.sqlText),不依赖编辑器,直接跑
  if (editorMode.value !== 'visual') {
    if (editing.value?.sqlText?.trim()) await runSql()
    return
  }

  /*
    可视化模式要等配置"可生成"。
    ⚠️ 踩过的坑:loadConfig 是异步的 —— 它要逐张源表去拉字段(schema),在拉回来之前
       canGenerate 还是 false,refreshSQLNow 会直接返回空串。我第一版只调一次、
       拿不到就 if (sql) 静默 return,表现就是用户说的"点进去没有默认执行"。
    这里的做法:
      · 触发一次生成,然后【轮询 getSQL()】等它出来 —— getSQL 是纯读,不烧接口;
      · 中途再补触发一次(1s 后),防 loadConfig 没触发防抖生成的情况;
      · 等满 4s 还没出来就给一句提示,别让人以为按钮坏了(原因多半是源表被删了)。
  */
  for (let i = 0; i < 40; i++) {
    if (!isEditorSession(session) || editorMode.value !== 'visual') return
    if (i === 0 || i === 10) void visualRef.value?.refreshSQLNow?.()
    const sql = visualRef.value?.getSQL?.()
    if (sql) {
      try {
        // 自动执行不是用户改动 → 明确不置 dirty(见 onVisualRun 的 opts 说明)
        await onVisualRun(sql, { markDirty: false })
      } catch {
        // 执行失败的原因已经显示在结果区,不再弹窗挡视线
      }
      return
    }
    await new Promise((r) => setTimeout(r, 100))
  }
  if (isEditorSession(session)) ElMessage.warning('打开后没能自动生成 SQL(源表可能已不存在),请手动点「运行」')
}

function openEditor(row: SQLQuery) {
  // 打开现有查询即离开新建流程，旧创建请求只能完成服务端写入，不能再切换编辑器。
  createVisible.value = false
  const session = ++editorSession
  // 切查询 → 上一个查询的待执行自动保存必须作废
  cancelAutoSave()
  autoSaving.value = false
  saving.value = false
  savingAs.value = false
  autoSaveAt.value = ''
  // ⚠️ 让在途请求作废:在查询 A 里跑一个慢查询→返回列表→打开查询 B,
  //    若不自增,A 的响应回来时守卫仍成立 → 把 A 的结果写成 B 的结果。
  sqlSeq++
  running.value = false
  editing.value = { ...row }
  dirty.value = false
  // 恢复可视化配置:本地草稿(未保存的改动)优先于库里的已保存配置
  const draft = readVisualDraft(row.id)
  const saved = row.visualConfig || ''
  const restore = draft || saved
  if (restore) {
    editorMode.value = 'visual'
    nextTick(() => {
      if (!isEditorSession(session)) return
      try {
        visualRef.value?.loadConfig?.(JSON.parse(restore))
        if (draft) ElMessage.info('已恢复上次未保存的可视化配置')
      } catch {
        editorMode.value = 'sql'
        ElMessage.warning('可视化配置解析失败,已回退到 SQL 模式')
      }
    })
  } else if (!row.sqlText?.trim()) {
    // 空查询(新建探索)→ 默认进可视化
    editorMode.value = 'visual'
    nextTick(() => { if (isEditorSession(session)) visualRef.value?.reset?.() })
  } else {
    // 有 SQL 内容但没有可视化配置 → 说明是手写 SQL 的老查询,留在 SQL 模式
    // (直接进可视化会是空白,看着像"SQL 丢了")
    editorMode.value = 'sql'
    nextTick(() => { if (isEditorSession(session)) visualRef.value?.reset?.() })
  }
  resultTabs.value = []
  collapseResultIfEmpty()
  message.value = ''
  elapsed.value = -1
  activeResult.value = ''
  page.value = 1
  lastSql.value = ''
  visualSqlSnapshot.value = ''
  sqlColCache.value = {} // 换查询可能换库 → 列缓存作废
  loadSqlTables(row.database)

  // 打开任务后【默认执行一次】:以前打开只是个空壳,还得再点一下「运行」。
  // 放在函数最后调用 —— 上面的配置加载/重置都在 nextTick 里,自动执行自己再等一个 tick。
  void autoRunAfterOpen(session)
}

/**
 * 点侧边栏「数据探索」时如果正停在该页,回到列表(详情态清掉)。
 * ⚠️ 复用 backToList:它已经有「未保存修改」的二次确认,不能绕过。
 */
const navResetTick = useNavReset()
watch(navResetTick, () => {
  if (editing.value) void backToList()
})

async function backToList() {
  const session = editorSession
  if (dirty.value) {
    try {
      await ElMessageBox.confirm('当前查询有未保存的修改,确定放弃并返回?', '未保存', {
        confirmButtonText: '放弃返回',
        cancelButtonText: '继续编辑',
        type: 'warning'
      })
    } catch {
      return
    }
  }
  if (!isEditorSession(session)) return
  leaveEditor()
}

/** 离开编辑器时统一取消延后操作；成功删除当前记录也必须使旧保存和自动执行失效。 */
function leaveEditor() {
  // 确认离开后才取消；选择“继续编辑”时仍保留待执行的自动保存。
  cancelAutoSave()
  editorSession++
  // 同上:离开编辑器要作废在途请求,否则结果会写到列表页/下一个查询
  sqlSeq++
  running.value = false
  saving.value = false
  savingAs.value = false
  autoSaving.value = false
  editing.value = null
  dirty.value = false
  editorMode.value = 'visual' // 回到默认模式
}

/**
 * 当前可视化配置。
 * ⚠️ 不看当前模式 —— builder 始终挂载(v-show),否则「在 SQL 模式保存」会丢掉可视化配置。
 * 只有真正配了表 + 字段才存,避免把空壳写进库。
 */
function currentVisualConfig(): string {
  const cfg = visualRef.value?.toConfig?.()
  if (cfg && cfg.sources?.length && cfg.fields?.length) return JSON.stringify(cfg)
  return ''
}

// ────────────── 自动保存 ──────────────
//
// 设计:开关默认开(用户选择持久化到 localStorage);停笔约 2s 后静默落库,
// 顶栏显示「已自动保存 12:34:56」。
//
// ⚠️ 自动保存【不】调 reloadAll():列表刷新会重建表格、打断编辑;
//    列表数据晚一拍无影响。
const AUTOSAVE_KEY = 'el2ck.autosave'
const autoSave = ref(localStorage.getItem(AUTOSAVE_KEY) !== '0')
const autoSaveAt = ref('')
const autoSaving = ref(false)
/** 可视化配置变更的递增计数(纯配置改动不一定会改 sqlText,靠它触发自动保存) */
const changeTick = ref(0)
watch(autoSave, (v) => {
  try {
    localStorage.setItem(AUTOSAVE_KEY, v ? '1' : '0')
  } catch {
    /* ignore */
  }
  if (!v) {
    if (autoSaveTimer) clearTimeout(autoSaveTimer)
    autoSaveTimer = null
    autoSaveAt.value = ''
  }
})
const autoSaveTip = computed(() =>
  autoSave.value
    ? '已开启:停止编辑约 2 秒后自动保存(运行中 / 手动保存中会跳过)'
    : '已关闭:改动只在本地暂存草稿,需手动点「保存」才会入库'
)
const autoSaveState = computed(() => {
  if (autoSaving.value) return { text: '保存中…', cls: 'busy' }
  if (autoSaveAt.value) return { text: `已自动保存 ${autoSaveAt.value}`, cls: 'ok' }
  return null
})

/** 当前编辑内容的签名(任何字段变化都会变)—— 用于去抖与「保存期间又改了」的判断 */
function editSignature(): string {
  const w = editing.value
  if (!w) return ''
  return [w.name, w.description ?? '', w.database, w.groupId ?? 0, w.sqlText, changeTick.value].join('\u0001')
}
const editSig = computed(() => editSignature())

let autoSaveTimer: ReturnType<typeof setTimeout> | null = null
function cancelAutoSave() {
  if (autoSaveTimer) clearTimeout(autoSaveTimer)
  autoSaveTimer = null
}
/** 停笔后自动保存(清掉在途定时器,保证只跑最后一次) */
function scheduleAutoSave() {
  if (editorDisposed || !autoSave.value) return
  const w = editing.value
  // 名称没填的不自动存:后端会拒,而且那也说明用户还没开始认真配
  if (!w || !w.name.trim() || !dirty.value) return
  cancelAutoSave()
  autoSaveTimer = setTimeout(() => {
    autoSaveTimer = null
    void autoSaveNow()
  }, 2000)
}

async function autoSaveNow() {
  const w = editing.value
  if (editorDisposed || !autoSave.value || !w || !dirty.value || saving.value || autoSaving.value) return
  if (!w.name.trim()) return
  const session = editorSession
  const before = editSignature()
  autoSaving.value = true
  try {
    const vc = currentVisualConfig()
    await sqlQueriesApi.update(w.id, {
      name: w.name.trim(),
      description: w.description ?? '',
      database: w.database,
      sqlText: w.sqlText,
      visualConfig: vc,
      groupId: w.groupId ?? 0
    })
    if (!isEditorSession(session)) return
    // ⚠️ 保存期间用户又改了 → 不能清 dirty(否则新改动被当成已保存),
    //    重新排一次自动保存。
    if (editSignature() !== before) {
      scheduleAutoSave()
      return
    }
    w.visualConfig = vc
    clearVisualDraft(w.id)
    dirty.value = false
    autoSaveAt.value = new Date().toLocaleTimeString()
  } catch {
    /* 拦截器已提示;保持 dirty,下次编辑再试 */
  } finally {
    if (isEditorSession(session)) autoSaving.value = false
  }
}

watch(editSig, () => scheduleAutoSave())

async function save() {
  const w = editing.value
  if (!w || saving.value || autoSaving.value) return
  const session = editorSession
  if (!w.name.trim()) {
    ElMessage.warning('请输入名称')
    return
  }
  if (!(await confirmAction('保存探索', `确认保存查询「${w.name.trim()}」的当前修改？`, '确认保存'))) return
  if (!isEditorSession(session) || saving.value || autoSaving.value) return
  cancelAutoSave()
  const before = editSignature()
  saving.value = true
  let savedRequest = false
  try {
    const vc = currentVisualConfig()
    await sqlQueriesApi.update(w.id, {
      name: w.name.trim(),
      description: w.description ?? '',
      database: w.database,
      sqlText: w.sqlText,
      visualConfig: vc,
      groupId: w.groupId ?? 0
    })
    if (!isEditorSession(session)) return
    savedRequest = true
    if (editSignature() === before) {
      w.visualConfig = vc
      clearVisualDraft(w.id)
      dirty.value = false
      autoSaveAt.value = new Date().toLocaleTimeString()
    }
    ElMessage.success('已保存')
    await reloadAll()
  } catch {
    /* 拦截器已提示 */
  } finally {
    if (isEditorSession(session)) {
      saving.value = false
      // 列表刷新也可能很慢，等手动保存完整结束后再安排后续编辑，避免去抖到期时被 saving 跳过。
      if (savedRequest && dirty.value) scheduleAutoSave()
    }
  }
}

async function saveAs() {
  const w = editing.value
  if (!w || editorDisposed || savingAs.value || saving.value || autoSaving.value) return
  const session = editorSession
  savingAs.value = true
  try {
    const { value } = await ElMessageBox.prompt('新查询名称', '另存为', {
      inputValue: `${w.name} 副本`,
      confirmButtonText: '保存',
      cancelButtonText: '取消'
    })
    if (!isEditorSession(session)) return
    const name = (value ?? '').trim()
    if (!name) return
    if (!(await confirmAction('另存为', `确认把当前查询另存为「${name}」？`, '确认保存'))) return
    if (!isEditorSession(session)) return
    const before = editSignature()
    // ⚠️ 必须带上可视化配置 —— 否则副本打开后只剩 SQL,可视化面板是空的。
    //    而且要用【构建器当前】的配置(与 save() 一致):
    //    w.visualConfig 是库里最后一次保存的快照,当前未保存的改动不会进副本。
    const created = await sqlQueriesApi.create({
      name,
      description: w.description ?? '',
      database: w.database,
      sqlText: w.sqlText,
      visualConfig: currentVisualConfig() || (w.visualConfig ?? ''),
      groupId: w.groupId ?? 0
    })
    if (!isEditorSession(session)) return
    ElMessage.success('已另存为')
    await reloadAll()
    // 创建或刷新列表期间可能已切换查询，或继续编辑原查询；不能用副本覆盖这些新操作。
    if (isEditorSession(session) && editSignature() === before) openEditor(created)
  } catch (e) {
    if (e === 'cancel' || e === 'close') return
    /* 拦截器已提示 */
  } finally {
    if (isEditorSession(session)) savingAs.value = false
  }
}

async function removeQuery(row: SQLQuery) {
  if (editorDisposed || deletingQueryIds.value.has(row.id)) return
  deletingQueryIds.value.add(row.id)
  try {
    if (!(await confirmDestructive('删除查询', row.name, '删除')) || editorDisposed) return
    await sqlQueriesApi.remove(row.id)
    if (editorDisposed) return
    ElMessage.success('已删除')
    clearVisualDraft(row.id)
    if (editing.value?.id === row.id) leaveEditor()
    await reloadAll()
  } catch {
    /* 拦截器已提示 */
  } finally {
    deletingQueryIds.value.delete(row.id)
  }
}

// ── 批量删除勾选 ──
const sqTableRef = ref<{ clearSelection: () => void; toggleRowSelection: (row: SQLQuery, selected: boolean) => void } | null>(null)
const selectedQueries = ref<SQLQuery[]>([])
const deletingBatch = ref(false)
const deletingQueryIds = ref(new Set<number>())
function onSqSelectionChange(sel: SQLQuery[]): void {
  selectedQueries.value = sel
}

/**
 * 批量删除探索。
 *
 * 逐个删而不是并发:单个失败要能继续,最后给「成功 n / 失败 m」的真实汇总；
 * 并发时错误信息会混在一起、也不好统计。
 */
async function batchRemoveQueries() {
  const rows = [...selectedQueries.value]
  if (editorDisposed || !rows.length || deletingBatch.value || deletingQueryIds.value.size) return
  // 确认阶段也占用忙碌状态，防止双击打开两份确认后重复删除同一批记录。
  deletingBatch.value = true
  rows.forEach(row => deletingQueryIds.value.add(row.id))
  try {
    if (!(await confirmDestructive('批量删除探索', `${rows.length} 个查询`, '删除')) || editorDisposed) return
    const removed = new Set<number>()
    const failed: { row: SQLQuery; reason: string }[] = []
    for (const r of rows) {
      try {
        await sqlQueriesApi.remove(r.id, true)
        removed.add(r.id)
        if (!editorDisposed) {
          clearVisualDraft(r.id)
          if (editing.value?.id === r.id) leaveEditor()
        }
      } catch (error) {
        failed.push({ row: r, reason: error instanceof Error ? error.message : '删除失败' })
      }
      if (editorDisposed) return
    }
    if (failed.length) {
      ElMessage.warning({ message: `已删除 ${removed.size} 个，失败 ${failed.length} 个：` +
        failed.map(({ row, reason }) => `${row.name}：${reason}`).join('；'), duration: 8000, showClose: true })
    } else ElMessage.success(`已删除 ${removed.size} 个`)
    await reloadAll()
    if (editorDisposed) return
    // 即使列表刷新失败，也先移除已确认删除的行；按 ID 重新勾选失败项，保留重试入口。
    queries.value = queries.value.filter(row => !removed.has(row.id))
    await nextTick()
    if (editorDisposed) return
    const failedIds = new Set(failed.map(({ row }) => row.id))
    const remaining = queries.value.filter(row => failedIds.has(row.id))
    sqTableRef.value?.clearSelection()
    for (const row of remaining) sqTableRef.value?.toggleRowSelection(row, true)
    // 编辑器打开时表格已卸载，仍保留失败项，返回列表后可直接再次发起批量删除。
    selectedQueries.value = remaining
  } finally {
    for (const row of rows) deletingQueryIds.value.delete(row.id)
    deletingBatch.value = false
  }
}

async function moveQuery(row: SQLQuery, groupId: number) {
  const groupName = groupId ? (groups.value.find((g) => g.id === groupId)?.name ?? `#${groupId}`) : '默认分组'
  if (!(await confirmAction('移动查询分组', `确认把查询「${row.name}」移动到「${groupName}」？`, '确认移动'))) return
  try {
    await sqlQueriesApi.setGroup(row.id, groupId)
    await Promise.all([loadGroups(), loadQueries()])
  } catch {
    /* 拦截器已提示 */
  }
}

// —— 分组 CRUD ——
function gmgNew() {
  gmgEditing.value = null
  gmgName.value = ''
  gmgRemark.value = ''
  groupDlgVisible.value = true
}
function openGroupManage() {
  gmgNew()
}
function gmgSelect(g: SQLQueryGroup) {
  gmgEditing.value = g
  gmgName.value = g.name
  gmgRemark.value = g.remark ?? ''
  groupDlgVisible.value = true
}
async function gmgSave() {
  const name = gmgName.value.trim()
  if (!name) {
    ElMessage.warning('请输入分组名称')
    return
  }
  if (!(await confirmAction(gmgEditing.value ? '保存查询分组' : '新建查询分组',
    gmgEditing.value ? `确认保存分组「${name}」的修改？` : `确认新建查询分组「${name}」？`,
    gmgEditing.value ? '确认保存' : '确认新建'))) return
  gmgSaving.value = true
  try {
    if (gmgEditing.value) {
      await sqlQueryGroupsApi.update(gmgEditing.value.id, name, gmgRemark.value.trim())
      ElMessage.success('分组已更新')
    } else {
      await sqlQueryGroupsApi.create(name, gmgRemark.value.trim())
      ElMessage.success('分组已创建')
    }
    groupDlgVisible.value = false
    await Promise.all([loadGroups(), loadQueries()])
  } catch {
    /* 拦截器已提示 */
  } finally {
    gmgSaving.value = false
  }
}
async function gmgRemove(g: SQLQueryGroup) {
  if (!(await confirmDestructive('删除分组', g.name, '删除', '\n分组下的查询将归入默认分组。'))) return
  try {
    await sqlQueryGroupsApi.remove(g.id)
    ElMessage.success('分组已删除')
    query.removeGroup(String(g.id))
    await Promise.all([loadGroups(), loadQueries()])
  } catch {
    /* 拦截器已提示 */
  }
}

// —— SQL 自动补全(当前编辑查询的库) ——
const sqlTables = ref<any[]>([])
const sqlColCache = ref<Record<string, string[]>>({})
const sqlColLoading: Record<string, boolean> = {}

async function loadSqlTables(db?: string) {
  const database = db || editing.value?.database
  if (!connId.value || !database) {
    sqlTables.value = []
    return
  }
  try {
    sqlTables.value = await workspaceApi.tables(connId.value, database)
  } catch {
    sqlTables.value = []
  }
}

// ⚠️ 列缓存必须按【库.表】做 key —— 同名表在标准源表（ods_data）/目标库表（dwd_data）里都有时,
//    只按表名缓存会把另一个库的列喂给补全。
function colCacheKey(database: string, table: string): string {
  return `${database}.${table}`
}

function getSqlColumns(table: string): string[] | undefined {
  const database = editing.value?.database
  if (!database) return undefined
  if (!sqlTables.value.some((t) => t.name === table)) return undefined
  const key = colCacheKey(database, table)
  if (sqlColCache.value[key]) return sqlColCache.value[key]
  if (sqlColLoading[key]) return undefined
  sqlColLoading[key] = true
  workspaceApi
    .schemaSilent(connId.value, database, table)
    .then((res) => {
      sqlColCache.value[key] = res.rows.map((r) => String(r[0]))
    })
    // ⚠️ 失败时删掉 key 而不是写 []:空数组是 truthy,会把失败永久缓存住 → 该表再也取不到列
    .catch(() => {
      delete sqlColCache.value[key]
    })
    .finally(() => {
      delete sqlColLoading[key]
    })
  return undefined
}
const completionData = computed(() => ({
  getTables: () => sqlTables.value.map((t) => t.name),
  getColumns: getSqlColumns
}))

/**
 * 配置区高度:【只由「字段面板是否有块展开」决定】。
 *
 * 用户定稿:
 *   · 字段配置 / 字段选择都收起 → 配置区缩到 28%,空间还给下面的查询结果(结果区自动展开)
 *   · 任一块展开 → 配置区 62%(够配聚合/处理链/别名/输出)
 *   · 用户手动拖过分隔条 → 一切自动调整让路
 *
 * ⚠️ 曾经还有一条"按当前页签撑高"(页签是输出字段就保底 62%)的机制 ——
 *    它会在"两块都收起、但刚执行过查询"时把高度又抬回去,和上面这条打架(无头浏览器实测)。
 *    字段面板本就只存在于「输出字段」页签里,页签携带不了额外信息,整条已撤掉。
 */
const PANEL_TALL_PCT = 62
/** 字段面板是否有块展开着 */
const fieldPanelOpen = ref(false)
/**
 * 「全部收起」信号:点运行时 +1,字段面板收到就把「字段选择」「字段配置」一起收起。
 * 用递增数字而不是布尔:连续点运行也要能再次触发(布尔第二次没变化就不生效了)。
 */
const collapseSignal = ref(0)
let splitPctBeforeTallTab = 0

function onFieldPanelChange(anyOpen: boolean) {
  fieldPanelOpen.value = anyOpen
  /*
    ⚠️ 这里【不】看 userAdjustedSplit:展开/收起是用户点出来的显式动作,
    理应立即生效。以前加了那道守卫,导致"手动拖过一次分隔条之后,字段面板的
    展开/收起就再也不起作用了"(用户实测反馈)。
    手动拖过只影响"自动展开/收回"(执行查询时),不该影响用户自己的点击。
  */
  if (anyOpen) {
    if (splitPct.value < PANEL_TALL_PCT) {
      splitPctBeforeTallTab = splitPct.value
      splitPct.value = PANEL_TALL_PCT
    }
    return
  }
  // 都收起:有结果就把高度让给结果区;空态(没结果)保持"配置区放到最大"
  splitPctBeforeTallTab = 0
  if (resultTabs.value.length) splitPct.value = SPLIT_DEFAULT
}

/** 按状态设完基准高度后调用:只有面板展开时才把配置区抬到 62%(只抬不压) */
function applyTabSplit() {
  if (userAdjustedSplit) return
  if (fieldPanelOpen.value && splitPct.value < PANEL_TALL_PCT) {
    splitPctBeforeTallTab = splitPct.value
    splitPct.value = PANEL_TALL_PCT
  }
}

async function runSql() {
  if (!auth.sqlExecutionEnabled) return
  const w = editing.value
  if (running.value || !w) return
  if (!connId.value) {
    ElMessage.warning('未找到可用连接')
    return
  }
  const selected = (codeMirrorRef.value?.getSelection?.() ?? '').trim()
  const sql = (selected || w.sqlText).trim()
  if (!sql) return

  // 运行 = 要看结果 → 自动收起字段面板(字段选择 / 字段配置),
  // 高度立刻让给下面的结果区。用户手动拖过分隔条也不拦:
  // 「收起」本来就是用户点出来的显式动作,只是这次由运行代劳(和手动点收起表现一致)。
  collapseSignal.value++
  // 未手改的构建器产物 → 继续按可视化执行(否则程序算子列会退化成明文)。
  //
  // ⚠️ 必须对【将要执行的那段 sql】判定,不能用「有没有选区」当条件 ——
  //    用户 Ctrl+A 全选后运行,选中的就是同一段构建器 SQL,
  //    曾经因为 `if (!selected)` 把守卫整段跳过,SM3 列直接以明文返回(已实测复现)。
  const cfg = builderConfigIfUntouched(sql)
  if (hasProgFields(cfg)) {
    lastSql.value = sql
    page.value = 1
    queryFailed.value = false
    queryError.value = ''
    resultSource.value = { kind: 'visual', config: cfg as QueryWorkflowConfig }
    await doVisualQuery(1)
    return
  }
  lastSql.value = sql
  page.value = 1
  queryFailed.value = false
  resultSource.value = { kind: 'sql' }
  await doQuery(sql, 1)
}

/** 应用一次查询结果(手写 SQL 与可视化执行共用) */
function applyResult(
  res: {
    results?: any[]
    messages?: string[]
    resultStatements?: string[]
    truncated?: boolean
    sql?: string
    /** 列名 → 表字段注释(响应级;后端按列名从 system.columns 取) */
    columnComments?: Record<string, string>
  },
  mySeq: number,
  p: number,
  t0: number,
  db: string,
  historySql: string
) {
  elapsed.value = Math.round(performance.now() - t0)
  queryTruncated.value = !!res.truncated
  // 可视化接口会把实际生成的 SQL 回传(手写 SQL 用调用方传入的)
  if (res.sql) executedSql.value = res.sql
  const tabs: ResultTab[] = []
  ;(res.results ?? []).forEach((r, i) => {
    // 列注释是响应级的(按列名索引),这里挂到每个结果上 ——
    // 结果表格拿到 result 就能直接读,不用再往下透传一层 props。
    const result = res.columnComments ? { ...r, columnComments: res.columnComments } : r
    tabs.push({ key: `r${i}`, seq: mySeq, kind: 'grid', result, stmt: res.resultStatements?.[i] ?? '' })
  })
  ;(res.messages ?? []).forEach((m, i) => {
    tabs.push({ key: `m${i}`, seq: mySeq, kind: 'msg', message: m })
  })
  resultTabs.value = tabs
  activeResult.value = tabs[0]?.key ?? ''
  if (!tabs.length) message.value = '执行成功'
  if (p === 1) pushHistory(historySql, true, elapsed.value, db)
}

/** 查询失败的统一处理 */
function applyQueryError(e: any, p: number, t0: number, db: string, sql: string) {
  elapsed.value = Math.round(performance.now() - t0)
  if (p === 1) pushHistory(sql, false, elapsed.value, db)
  queryFailed.value = true
  queryError.value = e?.message || '执行失败'
  // ⚠️ 有旧结果时由顶部横幅统一说明,不要再设 message 重复一遍
  message.value = resultTabs.value.length ? '' : `⚠ 执行失败:${queryError.value}`
}

/** 手写 SQL 执行(通用接口) */
async function doQuery(sql: string, p: number): Promise<boolean> {
  if (!auth.sqlExecutionEnabled) return false
  const w = editing.value
  if (!w) return false
  if (allowWrite.value && p === 1) {
    const brief = sql.length > 240 ? sql.slice(0, 240) + ' …' : sql
    if (!(await confirmAction('执行写操作 SQL', `已勾选「允许写操作」，该 SQL 可能修改或删除数据。确认执行？\n\n${brief}`, '确认执行'))) return false
  }
  const mySeq = ++sqlSeq
  running.value = true
  message.value = ''
  // 执行了查询 → 结果区展开(不再是一条窄条)
  expandResultForRun()
  const t0 = performance.now()
  if (p === 1) executedSql.value = sql
  try {
    const res = await workspaceApi.query(connId.value, sql, allowWrite.value, w.database, p, pageSize.value)
    if (mySeq !== sqlSeq) return false
    applyResult(res, mySeq, p, t0, w.database, sql)
    return true
  } catch (e: any) {
    // ⚠️ 必须与成功分支同样守卫 —— 否则旧请求的失败会把【新结果】误标成「执行失败」
    if (mySeq !== sqlSeq) return false
    applyQueryError(e, p, t0, w.database, sql)
    return false
  } finally {
    if (mySeq === sqlSeq) running.value = false
  }
}

/**
 * 可视化执行(专用接口):只传 config,SQL 与程序算子计划由后端一起推导。
 *
 * ⚠️ 这是「更稳」的关键 —— 前端不再分别传 sql 和 progCols,
 * 结构上不存在"漏传计划导致 SM3/3DES 被跳过"的可能。
 */
async function doVisualQuery(p: number): Promise<boolean> {
  if (!auth.sqlExecutionEnabled) return false
  const w = editing.value
  const src = resultSource.value
  if (!w || src.kind !== 'visual') return false
  const mySeq = ++sqlSeq
  running.value = true
  message.value = ''
  // 执行了查询 → 结果区展开(不再是一条窄条)
  expandResultForRun()
  const t0 = performance.now()
  try {
    const res = await queryWorkflowsApi.execute({
      config: src.config,
      page: p,
      pageSize: pageSize.value
    })
    if (mySeq !== sqlSeq) return false
    applyResult(res, mySeq, p, t0, w.database, res.sql || '')
    return true
  } catch (e: any) {
    if (mySeq !== sqlSeq) return false
    applyQueryError(e, p, t0, w.database, executedSql.value)
    return false
  } finally {
    if (mySeq === sqlSeq) running.value = false
  }
}

/** 统一分页入口:按当前结果集来源选择接口 */
async function executePage(p: number): Promise<boolean> {
  if (resultSource.value.kind === 'visual') return doVisualQuery(p)
  return doQuery(lastSql.value, p)
}

/** 翻页:用上次运行的 SQL 重新查询该页 */
async function onPageChange(p: number) {
  page.value = p
  // 可视化来源 → 走 execute 接口;手写 SQL → 走通用接口
  if (resultSource.value.kind !== 'visual' && !lastSql.value) return
  await executePage(p)
}

/** 每页条数变化:重置到第 1 页 */
async function onPageSizeChange(s: number) {
  pageSize.value = s
  page.value = 1
  // ⚠️ 必须走 executePage —— 可视化来源要重新走 execute 接口,否则程序算子(SM3/3DES)被跳过,
  //    脱敏列会以【明文】展示(它不在 SQL 里,由后端在结果返回后计算)。
  if (resultSource.value.kind !== 'visual' && !lastSql.value) return
  await executePage(1)
}
function clearResults() {
  queryFailed.value = false
  queryError.value = ''
  resultSource.value = { kind: 'sql' }
  resultTabs.value = []
  collapseResultIfEmpty()
  message.value = ''
  elapsed.value = -1
  activeResult.value = ''
  page.value = 1
  lastSql.value = ''
  // ⚠️ 这里【不能】清 visualSqlSnapshot:清空结果不动编辑器内容,快照仍是当前这段
  //    SQL 的有效凭证。清掉它守卫就失效,再运行会落到通用接口 → 程序算子列明文
  //    (已实测复现:同一段 SQL 从 96F6… 变成 13800000000)。
}

// —— 历史 ——
function loadHistory() {
  try {
    const raw = queryStorage.getItem(STORAGE_HD)
    history.value = raw ? JSON.parse(raw) : []
  } catch {
    history.value = []
  }
}
function pushHistory(sql: string, ok: boolean, ms: number, db: string) {
  const at = new Date().toLocaleString()
  history.value = [{ sql, at, ok, ms, db }, ...history.value].slice(0, 50)
  try {
    queryStorage.setItem(STORAGE_HD, JSON.stringify(history.value))
  } catch {
    /* 忽略 */
  }
}
function clearHistory() {
  history.value = []
  queryStorage.removeItem(STORAGE_HD)
  ElMessage.success('历史已清空')
}
function applyHistory(h: HistItem) {
  if (editing.value) {
    editing.value.sqlText = h.sql
    dirty.value = true
    // 载入的是手写 SQL → 切到 SQL 模式,否则在可视化模式下看着像"没反应"
    editorMode.value = 'sql'
    visualSqlSnapshot.value = ''
  }
  historyVisible.value = false
  ElMessage.success('已载入 SQL')
}

// 切换分组/搜索时清空过滤并回到第一页
watch([filter, kw], () => {
  listPage.value = 1
})

/**
 * 观测分栏容器高度,用于计算结果表格可用高度。
 *
 * ⚠️ 容器只在【编辑器分支】(v-else)里存在,而组件挂载时 editing=null,
 *    所以必须在打开编辑器后重新挂载 —— 否则 splitWrapH 恒为 0,结果表格永远用兜底高度。
 */
function attachSplitObserver() {
  ro?.disconnect()
  ro = null
  splitWrapH.value = 0
  const el = splitWrapRef.value
  if (!el || typeof ResizeObserver === 'undefined') return
  ro = new ResizeObserver(() => {
    splitWrapH.value = el.clientHeight
  })
  ro.observe(el)
  splitWrapH.value = el.clientHeight
}

watch(
  () => editing.value,
  async () => {
    await nextTick()
    attachSplitObserver()
  }
)

onMounted(async () => {
  loadHistory()
  await loadConn()
  await reloadAll()
})

onBeforeUnmount(() => {
  editorDisposed = true
  editorSession++
  sqlSeq++
  ro?.disconnect()
  ro = null
  dragCleanup?.()
  dragCleanup = null
  cancelAutoSave()
  if (draftTimer) {
    clearTimeout(draftTimer)
    draftTimer = null
  }
  if (exportDoneTimer) {
    clearTimeout(exportDoneTimer)
    exportDoneTimer = null
  }
})

</script>

<style scoped>
.sqlq-view {
  height: 100%;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  padding: 18px 22px;
}
.sq-access-note { flex-shrink: 0; }
.sq-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 10px;
  overflow: hidden;
}
.sq-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  flex: none;
}
.sq-head h2 { margin: 0; font-size: 18px; }
.sq-head-group { font-size: 14px; color: var(--el-text-color-secondary); }
.sq-sub { margin: 4px 0 0; font-size: 14px; color: var(--el-text-color-secondary); }
/* 工具栏动作区:下拉 / 设置 / 新建 / 刷新 高度一致(默认 32px)并垂直居中 */
.actions {
  display: flex;
  align-items: center;
  gap: 8px;
}
.ed-head-left { display: flex; align-items: center; gap: 8px; }
.ed-name { width: 220px; }
/* 自动保存:开关 + 状态提示 */
.ed-autosave {
  --el-switch-on-color: var(--el-color-primary);
}
.ed-save-state {
  font-size: 12px;
  white-space: nowrap;
}
.ed-save-state.ok {
  color: var(--el-text-color-placeholder);
}
.ed-save-state.busy {
  color: var(--el-color-primary);
}
.sq-tbl-wrap {
  flex: 1;
  min-height: 0;
  /* 外框由 el-table--border 统一提供(见 theme.css),这里不再套第二层边框 */
  overflow: hidden;
}
.sq-pager {
  flex: none;
  display: flex;
  justify-content: flex-end;
  padding-top: 10px;
}
.tbl-link { cursor: pointer; color: var(--el-color-primary); }
.tbl-link:hover { text-decoration: underline; }
.sq-empty-hint { padding: 14px; color: var(--el-text-color-secondary); }
/* 编辑器区 */
.sql-toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  flex: none;
}
.sql-toolbar .el-checkbox__label { font-size: 14px; }
.sql-hint { margin-left: auto; font-size: 13px; color: var(--el-text-color-secondary); }
/* ── 配置区 / 结果区 可拖拽分栏 ── */
.sq-split-wrap {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}
.sq-config {
  flex: 0 0 auto;
  min-height: 150px;
  display: flex;
  min-width: 0;
  /*
    ⚠️ 必须收口:以前这里没有 overflow —— 配置区里的内容(比如展开「字段选择」)一旦高过
    这个盒子,就会【画到下面的结果区上】,而后绘制的兄弟节点(结果区)再把它盖上,
    看上去就是"字段选择被查询结果遮住了"。溢出应当由配置区【内部】滚动消化。
  */
  overflow: hidden;
}
/* 两个模式面板都撑满,并允许内部自己滚(它们各自的列表本来就有滚动容器) */
.sq-config > .editor-box,
.sq-config > .builder-box {
  flex: 1;
  min-width: 0;
  min-height: 0;
  overflow: hidden;
}
.editor-box { flex: 1; min-width: 0; min-height: 0; display: flex; }
.editor-box > * { flex: 1; min-width: 0; }
/* 可视化构建区:填满配置区(高度由分栏比例控制) */
.builder-box {
  flex: 1;
  min-width: 0;
  min-height: 0;
  display: flex;
}
/* 构建器根元素必须撑满,否则它靠内容定宽 → 右侧留空 */
.builder-box > * {
  flex: 1 1 auto;
  min-width: 0;
  min-height: 0;
}
.editor-box :deep(.cm-editor) {
  height: 100%;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  overflow: hidden;
}
/* 「本次执行的 SQL」弹窗(原来在结果区上方占一条,现挂在结果标题行的「SQL」按钮上) */
.exec-sql-dlg {
  max-height: 58vh;
  overflow: auto;
}
.exec-sql-tip {
  margin: 0 0 8px;
  font-size: 13px;
  color: var(--el-text-color-placeholder);
}
.exp-prog-tip {
  margin-bottom: 10px;
}
.stale-banner {
  margin: 0 0 8px;
  padding: 7px 10px;
  border-radius: 6px;
  border: 1px solid var(--el-color-danger-light-5);
  background: var(--el-color-danger-light-9);
  color: var(--el-color-danger-dark-2);
  font-size: 13px;
  line-height: 1.65;
}
.sb-main {
  font-weight: 600;
  word-break: break-all;
}
.sb-sub {
  color: var(--el-color-warning-dark-2);
  margin-top: 2px;
}
/* 分隔条:平时一条细线,悬停出现抓手 */
.sq-divider {
  flex: 0 0 7px;
  margin: 2px 0;
  cursor: row-resize;
  display: flex;
  align-items: center;
  justify-content: center;
  position: relative;
}
.sq-divider::before {
  content: '';
  position: absolute;
  left: 0;
  right: 0;
  top: 3px;
  height: 1px;
  background: var(--el-border-color-lighter);
  transition: background 0.15s ease, height 0.15s ease, top 0.15s ease;
}
.sq-divider:hover::before,
.sq-divider.dragging::before {
  top: 2px;
  height: 3px;
  border-radius: 2px;
  background: var(--brand, #eab308);
}
.sq-divider-grip {
  width: 32px;
  height: 4px;
  border-radius: 2px;
  background: var(--el-border-color);
  opacity: 0;
  transition: opacity 0.15s ease;
  z-index: 1;
}
.sq-divider:hover .sq-divider-grip,
.sq-divider.dragging .sq-divider-grip {
  opacity: 1;
  background: var(--brand, #eab308);
}
.sql-result {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
}
.sql-result-body {
  flex: 1;
  min-height: 0;
  /*
    不滚:结果表自己带 max-height(gridMaxHeight,按结果区可用高度算好)并内部滚动。
    外层再 overflow:auto 就会出现【第二条】滚动条 —— 就是用户看到的那条。
    分页条在 body 之外(v-if="hasGrid" 的那个兄弟节点),所以不会被裁掉。
    ⚠️ 但 gridMaxHeight 是【估算】(视口/页签头/分页条高度都会有出入),差几像素时
       表格最后一行会被分页条压住 —— 所以这里保留"能滚"的能力,只是【不显示滚动条】:
       滚轮/触控板照常可用,视觉上跟没有滚动条一样(用户明确不想看到那条)。
  */
  overflow-y: auto;
  scrollbar-width: none;              /* Firefox:不显示滚动条 */
  padding: 8px;
}
.sql-result-body::-webkit-scrollbar { width: 0; height: 0; }   /* Chrome/Safari */
/*
  空态:结果区默认只留一条窄条,用一行居中提示就够。
  ⚠️ 不要用 el-empty —— 图标 60px + 内边距共 110px,窄条(实测 74px)里必然被裁。
*/
.sql-empty {
  height: 100%;
  min-height: 34px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 13px;
  color: var(--el-text-color-placeholder);
}
.result-block {
  margin-bottom: 12px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
}
.rt-spacer {
  flex: 1;
}
.result-title {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 13px;
  color: var(--el-text-color-secondary);
  padding: 4px 8px;
  border-bottom: 1px solid var(--el-border-color-lighter);
}
.result-title :deep(.el-button) {
  padding: 0 8px;
  height: 32px;
}
.res-pager {
  flex: none;
  display: flex;
  justify-content: flex-end;
  padding: 6px 10px;
  border-top: 1px solid var(--el-border-color-lighter);
}
.meta-warn { color: var(--el-color-warning); }
.res-tabs {
  margin-top: -6px;
}
/* 省掉标签栏时不能再往上提 6px(否则会压到上面的横幅/提示上) */
.res-tabs-single {
  margin-top: 0;
}
.res-tabs-single :deep(.el-tabs__header) {
  display: none;
}
.res-tabs :deep(.el-tabs__content) {
  padding-top: 4px;
}
.elapsed {
  font-size: 13px;
  color: var(--el-text-color-secondary);
  font-family: var(--font-mono, monospace);
}
/* 新建弹窗 */
.layer-opts { display: flex; flex-direction: column; gap: 8px; width: 100%; }
.layer-opt {
  display: flex;
  align-items: flex-start;
  height: auto;
  padding: 8px 10px;
  border: 1px solid var(--el-border-color);
  border-radius: 6px;
  margin-right: 0;
  width: 100%;
  box-sizing: border-box;
}
/*
 * 单选钮与文字必须【同一行】:
 * 圆点固定宽度不参与拉伸,文字区占剩余宽度并允许换行。
 * 不固定时,长描述会把圆点挤得与文字错行。
 */
.layer-opt :deep(.el-radio__input) {
  flex: none;
  align-self: flex-start;
  margin-top: 1px; /* 与首行文字视觉居中对齐 */
}
.layer-opt :deep(.el-radio__label) {
  flex: 1;
  min-width: 0;
  white-space: normal;
  line-height: 1.45;
}
.layer-line { display: flex; align-items: baseline; gap: 8px; flex-wrap: wrap; }
.layer-name { font-size: 14px; font-weight: 600; }
.layer-db { font-size: 12px; color: var(--el-text-color-placeholder); }
.layer-desc { font-size: 13px; color: var(--el-text-color-secondary); margin-top: 2px; }
/* 历史 */
.exp-cols {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 12px;
  max-height: 140px;
  overflow-y: auto;
}
.exp-tips {
  font-size: 13px;
  color: var(--el-text-color-placeholder);
  line-height: 1.8;
}
.exp-rename {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 4px;
}
.exp-from {
  min-width: 130px;
  font-size: 13px;
}
.sq-history { padding: 4px 8px; }
.sq-history-top { display: flex; align-items: center; justify-content: space-between; margin-bottom: 8px; }
.sq-hist-item {
  border: 1px solid var(--el-border-color);
  border-radius: 6px;
  padding: 8px 10px;
  margin-bottom: 8px;
}
.sq-hist-item:hover { border-color: var(--brand); }
.sq-hist-head { display: flex; align-items: center; gap: 8px; margin-bottom: 4px; font-size: 13px; }
.sq-hist-sql {
  font-size: 13px;
  color: var(--el-text-color-regular);
  white-space: pre-wrap;
  max-height: 80px;
  overflow: hidden;
}
/* .dim 见全局主题(assets/theme.css):placeholder 灰 + 12px */
.mono { font-family: var(--font-mono, monospace); }

/* ── SQL 片段库抽屉 ── */
.sn-wrap { display: flex; flex-direction: column; height: 100%; }
.sn-head { padding: 4px 4px 10px; border-bottom: 1px solid var(--el-border-color-lighter); }
.sn-head-line { display: flex; align-items: baseline; gap: 10px; margin-bottom: 8px; }
.sn-title { font-size: 15px; font-weight: 600; }
.sn-sub { font-size: 13px; color: var(--el-text-color-placeholder); }
.sn-search { max-width: 320px; }
.sn-body { display: flex; flex: 1; min-height: 0; gap: 10px; padding-top: 10px; }

.sn-groups { width: 148px; flex: 0 0 148px; overflow-y: auto; }
.sn-groups.dim .sn-gitem { opacity: 0.45; }
.sn-gitem {
  display: flex; align-items: center; gap: 7px;
  padding: 7px 9px; border-radius: 6px; cursor: pointer;
  font-size: 14px; transition: background 0.15s ease;
}
.sn-gitem:hover { background: var(--el-fill-color); }
.sn-gitem.active { background: rgba(234, 179, 8, 0.12); font-weight: 600; }
.sn-dot { width: 7px; height: 7px; border-radius: 50%; flex: 0 0 7px; }
.sn-gname { flex: 1; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.sn-gcount { font-size: 12px; color: var(--el-text-color-placeholder); }

.sn-list { flex: 1; min-width: 0; overflow-y: auto; padding-right: 4px; }
.sn-list-hint {
  font-size: 12px; color: var(--el-text-color-placeholder);
  margin-bottom: 8px; padding-left: 2px;
}
.sn-card {
  border: 1px solid var(--el-border-color);
  border-radius: 8px;
  padding: 9px 11px;
  margin-bottom: 9px;
  cursor: pointer;
  transition: border-color 0.15s ease, background 0.15s ease;
}
.sn-card:hover { border-color: var(--brand); background: var(--el-fill-color-lighter); }
.sn-card.warn { border-left: 3px solid #ef4444; }
.sn-card-head { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.sn-label { font-size: 14px; font-weight: 600; }
.sn-ins {
  font-size: 12px; color: var(--brand-strong, #b45309);
  opacity: 0; transition: opacity 0.15s ease; white-space: nowrap;
}
.sn-card:hover .sn-ins { opacity: 1; }
.sn-desc { font-size: 13px; color: var(--el-text-color-regular); margin-top: 3px; line-height: 1.5; }
.sn-code {
  font-family: var(--font-mono, monospace);
  font-size: 12px;
  line-height: 1.55;
  color: var(--el-text-color-secondary);
  background: var(--el-fill-color-light);
  border-radius: 5px;
  padding: 7px 9px;
  margin: 7px 0 0;
  max-height: 132px;
  overflow: auto;
  white-space: pre;
}

</style>
