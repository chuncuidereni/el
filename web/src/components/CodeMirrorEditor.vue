<!--
  CodeMirrorEditor.vue

  职责：CodeMirror 编辑器封装：同步双向值、主题、快捷键和销毁生命周期。
  边界：通过 modelValue/update:modelValue 双向同步并发出 run；程序化更新抑制回抛，编辑器实例在卸载时销毁，选区操作通过 expose 提供。
-->

<template>
  <div ref="host" class="cm-host"></div>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { EditorView, keymap, lineNumbers, highlightActiveLine, highlightActiveLineGutter } from '@codemirror/view'
import { EditorState } from '@codemirror/state'
import { autocompletion } from '@codemirror/autocomplete'
import { sql, MySQL } from '@codemirror/lang-sql'
import { defaultKeymap, history, historyKeymap, indentWithTab } from '@codemirror/commands'
import { HighlightStyle, syntaxHighlighting, indentOnInput, bracketMatching } from '@codemirror/language'
import { tags as t } from '@lezer/highlight'
import { clickHouseCompletion, type CompletionData } from '@/utils/clickhouse'

const props = defineProps<{
  modelValue: string
  /** 可选:ClickHouse 自动补全数据(表名/列名) */
  completionData?: CompletionData
  /** 只读展示模式(用于 DDL 等纯展示,禁用编辑) */
  readonly?: boolean
}>()
const emit = defineEmits<{
  (e: 'update:modelValue', v: string): void
  (e: 'run'): void
}>()

/*
  ⚠️ 程序化同步期间【不要回抛】update:modelValue。
  否则下面 watch(modelValue) 里那次 dispatch 会被 update 监听当成"用户输入"再 emit 回去,
  上层(SqlQueryView 的 @update:model-value)就会 editing.sqlText = v; dirty = true ——
  于是"打开任务时的自动执行"会把查询标记成【已改动】,触发自动保存、动 updatedAt。
  评审 r4 指出 markDirty:false 拦不住这条链路(它只挡住了显式赋值),已核实。
  CodeMirror 的 update 监听在 dispatch 内【同步】触发,所以这个标记可靠。
*/
let applyingExternal = false

const host = ref<HTMLDivElement>()

// —— 深色主题:琥珀关键词,与整体 UI 一致 ——
const lightTheme = EditorView.theme(
  {
    '&': { backgroundColor: '#f7f8fa', color: '#263238', fontSize: '14px' },
    '.cm-content': { caretColor: '#0052d9', fontFamily: "var(--font-mono), monospace", padding: '8px 0' },
    '.cm-cursor, .cm-dropCursor': { borderLeftColor: '#0052d9' },
    '&.cm-focused > .cm-scroller > .cm-selectionLayer .cm-selectionBackground, .cm-selectionBackground, ::selection':
      { backgroundColor: 'rgba(0, 82, 217, 0.18)' },
    '.cm-gutters': {
      backgroundColor: '#eef1f4',
      color: '#6b7280',
      border: 'none',
      borderRight: '1px solid #dfe3e8',
      fontFamily: 'var(--font-mono), monospace',
    },
    '.cm-activeLine': { backgroundColor: 'rgba(0, 82, 217, 0.05)' },
    '.cm-activeLineGutter': { backgroundColor: 'rgba(0, 82, 217, 0.09)', color: '#0052d9' },
    '.cm-selectionMatch': { backgroundColor: 'rgba(0, 82, 217, 0.15)' },
    '.cm-matchingBracket': { backgroundColor: 'rgba(0, 82, 217, 0.15)', color: '#0052d9', outline: 'none' },
    '&.cm-focused .cm-selectionBackground': { backgroundColor: 'rgba(0, 82, 217, 0.22)' },
  },
  { dark: false },
)

const sqlHighlight = HighlightStyle.define([
  { tag: t.keyword, color: '#0052d9', fontWeight: '600' },
  { tag: [t.string, t.special(t.string)], color: '#047857' },
  { tag: t.number, color: '#be185d' },
  { tag: t.comment, color: '#6b7280', fontStyle: 'italic' },
  { tag: t.operator, color: '#7c3aed' },
  { tag: t.typeName, color: '#047857' },
  { tag: t.function(t.variableName), color: '#1d4ed8' },
  { tag: t.variableName, color: '#263238' },
  { tag: t.bool, color: '#be185d' },
  { tag: t.propertyName, color: '#4f46e5' },
])

let view: EditorView | null = null

