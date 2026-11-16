<!--
  WorkspaceView.vue

  职责：数据工作台页面：浏览连接/数据库/表树，执行表操作并维护当前导航上下文。
  边界：按连接、库和表加载导航树与详情，路由和导航 tick 去重定位；侧栏宽度持久化，拖拽监听和延迟定时器在卸载时清理。
-->

<template>
  <div class="workspace-view">
    <el-container class="workspace">
      <el-aside :width="`${sideWidth}px`" class="side">
        <div class="conn-bar">
          <span class="conn-name" :title="store.active?.host">{{ store.active?.name || '内置连接' }}</span>
          <el-tooltip content="连接设置" placement="top">
            <el-button :icon="Setting" circle size="small" @click="editConnVisible = true" />
          </el-tooltip>
        </div>
        <div class="tree-toolbar" @click="dbExpanded = !dbExpanded">
          <span class="tree-toolbar-title">
            <span class="collapse-arrow" :class="{ collapsed: !dbExpanded }">▾</span>
            数据库
          </span>
          <span class="tree-toolbar-actions" @click.stop>
            <el-tooltip content="新建数据库" placement="top">
              <el-button :icon="Plus" circle size="small" @click="openCreateDb()" />
            </el-tooltip>
            <el-tooltip content="分组管理" placement="top">
              <el-button :icon="Folder" circle size="small" @click="openGroupManage" />
            </el-tooltip>
            <el-tooltip content="刷新" placement="top">
              <el-button :icon="Refresh" circle size="small" @click="reloadTree" />
            </el-tooltip>
          </span>
        </div>
        <el-tree
          v-if="dbExpanded"
          :key="treeKey"
          ref="treeRef"
          :data="dbTreeData"
          :props="treeProps"
          :load="loadNode"
          lazy
          node-key="id"
          highlight-current
          :expand-on-click-node="true"
          class="tree"
          @node-click="onNodeClick"
        >
          <template #default="{ data }">
            <span class="tree-node">
              <el-icon v-if="data.type === 'group'" class="node-icon">
                <el-icon><Folder /></el-icon>
              </el-icon>
              <el-icon v-else class="node-icon" :class="data.type">
                <DbIcon v-if="data.type === 'db'" />
                <TableIcon v-else-if="data.type === 'table'" />
              </el-icon>
              <span class="node-label" :title="data.label ?? data.name">{{ data.label ?? data.name }}</span>
              <span v-if="data.type !== 'group' && data.remark" class="node-remark" :title="data.remark">{{ data.remark }}</span>
              <span class="node-actions">
                <!-- 分组:在该组下新建库 / 编辑 / 删除 -->
                <template v-if="data.type === 'group'">
                  <el-tooltip v-if="data.groupId" content="分组下新建数据库" placement="top">
                    <el-button link size="small" type="primary" @click.stop="openCreateDb(data.groupId)">
                      <el-icon><Plus /></el-icon>
                    </el-button>
                  </el-tooltip>
                  <el-tooltip v-if="data.groupId" content="编辑分组" placement="top">
                    <el-button link size="small" type="primary" @click.stop="editGroupFromTree(data)">
                      <el-icon><EditPen /></el-icon>
                    </el-button>
                  </el-tooltip>
                  <el-tooltip v-if="data.groupId" content="删除分组" placement="top">
                    <el-button link size="small" type="danger" @click.stop="removeGroupFromTree(data)">
                      <el-icon><Delete /></el-icon>
                    </el-button>
                  </el-tooltip>
                  <el-tooltip v-else content="「默认分组」是虚拟节点,把库里所有数据库都分配到分组后它会自动消失" placement="top">
                    <el-button link size="small" @click.stop="openGroupManage">
                      <el-icon><Folder /></el-icon>
                    </el-button>
                  </el-tooltip>
                </template>
                <el-tooltip v-if="data.type === 'db'" content="新建表" placement="top">
                  <el-button link size="small" type="primary" @click.stop="openCreateTable(data.name)">
                    <el-icon><Plus /></el-icon>
                  </el-button>
                </el-tooltip>
                <!-- 删除按钮:db 与 table 共用同一条 v-if / v-else-if 链,靠 type 分流;
                     新增节点类型的删除入口时不要打断这个链,否则会出现两个按钮或一个都不出 -->
                <el-tooltip v-if="data.type === 'db' && data.name !== 'default'" content="删除数据库" placement="top">
                  <el-button link size="small" type="danger" @click.stop="dropDb(data)">
                    <el-icon><Delete /></el-icon>
                  </el-button>
                </el-tooltip>
                <el-tooltip v-else-if="data.type === 'table'" content="删除表" placement="top">
                  <el-button link size="small" type="danger" @click.stop="dropTable(data)">
                    <el-icon><Delete /></el-icon>
                  </el-button>
                </el-tooltip>
              </span>
            </span>
          </template>
        </el-tree>

        <div class="tree-toolbar user-toolbar" @click="toggleUsers">
          <span class="tree-toolbar-title">
            <span class="collapse-arrow" :class="{ collapsed: !usersExpanded }">▾</span>
            用户
          </span>
          <span class="tree-toolbar-actions" @click.stop>
            <el-tooltip content="新建用户" placement="top">
              <el-button :icon="Plus" circle size="small" @click="onCreateUser" />
            </el-tooltip>
            <el-tooltip content="刷新" placement="top">
              <el-button :icon="Refresh" circle size="small" @click="loadTreeUsers" />
            </el-tooltip>
          </span>
        </div>
        <div v-show="usersExpanded" class="user-list">
          <div v-for="u in treeUsers" :key="u.name" class="user-item" @click="openUserTab(u.name)">
            <el-icon class="user-item-icon"><User /></el-icon>
            <span class="user-item-name" :title="u.name">{{ u.name }}</span>
            <span v-if="u.remark" class="user-item-remark" :title="u.remark">{{ u.remark }}</span>
            <span v-if="u.isDefault" class="node-default">默认</span>
          </div>
          <div v-if="!treeUsers.length" class="user-empty">暂无用户</div>
        </div>
        <div
          class="side-resize-handle"
          role="separator"
          aria-label="拖动调整数据库表列表宽度"
          aria-orientation="vertical"
          tabindex="0"
          @pointerdown="startSideResize"
          @keydown.left.prevent="setSideWidth(sideWidth - 20)"
          @keydown.right.prevent="setSideWidth(sideWidth + 20)"
        />
      </el-aside>

      <el-main class="main">
        <el-tabs v-model="activeTab" type="card" closable class="main-tabs" @tab-remove="closeTab">
          <el-tab-pane label="目标库表（dwd_data）操作" name="sql" :closable="false">
            <div class="ops-home">
              <div class="ops-head">
                <h3 class="ops-title">目标库表（dwd_data）</h3>
                <p class="ops-sub">当前连接 · {{ store.active?.name || '内置连接' }}({{ store.active?.host || '' }})。点击左侧树可浏览数据库/表,或从下方开始新建。</p>
              </div>
              <div class="ops-cards">
                <div class="ops-card" @click="openGroupManage">
                  <el-icon class="ops-card-icon"><Folder /></el-icon>
                  <div class="ops-card-name">新建分组</div>
                  <div class="ops-card-desc">把数据库按业务归类管理</div>
                </div>
                <div class="ops-card" @click="openCreateDb()">
                  <el-icon class="ops-card-icon"><Plus /></el-icon>
                  <div class="ops-card-name">新建数据库</div>
                  <div class="ops-card-desc">在该连接下创建一个数据库</div>
                </div>
                <div class="ops-card" @click="openCreateTableFromHome">
                  <el-icon class="ops-card-icon"><Grid /></el-icon>
                  <div class="ops-card-name">新建表</div>
                  <div class="ops-card-desc">选择目标库后按字段/DDL 建表</div>
                </div>
              </div>
              <div class="ops-tip">在左侧数据库树中点击某个库/表,右侧会打开对应的详情;这些操作也可在每个分组 / 数据库的右键快捷按钮上触发。</div>
            </div>
          </el-tab-pane>

          <el-tab-pane v-if="usersTabVisible" label="用户管理" name="users">
            <UserManager
              ref="userManagerRef"
              :conn-id="currentId"
              :read-only-user="store.active?.username"
              :create-user-flag="createUserFlag"
              @changed="loadTreeUsers"
            />
          </el-tab-pane>

          <!-- 动态页签区:表/库/用户各一份列表,key 前缀(table: / db: / user:)决定它归属哪一份,
               也是 closeTab 关闭时唯一能定位到页签的依据 —— 新增页签种类时三处都要同步 -->
          <el-tab-pane v-for="t in tableTabs" :key="t.key" :label="t.deleted ? `${t.title} · 已删除` : t.title" :name="t.key" lazy>
            <el-result v-if="t.deleted" icon="success" title="表已删除" :sub-title="`${t.title} 已从当前连接删除，左侧库表列表已更新。`">
              <template #extra>
                <el-button type="primary" @click="openDbTab(t.db)">查看所在数据库</el-button>
              </template>
            </el-result>
            <TableDetail v-else
              :conn-id="currentId"
              :db="t.db"
              :table="t.table"
              @deleted="onTableDeleted(t.key)"
              @renamed="(newName: string) => onTableRenamed(t.key, newName)"
              @updated="refreshDbTables(t.db)"
            />
          </el-tab-pane>

          <el-tab-pane v-for="d in dbTabs" :key="d.key" :label="d.title" :name="d.key" lazy>
            <DatabaseDetail
              :key="d.refreshVersion || 0"
              :conn-id="currentId"
              :db="d.name"
              :remark="d.remark"
              @table-opened="userOpenTable"
              @create-table="openCreateTable"
              @deleted="onDbDeleted(d.key)"
            />
          </el-tab-pane>

          <el-tab-pane v-for="u in userTabs" :key="u.key" :label="u.title" :name="u.key" lazy>
            <UserDetail
              :conn-id="currentId"
              :user-name="u.name"
              :read-only-user="store.active?.username"
              @changed="loadTreeUsers"
              @deleted="onUserDeleted(u.key)"
            />
          </el-tab-pane>
        </el-tabs>
      </el-main>
    </el-container>

    <el-dialog v-model="createDbVisible" title="新建数据库" width="460px" :close-on-click-modal="false">
      <el-form label-width="70px" @submit.prevent>
        <el-form-item label="库名">
          <el-input v-model="createDbName" placeholder="例如 ods,仅字母/数字/下划线" @keyup.enter="onCreateDb" />
        </el-form-item>
        <el-form-item label="所属分组">
          <el-select v-model="createDbGroupId" clearable filterable placeholder="不选=默认分组" style="width: 100%">
            <el-option v-for="g in allGroups" :key="g.id" :value="g.id" :label="g.name" />
            <template #footer>
              <div class="grp-quick">
                <el-input
                  v-model="newGroupName"
                  size="small"
                  placeholder="新分组名,回车直接创建"
                  clearable
                  @keyup.enter="quickCreateGroup"
                />
                <el-button
                  size="small"
                  type="primary"
                  plain
                  :loading="newGroupSaving"
                  :disabled="!newGroupName.trim()"
                  @click="quickCreateGroup"
                >新建</el-button>
              </div>
            </template>
          </el-select>
          <div class="grp-tip">若分组不存在,可在此下拉底部直接新建</div>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="createDbRemark" placeholder="可选,显示在数据库节点上" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createDbVisible = false">取消</el-button>
        <el-button type="primary" :loading="creatingDb" @click="onCreateDb">创建</el-button>
      </template>
    </el-dialog>

    <!-- 分组管理 -->
    <el-dialog v-model="groupDlgVisible" title="数据库分组管理" width="680px" :close-on-click-modal="false">
      <div class="group-mgr">
        <div class="group-side">
          <div class="group-side-head">
            <span>分组</span>
            <el-button link type="primary" size="small" :icon="Plus" @click="groupEditing = null; groupName=''; groupRemark=''; groupChecked=[]">新建</el-button>
          </div>
          <div class="group-list">
            <div v-for="g in allGroups" :key="g.id" class="group-item" :class="{ active: groupEditing?.id === g.id }" @click="selectGroup(g)">
              <el-icon class="group-folder"><Folder /></el-icon>
              <span class="group-item-name" :title="g.name">{{ g.name }}</span>
              <span class="group-item-count">{{ g.dbs?.length ?? 0 }}</span>
              <el-tooltip content="删除分组" placement="top">
                <el-button link type="danger" size="small" :icon="Delete" class="group-item-del" @click.stop="removeGroup(g)" />
              </el-tooltip>
            </div>
            <el-empty v-if="!allGroups.length" description="暂无分组" :image-size="50" />
          </div>
        </div>
        <div class="group-main">
          <el-form label-width="70px">
            <el-form-item label="名称">
              <el-input v-model="groupName" placeholder="分组名" />
            </el-form-item>
            <el-form-item label="备注">
              <el-input v-model="groupRemark" placeholder="可选" />
            </el-form-item>
            <el-form-item label="成员库">
              <el-checkbox-group v-model="groupChecked" class="db-check-list">
                <el-checkbox v-for="db in groupDlgDbs" :key="db" :value="db">
                  <span>{{ dbLabel(db) }}</span>
                </el-checkbox>
              </el-checkbox-group>
              <div v-if="!groupDlgDbs.length" class="db-empty-hint">该连接暂无数据库</div>
            </el-form-item>
          </el-form>
          <div class="group-main-actions">
            <el-button v-if="groupEditing" type="danger" plain @click="removeGroup(groupEditing)">删除分组</el-button>
            <el-button type="primary" :loading="groupSaving" @click="createOrUpdateGroup">{{ groupEditing ? '保存修改' : '新建分组' }}</el-button>
          </div>
        </div>
      </div>
    </el-dialog>

    <ConnectionFormDialog v-model="editConnVisible" :connection="editingConn" @saved="onConnEdited" />

    <CreateTableDialog
      v-model="createTableVisible"
      :conn-id="currentId"
      :db="createTableDb"
      @created="onTableCreated"
    />

    <!-- 操作首页新建表:先选目标库 -->
    <el-dialog v-model="pickDbVisible" title="新建表 · 选择目标数据库" width="400px" :close-on-click-modal="false">
      <el-form label-width="70px" @submit.prevent>
        <el-form-item label="数据库">
          <el-select v-model="pickDbValue" filterable placeholder="选择目标库" style="width: 100%">
            <el-option v-for="d in pickDbOptions" :key="d" :value="d" :label="d" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="pickDbVisible = false">取消</el-button>
        <el-button type="primary" :disabled="!pickDbValue" @click="confirmCreateTableFromPick">下一步</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { confirmAction, confirmDestructive } from '@/utils/confirm'
