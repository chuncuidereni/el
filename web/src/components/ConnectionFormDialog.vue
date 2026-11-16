<!--
  ConnectionFormDialog.vue

  职责：连接新增/编辑弹窗：拆分基础字段与高级参数作用域并提交表单。
  边界：由 modelValue 控制显隐、connection 决定新增或编辑回显；保存或测试后分别通知父级，请求失败时保留弹窗内容。
-->

<template>
  <!--
    连接的「新增 / 编辑」共用弹窗(连接管理页、数据仓库页的就地编辑都复用同一个组件)。

    字段分两层,提交逻辑也据此分叉:
    - 基本字段(name/host/port/database/username/password/secure/description)总是写到连接本身;
    - 高级字段(三个超时 + 只读 + 自定义参数)可写到【连接级】也可写进【全局默认】,
      由「应用范围」单选决定 —— 选全局时会显式把连接级字段置 null 让后端清掉覆盖(见 updateAdvanced)。
    密码新增时必填;编辑时留空 = 保持原密码(接口不回传明文,前端无从回填)。
  -->
  <el-dialog
    :model-value="modelValue"
    :title="connection ? '编辑连接' : '新增连接'"
    width="680px"
    :close-on-click-modal="false"
    @update:model-value="onDialogVisibility"
  >
    <el-alert v-if="settingsError" type="error" :closable="false" show-icon>
      <template #title>高级配置加载失败，请重试后再测试或保存。</template>
      <el-button size="small" @click="retrySettings">重新加载</el-button>
    </el-alert>
    <el-form ref="formRef" :model="form" :rules="rules" :disabled="loadingSettings || saving || testing" label-width="96px">
      <el-form-item label="连接名称" prop="name">
        <el-input v-model="form.name" placeholder="例如:生产库" />
      </el-form-item>
      <el-row :gutter="12">
        <el-col :span="10">
          <el-form-item label="主机地址" prop="host">
            <el-input v-model="form.host" placeholder="127.0.0.1 或域名" />
          </el-form-item>
        </el-col>
        <el-col :span="7">
          <el-form-item label="协议" prop="protocol">
            <el-select v-model="form.protocol" style="width: 100%" @change="onProtocolChange">
              <el-option label="原生 TCP" value="native" />
              <el-option label="HTTP" value="http" />
            </el-select>
          </el-form-item>
        </el-col>
        <el-col :span="7">
          <el-form-item label="端口" prop="port">
            <el-input-number
              v-model="form.port"
              :min="1"
              :max="65535"
              controls-position="right"
              style="width: 100%"
            />
            <!-- 协议与端口组合明显不对时先提示,不必等到测试连接才被驱动层那句难懂的报错拦住 -->
            <div v-if="protocolMismatch" class="scope-hint">{{ protocolMismatch }}</div>
          </el-form-item>
        </el-col>
      </el-row>
      <el-row :gutter="12">
        <el-col :span="12">
          <el-form-item label="数据库" prop="database">
            <el-input v-model="form.database" placeholder="default" />
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="用户名" prop="username">
            <el-input v-model="form.username" placeholder="default" />
          </el-form-item>
        </el-col>
      </el-row>
      <!-- 编辑态密码不回填:接口不返回明文,留空即表示沿用原密码 -->
      <el-form-item :label="connection ? '新密码' : '密码'" prop="password">
        <el-input
          v-model="form.password"
          type="password"
          show-password
          :placeholder="connection ? '留空表示保持原密码' : '数据库用户密码'"
        />
      </el-form-item>
      <el-form-item label="安全连接">
        <el-switch v-model="form.secure" active-text="TLS/SSL" />
      </el-form-item>
      <el-form-item label="描述">
        <el-input v-model="form.description" type="textarea" :rows="2" />
      </el-form-item>

      <!-- 高级配置单独分区:同一批字段可写到连接级,也可写到全局默认 -->
      <el-divider content-position="left">
        <span class="divider-title">高级配置</span>
      </el-divider>

      <!-- 作用域决定落库位置:global 时不写连接级字段,改为继承全局 -->
      <el-form-item label="应用范围">
        <el-radio-group v-model="advancedScope">
          <el-radio value="connection">仅当前连接</el-radio>
          <el-radio v-if="connection" value="global">全局默认</el-radio>
        </el-radio-group>
        <div class="scope-hint">
          {{ advancedScope === 'global' ? '保存为所有连接的默认值,当前连接不单独设置' : '仅应用于当前连接' }}
        </div>
      </el-form-item>

      <!-- 界面用秒/分钟便于填写,提交时才换算回毫秒 -->
      <el-row :gutter="12">
        <el-col :span="8">
          <el-form-item label="连接超时(秒)">
            <el-input-number v-model="adv.dialTimeoutSec" :min="1" :max="3600" controls-position="right" style="width: 100%" />
          </el-form-item>
        </el-col>
        <el-col :span="8">
          <el-form-item label="查询超时(秒)">
            <el-input-number v-model="adv.queryTimeoutSec" :min="1" :max="36000" controls-position="right" style="width: 100%" />
          </el-form-item>
        </el-col>
        <el-col :span="8">
          <el-form-item label="连接保持(分)">
            <el-input-number v-model="adv.connMaxLifetimeMin" :min="1" :max="10080" controls-position="right" style="width: 100%" />
          </el-form-item>
        </el-col>
      </el-row>

      <el-form-item label="只读模式">
        <el-switch v-model="adv.readOnly" active-text="禁止写操作(仅允许 SELECT 等只读语句)" />
      </el-form-item>

      <el-form-item label="自定义参数">
        <el-input v-model="adv.paramsJson" type="textarea" :rows="2" placeholder='{"max_threads": 8, "max_result_rows": 10000}' />
        <div class="scope-hint">连接参数(JSON),如 max_threads、max_result_rows</div>
      </el-form-item>
    </el-form>

    <!-- 配置读取成功后才能测试或保存，操作期间锁住输入，避免请求内容与页面显示不一致。 -->
    <template #footer>
      <el-button :loading="testing" :disabled="!settingsReady || saving" @click="onTest">测试连接</el-button>
      <el-button @click="onDialogVisibility(false)">取消</el-button>
      <el-button type="primary" :loading="saving" :disabled="!settingsReady || testing" @click="onSave">保存</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { connectionsApi, settingsApi } from '@/api'
