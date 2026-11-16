<!--
  ActionIconBar.vue

  职责：通用操作图标栏：把命令、图标、禁用态和点击事件统一呈现。
  边界：只接收 actions/busy 并发出 command，不自行发请求；未知命令回退为通用设置图标，禁用态由 busy 或动作项控制。
-->

<template>
  <div class="action-icon-bar">
    <el-tooltip v-for="action in actions" :key="action.command" :content="action.label" placement="top">
      <span>
        <el-button
          :aria-label="action.label"
          :title="action.label"
          :icon="iconFor(action.command)"
          :type="action.danger ? 'danger' : action.primary ? 'primary' : 'default'"
          :disabled="busy || action.disabled"
          size="small"
          text
          class="action-icon"
          @click="emit('command', action.command)"
        />
      </span>
    </el-tooltip>
  </div>
</template>

<script setup lang="ts">
import {
  Brush, CircleCheck, Close, Clock, Connection, DataAnalysis, Delete, Edit,
  FolderOpened, Operation, Refresh, RefreshRight, Search, Setting, Share, Switch,
  VideoPlay, View,
} from '@element-plus/icons-vue'

export interface ActionIcon {
  command: string
  label: string
  disabled?: boolean
  danger?: boolean
  primary?: boolean
}

defineProps<{ actions: ActionIcon[]; busy?: boolean }>()
const emit = defineEmits<{ command: [command: string] }>()

const icons: Record<string, typeof View> = {
  open: View, data: View, edit: Edit, test: Connection, check: CircleCheck,
  sync: Setting, movegroup: FolderOpened, remove: Delete, run: VideoPlay,
  runforce: RefreshRight, cancel: Close, cancelqueued: Close, history: Clock, toggle: Switch,
  topology: Share, rebuild: RefreshRight, flatten: Refresh, probe: Search,
  runs: Clock, drop: Brush, agg: DataAnalysis, setup: VideoPlay,
  optimize: Operation, share: Share,
}

/** 为后端命令解析图标；未知命令使用通用设置图标，避免渲染中断。 */
function iconFor(command: string) {
  return icons[command] ?? Setting
}
</script>

<style scoped>
.action-icon-bar {
  display: flex;
  align-items: center;
  justify-content: center;
  flex-wrap: nowrap;
  gap: 4px;
}
.action-icon {
  width: 32px;
  height: 32px;
  padding: 0;
  font-size: 16px;
}
</style>
