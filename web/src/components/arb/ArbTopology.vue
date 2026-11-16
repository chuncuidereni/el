<!--
  ArbTopology.vue

  职责：聚合工作流只读拓扑图：把源表、聚合层、服务层和发布关系转换为可视化节点。
  边界：只根据 sources/columns/probe 派生只读拓扑；停用源不进入图，未保存工作流与已探测但缺少节点需要保持区分。
-->

<template>
  <!--
    工作流拓扑(只读流程图)。

    为什么是「只读图」而不是可拖拽的画布:这个工作流的形状是固定的
    —— 源(1..N) 同时喂给同一张聚合表(每源一个物化视图),彼此没有先后、
    没有条件分支、没有环。画布最值钱的三种能力(依赖顺序/条件分支/重试)
    一个都用不上;而列级映射(某目标列的候选来自几个源的几个列)本来就
    不是「节点连边」能表达的。所以这里只画「现在的配置长什么样 +
    跑起来是什么状态」—— 这是真看不懂的那部分。

    运行状态(probe)是可选的:新建工作流还没落库时只有结构,没有状态。
  -->
  <div class="tp">
    <!-- ① 源层 -->
    <div class="tp-layer">
      <div class="tp-layer-tag">
        <span class="tp-dot tp-dot-src"></span>源表
        <span class="tp-dim">{{ activeSources.length }} 张</span>
      </div>
      <div class="tp-srcs">
        <!-- 只画启用中的源:停用的源不供数,画进来会让人以为它也在往聚合层写 -->
        <div
          v-for="(s, i) in activeSources"
          :key="s.id"
          class="tp-node tp-node-src"
          :class="{ 'tp-node-off': !s.enabled }"
        >
          <!-- 圆圈里的序号只是展示顺序(配置里的先后),决定冲突归属的是右侧的 pri -->
          <div class="tp-node-head">
            <span class="tp-idx">{{ i + 1 }}</span>
            <span class="mono tp-name">{{ s.table || '(未选表)' }}</span>
            <span class="tp-pri" :title="'来源优先级,越大越权威'">pri {{ s.pri }}</span>
          </div>
          <div class="tp-kv">
            <span class="tp-k">号码列</span>
            <span class="mono">{{ s.mdnCol || '?' }}</span>
            <!-- 有处理链时只报步数不展开:链路细节在编辑页看,这里只回答"这个源洗干净了吗" -->
            <span v-if="s.mdnSteps?.length" class="tp-ok">+{{ s.mdnSteps.length }} 步处理链</span>
          </div>
          <div class="tp-kv">
            <span class="tp-k">时间列</span>
            <template v-if="s.timeCol">
              <span class="mono">{{ s.timeCol }}</span>
            </template>
            <template v-else>
              <!-- 没有时间列就没有"最新"可判,同号码多行只能整片交给优先级决定 -->
              <span class="tp-warn">未配 · 只比优先级</span>
            </template>
          </div>
          <div v-if="s.where" class="tp-kv">
            <span class="tp-k">过滤</span>
            <span class="mono tp-ellipsis" :title="s.where">{{ s.where }}</span>
          </div>

          <!-- 运行状态 -->
          <!--
            下面这堆数字必须有 v-if 兜底:未保存的工作流没有现场可探,
            此时要给「保存后可看…」的说明,而不是一排像出错的空位。
          -->
          <div class="tp-stat">
            <template v-if="statOf(s.id)">
              <span>{{ fmtNum(statOf(s.id)!.rows) }} 行</span>
              <span class="tp-sep">·</span>
              <span>{{ fmtNum(statOf(s.id)!.mdns) }} 号码</span>
              <span v-if="rowsPerMdn(s.id)" class="tp-hint" title="一个号码平均多行:靠时间列/仲裁取一条">
                平均 {{ rowsPerMdn(s.id) }} 行/号码
              </span>
              <!-- 视图缺失是拓扑里最致命的一类断链:这张源之后写入的数据不会进聚合层 -->
              <span class="tp-badge" :class="statOf(s.id)!.mvExists ? 'tp-badge-ok' : 'tp-badge-bad'">
                {{ statOf(s.id)!.mvExists ? '视图已建' : '视图缺失' }}
              </span>
              <!-- 单个源读失败不牵连其它源,只在这一处标红并靠 title 给出原始错误 -->
              <span v-if="statOf(s.id)!.error" class="tp-bad" :title="statOf(s.id)!.error">读源表失败</span>
            </template>
            <!-- 区分两种"没有数字":已探测过但缺这条源的状态 vs 压根没探测过 -->
            <span v-else class="tp-dim">{{ hasProbe ? '未探测' : '保存后可看行数与视图状态' }}</span>
          </div>
        </div>
      </div>
    </div>

    <!-- 汇流箭头:每源一个物化视图 -->
    <div class="tp-edge">
      <div class="tp-edge-line"></div>
      <div class="tp-edge-label">
        每源一个物化视图 · 源表写入即流入
      </div>
      <div class="tp-arrow"></div>
    </div>

    <!-- ② 聚合层 -->
    <div class="tp-layer">
      <div class="tp-layer-tag">
        <span class="tp-dot tp-dot-agg"></span>聚合表
        <span class="tp-dim">中间层 · 不能直接查</span>
      </div>
      <div class="tp-node tp-node-agg tp-node-wide">
        <div class="tp-node-head">
          <span class="mono tp-name">{{ aggTableName }}</span>
          <span v-if="probe" class="tp-badge" :class="probe.aggExists ? 'tp-badge-ok' : 'tp-badge-bad'">
            {{ probe.aggExists ? '已生成' : '未生成' }}
          </span>
        </div>
        <div class="tp-kv">
          <span class="tp-k">目标列</span>
          <span>{{ columns.length }} 个</span>
          <!-- 目标列只有两类语义:仲裁(多源挑一条)与聚合(多源汇总),分开报数便于核对配置 -->
          <span v-if="arbitrateCount" class="tp-dim">仲裁 {{ arbitrateCount }}</span>
          <span v-if="aggCount" class="tp-dim">聚合 {{ aggCount }}</span>
        </div>
        <div class="tp-kv">
          <span class="tp-k">读取方式</span>
          <span class="mono tp-read">max(列).4 … GROUP BY mdn</span>
        </div>
        <div class="tp-stat">
          <template v-if="probe?.aggExists">
            <span>{{ fmtNum(probe!.mdns) }} 号码</span>
            <span class="tp-sep">·</span>
            <span>{{ fmtNum(probe!.rows) }} 物理行</span>
            <span v-if="probe!.rows > probe!.mdns" class="tp-hint" title="AggregatingMergeTree 的 part 尚未合并,属正常">
              未合并 {{ probe!.rows - probe!.mdns }} 行
            </span>
          </template>
          <span v-else class="tp-dim">{{ hasProbe ? '还没生成 —— 点「生成 / 更新」' : '' }}</span>
        </div>
        <!-- 缺视图只丢增量:历史数据仍在,但之后源表新写入的行不会再进这一层 -->
        <div v-if="probe && probe.missingMvs.length" class="tp-alert">
          ⚠️ {{ probe.missingMvs.length }} 个物化视图缺失,源表增量不会流进来
        </div>
        <!--
          配置外的物化视图:删掉一个源之后它还在库里,而且仍在往聚合层写数据。
          不管它会出现「删掉的源又活了」—— 重建后的历史没它,但它之后写入的行会进来。
        -->
        <div v-if="probe && probe.orphanMvs?.length" class="tp-alert tp-alert-orphan">
          ⚠️ {{ probe.orphanMvs.length }} 个物化视图已不在配置里(删掉的源留下的),
          它们仍在往聚合层写数据:{{ probe.orphanMvs.join('、') }}。
          点「生成 / 更新」会自动清掉它们。
        </div>
      </div>
    </div>

    <!-- 摊平箭头:这一段【不是】实时的 —— 要手动或定时触发,是目标表落后于聚合层的根源 -->
    <div class="tp-edge">
      <div class="tp-edge-line"></div>
      <div class="tp-edge-label">
        摊平 · <span class="mono">max(列).4 … GROUP BY mdn</span>
      </div>
      <div class="tp-arrow"></div>
    </div>

    <!-- ③ 目标层 -->
    <div class="tp-layer">
      <div class="tp-layer-tag">
        <span class="tp-dot tp-dot-svc"></span>目标表
        <span class="tp-dim">业务只查这张</span>
      </div>
      <div class="tp-node tp-node-svc tp-node-wide">
        <div class="tp-node-head">
          <span class="mono tp-name">{{ svcTableName }}</span>
          <span v-if="probe" class="tp-badge" :class="probe.svcExists ? 'tp-badge-ok' : 'tp-badge-bad'">
            {{ probe.svcExists ? '已生成' : '未生成' }}
          </span>
        </div>
        <div class="tp-kv">
          <span class="tp-k">用法</span>
          <!-- 摊平后的普通列没有聚合中间态:能直接 WHERE / ORDER BY,不用像聚合层那样套 -Merge 后缀 -->
          <span>普通列,可以直接 <code>WHERE</code> / <code>ORDER BY</code> / 圈选</span>
        </div>
        <div class="tp-stat">
          <!-- 三种情形分开说(已摊平 / 有探测但没摊平 / 没探测),各自对应不同的下一步动作 -->
          <template v-if="probe?.svcExists">
            <span>{{ fmtNum(probe.svcRows) }} 行</span>
            <span v-if="probe.svcModified" class="tp-hint">最后摊平 {{ probe.svcModified }}</span>
          </template>
          <span v-else-if="hasProbe" class="tp-dim">还没摊平 —— 点「生成 / 更新」或「刷新服务表」</span>
          <span v-else class="tp-dim">保存后可看状态</span>
        </div>
        <!--
          落后提示:聚合层由物化视图实时更新,目标表只能手动/定时摊平,两者天然差一段时间。
          用两张表的最后写入时间比较(比只看行数准:行数一样但内容旧的情况也有)。
        -->
        <div v-if="svcStale" class="tp-alert">
          ⚠️ 目标表落后于聚合表（聚合 {{ probe?.aggModified || '—' }} / 目标 {{ probe?.svcModified || '—' }}）——
          聚合层已更新,点「刷新服务表」把最新数据摊平下来
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { ArbColumn, ArbProbe, ArbSource } from '@/types'