onMounted(() => {
  if (!host.value) return
  const state = EditorState.create({
    doc: props.modelValue,
    extensions: [
      // 只读需要两道:readOnly 拦命令与 API 写入,editable 去掉 contenteditable,少一个都还能被输入法塞进字符
      EditorState.readOnly.of(!!props.readonly),
      EditorView.editable.of(!props.readonly),
      lineNumbers(),
      highlightActiveLineGutter(),
      highlightActiveLine(),
      history(),
      // 回车时沿用上一行缩进,写多行 SQL 不必手动对齐
      indentOnInput(),
      bracketMatching(),
      // lang-sql 没有 ClickHouse 方言,借 MySQL 的关键字/标识符规则来解析,CK 特有的表名/列名交给 clickHouseCompletion 补全
      // upperCaseKeywords 只决定补全候选中关键字插入时的大小写,不会改写用户已输入的内容
      sql({ dialect: MySQL, upperCaseKeywords: true }),
      // 没有补全数据时传空数组占位:extensions 里塞 false/undefined 会被 CodeMirror 拒绝,而补全数据是挂载时快照
      props.completionData ? autocompletion({ override: [clickHouseCompletion(props.completionData)] }) : [],
      keymap.of([
        // 自定义键必须排在 defaultKeymap 前面:defaultKeymap 已把 Mod-Enter 绑成 insertBlankLine,靠后就会被它先吃掉
        { key: 'Mod-Enter', run: () => (emit('run'), true) },
        ...defaultKeymap,
        ...historyKeymap,
        indentWithTab,
      ]),
      lightTheme,
      syntaxHighlighting(sqlHighlight, { fallback: true }),
      // 集中在此处上抛:applyingExternal 期间静默,切断"程序化改写 → emit → 父级再回传"的回环
      EditorView.updateListener.of((u) => {
        if (!props.readonly && u.docChanged && !applyingExternal) emit('update:modelValue', u.state.doc.toString())
      }),
    ],
  })
  view = new EditorView({ state, parent: host.value })
})

onBeforeUnmount(() => view?.destroy())

watch(
  () => props.modelValue,
  (nv) => {
    // 相等则不 dispatch:父级把用户刚输入的内容回传时文本本就一致,再整体替换会重置光标与撤销栈
    if (view && nv !== view.state.doc.toString()) {
      applyingExternal = true
      // 整体替换而不是找差异区间:外部改动基本是"切换任务/切换片段"这类整篇换文本,算 diff 得不偿失
      view.dispatch({ changes: { from: 0, to: view.state.doc.length, insert: nv } })
      applyingExternal = false
    }
  },
)

/** 返回当前选中的文本(无选中返回空串) */
function getSelection(): string {
  if (!view) return ''
  // main 是主选区:多光标时其余选区忽略,片段替换只认用户当前操作的那一处
  const sel = view.state.selection.main
  return view.state.sliceDoc(sel.from, sel.to)
}

/**
 * 插入片段文本。占位符 `{{x}}` 处理规则:
 * - 编辑器有选中文本 → 用它替换【第一个】占位符(方便先选列名再点片段)
 * - 无选中 → 保留占位符内的默认文本
 * - 插入后自动选中第一个占位符内容,可直接输入覆盖
 */
function insertSnippet(text: string) {
  // 只读态直接放弃,不由本方法负责报错:调用方可能没同步禁用触发按钮
  if (!view || props.readonly) return
  const sel = view.state.selection.main
  const selText = view.state.sliceDoc(sel.from, sel.to)
  // 只替换第一个占位符:多个占位符时后续的保留默认值,交给用户自己点选修改
  const m = /\{\{([^}]*)\}\}/.exec(text)
  let out = text
  let selFrom = -1
  let selTo = -1
  if (m) {
    // 有选中文本就用它顶替占位符内容,否则保留占位符里的默认值(如 {{column}} 变回 column)
    const inner = selText || m[1]
    out = text.slice(0, m.index) + inner + text.slice(m.index + m[0].length)
    // 选区坐标要加上插入起点 sel.from:m.index 是相对片段文本的偏移,不是文档偏移
    selFrom = sel.from + m.index
    selTo = selFrom + inner.length
  }
  view.dispatch({
    changes: { from: sel.from, to: sel.to, insert: out },
    // 有占位符则选中它方便直接覆盖输入;没有就把光标停在插入内容末尾,避免落到片段开头
    selection: selFrom >= 0 ? { anchor: selFrom, head: selTo } : { anchor: sel.from + out.length },
    scrollIntoView: true,
  })
  // dispatch 不会自动夺焦,不 focus 的话用户还得再点一次编辑器才能继续打字
  view.focus()
}

// 向外暴露的只有这三个命令式方法:编辑器内容仍以 modelValue 为准,父级拿到光标/选区时才用它们
defineExpose({ getSelection, insertSnippet, focus: () => view?.focus() })
</script>

<style scoped>
.cm-host {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  height: 100%;
  overflow: hidden;
  background: var(--editor-bg);
  transition: box-shadow 0.2s ease, border-color 0.2s ease;
}
.cm-host:focus-within {
  border-color: var(--brand);
  box-shadow: 0 0 0 1px var(--brand-dim);
}
.cm-host :deep(.cm-editor) {
  height: 100%;
}
</style>
