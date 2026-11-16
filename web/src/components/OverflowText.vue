<template>
  <el-tooltip placement="top" :trigger="['hover', 'focus']" :show-after="250" :disabled="!text">
    <template #content><div class="overflow-text-detail">{{ detail || text }}</div></template>
    <span v-bind="$attrs" class="overflow-text" tabindex="0">{{ text || '—' }}</span>
  </el-tooltip>
</template>

<script setup lang="ts">
/** 只压缩展示，不截断实际字段名；悬停/聚焦可查看、选择和复制完整文本。 */
defineProps<{ text?: string; detail?: string }>()
defineOptions({ inheritAttrs: false })
</script>

<style scoped>
.overflow-text { display: block; min-width: 0; max-width: 100%; overflow: hidden; white-space: nowrap; text-overflow: ellipsis; }
.overflow-text-detail { max-width: min(640px, 80vw); max-height: 320px; overflow: auto; white-space: pre-wrap; overflow-wrap: anywhere; line-height: 1.7; }
</style>