import { dbLabel } from '@/utils/layer'
import { Delete, EditPen, Folder, Grid, Plus, Refresh, Setting, User } from '@element-plus/icons-vue'
import DbIcon from '@/components/icons/DbIcon.vue'
import TableIcon from '@/components/icons/TableIcon.vue'
import CreateTableDialog from '@/components/CreateTableDialog.vue'
import UserManager from '@/components/UserManager.vue'
import UserDetail from '@/components/UserDetail.vue'
import { connectionsApi, workspaceApi, usersApi } from '@/api'
import { useConnectionStore } from '@/stores/connection'
import type { Connection, DBGroup, QueryResult, TableInfo } from '@/types'
import CodeMirrorEditor from '@/components/CodeMirrorEditor.vue'
import ResultGrid from '@/components/ResultGrid.vue'
import TableDetail from '@/components/TableDetail.vue'
import DatabaseDetail from '@/components/DatabaseDetail.vue'
import ConnectionFormDialog from '@/components/ConnectionFormDialog.vue'

const router = useRouter()
const route = useRoute()
const store = useConnectionStore()

const SIDE_WIDTH_KEY = 'el2ck.workspaceSideWidth'
const savedSideWidth = Number(localStorage.getItem(SIDE_WIDTH_KEY))
const sideWidth = ref(Number.isFinite(savedSideWidth) && savedSideWidth >= 240 ? savedSideWidth : 300)
let sideResizeStartX = 0
let sideResizeStartWidth = 0
let sideResizing = false