const props = defineProps<{
  sources: ArbSource[]
  columns: ArbColumn[]
  /** 聚合表全名(不含库),如 dwd_hahaha_agg */
  aggTableName: string
  /** 目标表全名(不含库),如 dwd_hahaha_svc */
  svcTableName: string
  /** 现场状态;新建(未保存)时没有 */
  probe?: ArbProbe | null
}>()

/** 探测结果整体缺席(新建未保存)与"探测了但没这条"是两回事,模板里靠它区分话术 */
const hasProbe = computed(() => !!props.probe)
/** 停用的源不供数,不参与拓扑;它在配置里仍然存在,只是不该出现在这张图上 */
const activeSources = computed(() => props.sources.filter((s) => s.enabled))
/** 两类目标列的计数:mode 只有 arbitrate / 其余(聚合)两种,所以用 !== 取补集 */
const arbitrateCount = computed(() => props.columns.filter((c) => c.mode === 'arbitrate').length)
const aggCount = computed(() => props.columns.filter((c) => c.mode !== 'arbitrate').length)

/** 按 sourceId 找状态而不是按数组下标:源的增删会让下标错位,id 才稳定;缺状态返回 undefined */
const statOf = (sourceId: string) => props.probe?.sources?.find((s) => s.sourceId === sourceId)

