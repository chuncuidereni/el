<template>
  <div class="optional-date-range" :aria-label="ariaLabel">
    <el-date-picker
      :model-value="startDate"
      type="date"
      format="YYYY-MM-DD"
      value-format="YYYYMMDD"
      :placeholder="startPlaceholder"
      :aria-label="startPlaceholder"
      :disabled="disabled"
      :aria-invalid="!!rangeError"
      :aria-describedby="rangeError ? errorId : undefined"
      clearable
      @update:model-value="onStartChange"
    />
    <span class="optional-date-range-separator" aria-hidden="true">至</span>
    <el-date-picker
      :model-value="endDate"
      type="date"
      format="YYYY-MM-DD"
      value-format="YYYYMMDD"
      :placeholder="endPlaceholder"
      :aria-label="endPlaceholder"
      :disabled="disabled"
      :aria-invalid="!!rangeError"
      :aria-describedby="rangeError ? errorId : undefined"
      clearable
      @update:model-value="onEndChange"
    />
    <span v-if="rangeError" :id="errorId" role="alert" class="optional-date-range-error">{{ rangeError }}</span>
  </div>
</template>

<script setup lang="ts">
import { computed, useId } from 'vue'

const props = withDefaults(defineProps<{
  startDate: string
  endDate: string
  startPlaceholder?: string
  endPlaceholder?: string
  ariaLabel?: string
  disabled?: boolean
}>(), {
  startPlaceholder: '开始日期',
  endPlaceholder: '结束日期',
  ariaLabel: '可选日期区间'
})
const errorId = `date-range-error-${useId()}`

function validDate(value: string): boolean {
  if (!/^\d{8}$/.test(value)) return false
  const year = Number(value.slice(0, 4)), month = Number(value.slice(4, 6)), day = Number(value.slice(6, 8))
  const date = new Date(0)
  date.setUTCFullYear(year, month - 1, day)
  return year >= 1 && date.getUTCFullYear() === year && date.getUTCMonth() === month - 1 && date.getUTCDate() === day
}

const emit = defineEmits<{
  'update:startDate': [value: string]
  'update:endDate': [value: string]
  change: []
}>()

const rangeError = computed(() => {
  const start = props.startDate?.trim() ?? ''
  const end = props.endDate?.trim() ?? ''
  if ((start && !validDate(start)) || (end && !validDate(end))) return '请选择真实有效的日期'
  if (end && !start) return '填写结束日期时需同时填写开始日期'
  if (start && end && end < start) return '结束日期不能早于开始日期'
  return ''
})

function normalizeDate(value: unknown): string {
  const text = typeof value === 'string' ? value.trim() : ''
  // Element Plus and older cached builds may emit either YYYYMMDD or YYYY-MM-DD.
  // Store the backend's canonical 8-digit value in both cases.
  return text.replace(/-(?=\d)/g, '')
}

function onStartChange(value: unknown) {
  if (props.disabled) return
  emit('update:startDate', normalizeDate(value))
  emit('change')
}

function onEndChange(value: unknown) {
  if (props.disabled) return
  emit('update:endDate', normalizeDate(value))
  emit('change')
}
</script>

<style scoped>
.optional-date-range {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto minmax(0, 1fr);
  align-items: center;
  gap: 8px;
  width: 100%;
  min-width: 0;
}

.optional-date-range :deep(.el-date-editor.el-input) {
  width: 100%;
  min-width: 0;
}

.optional-date-range-separator {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1;
}

.optional-date-range-error {
  grid-column: 1 / -1;
  color: var(--el-color-danger);
  font-size: 12px;
  line-height: 1.4;
}

@media (max-width: 520px) {
  .optional-date-range {
    grid-template-columns: 1fr;
  }

  .optional-date-range-separator {
    display: none;
  }
}
</style>