function setSideWidth(width: number) {
  sideWidth.value = Math.max(240, Math.min(650, window.innerWidth - 400, width))
  localStorage.setItem(SIDE_WIDTH_KEY, String(sideWidth.value))
}

function onSideResizeMove(event: PointerEvent) {
  if (sideResizing) setSideWidth(sideResizeStartWidth + event.clientX - sideResizeStartX)
}

function stopSideResize() {
  if (!sideResizing) return
  sideResizing = false
  window.removeEventListener('pointermove', onSideResizeMove)
  window.removeEventListener('pointerup', stopSideResize)
  document.body.style.cursor = ''
  document.body.style.userSelect = ''
}

function startSideResize(event: PointerEvent) {
  if (event.button !== 0) return
  event.preventDefault()
  sideResizeStartX = event.clientX
  sideResizeStartWidth = sideWidth.value
  sideResizing = true
  document.body.style.cursor = 'col-resize'
  document.body.style.userSelect = 'none'
  window.addEventListener('pointermove', onSideResizeMove)
  window.addEventListener('pointerup', stopSideResize)
}

onBeforeUnmount(stopSideResize)

/** 跳转导航去重:同一目标短时间只处理一次(避免 onMounted 与 watch 重复触发)。 */
let lastNavSig = ''
let lastNavAt = 0
/** 是否本次 query 变化由用户点击(自身导航)触发 — 命中则跳过 openFromQuery 重复定位 */
let selfNavPending = false

// ---- 连接(内置,不展示连接列表) ----
const connections = ref<Connection[]>([])
const currentId = computed(() => store.active?.id ?? -1)
const editConnVisible = ref(false)
const editingConn = computed(() => store.active)

async function onConnEdited() {
  try {
    connections.value = await connectionsApi.list()
  } catch {
    /* 忽略 */
  }
  // 首次加载(无内存 active)时先从本地存储恢复上次选中的连接
  if (!store.active) {
    store.restore(connections.value)
  }
  // 保持当前连接(若仍在列表);否则回退默认/首个
  const keep = connections.value.find((c) => c.id === store.active?.id)
  const def = keep ?? connections.value.find((c) => c.isDefault) ?? connections.value[0]
  if (def) {
    activateConnection(def)
  }
}

// ---- 树 ----
const treeKey = ref(0)
const treeRef = ref<any>(null)
const dbTreeData = ref<TreeNode[]>([])
// 懒加载树必须显式声明 isLeaf:只有表是叶子,分组/库都要留给 loadNode 去拉子节点,
// 否则 el-tree 会给它们画上展开箭头却永远加载不出内容。
const treeProps = { label: 'name', isLeaf: (d: TreeNode) => d.type === 'table' }

interface TreeNode {
  id: string
  /**
   * 节点名。
   * ⚠️ db 节点这里是【物理库名】:点击打开、新建表、定位高亮都按它取库。
   * 界面上显示的是 label(层级名),不要为了"不显示库名"去改这个字段 ——
   * 改了所有按库名判断/传参的地方都会跟着坏(实测踩过)。
   */
  name: string
  /** 显示名(层级名)。只有显示用它:物理库名属于实现细节,前台不暴露 */
  label?: string
  type: 'group' | 'db' | 'table'
  database?: string
  engine?: string
  remark?: string
  /** group 节点:分组 ID */
  groupId?: number
  /** 子节点(分组中包含的数据库) */
  children?: TreeNode[]
}

async function loadNode(node: any, resolve: (data: TreeNode[]) => void) {
  // 根节点数据来自 dbTreeData(data 属性),这里仅兜底返回;子节点为 Node 实例。
  const data: unknown = node?.data
  // 根调用的 data 是 dbTreeData 数组、不是 Node 实例:Array.isArray 是区分两种入参形态的依据
  const isRoot = !node || data == null || Array.isArray(data)
  if (isRoot) {
    resolve(dbTreeData.value)
    return
  }
  const nodeData = data as TreeNode
  if (nodeData.type === 'group') {
    // 分组:返回其下数据库(children 已由 loadDatabases 组装)
    resolve(nodeData.children ?? [])
    return
  }
  if (nodeData.type === 'db') {
    try {
      const tables = await workspaceApi.tables(currentId.value, nodeData.name)
      resolve(
        tables.map((t) => ({
          id: `table:${nodeData.name}.${t.name}`,
          name: t.name,
          type: 'table' as const,
          database: nodeData.name,
          engine: t.engine,
          remark: t.remark
        }))
      )
    } catch {
      resolve([])
    }
    return
  }
  resolve([])
}

/** 加载数据库列表到树根节点 */
async function loadDatabases() {
  const id = currentId.value
  if (id <= 0) {
    dbTreeData.value = []
    return
  }
  try {
    // 两个请求各自兜底:分组只是归类,库列表才是树的骨架 —— 分组接口异常时不能连库一起丢
    const [groups, dbs] = await Promise.all([
      workspaceApi
        .groups(id)
        .then((r: DBGroup[] | null) => r ?? [])
        .catch(() => [] as DBGroup[]),
      workspaceApi.databases(id).catch(() => [])
    ])
    allGroups.value = groups
    // 分组成员 → db 节点
    const inGroup = new Set<string>()
    const existSet = new Set(dbs.map((d: any) => d.name)) // 连接中真实存在的库
    const tree: TreeNode[] = groups.map((g: DBGroup) => {
      // 分组下只展示物理存在的库(过滤被删/残留的库名)
      const children: TreeNode[] = (g.dbs ?? [])
        .filter((db: string) => existSet.has(db))
        .map((db: string) => {
          inGroup.add(db)
          // name 保持【物理库名】:节点的点击/新建表/定位都按它取库,不能动;
          // 界面上显示的是 label(层级名)——见 TreeNode.label 的注释
          return { id: `db:${db}`, name: db, type: 'db' as const, label: dbLabel(db) }
        })
      return { id: `group:${g.id}`, name: g.name, type: 'group' as const, groupId: g.id, remark: g.remark, children }
    })
    // 默认分组(未分配具体分组的库)
    const ungrouped: TreeNode[] = []
    for (const d of dbs) {
      if (inGroup.has(d.name)) continue
      ungrouped.push({ id: `db:${d.name}`, name: d.name, type: 'db' as const, label: dbLabel(d.name), remark: d.remark })
    }
    // 「默认分组」是虚拟节点(无 groupId,不能编辑/删除):只承载未归类的库,全部归类后它自动消失
    if (ungrouped.length) tree.push({ id: 'ungrouped', name: '默认分组', type: 'group' as const, children: ungrouped })
    dbTreeData.value = tree
  } catch {
    dbTreeData.value = []
  }
}

