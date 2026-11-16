<!--
  BigDataSourceFields.vue

  职责：大数据源表单片段：编辑 Kafka、SFTP 等类型特有连接参数。
  边界：直接双向修改父级 config；Hive 认证模式兼容旧密码，端口空值回退 10000，不负责整体表单提交。
-->

<template>
  <template v-if="type === 'kafka'">
    <el-form-item label="Broker 地址" prop="config.bootstrapServers" required>
      <el-input v-model="config.bootstrapServers" placeholder="127.0.0.1:19092，多个地址用逗号分隔" />
    </el-form-item>
    <el-form-item label="默认 Topic"><el-input v-model="config.topic" placeholder="可选，如 demo_events" /></el-form-item>
    <el-form-item label="安全协议">
      <el-select v-model="config.securityProtocol" style="width:100%">
        <el-option label="用户名密码认证（SASL_PLAINTEXT）" value="SASL_PLAINTEXT" />
        <el-option label="用户名密码认证 + TLS（SASL_SSL）" value="SASL_SSL" />
        <el-option label="免认证（PLAINTEXT）" value="PLAINTEXT" />
        <el-option label="TLS，不使用用户名密码（SSL）" value="SSL" />
      </el-select>
    </el-form-item>
    <template v-if="String(config.securityProtocol).startsWith('SASL_')">
      <el-form-item label="SASL 机制">
        <el-select v-model="config.saslMechanism" style="width:100%">
          <el-option v-for="m in ['PLAIN','SCRAM-SHA-256','SCRAM-SHA-512']" :key="m" :label="m" :value="m" />
        </el-select>
      </el-form-item>
      <el-form-item label="用户名" prop="config.username" required><el-input v-model="config.username" /></el-form-item>
      <el-form-item label="密码" prop="config.password" :required="!keepPassword">
        <el-input v-model="config.password" type="password" show-password :placeholder="keepPassword ? '留空保持原密码' : '请输入密码'" />
      </el-form-item>
    </template>
    <div class="source-tip">密码认证需与 Kafka 服务端的 SASL 协议和机制一致。连接测试和对象检查仅读取元数据，不消费消息，也不改变消费位点。</div>
  </template>
  <template v-else-if="type === 'hive'">
    <el-form-item label="主机" prop="config.host" required><el-input v-model="config.host" placeholder="HiveServer2 主机" /></el-form-item>
    <el-form-item label="端口" prop="config.port" required><el-input-number v-model="port" :min="1" :max="65535" style="width:100%" /></el-form-item>
    <el-form-item label="数据库" prop="config.database" required><el-input v-model="config.database" placeholder="default" /></el-form-item>
    <el-form-item label="认证方式">
      <el-select v-model="hiveAuthMode" style="width:100%">
        <el-option label="用户名密码认证" value="PASSWORD" />
        <el-option label="免认证（兼容原配置）" value="NONE" />
      </el-select>
    </el-form-item>
    <el-form-item label="用户名" prop="config.username" :required="hiveAuthMode === 'PASSWORD'"><el-input v-model="config.username" placeholder="Hive 用户名" /></el-form-item>
    <el-form-item label="密码" prop="config.password" :required="hiveAuthMode === 'PASSWORD' && !keepPassword"><el-input v-model="config.password" type="password" show-password :placeholder="keepPassword ? '留空保持原密码' : hiveAuthMode === 'PASSWORD' ? '请输入密码' : '无认证时可留空'" /></el-form-item>
    <el-form-item label="默认表"><el-input v-model="config.table" placeholder="可选，如 orders" /></el-form-item>
    <div class="source-tip">使用 HiveServer2 JDBC 二进制连接。密码认证要求服务端启用 LDAP、CUSTOM 或 PAM；此处填写密码不会开启服务端认证。暂不支持 Kerberos。</div>
  </template>
</template>
<script setup lang="ts">
import { computed } from 'vue'
const props = defineProps<{type: string; config: Record<string, string | number | boolean>; keepPassword: boolean}>()
/** Hive 认证模式兼容旧配置：已有密码或保持密码时默认 PASSWORD，否则默认 NONE。 */
const hiveAuthMode = computed({ get: () => String(props.config.authMode || (props.keepPassword || props.config.password ? 'PASSWORD' : 'NONE')), set: (v: string) => { props.config.authMode = v } })
/** 端口双向绑定为数字；空值展示 Hive 默认端口 10000，写回时更新原配置对象。 */
const port = computed({ get: () => Number(props.config.port) || 10000, set: (v: number) => { props.config.port = v } })
</script>
<style scoped>
.source-tip { color: var(--el-text-color-secondary); font-size: 12px; margin: 0 0 16px 100px; }
</style>
