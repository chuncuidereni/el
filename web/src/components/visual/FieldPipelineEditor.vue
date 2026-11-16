<!--
  FieldPipelineEditor.vue

  职责：字段处理流水线编辑器：维护步骤顺序、参数校验和表达式预览。
  边界：编辑单列的 steps/prog 程序链并校验参数；sqlOnly 模式隐藏程序算子，输出表达式供父级汇总，本组件不执行 SQL。
-->

<template>
  <div class="pe-wrap">
    <!-- 头部预告「原始类型 → 处理后的类型」:算子会改值类型(如 toDate),
         不预告会让人以为下面候选算子按原始类型过滤是漏了项 -->
    <div class="pe-head">
      处理链:<b class="mono">{{ col }}</b>
      <span class="pe-kind">
        {{ KIND_LABEL[kind] }}
        <template v-if="currentKind !== kind">
          <span class="pe-arrow">→</span>
          <b>{{ KIND_LABEL[currentKind] }}</b>
        </template>
      </span>
    </div>

    <!-- ═══ ① 查询时由引擎计算 ═══ -->
    <div class="pe-sec pe-sec-ck">
      <div class="pe-sec-head">
        <span class="pe-badge ck">SQL</span>
        <span class="pe-sec-name">查询时计算</span>
        <span class="pe-sec-note">写进 SQL → 结果列可过滤 / 分组 / 排序</span>
      </div>

      <div v-if="!steps.length" class="pe-empty">未配置 —— 字段原样输出</div>
      <div v-else class="pe-chain">
        <!-- 用下标做 key:步骤没有稳定 id,上移/下移也是按位置 splice 的,
             下标才与「第 N 步」的序号展示一致 -->
        <div v-for="(st, i) in steps" :key="`s${i}`" class="pe-item">
          <div class="pe-item-main">
            <span class="pe-order">{{ i + 1 }}</span>
            <span class="pe-opname">{{ opLabel(st.fn) }}</span>
            <span v-for="(p, pi) in opParams(st.fn)" :key="pi" class="pe-param">
              <span class="pe-param-label">{{ p.label }}</span>
              <!-- 有 options 的参数 = 后端只认这几个值 → 渲染成下拉,避免手输错值 -->
              <el-select
                v-if="p.options?.length"
                :model-value="st.args?.[pi]?.value ?? ''"
                size="small"
                class="pe-param-input"
                :class="{ bad: !argValid(st, pi) }"
                :placeholder="p.def || p.label"
                @update:model-value="(v: string) => setArg('steps', i, pi, v)"
              >
                <el-option v-for="o in p.options" :key="o.value" :value="o.value" :label="o.label" />
              </el-select>
              <el-input
                v-else
                :model-value="st.args?.[pi]?.value ?? ''"
                size="small"
                class="pe-param-input"
                :class="{ bad: !argValid(st, pi) }"
                :type="p.kind === 'number' ? 'number' : 'text'"
                :placeholder="p.def || p.label"
                @update:model-value="(v: string) => setArg('steps', i, pi, v)"
              />
            </span>
            <span class="pe-spacer"></span>
            <span class="pe-item-ops">
              <!-- 首/末步的移动按钮「禁用」而不是隐藏:位置固定,连点下移时鼠标不用追着按钮跑 -->
              <el-button link size="small" :disabled="i === 0" @click="move('steps', i, i - 1)" title="上移">
                <el-icon><Top /></el-icon>
              </el-button>
              <el-button link size="small" :disabled="i === steps.length - 1" @click="move('steps', i, i + 1)" title="下移">
                <el-icon><Bottom /></el-icon>
              </el-button>
              <el-button link size="small" type="danger" @click="removeAt('steps', i)" title="移除">
                <el-icon><Close /></el-icon>
              </el-button>
            </span>
          </div>
          <!-- 拆到每一步显示「累计到此步」的表达式:整链预览看不出是哪一层套错,
               分开显示才能定位到具体算子 -->
          <div class="pe-item-sub mono">{{ stepsExpr(i) }}</div>
          <div v-if="opDesc(st.fn)" class="pe-item-desc">{{ opDesc(st.fn) }}</div>
        </div>
      </div>

      <div v-if="steps.length" class="pe-preview">
        表达式:<span class="mono">{{ exprPreview }}</span>
      </div>

      <!-- 候选算子按当前输出类型过滤(opsForKind):类型一变,能接的算子跟着变 -->
      <div class="pe-sec-title">可选算子(点击追加到链尾)</div>
      <div v-for="g in opGroups" :key="g.key" class="pe-group">
        <span class="pe-dot" :style="{ background: g.accent }"></span>
        <span class="pe-gname">{{ g.label }}</span>
        <div class="pe-ops">
          <span
            v-for="op in g.ops"
            :key="op.key"
            class="pe-chip"
            :title="op.desc || op.tpl"
            @click="append('steps', op)"
          >
            {{ op.label }}
          </span>
        </div>
      </div>
      <!-- 常用正则示例直接展示在页面上:正则参数没有通用默认值,只靠 placeholder 用户不知道怎么写 -->
      <div v-if="currentKind === 'string'" class="pe-examples">
        <div class="pe-examples-title">常用正则示例</div>
        <div><code>【(.+?)】</code> 提取【】中的内容（如「【测试】」→「测试」）</div>
        <div><code>\((.+?)\)</code> 提取圆括号中的内容（如「(测试)」→「测试」）</div>
        <div><code>\[(.+?)\]</code> 提取方括号中的内容（如「[测试]」→「测试」）</div>
        <div><code>1[3-9]\d{9}</code> 匹配 11 位手机号（用于正则匹配/提取）</div>
        <div><code>(\d{3})\d{4}(\d{4})</code> 替换为 <code>\1****\2</code> 可对手机号脱敏</div>
        <div class="pe-examples-note">正则填在「正则」参数里；反向引用 SQL 段用 <code>\1</code>，程序段用 <code>$1</code>。</div>
      </div>
    </div>

    <!-- ═══ ② 返回后由本程序计算(sqlOnly 时不显示:关联键不进结果、程序算子救不了) ═══ -->
    <div v-if="!sqlOnly" class="pe-sec pe-sec-prog" :class="{ on: prog.length > 0 }">
      <div class="pe-sec-head">
        <span class="pe-badge prog">程序</span>
        <span class="pe-sec-name">返回后计算</span>
        <span class="pe-sec-note">由后置程序逐行计算,查询引擎不参与</span>
      </div>

      <!-- 空态文案要说清「什么时候才需要这一段」,否则用户不知道程序算子存在的意义 -->
      <div v-if="!prog.length" class="pe-empty">
        未配置 —— 需要 SM3 / 3DES 等数据库没有的能力时,从这里添加
      </div>
      <div v-else class="pe-chain">
        <div v-for="(st, i) in prog" :key="`p${i}`" class="pe-item prog">
          <div class="pe-item-main">
            <span class="pe-order prog">{{ i + 1 }}</span>
            <span class="pe-opname">{{ opLabel(st.fn) }}</span>
            <span v-for="(p, pi) in opParams(st.fn)" :key="pi" class="pe-param">
              <span class="pe-param-label">{{ p.label }}</span>
              <!-- 有 options 的参数 = 后端只认这几个值 → 渲染成下拉,避免手输错值 -->
              <el-select
                v-if="p.options?.length"
                :model-value="st.args?.[pi]?.value ?? ''"
                size="small"
                class="pe-param-input"
                :class="{ bad: !argValid(st, pi) }"
                :placeholder="p.def || p.label"
                @update:model-value="(v: string) => setArg('prog', i, pi, v)"
              >
                <el-option v-for="o in p.options" :key="o.value" :value="o.value" :label="o.label" />
              </el-select>
              <el-input
                v-else
                :model-value="st.args?.[pi]?.value ?? ''"
                size="small"
                class="pe-param-input"
                :class="{ bad: !argValid(st, pi) }"
                :type="p.kind === 'number' ? 'number' : 'text'"
                :placeholder="p.def || p.label"
                @update:model-value="(v: string) => setArg('prog', i, pi, v)"
              />
            </span>
            <span class="pe-spacer"></span>
            <span class="pe-item-ops">
              <!-- 首/末步的移动按钮「禁用」而不是隐藏:位置固定,连点下移时鼠标不用追着按钮跑 -->
              <el-button link size="small" :disabled="i === 0" @click="move('prog', i, i - 1)" title="上移">
                <el-icon><Top /></el-icon>
              </el-button>
              <el-button link size="small" :disabled="i === prog.length - 1" @click="move('prog', i, i + 1)" title="下移">
                <el-icon><Bottom /></el-icon>
              </el-button>
              <el-button link size="small" type="danger" @click="removeAt('prog', i)" title="移除">
                <el-icon><Close /></el-icon>
              </el-button>
            </span>
          </div>
          <div class="pe-item-sub mono">{{ progExpr(i) }}</div>
          <div v-if="opDesc(st.fn)" class="pe-item-desc">{{ opDesc(st.fn) }}</div>
        </div>
      </div>

      <div v-if="prog.length" class="pe-warn">
        ⚠️ 此列由本程序计算,查询引擎看不到它的值 ——
        <b>不能用于过滤条件 / 分组 / 排序</b>(已自动从那些下拉中移除)。<br />
        展示与导出都会应用同样的计算(不会漏出原始值)。
      </div>

      <div class="pe-sec-title">程序算子(点击追加到链尾)</div>
      <!-- 按「加密 / 字符串 / 时间 / 数值」分组展示 —— 算子已有 28 个,挤一组会找不到 -->
      <div v-for="g in PROG_OP_GROUPS" :key="g.key" class="pe-group">
        <span class="pe-dot" :style="{ background: g.accent }"></span>
        <span class="pe-gname">{{ g.label }}</span>
        <div class="pe-ops">
          <span
            v-for="op in g.ops"
            :key="op.key"
            class="pe-chip prog"
            :title="op.desc"
            @click="append('prog', op)"
          >
            {{ op.label }}
          </span>
        </div>
      </div>
    </div>

    <div class="pe-tip">
      ⚠️ SQL 段只支持<b>单输入</b>算子(输入值永远是第一个参数)。
      <code>if</code> / <code>multiIf</code> / <code>dateDiff</code> 这类多输入语义的写法请用 <b>SQL 模式</b>。
      <br />
      ⚠️ 正则替换的反向引用用 <code>\1</code> <b>不是</b> <code>$1</code>。
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { Bottom, Close, Top } from '@element-plus/icons-vue'
import {
  KIND_LABEL,
  PIPELINE_OP_MAP,
  PROG_OP_GROUPS,
  defaultArgs,
  opsForKind,
  renderStepsExpr,
  type PipelineOp,
  type ValueKind,
} from '@/constants/queryBuilderMeta'
import type { QueryPipelineStep } from '@/types'