/** 在分组树中定位某数据库:展开其分组并高亮库节点;返回是否找到 */
function locateDbInTree(dbName: string): boolean {
  if (!dbTreeData.value.length) return false
  // 已是顶层库(默认分组未渲染时)
  let node = dbTreeData.value.find((n) => n.type === 'db' && n.name === dbName)
  if (node) {
    nextTick(() => treeRef.value?.setCurrentKey(node.id))
    return true
  }
  // 在某分组下
  const grp = dbTreeData.value.find((n) => n.type === 'group' && (n.children ?? []).some((c) => c.name === dbName))
  if (grp) {
    nextTick(() => {
      treeRef.value?.getNode(grp.id)?.expand()
      nextTick(() => treeRef.value?.setCurrentKey(`db:${dbName}`))
    })
    return true
  }
  return false
}

function reloadTree() {
  treeKey.value++
  loadDatabases()
}

// ---- 新建数据库 ----
const createDbVisible = ref(false)
const createDbName = ref('')
const createDbRemark = ref('')
const createDbGroupId = ref<number | null>(null) // 所属分组(空=默认分组)
const creatingDb = ref(false)
/** 新建数据库下拉内“就地新建分组” */
const newGroupName = ref('')
const newGroupSaving = ref(false)

async function quickCreateGroup() {
  const name = newGroupName.value.trim()
  if (!name) {
    ElMessage.warning('请输入分组名称')
    return
  }
  // 同名分组已存在则直接选中
  const existed = allGroups.value.find((g) => g.name === name)
  if (existed) {
    createDbGroupId.value = existed.id ?? null
    newGroupName.value = ''
    ElMessage.success(`已选用现有分组「${name}」`)
    return
  }
  if (!(await confirmAction('新建数据库分组', `确认新建分组「${name}」？`, '确认新建'))) return
  newGroupSaving.value = true
  try {
    const g = await workspaceApi.createGroup(currentId.value, name)
    allGroups.value = [...allGroups.value, { ...g }]
    createDbGroupId.value = g.id ?? null // 建好后自动选中
    newGroupName.value = ''
    ElMessage.success(`已创建分组「${g.name}」`)
  } catch {
    /* 拦截器已提示 */
  } finally {
    newGroupSaving.value = false
  }
}

/** 打开建库对话框:preGroupId 非空则预选分组 */
function openCreateDb(preGroupId?: number) {
  createDbName.value = ''
  createDbRemark.value = ''
  createDbGroupId.value = preGroupId ?? null
  newGroupName.value = ''
  createDbVisible.value = true
}

async function onCreateDb() {
  const name = createDbName.value.trim()
  if (!name) {
    ElMessage.warning('请输入数据库名')
    return
  }
  // ClickHouse 库连接级唯一:库已存在时明确提示(不再静默归属/跳组)
  try {
    const existing = await workspaceApi.databases(currentId.value).catch(() => [])
    if (existing.some((d: any) => d.name === name)) {
      ElMessage.warning(`数据库「${name}」已存在,不可重复创建。若需调整所属分组,请用分组管理勾选移动。`)
      return
    }
  } catch {
    /* 忽略查库失败,由后端兜底 */
  }
  const groupName = createDbGroupId.value ? (allGroups.value.find((g) => g.id === createDbGroupId.value)?.name ?? '所选分组') : ''
  if (!(await confirmAction('新建数据库', `确认创建数据库「${name}」${groupName ? `并加入分组「${groupName}」` : ''}？`, '确认创建'))) return
  creatingDb.value = true
  try {
    await workspaceApi.createDatabase(currentId.value, name, createDbRemark.value.trim())
    // 指定了分组:自动加入该分组(后端已保证库唯一归属)
    if (createDbGroupId.value) {
      const g = allGroups.value.find((x) => x.id === createDbGroupId.value)
      if (g) await workspaceApi.setGroupDbs(createDbGroupId.value, [...(g.dbs ?? []), name])
    }
    ElMessage.success('数据库已创建')
    createDbVisible.value = false
    reloadTree()
  } catch {
    /* 拦截器已提示 */
  } finally {
    creatingDb.value = false
  }
}

// ---- 分组管理 ----
const allGroups = ref<DBGroup[]>([]) // 当前连接的分组(含成员)
const groupDlgVisible = ref(false)
const groupEditing = ref<DBGroup | null>(null) // 正在编辑的分组
const groupName = ref('')
const groupRemark = ref('')
const groupSaving = ref(false)
const groupChecked = ref<string[]>([]) // 分组管理对话框中选中的库

function openGroupManage() {
  groupEditing.value = null
  groupName.value = ''
  groupRemark.value = ''
  refreshGroupDlg()
  groupDlgVisible.value = true
}

/** 分组管理对话框的库清单(当前连接全部库) */
const groupDlgDbs = ref<string[]>([])

async function refreshGroupDlg() {
  try {
    const [groups, dbs] = await Promise.all([
      workspaceApi.groups(currentId.value).catch(() => []),
      workspaceApi.databases(currentId.value).catch(() => [])
    ])
    allGroups.value = groups
    groupDlgDbs.value = dbs.map((d) => d.name)
  } catch {
    /* ignore */
  }
}

function selectGroup(g: DBGroup) {
  groupEditing.value = g
  groupName.value = g.name
  groupRemark.value = g.remark ?? ''
  groupChecked.value = [...(g.dbs ?? [])]
}

async function createOrUpdateGroup() {
  if (!groupName.value.trim()) {
    ElMessage.warning('请输入分组名称')
    return
  }
  if (!(await confirmAction(groupEditing.value?.id ? '保存数据库分组' : '新建数据库分组',
    groupEditing.value?.id
      ? `确认保存分组「${groupName.value.trim()}」及其 ${groupChecked.value.length} 个成员库的归属？`
      : `确认新建分组「${groupName.value.trim()}」并把 ${groupChecked.value.length} 个库移入？`,
    groupEditing.value?.id ? '确认保存' : '确认新建'))) return
  groupSaving.value = true
  try {
    let gid = groupEditing.value?.id
    if (gid) {
      await workspaceApi.updateGroup(gid, groupName.value.trim(), groupRemark.value.trim())
      await workspaceApi.setGroupDbs(gid, groupChecked.value)
      ElMessage.success('分组已更新')
    } else {
      const g = await workspaceApi.createGroup(currentId.value, groupName.value.trim(), groupRemark.value.trim())
      await workspaceApi.setGroupDbs(g.id!, groupChecked.value)
      ElMessage.success('分组已创建')
    }
    groupEditing.value = null
    groupName.value = ''
    groupRemark.value = ''
    groupChecked.value = []
    await refreshGroupDlg()
    reloadTree()
  } catch {
    /* 拦截器已提示 */
  } finally {
    groupSaving.value = false
  }
}

async function removeGroup(g: DBGroup) {
  if (!(await confirmDestructive('删除分组', g.name, '删除'))) return
  await workspaceApi.removeGroup(g.id!)
  ElMessage.success('已删除')
  if (groupEditing.value?.id === g.id) groupEditing.value = null
  await refreshGroupDlg()
  reloadTree()
}