import type { Connection, ConnectionInput, GlobalSettings } from '@/types'
import { confirmAction } from '@/utils/confirm'

const props = defineProps<{
  modelValue: boolean
  connection: Connection | null
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void
  (e: 'saved'): void
}>()

const formRef = ref<FormInstance>()
const saving = ref(false)
const testing = ref(false)
const loadingSettings = ref(false)
const settingsReady = ref(false)
const settingsError = ref(false)
let formSession = 0
let sessionOpen = false
let disposed = false

// 表单初值:9000 是 ClickHouse 原生 TCP 协议端口(8443/9440 才是 TLS),与后端默认保持一致
const form = reactive<ConnectionInput>({
  name: '',
  host: '127.0.0.1',
  port: 9000,
  protocol: 'native',
  database: 'default',
  username: 'default',
  password: '',
  secure: false,
  description: '',
})

// 高级配置(秒/分钟,便于编辑)
const adv = reactive({
  dialTimeoutSec: 5,
  queryTimeoutSec: 60,
  connMaxLifetimeMin: 30,
  readOnly: false,
  paramsJson: '{}',
})
const advancedScope = ref<'connection' | 'global'>('connection')

/**
 * 内置的全局默认值。
 *
 * 仅用于加载期间的占位显示；读取失败时禁止保存，不能把占位值当作真实配置写回。
 */
const DEFAULT_GLOBAL: GlobalSettings = {
  dialTimeoutMs: 5000,
  queryTimeoutMs: 60000,
  connMaxLifetimeMin: 30,
  readOnly: false,
  paramsJson: '{}',
}

// 必填校验:连接名称 / 主机 / 端口 / 数据库 / 用户名 —— 新增与编辑都要。
// 密码只在【新增】时必填;编辑时留空表示不修改密码(故不放进通用规则,见下方的动态规则)。
const rules = computed<FormRules>(() => {
  const base: FormRules = {
    name: [{ required: true, message: '请输入连接名称', trigger: 'blur' }],
    host: [{ required: true, message: '请输入主机地址', trigger: 'blur' }],
    port: [{ required: true, message: '请输入端口', trigger: 'blur' }],
    database: [{ required: true, message: '请输入数据库', trigger: 'blur' }],
    username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  }
  // 仅新增连接时要求密码:编辑态留空是有意义的"保持原密码",不能卡住
  if (!props.connection) {
    base.password = [{ required: true, message: '请输入密码', trigger: 'blur' }]
  }
  return base
})

// 每次打开或切换连接都建立新会话。旧请求只能完成服务端操作，不能再改动新表单。
watch(
  [() => props.modelValue, () => props.connection?.id],
  ([open]) => {
    invalidateSession()
    if (open) {
      sessionOpen = true
      resetForm(formSession)
    }
  },
  { immediate: true, flush: 'sync' },
)
onBeforeUnmount(() => { disposed = true; invalidateSession() })