/**
 * 服务表是否落后于聚合表(按最后写入时间比)。
 *
 * 用时间而不是行数:聚合层由物化视图实时更新、目标表只能手动摊平,行数一样但内容旧的情况存在。
 * 缺 svcModified 时判为落后 —— 宁可多提示一次,也不要让人以为数据是新的。
 */
const svcStale = computed(() => {
  const p = props.probe
  if (!p?.svcExists || !p.aggModified) return false
  if (!p.svcModified) return true
  return p.aggModified > p.svcModified
})

/** 源表「平均几行一个号码」—— 大于 1 就说明这个源一个号码多行,仲裁在替你去重 */
function rowsPerMdn(sourceId: string): string {
  const st = statOf(sourceId)
  // 没有号码数就除不出平均值;行数为 0 也没有意义(0/x = 0)
  if (!st || !st.mdns || !st.rows) return ''
  const r = st.rows / st.mdns
  // 阈值 1.05:略大于 1 属于分组误差或个别重复,还不到"需要仲裁去重"的程度,报出来只是噪音
  return r >= 1.05 ? r.toFixed(1) : ''
}

/** 大数折成"万":百万级的行数直接读数字还得数位数 */
function fmtNum(n?: number) {
  if (n === undefined || n === null) return '—'
  if (n >= 10000) return `${(n / 10000).toFixed(1)} 万`
  return String(n)
}
</script>