/** 从树节点打开分组编辑 */
function editGroupFromTree(data: TreeNode) {
  groupDlgVisible.value = true
  // 树节点只带 groupId,完整分组(含成员库)得从 allGroups 里取
  const g = allGroups.value.find((x) => x.id === data.groupId)
  if (!g) return
  selectGroup(g)
}

/** 从树节点删除分组 */
async function removeGroupFromTree(data: TreeNode) {
  // 同上:节点上只有 groupId,取不到完整分组(如已被删)就静默返回,交给 reloadTree 后的树自愈
  const g = allGroups.value.find((x) => x.id === data.groupId)
  if (!g) return
  await removeGroup(g)
}

// ---- 标签页 ----
const activeTab = ref('sql')
const tableTabs = ref<{ key: string; title: string; db: string; table: string; deleted?: boolean }[]>([])
const dbTabs = ref<{ key: string; title: string; name: string; remark?: string; refreshVersion?: number }[]>([])
const userTabs = ref<{ key: string; title: string; name: string }[]>([])

function openTableTab(db: string, table: string) {
  const key = `table:${db}.${table}`
  const existing = tableTabs.value.find((t) => t.key === key)
  if (existing) {
    // 同名表重新创建后从树中打开,恢复可操作的详情页。
    existing.deleted = false
  } else {
    // 带层级名:工作台里可能同时开着两个层级的同名表(如 user),只写表名会分不清
    tableTabs.value.push({ key, title: `${dbLabel(db)} · ${table}`, db, table })
  }
  activeTab.value = key
}

/** 删除后保留原标签展示结果，不再把用户送回操作首页。 */
function showDeletedTable(db: string, table: string) {
  const key = `table:${db}.${table}`
  let tab = tableTabs.value.find((t) => t.key === key)
  if (!tab) {
    tab = { key, title: `${dbLabel(db)} · ${table}`, db, table }
    tableTabs.value.push(tab)
  }
  tab.deleted = true
  activeTab.value = key
  // 已打开的数据库详情也要重取表清单,否则点“查看所在数据库”仍看到旧行。
  const dbTab = dbTabs.value.find((d) => d.name === db)
  if (dbTab) dbTab.refreshVersion = (dbTab.refreshVersion || 0) + 1
  // 地址栏不再指向已不存在的表;重新加载时打开所在数据库。
  selfNavQuery(db)
}

function openDbTab(name: string, remark?: string) {
  const key = `db:${name}`
  if (!dbTabs.value.find((t) => t.key === key)) {
    dbTabs.value.push({ key, title: dbLabel(name), name, remark })
  }
  activeTab.value = key
}

function onNodeClick(data: TreeNode) {
  if (data.type === 'db') {
    openDbTab(data.name, data.remark)
    selfNavQuery(data.name)
  } else if (data.type === 'table' && data.database) {
    if (data.engine === 'MaterializedView') {
      ElMessage.info('物化视图是工作流写入管道，请打开对应的服务表查看数据')
      return
    }
    userOpenTable(data.database, data.name)
  }
  // group 节点:仅展开/收起,不打开页签
}

/** 用户点击库/表时打开并同步地址栏(?conn/db/tbl),刷新/分享保持当前选择。 */
function userOpenTable(db: string, table: string) {
  openTableTab(db, table)
  selfNavQuery(db, table)
}

/** 自身导航式更新 query(打标记,供 watch 识别并跳过重复定位,避免闪回库页签)。 */
function selfNavQuery(db: string, table?: string) {
  selfNavPending = true
  const q: Record<string, string> = { conn: String(store.active?.id ?? '') }
  if (db) q.db = db
  if (table) q.tbl = table
  router.replace({ query: q })
  // 若 query 未变(replace 不触发 watch),短暂后复位,避免标志残留
  window.setTimeout(() => {
    selfNavPending = false
  }, 600)
}

// ---- 新建表(弹窗) ----
const createTableVisible = ref(false)
const createTableDb = ref('')
// 从「操作首页」新建表:需先选一个目标库
const pickDbVisible = ref(false)
const pickDbValue = ref('')
const pickDbOptions = ref<string[]>([])

function openCreateTable(db: string) {
  createTableDb.value = db
  createTableVisible.value = true
}

/** 收集当前连接可用库(顶层库 + 各分组下的库),供“新建表”选目标库用 */
function collectDbNames(): string[] {
  const out: string[] = []
  const push = (n: string) => {
    if (n && !out.includes(n)) out.push(n)
  }
  for (const node of dbTreeData.value) {
    if (node.type === 'db') push(node.name)
    else if (node.type === 'group')
      (node.children ?? []).forEach((c) => {
        if (c.type === 'db') push(c.name)
      })
  }
  return out
}

async function openCreateTableFromHome() {
  const dbs = collectDbNames()
  if (!dbs.length) {
    ElMessage.warning('该连接下还没有数据库,请先新建数据库')
    openCreateDb()
    return
  }
  // 若有正在查看的库页签,优先用当前库,免去选择
  // (页签是 push 追加的,数组末尾即最近打开的那个库)
  const openDb = dbTabs.value[dbTabs.value.length - 1]?.name
  // 该库必须仍在当前列表里才算数(页签可能对应已被删的库),否则退回弹窗让用户重选
  const target = openDb && dbs.includes(openDb) ? openDb : ''
  if (target) {
    openCreateTable(target)
    return
  }
  pickDbValue.value = dbs[0]
  pickDbOptions.value = dbs
  pickDbVisible.value = true
}

function confirmCreateTableFromPick() {
  if (!pickDbValue.value) return
  pickDbVisible.value = false
  openCreateTable(pickDbValue.value)
}

// 表增删后只刷新对应数据库节点,保持树的展开状态。
async function refreshDbTables(db: string) {
  const node = treeRef.value?.getNode(`db:${db}`)
  if (node) {
    node.loaded = false
    await node.loadData()
  } else {
    reloadTree()
  }
}

async function onTableCreated() {
  await refreshDbTables(createTableDb.value)
}

function closeTab(name: string) {
  // 用户管理:可关闭,关掉后可通过侧边栏「用户」区重新打开
  if (name === 'users') {
    usersTabVisible.value = false
    if (activeTab.value === 'users') activeTab.value = 'sql'
    return
  }
  tableTabs.value = tableTabs.value.filter((t) => t.key !== name)
  dbTabs.value = dbTabs.value.filter((t) => t.key !== name)
  userTabs.value = userTabs.value.filter((t) => t.key !== name)
  if (activeTab.value === name) activeTab.value = 'sql'
}

function openUserTab(name: string) {
  const key = `user:${name}`
  if (!userTabs.value.find((t) => t.key === key)) {
    userTabs.value.push({ key, title: `用户 · ${name}`, name })
  }
  activeTab.value = key
}

function onUserDeleted(key: string) {
  closeTab(key)
  loadTreeUsers()
}

/** 表详情页内删除表:留在结果页,只刷新所在库的表列表。 */
function onTableDeleted(key: string) {
  const tab = tableTabs.value.find((t) => t.key === key)
  if (!tab) return
  showDeletedTable(tab.db, tab.table)
  void refreshDbTables(tab.db)
}

/** 表详情页内改表名:更新 tab 标题 + 刷新树 */
function onTableRenamed(key: string, newName: string) {
  const t = tableTabs.value.find((x) => x.key === key)
  if (t) {
    t.title = `${dbLabel(t.db)} · ${newName}`
    t.table = newName
  }
  reloadTree()
}

