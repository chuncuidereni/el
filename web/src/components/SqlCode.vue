<!--
  SqlCode.vue

  职责：SQL 展示组件：以安全文本和语法高亮形式呈现 SQL。
  边界：只把传入 SQL 安全转义后高亮展示，不执行编辑或网络请求；展示交互不得改变原始 SQL。
-->

<template>
  <!--
    只读的 SQL 高亮块(用在哪:"SQL 查询"页展示实际执行的语句、可视化构建器里预览生成的 SQL)。

    与 CodeMirrorEditor 的分工:这里只做【展示】,没有光标/编辑/补全,所以用 <pre> + v-html
    而不是拉起整个 CodeMirror —— 后者在列表/预览里反复实例化代价太高。

    用 <pre> 配 v-html:换行与缩进由文本自身保留,不走 white-space 预处理;html 已整体转义,不存在注入
  -->
  <pre class="sql-code" :class="[`sz-${size}`]" v-html="html"></pre>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { highlightSql } from '@/utils/sqlHighlight'

const props = withDefaults(
  defineProps<{
    /** 待高亮的 SQL;允许传空串(父级用 v-if 控制时可能短暂为空) */
    sql: string
    /** 字号档位 */
    size?: 'sm' | 'md'
  }>(),
  // 默认 sm:该组件多用于日志/任务列表里的次要信息,大字号留给主查询区
  { size: 'sm' },
)

// 输入一律经 highlightSql 转义后才拼成 HTML,产物可安全交给 v-html;用 computed 缓存避免父组件每次重渲染都重跑一遍 tokenize
// 空串本身 highlightSql 已能处理(其内部有 !src 分支),这里 || '' 只是给动态绑定可能传 null 的父级留一道保险
const html = computed(() => highlightSql(props.sql || ''))
</script>

<style scoped>
/* 刻意用固定深色底 + 硬编码 token 色,不跟随 --el-* 主题变量:
   语法配色只有在深底上才达标(亮底会把关键字黄、注释灰糊在一起),所以明暗两种主题下这块都保持同一套色 */
.sql-code {
  margin: 0;
  background: #0d1117;
  color: #d7dee6;
  border-radius: 6px;
  line-height: 1.65;
  overflow: auto;
  /* 长 SQL 优先折行而不是横向滚动:容器宽度不定(弹窗/侧栏/单元格),横向滚动条容易被忽略
     且 break-all 能让没有空格的长串(哈希、URL)也不撑破容器 */
  white-space: pre-wrap;
  word-break: break-all;
  font-family: var(--font-mono, monospace);
}
/* 两档只差字号与内边距:长语句(预览区)用 sm 多塞几行,短语句强调展示时用 md */
.sz-sm {
  font-size: 12px;
  padding: 9px 10px;
}
.sz-md {
  font-size: 13px;
  padding: 10px 12px;
}

/* 配色与 CodeMirrorEditor 的 sqlHighlight 保持一致 */
.sql-code :deep(.sql-k) {
  color: #eab308;
  font-weight: 600;
}
.sql-code :deep(.sql-s) {
  color: #7dd3fc;
}
.sql-code :deep(.sql-n) {
  color: #f472b6;
}
.sql-code :deep(.sql-c) {
  color: #56606b;
  font-style: italic;
}
.sql-code :deep(.sql-o) {
  color: #c084fc;
}
.sql-code :deep(.sql-t) {
  color: #34d399;
}
.sql-code :deep(.sql-f) {
  color: #60a5fa;
}
.sql-code :deep(.sql-b) {
  color: #f472b6;
}
.sql-code :deep(.sql-i) {
  color: #a5b4fc;
}
</style>