const props = defineProps<{
  col: string
  kind: ValueKind
  steps: QueryPipelineStep[]
  /** 程序算子链(由 el-to-ck 计算) */
  prog: QueryPipelineStep[]
  /**
   * 只显示 SQL 段(隐藏程序算子段)。
   *
   * 用于「关联键(mdn 归一化)」这类场景:它的表达式直接进 SQL 参与 JOIN 关联,
   * 而程序算子是结果返回后才算的 —— 放进关联键里没有意义(后端也根本不支持,
   * mdnRules 只有 steps 没有 prog)。
   */
  sqlOnly?: boolean
}>()

const emit = defineEmits<{
  (e: 'update:steps', v: QueryPipelineStep[]): void
  (e: 'update:prog', v: QueryPipelineStep[]): void
}>()

/** 当前段的链(CK 段会推进类型;程序段不影响 CK 的算子过滤) */
const currentKind = computed<ValueKind>(() => {
  let k = props.kind
  for (const st of props.steps) {
    // 逐级推进:每一步的输出类型就是下一步的输入类型,最终类型由最后一步的 outKind 决定
    const out = PIPELINE_OP_MAP[st.fn]?.outKind
    if (out) k = out
  }
  return k
})

const opGroups = computed(() => opsForKind(currentKind.value))