/** 数据库详情页内删除库:关标签 + 刷新树 */
function onDbDeleted(key: string) {
  closeTab(key)
  reloadTree()
}

function openUsers() {
  usersTabVisible.value = true
  activeTab.value = 'users'
}

// ---- 侧边栏分区折叠 ----
const dbExpanded = ref(true)

// ---- 用户分区(侧边栏) ----
const usersExpanded = ref(false)
const usersTabVisible = ref(false) // 用户管理标签页是否打开(默认不打开,侧边栏可重开)
const treeUsers = ref<{ name: string; isDefault: boolean; remark?: string }[]>([])
const createUserFlag = ref(0)
const userManagerRef = ref<any>(null)

async function loadTreeUsers() {
  const id = currentId.value
  if (id <= 0) return
  try {
    const users = await usersApi.list(id)
    const readOnlyUser = store.active?.username
    // 连接配置里的用户名就是本连接登录用的账号:标为 isDefault,列表中置灰并禁止改名/删除
    // (与传给 UserManager / UserDetail 的 readOnlyUser 是同一套判定)
    treeUsers.value = users.map((u) => ({ name: u.name, isDefault: u.name === readOnlyUser, remark: u.remark }))
  } catch {
    /* 忽略 */
  }
}

function toggleUsers() {
  usersExpanded.value = !usersExpanded.value
  // 同时打开「用户管理」标签页(关闭后可由此重新打开)
  usersTabVisible.value = true
  activeTab.value = 'users'
}

function onCreateUser() {
  usersTabVisible.value = true
  activeTab.value = 'users'
  createUserFlag.value = Date.now()
  // 等标签页挂载后直接调用 UserManager 的新建弹窗(更可靠)
  nextTick(() => userManagerRef.value?.openCreate())
}

// ---- 危险操作 ----
async function dropDb(data: TreeNode) {
  if (data.name === 'default') {
    ElMessage.warning('默认库 default 不允许删除(库内表仍可删除)')
    return
  }
  if (!(await confirmDestructive('删除数据库', data.name, '删除'))) return
  await workspaceApi.dropDatabase(currentId.value, data.name)
  ElMessage.success('数据库已删除')
  // 这里只按库名关掉库页签(表页签的键是 table:库.表,不在此逐个匹配);
  // 重新打开目标表时会先经 openFromQuery 探测库/表是否还在,已删就直接拦截
  dbTabs.value = dbTabs.value.filter((t) => t.name !== data.name)
  reloadTree()
}

async function dropTable(data: TreeNode) {
  if (!data.database) return
  const db = data.database
  if (!(await confirmDestructive('删除表', data.name, '删除'))) return
  await workspaceApi.dropTable(currentId.value, db, data.name)
  ElMessage.success('表已删除')
  showDeletedTable(db, data.name)
  await refreshDbTables(db)
}

// ---- SQL 执行 ----
const sqlText = ref('')
const codeMirrorRef = ref<any>(null)
const allowWrite = ref(false)
const running = ref(false)
const queryResults = ref<QueryResult[]>([])
const queryMessages = ref<string[]>([])
const queryTruncated = ref(false)
const queryMessage = ref('')
const activeResult = ref('0')
const resultTabCount = computed(() => queryResults.value.length + queryMessages.value.length)

let sqlSeq = 0 // 请求序号,丢弃过期响应
async function runSql() {
  if (running.value) return // 并发保护(按钮 + Ctrl+Enter 都走这里)
  // 有选中文本则执行选中部分,否则执行全部
  const selected = (codeMirrorRef.value?.getSelection?.() ?? '').trim()
  const sql = (selected || sqlText.value).trim()
  if (!sql) return
  if (allowWrite.value) {
    const brief = sql.length > 240 ? sql.slice(0, 240) + ' …' : sql
    if (!(await confirmAction('执行写操作 SQL', `已勾选「允许写操作」，该 SQL 可能修改或删除数据。确认执行？\n\n${brief}`, '确认执行'))) return
  }
  const seq = ++sqlSeq
  running.value = true
  queryMessage.value = ''
  try {
    const res = await workspaceApi.query(currentId.value, sql, allowWrite.value)
    if (seq !== sqlSeq) return // 已被更新的执行覆盖,丢弃过期结果
    queryResults.value = res.results ?? []
    queryMessages.value = res.messages ?? []
    queryTruncated.value = res.truncated
    // 有结果选结果页签;纯消息(多条 DDL)选第一条消息,避免空白
    if (queryResults.value.length) activeResult.value = '0'
    else if (queryMessages.value.length) activeResult.value = 'm0'
    if (!queryResults.value.length && !queryMessages.value.length) {
      queryMessage.value = '执行成功'
    }
  } catch {
    /* 拦截器已提示 */
  } finally {
    // 只有仍是「最新一次执行」时才解除 loading:过期请求的 finally 不能把新请求的锁关掉
    if (seq === sqlSeq) running.value = false
  }
}

// ---- SQL 自动补全(当前连接默认库的表 + 列,列按需加载缓存) ----
const sqlTables = ref<TableInfo[]>([])
const sqlColCache = ref<Record<string, string[]>>({})
const sqlColLoading: Record<string, boolean> = {}

async function loadSqlTables() {
  const db = store.active?.database
  if (!db || currentId.value <= 0) return
  try {
    sqlTables.value = await workspaceApi.tables(currentId.value, db)
  } catch {
    /* 忽略 */
  }
}

function getSqlColumns(table: string): string[] | undefined {
  // 只对已知表拉取列,避免输入不存在/未加载的表时触发请求报错
  if (!sqlTables.value.some((t) => t.name === table)) return undefined
  if (sqlColCache.value[table]) return sqlColCache.value[table]
  if (sqlColLoading[table]) return undefined
  sqlColLoading[table] = true
  const db = store.active?.database
  if (db) {
    workspaceApi
      .schemaSilent(currentId.value, db, table)
      .then((res) => {
        sqlColCache.value[table] = res.rows.map((r) => String(r[0]))
      })
      .catch(() => {
        sqlColCache.value[table] = []
      })
      .finally(() => {
        delete sqlColLoading[table]
      })
  }
  // 首次调用只负责发起后台拉取、先返回 undefined:补全回调不能阻塞在请求上,
  // 用户下一次输入时该列已进缓存,自然就补全出来了(失败也写空数组,不再重复请求)
  return undefined
}

const completionData = computed(() => ({
  getTables: () => sqlTables.value.map((t) => t.name),
  getColumns: getSqlColumns
}))

// ---- 初始化 ----
/**
 * 切换到某连接并刷新其数据。
 * 连接变化时清空该连接专属的页签与缓存(避免残留上个连接的库/表)。
 * 相同连接但数据尚未加载(首次进入)时,仍需触发一次初始加载。
 */
function activateConnection(c: Connection) {
  const same = store.active?.id === c.id
  store.setActive(c)
  // 同一连接且树已加载过:只需 setActive 保持现状,不能走下面的清空 ——
  // 否则每次回到工作台都会把用户开着的库/表页签全关掉
  if (same && dbTreeData.value.length) return
  // 连接变了(或首载):重建连接专属状态
  activeTab.value = 'sql'
  tableTabs.value = []
  dbTabs.value = []
  userTabs.value = []
  usersTabVisible.value = false
  sqlTables.value = []
  sqlColCache.value = {}
  treeUsers.value = []
  reloadTree()
  loadSqlTables()
  loadTreeUsers()
}