<style scoped>
.tp {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 6px 4px 2px;
}
/* ── 每一层:层标签 + 节点 ── */
.tp-layer {
  width: 100%;
}
.tp-layer-tag {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  font-weight: 600;
  margin-bottom: 6px;
}
.tp-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  display: inline-block;
}
.tp-dot-src {
  background: var(--el-text-color-secondary);
}
.tp-dot-agg {
  background: var(--brand, #d97706);
}
.tp-dot-svc {
  background: var(--el-color-primary);
}
/* 源:横向铺开,多了自动换行 —— 视觉上就是"多张源汇成一张表" */
.tp-srcs {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}
/* ── 节点(流程图里的方框) ── */
.tp-node {
  flex: 1 1 260px;
  min-width: 240px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 10px;
  padding: 9px 11px;
  background: var(--el-bg-color);
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.04);
  border-left-width: 3px;
}
.tp-node-wide {
  flex: 1 1 auto;
  width: 100%;
}
.tp-node-src {
  border-left-color: var(--el-border-color);
}
.tp-node-agg {
  border-left-color: var(--brand, #d97706);
  background: linear-gradient(180deg, rgba(217, 119, 6, 0.05), transparent 60%);
}
.tp-node-svc {
  border-left-color: var(--el-color-primary);
  background: linear-gradient(180deg, rgba(64, 158, 255, 0.06), transparent 60%);
}
.tp-node-off {
  opacity: 0.5;
}
.tp-node-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}
.tp-idx {
  width: 18px;
  height: 18px;
  line-height: 18px;
  text-align: center;
  border-radius: 50%;
  font-size: 12px;
  flex: none;
  background: var(--el-fill-color);
  color: var(--el-text-color-secondary);
}
.tp-name {
  font-weight: 600;
  font-size: 14px;
}
.tp-pri {
  margin-left: auto;
  font-size: 13px;
  color: var(--brand, #d97706);
}
.tp-kv {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  font-size: 13px;
  line-height: 1.9;
  color: var(--el-text-color-primary);
}
.tp-k {
  color: var(--el-text-color-secondary);
  flex: none;
  min-width: 42px;
}
.tp-read {
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
.tp-ellipsis {
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.tp-stat {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 6px;
  padding-top: 6px;
  border-top: 1px dashed var(--el-border-color-lighter);
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
.tp-sep {
  opacity: 0.5;
}
.tp-dim {
  font-size: 13px;
  color: var(--el-text-color-placeholder);
}
.tp-ok {
  color: var(--el-color-success);
}
.tp-warn {
  color: var(--el-color-warning);
}
.tp-bad {
  color: var(--el-color-danger);
}
.tp-hint {
  opacity: 0.85;
}
.tp-badge {
  margin-left: auto;
  padding: 0 6px;
  border-radius: 3px;
  font-size: 12px;
  flex: none;
}
.tp-badge-ok {
  background: var(--el-color-success-light-9);
  color: var(--el-color-success);
}
.tp-badge-bad {
  background: var(--el-color-danger-light-9);
  color: var(--el-color-danger);
}
.tp-alert {
  margin-top: 6px;
  font-size: 13px;
  line-height: 1.7;
  color: var(--el-color-warning);
}
/* 配置外的视图:比「缺失」更急 —— 它是在往数据里偷偷写东西,不只是不流进来 */
.tp-alert-orphan {
  color: var(--el-color-danger);
}
/* ── 层之间的连接:竖线 + 标签 + 箭头 ── */
.tp-edge {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 2px 0;
}
.tp-edge-line {
  width: 2px;
  height: 14px;
  background: var(--el-border-color);
}
.tp-edge-label {
  margin: 2px 0;
  padding: 2px 10px;
  font-size: 13px;
  border-radius: 10px;
  background: var(--el-fill-color-light);
  color: var(--el-text-color-secondary);
}
.tp-edge-label .mono {
  font-size: 13px;
}
/* 箭头:纯 CSS 三角,不用图标 */
.tp-arrow {
  width: 0;
  height: 0;
  border-left: 6px solid transparent;
  border-right: 6px solid transparent;
  border-top: 8px solid var(--el-border-color);
}
</style>