function invalidateSession() {
  formSession++
  sessionOpen = false
  saving.value = false
  testing.value = false
  loadingSettings.value = false
  settingsReady.value = false
  settingsError.value = false
}

function isCurrent(session: number) {
  return !disposed && sessionOpen && props.modelValue && formSession === session
}

function onDialogVisibility(open: boolean) {
  // 不等待父组件回传 props，点击关闭后立即使尚未结束的确认、校验和网络请求失效。
  if (!open) invalidateSession()
  emit('update:modelValue', open)
}

/**
 * 切换协议时顺手调端口:仅当端口还是「另一种协议的默认值」时才改。
 * 用户手填过的非默认端口保持不动,避免覆盖用户意图。
 */
function onProtocolChange(value: string) {
  if (value === 'http' && form.port === 9000) form.port = 8123
  else if (value === 'native' && form.port === 8123) form.port = 9000
}

/**
 * 协议与端口明显不匹配时的提示文案。
 * 规则与后端 CkClient.open 的拦截保持一致:只针对 native 9000 / http 8123 这两对通行约定,
 * 自定义端口不做推断,以免挡住正常用法。
 */
const protocolMismatch = computed(() => {
  if (form.protocol !== 'http' && form.port === 8123)
    return '端口 8123 通常是 HTTP 接口端口:要用 HTTP 请把协议改为 http,否则端口应填 native 端口(通常 9000)'
  if (form.protocol === 'http' && form.port === 9000)
    return '端口 9000 通常是 native 端口:要用 HTTP 端口应填 8123,否则请把协议改回 native'
  return ''
})

function resetForm(session: number) {
  const c = props.connection
  form.name = c?.name ?? ''
  form.host = c?.host ?? '127.0.0.1'
  form.port = c?.port ?? 9000
  form.protocol = c?.protocol ?? 'native'
  form.database = c?.database ?? 'default'
  form.username = c?.username ?? 'default'
  // 密码永不回填:置空既让输入框干净,也让提交时后端识别为"不修改密码"
  form.password = ''
  form.secure = c?.secure ?? false
  form.description = c?.description ?? ''
  formRef.value?.clearValidate()

  applyAdvanced(c, DEFAULT_GLOBAL)
  void loadSettings(session, c)
}

async function loadSettings(session: number, c: Connection | null) {
  loadingSettings.value = true
  settingsReady.value = false
  settingsError.value = false
  try {
    const g = await settingsApi.get()
    if (!isCurrent(session)) return
    applyAdvanced(c, g)
    settingsReady.value = true
  } catch {
    if (isCurrent(session)) settingsError.value = true
  } finally {
    if (isCurrent(session)) loadingSettings.value = false
  }
}

function retrySettings() {
  if (!isCurrent(formSession) || loadingSettings.value || saving.value || testing.value) return
  // 重试只更新高级配置；保留用户已填写的名称、主机和密码等基础字段。
  return loadSettings(formSession, props.connection)
}

function applyAdvanced(c: Connection | null, g: GlobalSettings) {
  // 连接级优先,未单独设置则继承全局;毫秒→秒只是编辑单位,保存时再乘回 1000
  adv.dialTimeoutSec = Math.round((c?.dialTimeoutMs ?? g.dialTimeoutMs) / 1000)
  adv.queryTimeoutSec = Math.round((c?.queryTimeoutMs ?? g.queryTimeoutMs) / 1000)
  adv.connMaxLifetimeMin = c?.connMaxLifetimeMin ?? g.connMaxLifetimeMin
  adv.readOnly = c?.readOnly ?? g.readOnly
  // 连接侧空对象视为未覆盖,回落到全局,防止把 '{}' 当成有效自定义值提交
  adv.paramsJson = c?.paramsJson && c.paramsJson !== '{}' ? c.paramsJson : g.paramsJson

  // false 也是显式覆盖；只有自定义参数的连接同样必须保持“仅当前连接”，
  // 否则用户只改主机地址，也会意外把该连接的参数写成所有连接的全局默认。
  const hasOverride = [c?.dialTimeoutMs, c?.queryTimeoutMs, c?.connMaxLifetimeMin, c?.readOnly]
    .some(value => value != null) || !!(c?.paramsJson && c.paramsJson !== '{}')
  // 新建接口只保存当前连接；全局配置可在创建完成后的编辑弹窗中调整。
  advancedScope.value = !c || hasOverride ? 'connection' : 'global'
}