/**
 * 解析 URL 查询参数并打开目标库/表。供两种场景复用:
 *  1) 首次进入(调度中心/其它页跳转来,携带 conn/db/tbl)
 *  2) 已在目标库表（dwd_data）页时 URL 参数变化(地址栏直接改参、再次跳转) → 由 watch 触发
 *
 * 关键:表数据(DataEditor)是按 connId/db/table 直接查询的,不依赖左侧库树加载。
 * 打开前先探测目标库/表是否还存在——若已被删除(或分组/库/表名拼错),
 * 拦截并只提示一次友好信息,不再把 DataEditor 挂上去触发一连串 code 60。
 */
async function openFromQuery() {
  const q = route.query
  const db = q.db ? String(q.db) : ''
  const tbl = q.tbl ? String(q.tbl) : ''
  if (!db) return

  // 去重:同一 conn/db/tbl 目标在短时间内只处理一次。
  // (跳转时 onMounted 手动调用 + watch 可能都触发,避免重复打开/重复提示)
  const sig = `${q.conn ?? ''}|${db}|${tbl}`
  const now = Date.now()
  if (sig === lastNavSig && now - lastNavAt < 2000) return
  lastNavSig = sig
  lastNavAt = now

  // 目标连接:显式 ?conn → 存在则切换;否则沿用当前激活连接
  let connId = store.active?.id ?? -1
  if (q.conn) {
    const conn = connections.value.find((c) => c.id === Number(q.conn))
    if (conn) {
      connId = conn.id
      if (store.active?.id !== conn.id) activateConnection(conn)
    }
  }
  if (connId <= 0) return

  const dbExists = (): boolean => {
    // 树已含该库(顶层或分组下)则视为库存在;树未就绪时保守按存在处理
    if (!dbTreeData.value.length) return true
    return (
      dbTreeData.value.some((n) => n.type === 'db' && n.name === db) ||
      dbTreeData.value.some((n) => n.type === 'group' && (n.children ?? []).some((c) => c.name === db))
    )
  }

  // 先打开库页签(库不存在也会在 DatabaseDetail 里提示,但避免表连环报错)
  openDbTab(db, '')

  if (!tbl) {
    // 只打开库:直接返回
    locateAfterReady(db)
    return
  }

  // 探测表结构:表存在 → 正常打开;不存在 → 拦截并友好提示一次
  // 这里刻意用 schemaSilent(静默失败,不弹全局错误),提示统一由下方给出,避免一次跳转弹两条
  let probeOk = false
  try {
    await workspaceApi.schemaSilent(connId, db, tbl)
    probeOk = true
  } catch {
    probeOk = false
  }
  if (probeOk) {
    openTableTab(db, tbl)
    locateAfterReady(db, tbl)
    return
  }

  // 表探测失败:库/表可能被删除或连接不通。给一次友好提示,不挂 DataEditor
  const hint = dbExists()
    ? `目标表 ${tbl} 不存在(可能已被删除或尚未同步创建)。请检查分组下的库,或重新配置同步任务的目标表。`
    : `目标表所在的库不存在(可能已被删除)。请检查左侧库分组,或重新配置同步任务。`
  ElMessage.warning(hint)
  // 库若还在,留在库页签让用户看真实表;库已删则切回 SQL
  if (!dbExists()) {
    activeTab.value = 'sql'
  }
}

/** 树就绪后尽量定位高亮目标库/表(纯体验,失败无碍)。 */
function locateAfterReady(db: string, table?: string) {
  const tryLocate = () => {
    if (!dbTreeData.value.length) return false
    if (table) locateTableInTree(db, table)
    else locateDbInTree(db)
    return true
  }
  if (tryLocate()) return
  // 库树是异步填充的,此刻可能还是空的:轮询等树就绪再定位;
  // 5s 后仍没等到就放弃(定位只是体验,不影响页签已正确打开)
  const iv = window.setInterval(() => {
    if (tryLocate()) window.clearInterval(iv)
  }, 200)
  window.setTimeout(() => window.clearInterval(iv), 5000)
}

/**
 * 定位并高亮到具体的表节点(表是懒加载,需先展开其所在分组与库节点,
 * 等子表列表加载完成后才 setCurrentKey)。
 */
function locateTableInTree(db: string, table: string) {
  const dbKey = `db:${db}`
  const tableKey = `table:${db}.${table}`
  if (!dbTreeData.value.length) return

  // 1. 先高亮库(会展开所在分组)
  locateDbInTree(db)

  // 2. 确保库节点展开(展开触发 loadNode 懒加载其下的表)
  nextTick(() => {
    const dbNode = treeRef.value?.getNode(dbKey)
    if (dbNode && !dbNode.expanded) dbNode.expand()
  })

  // 3. 等待表子节点出现后高亮它
  let tries = 0
  const timer = window.setInterval(() => {
    tries++
    const tNode = treeRef.value?.getNode(tableKey)
    if (tNode) {
      window.clearInterval(timer)
      treeRef.value?.setCurrentKey(tableKey)
      return
    }
    if (tries > 25) window.clearInterval(timer) // 最多等 ~5s
  }, 200)
}

onMounted(async () => {
  // 先确保有连接可用;加载失败不中断跳转处理(下方 openFromQuery 再自行兜底)
  try {
    connections.value = await connectionsApi.list()
  } catch {
    connections.value = []
  }
  if (!connections.value.length) {
    // 确实无连接(非加载失败):引导去连接管理
    if (route.query.conn) {
      // 带参跳转但一个连接都没有——直接提示,不再静默跳走
      return
    }
    router.replace('/connections')
    return
  }
  // 目标连接优先级:?conn 显式指定 > 上次激活的连接(若仍存在) > 默认连接
  const byQuery = route.query.conn ? connections.value.find((c) => c.id === Number(route.query.conn)) : undefined
  const target =
    byQuery ??
    connections.value.find((c) => c.id === store.active?.id) ??
    connections.value.find((c) => c.isDefault) ??
    connections.value[0]
  if (target) activateConnection(target)
  // 携带 conn/db/tbl 跳转(如调度中心点击目标表 → 查看数据)
  openFromQuery()
  // URL 参数变化时(地址栏改参 / 再次从别处跳转)重新定位
  watch(
    () => [route.query.conn, route.query.db, route.query.tbl],
    () => {
      // 用户点击产生的自身导航:表/库已由 userOpenTable/onNodeClick 打开,跳过重复定位
      if (selfNavPending) {
        selfNavPending = false
        return
      }
      openFromQuery()
    }
  )
})
</script>

<style scoped>
.workspace-view {
  height: 100%;
}
.workspace {
  height: 100%;
}
.side {
  position: relative;
  flex: none;
  overflow: hidden;
  border-right: 1px solid var(--el-border-color-lighter);
  background: var(--bg-panel);
  padding: 10px 8px 8px;
  display: flex;
  flex-direction: column;
}
.side-resize-handle {
  position: absolute;
  top: 0;
  right: 0;
  bottom: 0;
  z-index: 5;
  width: 8px;
  cursor: col-resize;
  touch-action: none;
}
.side-resize-handle:hover,
.side-resize-handle:focus-visible {
  border-right: 2px solid var(--el-color-primary);
  outline: none;
}
.conn-bar {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-bottom: 8px;
}
.conn-name {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 14px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}
.tree-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 2px 6px;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
.tree-toolbar-actions {
  display: flex;
  gap: 2px;
}
.tree.tree {
  /* 树占满侧边栏剩余空间(flex:1),内部滚动;
     用户区固定在其下方,始终贴着树底。 */
  flex: 1;
  min-height: 0;
  overflow-x: hidden;
  overflow-y: auto;
}
.tree.collapsed-tree {
  flex: 0 0 auto;
}
.tree-node {
  display: flex;
  align-items: center;
  gap: 4px;
  width: 100%;
  padding-right: 4px;
}
.node-icon.db {
  color: var(--brand);
}
.node-icon.table {
  color: #7aa2f7;
}
/* 分组节点 */
.tree-node .node-icon.group {
  color: #34d399;
}
.tree-node.group-row {
}
.tree-node .node-label.group-name {
  font-weight: 600;
}