/** steps / prog 两段共用同一套增删改逻辑,靠 which 选择操作对象 */
function listOf(which: 'steps' | 'prog'): QueryPipelineStep[] {
  return which === 'steps' ? props.steps : props.prog
}

/** 老配置里可能有未收录的算子:退回显示 fn 名,不能让 label 变空白以致这一步看起来消失 */
function opLabel(fn: string) {
  return PIPELINE_OP_MAP[fn]?.label ?? fn
}
function opParams(fn: string) {
  return PIPELINE_OP_MAP[fn]?.params ?? []
}
/** 算子的用法说明（含常见示例），直接显示在每一步下方，不只放在悬停提示里。 */
function opDesc(fn: string) {
  return PIPELINE_OP_MAP[fn]?.desc ?? ''
}

/** 唯一出口:props 只读,所有增删改都得构造新数组回抛父组件,父组件才拿得到变更 */
function emitChange(which: 'steps' | 'prog', next: QueryPipelineStep[]) {
  if (which === 'steps') emit('update:steps', next)
  else emit('update:prog', next)
}

/** 只追加到链尾:链有先后语义,先追加再用上移调位,比先选插入位置再填参数少一步 */
function append(which: 'steps' | 'prog', op: PipelineOp) {
  emitChange(which, [...listOf(which), { fn: op.key, args: defaultArgs(op) }])
}
function removeAt(which: 'steps' | 'prog', i: number) {
  // 复制一份再 splice:直接改 props 上的数组等于绕过父组件改状态,父组件的刷新逻辑不会触发
  const next = [...listOf(which)]
  next.splice(i, 1)
  emitChange(which, next)
}
/** 先摘除再插入,元素被摘走后 to 之后的下标整体前移,所以 to 应理解为「最终位置」 */
function move(which: 'steps' | 'prog', from: number, to: number) {
  const next = [...listOf(which)]
  const [it] = next.splice(from, 1)
  next.splice(to, 0, it)
  emitChange(which, next)
}
function setArg(which: 'steps' | 'prog', i: number, pi: number, v: string) {
  const next = listOf(which).map((s, si) => {
    // 未改动的步骤原样返回同一引用:整表重建会让其它行的输入框一起重渲染、光标可能丢失
    if (si !== i) return s
    const args = [...(s.args ?? [])]
    const p = opParams(s.fn)[pi]
    // kind 取参数定义而非输入框的 type:后端 renderArg 靠它决定值要不要加引号
    args[pi] = { kind: p?.kind ?? 'string', value: v }
    return { ...s, args }
  })
  emitChange(which, next)
}

