<!--
  ClickHouseSourceFields.vue

  职责：ClickHouse 连接字段片段：编辑协议、主机、端口、TLS 与认证参数。
  边界：直接更新父级 config；协议或 TLS 切换只替换旧建议端口，保留 9001、8132 等手工代理端口。
-->

<template>
  <el-form-item label="连接协议">
    <el-select v-model="protocol" style="width: 100%">
      <el-option label="Native / TCP" value="native" />
      <el-option label="HTTP / HTTPS" value="http" />
    </el-select>
  </el-form-item>
  <el-form-item label="TLS 加密"><el-switch v-model="secure" /></el-form-item>
  <div class="conn-hint">{{ protocol === 'http' ? 'HTTP' : 'Native' }}{{ secure ? ' 加密' : '' }}连接，常用端口 {{ suggestedPort(protocol, secure) }}；自定义端口请按实际服务填写。表列表、字段和同步数据均使用此处保存的账号读取。</div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
const props = defineProps<{ config: Record<string, string | number | boolean> }>()
/** 根据协议与 TLS 给出默认端口；这是建议值，不阻止用户填写代理转发端口。 */
function suggestedPort(p: string, tls: boolean) { return p === 'http' ? (tls ? 8443 : 8123) : (tls ? 9440 : 9000) }
/** 仅在端口仍是旧建议值时自动替换，避免覆盖用户手工配置的 9001/8132 等端口。 */
function update(p: string, tls: boolean) {
  const oldPort = suggestedPort(protocol.value, secure.value)
  if (!props.config.port || Number(props.config.port) === oldPort) props.config.port = suggestedPort(p, tls)
  props.config.protocol = p
  props.config.secure = tls
}
const protocol = computed({
  get: () => String(props.config.protocol || 'native'),
  set: (value: string) => update(value, secure.value)
})
const secure = computed({
  get: () => props.config.secure === true || props.config.secure === 'true',
  set: (value: boolean) => update(protocol.value, value)
})
</script>

<style scoped>
.conn-hint { color: var(--el-text-color-secondary); font-size: 12px; margin: 0 0 16px 100px; }
</style>