/** 始终发送具体值(测试/新建用) */
function concreteAdvanced(): Partial<ConnectionInput> {
  return {
    // 秒→毫秒换回后端单位;paramsJson 去空白并兜底 '{}',避免后端解析空串报错
    dialTimeoutMs: adv.dialTimeoutSec * 1000,
    queryTimeoutMs: adv.queryTimeoutSec * 1000,
    connMaxLifetimeMin: adv.connMaxLifetimeMin,
    readOnly: adv.readOnly,
    paramsJson: adv.paramsJson.trim() || '{}',
  }
}

/** 更新用:按作用域决定是否携带具体值(global 则置空,继承全局) */
function updateAdvanced(): Partial<ConnectionInput> {
  if (advancedScope.value === 'global') {
    // 显式置 null 让后端清掉连接级覆盖;paramsJson 也必须重置,否则旧值仍会生效
    return {
      advancedScope: 'global',
      dialTimeoutMs: null,
      queryTimeoutMs: null,
      connMaxLifetimeMin: null,
      readOnly: null,
      paramsJson: '{}',
    }
  }
  return { advancedScope: 'connection', ...concreteAdvanced() }
}

async function onTest() {
  if (!isCurrent(formSession) || !settingsReady.value || saving.value || testing.value) return
  const session = formSession
  const connectionId = props.connection?.id
  testing.value = true
  try {
    const valid = await formRef.value?.validate().catch(() => false)
    if (!valid || !isCurrent(session)) return
    // 测试连接只带具体值；空密码由 test-config 接口复用对应连接的已保存密码。
    const input = { ...form, ...concreteAdvanced() }
    if (connectionId != null) await connectionsApi.testConfig(connectionId, input)
    else await connectionsApi.test(input)
    if (isCurrent(session)) ElMessage.success('连接成功 ✓')
  } catch {
    /* 错误已由拦截器提示 */
  } finally {
    if (isCurrent(session)) testing.value = false
  }
}

async function onSave() {
  // 校验和确认弹窗也属于一次保存，避免快速重复点击提交两份全局配置。
  if (!isCurrent(formSession) || !settingsReady.value || saving.value || testing.value) return
  const session = formSession
  const connectionId = props.connection?.id
  saving.value = true
  try {
    const valid = await formRef.value?.validate().catch(() => false)
    if (!valid || !isCurrent(session)) return
    const editing = connectionId != null
    const globalScope = editing && advancedScope.value === 'global'
    const input = { ...form, ...(editing ? updateAdvanced() : concreteAdvanced()) }
    const globalSettings: GlobalSettings = {
      dialTimeoutMs: adv.dialTimeoutSec * 1000,
      queryTimeoutMs: adv.queryTimeoutSec * 1000,
      connMaxLifetimeMin: adv.connMaxLifetimeMin,
      readOnly: adv.readOnly,
      paramsJson: adv.paramsJson.trim() || '{}',
    }
    const tip = editing
      ? `确认保存对连接「${input.name}」的修改？${globalScope ? '同时会更新高级参数(可能影响其他连接)。' : ''}`
      : `确认新建连接「${input.name}」？`
    if (!(await confirmAction(editing ? '保存连接' : '新建连接', tip, editing ? '确认保存' : '确认新建'))) return
    if (!isCurrent(session)) return
    if (connectionId != null) {
      // 全局参数与当前连接必须由一个服务端事务保存，避免第二步失败时其他连接已受影响。
      if (globalScope) await connectionsApi.updateWithSettings(connectionId, input, globalSettings)
      else await connectionsApi.update(connectionId, input)
    } else await connectionsApi.create(input)
    if (!isCurrent(session)) return
    ElMessage.success(editing ? '已更新' : '已创建')
    emit('saved')
    if (isCurrent(session)) onDialogVisibility(false)
  } catch {
    // 拦截器已展示失败原因；保留表单，允许用户修正后重试。
  } finally {
    if (isCurrent(session)) saving.value = false
  }
}
</script>

<style scoped>
/* 分区标题与提示文字都降一档色阶:表单主体是输入框,说明文字不能跟字段名抢注意力 */
.divider-title {
  font-size: 14px;
  color: var(--el-text-color-secondary);
}
/* 提示块必须 width:100%:el-form-item 的内容区是 flex 行,不占满就会被后一个控件挤到同一行 */
.scope-hint {
  font-size: 12px;
  color: var(--el-text-color-placeholder);
  margin-top: 4px;
  width: 100%;
}
</style>