/** 参数合法性(number 必须能转数字,ident 必须是标识符,必填参数不能空,3DES 密钥长度) */
function argValid(st: QueryPipelineStep, pi: number): boolean {
  const p = opParams(st.fn)[pi]
  const v = st.args?.[pi]?.value ?? ''
  if (!p) return true
  // ⚠️ 必填参数不能留空。不显式标注时按 kind 推断(string 可空)。
  //    必须在 options 之前判:枚举参数留空走「用后端默认值」,而必填的枚举参数
  //    (目前没有)应该报错 —— 这条放在前面,语义更清楚。
  const required = p.required ?? p.kind !== 'string'
  if (required && v === '') return false
  // 枚举参数:留空 = 用后端默认值(放行);填了就必须是选项之一
  if (p.options?.length) return v === '' || p.options.some((o) => o.value === v)
  // ⚠️ 用 Number.isFinite 而不是 !Number.isNaN:Number('Infinity') 是 Infinity
  //    不是 NaN,会被放行 → 后端 renderPipelineArg 会生成裸字面量 f(x, Infinity)。
  if (p.kind === 'number') return v !== '' && Number.isFinite(Number(v))
  // ident 会被裸拼进 SQL(不加引号),出现空格 / 引号 / 中文会直接生成语法错误
  if (p.kind === 'ident') return /^[A-Za-z_][A-Za-z0-9_]*$/.test(v)
  // 3DES 密钥:24 字节或 48 位 hex
  if ((st.fn === 'des3_enc' || st.fn === 'des3_dec') && pi === 0) {
    return v.length === 24 || (v.length === 48 && /^[0-9a-fA-F]+$/.test(v))
  }
  if ((st.fn === 'aes256_dec' || st.fn === 'aes256_cbc_dec') && pi === 0) return new TextEncoder().encode(v).length === 32
  if (st.fn === 'aes256_cbc_dec' && pi === 1) return new TextEncoder().encode(v).length === 16
  // FF1 密钥:16/24/32 字节,或 32/48/64 位 hex
  // 两种写法并存:先按 hex 位数判(要求全是十六进制字符),不匹配再退回按字节数判
  if ((st.fn === 'ff1_enc' || st.fn === 'ff1_dec') && pi === 0) {
    if (v.length === 32 || v.length === 48 || v.length === 64) return /^[0-9a-fA-F]+$/.test(v)
    return v.length === 16 || v.length === 24 || v.length === 32
  }
  // FF1 字母表:至少 2 个字符(字符数,不是字节数 —— 与后端 utf8.RuneCountInString 同口径),
  // 且不能有重复字符(重复会让加解密不是双射)。
  if ((st.fn === 'ff1_enc' || st.fn === 'ff1_dec') && pi === 2) {
    const rs = [...v]
    if (rs.length < 2) return false
    return new Set(rs).size === rs.length
  }
  // prog_substr:长度允许留空(到末尾),起始必须是 >=1 的整数
  if (st.fn === 'prog_substr') {
    // pi 1 = 长度,留空表示取到末尾(与后端语义一致),不算非法
    if (pi === 1 && v === '') return true
    const nn = Number(v)
    if (!Number.isInteger(nn)) return false
    // pi 0 = 起始位置(1-based,0 无意义);pi 1 = 长度,0 合法(表示空串)
    return pi === 0 ? nn >= 1 : nn >= 0
  }
  // 没单独校验的算子一律放行:这里只负责提前拦下后端必然报错的输入,
  // 规则没覆盖到就宁可放行 —— 误判成非法会让用户彻底填不了这个参数。
  return true
}