/* 分组管理对话框 */
.group-mgr {
  display: flex;
  gap: 14px;
  min-height: 320px;
}
.group-side {
  width: 200px;
  flex: none;
  border-right: 1px solid var(--el-border-color);
  padding-right: 12px;
  display: flex;
  flex-direction: column;
}
.group-side-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 13.5px;
  color: var(--el-text-color-secondary);
  margin-bottom: 6px;
}
.group-list {
  flex: 1;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.group-item {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 8px;
  border-radius: 6px;
  cursor: pointer;
  font-size: 14px;
}
.group-item:hover {
  background: var(--el-fill-color);
}
.group-item.active {
  background: rgba(234, 179, 8, 0.12);
  color: var(--brand-strong);
}
.group-folder {
  color: #34d399;
}
.group-item-name {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.group-item-count {
  font-size: 12px;
  color: var(--el-text-color-placeholder);
}
.group-item-del {
  opacity: 0;
  transition: opacity 0.15s;
}
.group-item:hover .group-item-del {
  opacity: 1;
}
.group-main {
  flex: 1;
}
.group-main-actions {
  display: flex;
  justify-content: space-between;
  margin-top: 8px;
}
.db-check-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
  max-height: 220px;
  overflow-y: auto;
}
.db-empty-hint {
  font-size: 13px;
  color: var(--el-text-color-placeholder);
}
.grp-quick {
  display: flex;
  gap: 6px;
  align-items: center;
  padding: 6px 8px 8px;
  border-top: 1px solid var(--el-border-color-lighter);
  margin-top: 2px;
}
.grp-tip {
  font-size: 12px;
  color: var(--el-text-color-placeholder);
  line-height: 1.6;
  margin-top: 2px;
}
.node-label {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-family: var(--font-mono);
  font-size: 13.5px;
}
.node-remark {
  flex: none;
  max-width: 110px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 12px;
  color: var(--el-text-color-placeholder);
  margin-left: 6px;
}
.node-default {
  flex: none;
  font-size: 12px;
  color: var(--el-text-color-placeholder);
  border: 1px solid var(--el-border-color);
  border-radius: 3px;
  padding: 0 3px;
  line-height: 1.5;
  margin-left: 4px;
}
.user-toolbar {
  margin-top: 8px;
  cursor: pointer;
  flex: none; /* 固定,不参与伸缩 */
}
.tree-toolbar {
  cursor: pointer;
  user-select: none;
}
.collapse-arrow {
  display: inline-block;
  font-size: 12px;
  color: var(--el-text-color-placeholder);
  margin-right: 4px;
  transition: transform 0.15s ease;
}
.collapse-arrow.collapsed {
  transform: rotate(-90deg);
}
.user-list {
  flex: none; /* 固定高度,紧跟树底 */
  max-height: 220px;
  overflow: auto;
  padding: 2px 2px 6px;
}
.user-item {
  display: flex;
  align-items: center;
  gap: 6px;
  height: 30px;
  padding: 0 8px;
  border-radius: 6px;
  cursor: pointer;
}
.user-item:hover {
  background: var(--hover-bg);
}
.user-item-icon {
  color: var(--el-text-color-secondary);
}
.user-item-name {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-family: var(--font-mono);
  font-size: 13.5px;
}
.user-item-remark {
  flex: none;
  max-width: 110px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 12px;
  color: var(--el-text-color-placeholder);
  margin-left: 6px;
}
.user-empty {
  padding: 6px 8px;
  font-size: 13px;
  color: var(--el-text-color-placeholder);
}
.node-actions {
  display: none;
  align-items: center;
}
.tree-node:hover .node-actions {
  display: flex;
}
.main {
  padding: 12px 16px 0;
}
.main-tabs {
  height: 100%;
  display: flex;
  flex-direction: column;
}
.main-tabs :deep(.el-tabs__header) {
  flex: none;
}
.main-tabs :deep(.el-tabs__content) {
  flex: 1;
  min-height: 0;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}
.main-tabs :deep(.el-tab-pane) {
  height: 100%;
}
/* tab 过多时:项不收缩 + 导航条横向滚动,固定第一个(数据探索)不被挤掉 */
.main-tabs :deep(.el-tabs__nav-wrap) {
  overflow-x: auto;
  overflow-y: hidden;
}
.main-tabs :deep(.el-tabs__nav-scroll) {
  overflow-x: auto !important;
  overflow-y: hidden !important;
}
.main-tabs :deep(.el-tabs__nav-prev),
.main-tabs :deep(.el-tabs__nav-next) {
  display: none !important;
}
.main-tabs :deep(.el-tabs__nav) {
  transform: none !important;
  flex-wrap: nowrap;
}
.main-tabs :deep(.el-tabs__item) {
  flex-shrink: 0;
}
.ops-home {
  height: 100%;
  display: flex;
  flex-direction: column;
  gap: 20px;
  padding: 6px 4px;
  overflow: auto;
}
.ops-head .ops-title {
  margin: 0 0 6px;
  font-size: 20px;
}
.ops-sub {
  margin: 0;
  color: var(--el-text-color-secondary);
  font-size: 14px;
}
.ops-cards {
  display: flex;
  gap: 14px;
  flex-wrap: wrap;
}
.ops-card {
  width: 180px;
  padding: 18px 16px;
  border: 1px solid var(--el-border-color);
  border-radius: 10px;
  cursor: pointer;
  transition: border-color 0.15s ease, transform 0.12s ease, box-shadow 0.15s ease;
  user-select: none;
}
.ops-card:hover {
  border-color: var(--brand);
  transform: translateY(-1px);
  box-shadow: 0 6px 18px rgba(0, 0, 0, 0.06);
}
.ops-card-icon {
  font-size: 24px;
  color: var(--brand);
}
.ops-card-name {
  margin-top: 10px;
  font-weight: 600;
  font-size: 14px;
}
.ops-card-desc {
  margin-top: 4px;
  font-size: 13px;
  color: var(--el-text-color-secondary);
  line-height: 1.5;
}
.ops-tip {
  font-size: 13px;
  color: var(--el-text-color-placeholder);
  line-height: 1.7;
}
.sql-pane {
  display: flex;
  flex-direction: column;
  gap: 8px;
  height: 100%;
}
.sql-toolbar .el-checkbox__label {
  font-size: 14px;
}
.sql-toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
}
.editor-box {
  height: 34%;
  min-height: 160px;
}
.sql-result {
  flex: 1;
  overflow: auto;
}
.result-block {
  margin-bottom: 12px;
}
.result-title {
  font-size: 13px;
  color: var(--el-text-color-secondary);
  margin-bottom: 4px;
}
.query-msg {
  font-size: 14px;
  color: var(--el-color-success);
}
</style>