/** CK 段:累计到第 i 步的表达式 */
function stepsExpr(i: number): string {
  return renderStepsExpr(props.col, props.steps.slice(0, i + 1))
}
/** 全链最终表达式:与后端 buildPipelineExpr 同构,所见即所发 */
const exprPreview = computed(() => renderStepsExpr(props.col, props.steps))

/** 程序段:表达式预览(用展示模板,不代表 SQL) */
function progExpr(i: number): string {
  // 程序段吃的是 SQL 段的输出(两段串联),预览必须把 steps 套进来,
  // 否则会让人误以为「查询时计算」与「返回后计算」是两条并行分支
  let e = props.steps.length ? renderStepsExpr(props.col, props.steps) : `\`${props.col}\``
  for (let k = 0; k <= i; k++) {
    const op = PIPELINE_OP_MAP[props.prog[k]?.fn]
    // 逐层用模板套壳,只是给人看的展示形式:真正执行的是后端程序,不生成 SQL
    e = (op?.tpl ?? '%s').replace('%s', e)
  }
  return e
}
</script>

<style scoped>
.pe-wrap {
  font-size: 14px;
}
.pe-head {
  font-size: 13.5px;
  margin-bottom: 10px;
}
.pe-kind {
  margin-left: 8px;
  font-size: 12px;
  color: var(--el-text-color-placeholder);
}
.pe-arrow {
  margin: 0 3px;
  color: var(--brand, #eab308);
}
.pe-kind b {
  color: var(--el-color-success);
}

/* ── 两段式分区 ── */
.pe-sec {
  border-radius: 7px;
  padding: 7px 9px;
  margin-bottom: 8px;
}
.pe-sec-ck {
  border: 1px solid var(--el-border-color-lighter);
  background: var(--el-fill-color-lighter);
}
.pe-sec-prog {
  border: 1px dashed var(--el-border-color);
  background: var(--el-fill-color-lighter);
}
.pe-sec-prog.on {
  border-style: solid;
  border-color: var(--el-color-warning-light-5);
  background: var(--el-color-warning-light-9);
}
.pe-sec-head {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 8px;
}
.pe-badge {
  font-size: 11px;
  padding: 1px 6px;
  border-radius: 4px;
  font-weight: 700;
  flex: 0 0 auto;
}
.pe-badge.ck {
  background: rgba(96, 165, 250, 0.18);
  color: #60a5fa;
}
.pe-badge.prog {
  background: rgba(245, 158, 11, 0.2);
  color: #f59e0b;
}
.pe-sec-name {
  font-size: 13.5px;
  font-weight: 600;
}
.pe-sec-note {
  font-size: 11px;
  color: var(--el-text-color-placeholder);
  margin-left: auto;
}

.pe-sec-title {
  font-size: 13px;
  color: var(--el-text-color-secondary);
  margin: 8px 0 5px;
}
.pe-empty {
  font-size: 13px;
  color: var(--el-text-color-placeholder);
  padding: 4px 0;
}
.pe-chain {
  display: flex;
  flex-direction: column;
  gap: 5px;
}
.pe-item {
  border: 1px solid var(--el-border-color);
  border-radius: 6px;
  background: var(--el-bg-color);
  padding: 5px 8px;
}
.pe-item.prog {
  border-color: var(--el-color-warning-light-5);
}
.pe-item-main {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.pe-order {
  width: 16px;
  height: 16px;
  border-radius: 50%;
  background: var(--el-color-primary);
  color: #fff;
  font-size: 11px;
  line-height: 16px;
  text-align: center;
  flex: 0 0 16px;
  font-weight: 700;
}
.pe-order.prog {
  background: #f59e0b;
  color: #1f2937;
}
.pe-opname {
  font-size: 13.5px;
  font-weight: 500;
  flex: 0 0 auto;
}
.pe-param {
  display: inline-flex;
  align-items: center;
  gap: 3px;
}
.pe-param-label {
  font-size: 11px;
  color: var(--el-text-color-placeholder);
}
.pe-param-input {
  width: 110px;
}
.pe-param-input :deep(.el-input__inner) {
  font-family: var(--font-mono, monospace);
  font-size: 12px;
  height: 22px;
}
.pe-param-input.bad :deep(.el-input__wrapper) {
  box-shadow: 0 0 0 1px var(--el-color-danger) inset;
}
.pe-spacer {
  flex: 1;
}
.pe-item-ops {
  display: flex;
  align-items: center;
  flex: 0 0 auto;
}
.pe-item-ops :deep(.el-button) {
  padding: 0 3px;
  height: 18px;
}
.pe-item-sub {
  font-size: 11px;
  color: var(--el-text-color-placeholder);
  margin-top: 3px;
  padding-left: 24px;
  word-break: break-all;
}
.pe-item-desc {
  margin-top: 2px;
  padding-left: 24px;
  font-size: 12px;
  line-height: 1.55;
  color: var(--el-text-color-secondary);
  word-break: break-word;
}
.pe-examples {
  margin-top: 10px;
  padding: 8px 10px;
  border-radius: 6px;
  background: var(--el-fill-color-lighter);
  font-size: 12px;
  line-height: 1.8;
  color: var(--el-text-color-regular);
}
.pe-examples-title { margin-bottom: 2px; font-weight: 600; color: var(--el-text-color-secondary); }
.pe-examples code { padding: 0 3px; border-radius: 3px; background: var(--el-fill-color); font-family: var(--font-mono, monospace); }
.pe-examples-note { margin-top: 4px; color: var(--el-text-color-secondary); }
.pe-preview {
  margin-top: 8px;
  padding: 6px 8px;
  border-radius: 5px;
  background: var(--el-fill-color);
  font-size: 12px;
  color: var(--el-text-color-secondary);
  word-break: break-all;
}
.pe-warn {
  margin-top: 8px;
  padding: 6px 8px;
  border-radius: 5px;
  background: var(--el-color-warning-light-8);
  color: var(--el-color-warning-dark-2);
  font-size: 12px;
  line-height: 1.7;
}
.pe-group {
  display: flex;
  align-items: flex-start;
  gap: 5px;
  margin-bottom: 5px;
}
.pe-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  margin-top: 6px;
  flex: 0 0 6px;
}
.pe-gname {
  width: 62px;
  flex: 0 0 62px;
  font-size: 13px;
  color: var(--el-text-color-regular);
  padding-top: 3px;
}
.pe-ops {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  flex: 1;
}
.pe-chip {
  font-size: 12px;
  padding: 1px 7px;
  border: 1px solid var(--el-border-color);
  border-radius: 9px;
  cursor: pointer;
  transition: all 0.15s ease;
  line-height: 18px;
}
.pe-chip:hover {
  border-color: var(--brand, #eab308);
  background: rgba(234, 179, 8, 0.1);
}
.pe-chip.prog {
  border-color: var(--el-color-warning-light-5);
  color: #b45309;
}
.pe-chip.prog:hover {
  background: rgba(245, 158, 11, 0.14);
  border-color: #f59e0b;
}
.pe-tip {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  border-top: 1px solid var(--el-border-color-lighter);
  padding-top: 8px;
  line-height: 1.75;
}
.pe-tip code {
  font-family: var(--font-mono, monospace);
  background: var(--el-fill-color);
  padding: 0 3px;
  border-radius: 3px;
}
.mono {
  font-family: var(--font-mono, monospace);
}
</style>
