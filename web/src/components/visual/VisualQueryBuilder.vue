<!--
  VisualQueryBuilder.vue

  职责：可视化查询构建器：组合来源、字段、过滤、排序和预览状态。
  边界：组合来源、字段、过滤、排序和 SQL 预览；执行开关与外部 SQL 差异由 props 控制，页签高度和打开 SQL 通过事件交回页面。
-->

<template>
  <div class="vb-wrap">
    <!-- ═══ 左:数据源树(已配多表关联时隐藏 —— 表和列都在分组面板里勾选,树几无用处,腾出空间) ═══ -->
    <aside v-if="!vq.multiTable.value" class="vb-side">
      <SourceTreePanel
        ref="treeRef"
        :conn-id="connId"
        :database="database"
        :active-table="vq.table.value"
        :selected-tables="vq.sources.value.map((x) => x.table)"
        :added-cols="addedColsByTable"
        @select-table="onSelectTable"
        @join-table="onJoinTable"
        @add-field="onAddField"
        @add-fields="onAddFields"
      />
    </aside>

    <!-- ═══ 中:构建区 ═══ -->
    <main class="vb-center">
      <!-- 顶栏:主表 + 主操作 -->
      <header class="vb-bar">
        <!-- 主表是整个查询的锚点:字段来源、关联键对齐都相对它,没选表时下面几张卡片全是空态 -->
        <el-tag v-if="vq.table.value" size="small" effect="plain" type="warning" class="vb-table-tag">
          主表 <b class="mono">{{ vq.table.value }}</b>
        </el-tag>
        <el-tag v-else size="small" effect="plain" type="info">未选表</el-tag>
        <!-- 探索向导入口:向导式一次性完成选表→关联键→输出列→过滤排序 -->
        <el-button
          size="small"
          :icon="MagicStick"
          type="primary"
          plain
          class="vb-wizard-btn"
          @click="openWizardAt(1)"
        >
          探索向导<template v-if="vq.sources.value.length">({{ vq.sources.value.length }} 表)</template>
        </el-button>
        <!-- 结果区还停在上一次运行的数据:任何配置改动都会置脏,只有运行成功后才由父组件清掉 -->
        <el-tag
          v-if="vq.resultStale.value"
          size="small"
          effect="plain"
          type="warning"
          class="vb-stale"
        >
          配置已改 · 下方结果未更新
        </el-tag>
        <el-tag
          v-if="diverged"
          size="small"
          effect="plain"
          type="info"
          class="vb-stale"
          title="你在 SQL 模式手改过 SQL。可视化配置只反映构建器状态,不会解析你手写的 SQL。"
        >
          SQL 已手改 · 与可视化不同步
        </el-tag>
        <span class="vb-bar-spacer"></span>

        <el-tooltip content="重新生成 SQL" placement="bottom">
          <el-button size="small" text :icon="Refresh" :loading="vq.generating.value" @click="vq.refreshSQL()" />
        </el-tooltip>
        <el-tooltip content="复制 SQL" placement="bottom">
          <el-button size="small" text :icon="DocumentCopy" :disabled="!vq.sql.value" @click="copySql" />
        </el-tooltip>
        <el-tooltip content="在 SQL 模式打开,继续手工编辑" placement="bottom">
          <el-button size="small" text :icon="EditPen" :disabled="!vq.sql.value" @click="emit('open-in-sql', vq.sql.value)" />
        </el-tooltip>
        <el-button
          size="small"
          type="primary"
          :icon="VideoPlay"
          :disabled="props.executionEnabled === false || !vq.canGenerate.value"
          class="vb-run-btn"
          @click="onRun"
        >
          运行
        </el-button>
      </header>

      <!-- 提示:紧跟顶栏,始终可见 -->
      <!-- 刻意放在滚动容器之外:待改的配置往往就在下面几屏,提示跟着滚走等于没有 -->
      <div
        v-if="visibleWarnings.length || vq.notices.value.length"
        class="vb-tips"
        :class="{ 'only-info': !visibleWarnings.length }"
      >
        <div v-for="(w, i) in visibleWarnings" :key="'w' + i" class="vb-tip">
          <el-icon><Warning /></el-icon><span>{{ w }}</span>
        </div>
        <!-- 非阻塞提示:这些不会阻止执行(如「条件还没填值,已忽略」),用中性色区分 -->
        <div v-for="(n, i) in vq.notices.value" :key="'n' + i" class="vb-tip info">
          <el-icon><InfoFilled /></el-icon><span>{{ n }}</span>
        </div>
      </div>

      <!-- 卡片区 -->
      <!-- 整个构建区都是拖拽投放目标:左侧树的列拖到任意位置都算「加为输出字段」 -->
      <div
        class="vb-scroll"
        :class="{ 'drop-active': dropActive }"
        @dragover.prevent="onDragOver"
        @dragleave="onDragLeave"
        @drop.prevent="onDrop"
      >
        <!-- 四个页签就是配置主流程;页签本身不做拦截,缺什么由顶栏警告与「运行」置灰兜底 -->
        <el-tabs v-model="fieldTab" class="vb-tabs">
        <!-- 「关联」页签只在多表时存在:单表没有 JOIN,留着会让人以为必须先配关联 -->
        <el-tab-pane v-if="vq.multiTable.value" name="join">
        <template #label>
          <span class="vb-tab"><span class="vb-tab-no">①</span>关联
            <span class="vb-tab-count">{{ vq.sources.value.length }} 表</span>
          </span>
        </template>
        <section class="vb-card vb-join">
          <div class="vb-join-top">
            <p class="vb-join-hint">
              多表按<b>关联键</b>等值关联 —— 每张表各选一列(通常是手机号),归一化后按
              <code>mdn_norm</code> 对齐。键格式不一致时点该表的<b>处理链</b>配归一化。
            </p>
            <div class="vb-join-acts">
              <el-button size="small" type="primary" :icon="MagicStick" @click="openWizardAt(2)">
                配置关联关系
              </el-button>
              <!-- 到上限直接置灰而不是点了再报错:addSource 同样会拒,但按钮先变灰意图更明确 -->
              <el-button size="small" :icon="Plus" plain :disabled="vq.sources.value.length >= 6" @click="addJoinTableOpen = true">
                加关联表
              </el-button>
            </div>
          </div>

          <!-- 关系图:主表在左,关联表逐行连线;每行可就地改键/配处理链/移除 -->
          <!-- 内层包裹:外层只管横向滚动,内层负责居中 —— 直接给可滚动容器
               加 justify-content:center 会在内容超宽时把左侧裁到滚不到的地方 -->
          <div class="vb-graph">
            <div class="vb-graph-inner">
            <div class="vb-graph-left">
              <div class="vb-gnode main">
                <div class="vb-gnode-head">
                  <el-tag size="small" type="warning" effect="plain">主表</el-tag>
                  <span class="mono vb-gnode-name" :title="vq.sources.value[0]?.table">{{ vq.sources.value[0]?.table }}</span>
                </div>
                <div class="vb-gnode-row">
                  <span class="vb-gnode-label">关联键</span>
                  <el-select
                    :model-value="vq.sources.value[0]?.joinCol"
                    size="small"
                    filterable
                    class="vb-gnode-sel"
                    placeholder="选列"
                    @update:model-value="(v: string) => vq.setJoinCol(0, v)"
                  >
                    <el-option v-for="c in vq.sources.value[0]?.columns ?? []" :key="c.name" :value="c.name" :label="c.name" />
                  </el-select>
                </div>
                <div class="vb-gnode-row vb-gnode-row-chain">
                  <span class="vb-gnode-label">处理链</span>
                  <div
                    class="vb-chain"
                    :class="{ empty: !(vq.sources.value[0]?.joinSteps.length ?? 0) }"
                    :title="chainTitle(vq.sources.value[0]?.joinSteps)"
                    @click="openJoinSteps(0)"
                  >
                    <template v-if="vq.sources.value[0]?.joinSteps.length">
                      <span v-for="(st, ci) in vq.sources.value[0].joinSteps" :key="ci" class="vb-chain-chip mono">
                        {{ opLabel(st.fn) }}
                      </span>
                    </template>
                    <span v-else>未配置(格式一致可不动)</span>
                    <el-icon class="vb-chain-edit"><EditPen /></el-icon>
                  </div>
                </div>
              </div>
            </div>

            <!-- 逐行一个关联表:slice(1) 跳过主表,所以行内下标都要 +1 才是 sources 的下标 -->
            <div class="vb-graph-right">
              <div v-for="(src, si) in vq.sources.value.slice(1)" :key="si" class="vb-gpair">
                <div class="vb-glink" :class="{ ok: !!src.joinCol && !!vq.sources.value[0]?.joinCol }">
                  <span class="vb-glink-label mono">
                    {{ vq.sources.value[0]?.joinCol || '?' }} ⟷ {{ src.joinCol || '?' }}
                  </span>
                </div>
                <div class="vb-gnode">
                  <div class="vb-gnode-head">
                    <el-tag size="small" effect="plain">关联表</el-tag>
                    <span class="mono vb-gnode-name" :title="src.table">{{ src.table }}</span>
                    <el-button size="small" link type="danger" title="移除该表" @click="vq.removeSource(si + 1)">✕</el-button>
                  </div>
                  <div class="vb-gnode-row">
                    <span class="vb-gnode-label">关联键</span>
                    <el-select
                      :model-value="src.joinCol"
                      size="small"
                      filterable
                      class="vb-gnode-sel"
                      placeholder="选列"
                      @update:model-value="(v: string) => vq.setJoinCol(si + 1, v)"
                    >
                      <el-option v-for="c in src.columns" :key="c.name" :value="c.name" :label="c.name" />
                    </el-select>
                  </div>
                  <div class="vb-gnode-row vb-gnode-row-chain">
                    <span class="vb-gnode-label">处理链</span>
                    <div
                      class="vb-chain"
                      :class="{ empty: !src.joinSteps.length }"
                      :title="chainTitle(src.joinSteps)"
                      @click="openJoinSteps(si + 1)"
                    >
                      <template v-if="src.joinSteps.length">
                        <span v-for="(st, ci) in src.joinSteps" :key="ci" class="vb-chain-chip mono">
                          {{ opLabel(st.fn) }}
                        </span>
                      </template>
                      <span v-else>未配置(格式一致可不动)</span>
                      <el-icon class="vb-chain-edit"><EditPen /></el-icon>
                    </div>
                  </div>
                </div>
              </div>
            </div>
            </div>
          </div>

          <p class="vb-join-note">
            关联方式默认按各表关联键自动对齐(LEFT JOIN,保留主表全部行),不需要手写 ON 条件;
            需要自定义写法请切到 <b>SQL 模式</b>。
          </p>
        </section>
        </el-tab-pane>
        <!-- ② 输出字段:整块渲染交给 FieldRuleTable,本组件只转发数据与事件(状态全在 useVisualQuery) -->
        <el-tab-pane name="fields">
        <template #label>
          <span class="vb-tab"><span class="vb-tab-no">②</span>输出字段
            <span v-if="vq.fields.value.length" class="vb-tab-count">{{ vq.fields.value.length }}</span>
          </span>
        </template>
        <section class="vb-card">
          <FieldRuleTable
            :collapse-signal="collapseSignal"
      @collapse-change="(v: boolean) => emit('field-panel', v)"
            :fields="vq.fields.value"
            :has-agg="vq.hasAgg.value"
            :kind-of="kindOf"
            :col-type="colType"
            :col-comment="colComment"
            :sources="vq.sources.value"
            :cols-of="colsOf"
            @set-source="vq.setFieldSource"
            @set-col="vq.setFieldCol"
            @set-hidden="vq.setFieldHidden"
            @add-first="onAddFirst"
            @remove="vq.removeField"
            @move="vq.moveField"
            @set-agg="vq.setAgg"
            @set-steps="vq.setSteps"
            @set-prog="vq.setProg"
            @set-alias="vq.setAlias"
            @toggle-col="onToggleCol"
            @toggle-all="onToggleAll"
            @retry-cols="onRetryCols"
          />
        </section>

        </el-tab-pane>
        <!-- ③ 过滤(WHERE):聚合【之前】按行筛;与下面 HAVING 是两份互不相干的规则列表 -->
        <el-tab-pane name="filter">
        <template #label>
          <span class="vb-tab"><span class="vb-tab-no">③</span>过滤条件
            <span v-if="vq.where.value.length" class="vb-tab-count">{{ vq.where.value.length }}</span>
          </span>
        </template>
        <section class="vb-card">
          <FilterRuleList
            :where="vq.where.value"
            :fields="selectableFields"
            :kind-of="kindOf"
            :has-table="!!vq.table.value"
            @add="vq.addWhere()"
            @remove="vq.removeWhere"
            @set-column="vq.setWhereColumn"
            @set-op="(i, v) => setWhereField(i, 'op', v)"
            @set-preset="(i, v) => setWhereField(i, 'preset', v)"
            @set-value="(i, v) => setWhereField(i, 'value', v)"
            @set-value2="(i, v) => setWhereField(i, 'value2', v)"
          />
        </section>

        <!--
          聚合后过滤(HAVING)。
          ⚠️ 卡片显示条件里必须带上 having.value.length —— 只看 hasAgg 的话,
             把聚合取消后卡片整个消失(连同删除按钮),而条件还在、warnings
             还在卡死执行 → 界面上没有任何入口能删掉它(死状态)。
        -->
        <section v-if="vq.hasAgg.value || vq.having.value.length" class="vb-card">
          <div class="vb-card-head">
            <span class="vb-card-title">聚合后过滤(HAVING)</span>
            <span class="vb-card-hint">对聚合结果再筛,例:计数 &gt; 100 的城市</span>
          </div>
          <div v-if="!vq.havingUsable.value" class="vb-note">
            当前「结果粒度」是<b>保持明细</b>,HAVING 只在<b>汇总统计</b>下有意义 ——
            请先在右侧 ④ 结果粒度切回去。
            <!-- 条件残留时给一条出路:否则用户既看不到它也删不掉,只能去改聚合字段 -->
            <div v-if="vq.having.value.length" class="vb-note-act">
              已残留 <b>{{ vq.having.value.length }}</b> 条聚合后过滤条件(它们会阻止执行)。
              <el-button size="small" link type="danger" @click="vq.clearHaving()">全部清除</el-button>
            </div>
          </div>
          <FilterRuleList
            v-else
            :where="vq.having.value"
            hint="作用在分组聚合【之后】;按行筛请用上面的「过滤条件」(WHERE)"
            empty-text="没有聚合后过滤条件(返回全部聚合结果)"
            :fields="selectableFields"
            :kind-of="kindOf"
            :has-table="!!vq.table.value"
            @add="vq.addHaving()"
            @remove="vq.removeHaving"
            @set-column="vq.setHavingColumn"
            @set-op="(i, v) => setHavingField(i, 'op', v)"
            @set-preset="(i, v) => setHavingField(i, 'preset', v)"
            @set-value="(i, v) => setHavingField(i, 'value', v)"
            @set-value2="(i, v) => setHavingField(i, 'value2', v)"
          />
        </section>

        </el-tab-pane>
        <!-- ④ 排序:除了决定结果顺序,也决定「按列去重时留下哪一条」(留第一条) -->
        <el-tab-pane name="order">
        <template #label>
          <span class="vb-tab"><span class="vb-tab-no">④</span>排序
            <span v-if="vq.orderBy.value.length" class="vb-tab-count">{{ vq.orderBy.value.length }}</span>
          </span>
        </template>
        <section class="vb-card">
          <OrderRuleList
            :order-by="vq.orderBy.value"
            :fields="selectableFields"
            :has-table="!!vq.table.value"
            @add="vq.addOrderBy"
            @remove="vq.removeOrderBy"
            @set-column="(i, v) => setOrderField(i, 'column', v)"
            @set-desc="(i, v) => setOrderField(i, 'desc', v)"
          />
        </section>
        </el-tab-pane>
        </el-tabs>

        <!-- 关联键处理链编辑(sqlOnly:关联键的表达式直接进 SQL 参与 JOIN,
             程序算子是结果返回后才算的,对关联键没有意义) -->
        <el-dialog
          v-model="joinStepsVisible"
          :title="joinStepsSource
            ? `关联键处理链 · ${joinStepsSource.table}.${joinStepsSource.joinCol}`
            : '关联键处理链'"
          class="vb-join-dlg"
          width="min(760px, calc(100vw - 32px))"
          top="5vh"
          append-to-body
          destroy-on-close
        >
          <div v-if="joinStepsSource" class="vb-join-dlg-body">
            <!-- 编辑即时写回 source(没有本地副本):关掉弹窗后关系图上的算子芯片要立刻是新的 -->
            <FieldPipelineEditor
              :col="joinStepsSource.joinCol"
              :kind="kindOf(joinStepsSource.joinCol, joinStepsIdx ?? 0)"
              :steps="joinStepsSource.joinSteps"
              :prog="[]"
              sql-only
              @update:steps="setJoinSteps"
            />
          </div>
          <template #footer>
            <el-button @click="joinStepsVisible = false">完成</el-button>
          </template>
        </el-dialog>

        <!-- 脚注兜底说明两种添加方式与处理链的能力边界,省得用户为了「为什么只能单参数」去翻文档 -->
        <div class="vb-footnote">
          <template v-if="vq.multiTable.value">
            输出字段在「② 输出字段」分组区勾选(或拖入表格);&nbsp;
          </template>
          <template v-else>
            字段可点击或从左侧拖入;&nbsp;
          </template>
          处理链只支持<b>单参数</b>算子,复杂写法请切到 <b>SQL 模式</b>。
        </div>
      </div>
    </main>

    <!-- ═══ 右:设置 + SQL ═══ -->
    <!-- 右栏可整体收起:收起后只留一条竖边,把宽度让给中间的构建区(窄屏下尤其需要) -->
    <aside class="vb-right" :class="{ collapsed: rightCollapsed }">
      <!-- 独立的头部行:收起按钮不再绝对定位,避免压住 ④ 卡片 -->
      <div class="vb-right-head">
        <span v-if="!rightCollapsed" class="vb-right-title">设置</span>
        <el-tooltip :content="rightCollapsed ? '展开设置与 SQL' : '收起'" placement="left">
          <button class="vb-right-toggle" @click="rightCollapsed = !rightCollapsed">
            <el-icon><Fold v-if="!rightCollapsed" /><Expand v-else /></el-icon>
          </button>
        </el-tooltip>
      </div>

      <!-- 用 v-show 保住 DOM:来回收起展开时 SQL 预览组件不重建,不丢滚动位置也不闪一下空态 -->
      <div v-show="!rightCollapsed" class="vb-right-inner">
        <!-- 结果粒度(最重要的概念,提到最上) -->
        <section class="vb-card">
          <div class="vb-card-head">
            <span class="vb-card-no">④</span>
            <span class="vb-card-title">结果粒度</span>
          </div>
          <div class="vb-card-body">
            <el-radio-group v-model="vq.granularity.value" class="vb-grain" @change="vq.scheduleRefresh()">
              <!-- 无聚合字段时「汇总统计」和「保持明细」生成的 SQL 完全等价,禁用掉省得选了却看不出区别 -->
              <el-radio value="auto" :disabled="!vq.hasAgg.value">汇总统计</el-radio>
              <el-radio value="detail">保持明细</el-radio>
            </el-radio-group>

            <!--
              去重:按某些列各保留一行,其它列原样带出来(后端生成 ClickHouse 的 LIMIT 1 BY)。
              这是"按号码去重后的数据列表"的正解 —— 汇总统计会把其它列折叠成聚合值,
              而 DISTINCT 要求每一列都参与去重,都做不到"键去重 + 其它列保留原值"。
            -->
            <div v-if="vq.granularity.value === 'detail'" class="vb-dedup">
              <el-select v-model="vq.distinctKeys.value" multiple filterable collapse-tags clearable
                placeholder="按哪些列去重?不选 = 不去重" style="width: 100%" @change="vq.scheduleRefresh()">
                <el-option v-for="f in vq.detailFields.value" :key="f.alias" :value="f.alias" :label="f.alias" />
              </el-select>
              <div class="vb-grain-note">
                按选中的列各留一行,其它列原样带出来。想要「每个号码留最新那条」——
                先去「排序」加上 <b class="mono">时间列 降序</b>,再在这里选 <b class="mono">号码列</b>(留哪一条由排序决定)。
              </div>
            </div>

            <!-- 把 GROUP BY 的效果翻译成一句话回显:用户不必靠读 SQL 才能确认自己配出了什么粒度 -->
            <div class="vb-grain-note">
              <template v-if="vq.groupByLabels.value.length">
                每个
                <b v-for="(gname, i) in vq.groupByLabels.value" :key="gname" class="mono">
                  {{ gname }}<template v-if="i < vq.groupByLabels.value.length - 1">, </template>
                </b>
                一行
              </template>
              <template v-else-if="vq.granularity.value === 'detail'">
                不分组,返回明细行
              </template>
              <template v-else> 添加聚合函数后,未聚合的字段会成为分组依据 </template>
            </div>
          </div>
        </section>

        <!-- 查询设置 -->
        <section class="vb-card">
          <div class="vb-card-head">
            <span class="vb-card-no">⑤</span>
            <span class="vb-card-title">查询设置</span>
          </div>
          <div class="vb-card-body vb-set-row">
            <span class="vb-set-label">限制行数</span>
            <!-- 上限 1000 万:再大结果区的表格和浏览器内存都撑不住,真正的大批量走导出 -->
            <el-input-number
              v-model="vq.limit.value"
              size="small"
              :min="0"
              :max="10000000"
              :step="100"
              controls-position="right"
              class="vb-limit"
              @change="vq.scheduleRefresh()"
            />
          </div>
          <div class="vb-set-hint">0 = 不限制(大表慎用)</div>
        </section>

        <!-- SQL 常驻 -->
        <!-- 常驻而不是弹窗:改任何一处配置都能立刻看到 SQL 怎么变,这是可视化构建器的主要反馈回路 -->
        <section class="vb-card vb-card-sql">
          <div class="vb-card-head">
            <span class="vb-card-title">生成的 SQL</span>
            <span class="vb-sql-status" :class="sqlStatusClass">{{ sqlStatusText }}</span>
          </div>
          <!--
            ⚠️ 错误必须把原文显示出来。以前只用 sqlError 判断状态文案(显示「出错」),
              模板里从不渲染它 → 用户看到的是「配置看着没问题、但 SQL 一直不出来」,
              只有点「运行」弹一下 toast 才知道原因。
          -->
          <div v-if="vq.sqlError.value" class="vb-sql-err">{{ vq.sqlError.value }}</div>
          <!-- 下三行是互斥的三态:错误原文 → 有 SQL → 尚未配置的占位,不会同时出现两个 -->
          <SqlCode v-if="vq.sql.value" :sql="vq.sql.value" size="sm" class="vb-sql-body" />
          <pre v-else-if="!vq.sqlError.value" class="vb-sql-empty mono">— 配置完成后在此显示 —</pre>
        </section>
      </div>
    </aside>
    <!-- ═══ 关联页签的加表选择器 ═══ -->
    <!-- 只在首次打开时拉一次全库表名,之后本地过滤:库里表多时每敲一个字发一次请求体验很差 -->
    <el-dialog
      v-model="addJoinTableOpen"
      title="加关联表"
      width="420px"
      append-to-body
      :close-on-click-modal="false"
    >
      <el-input v-model="addJoinKw" size="small" clearable placeholder="搜索表名 / 注释">
        <template #prefix><el-icon><Search /></el-icon></template>
      </el-input>
      <div v-loading="addJoinLoading" class="wz-add-list" style="margin-top: 8px">
        <div
          v-for="t in addJoinCandidates"
          :key="t.name"
          class="vb-addjoin-item"
          @click="onAddJoinTable(t.name)"
        >
          <span class="mono">{{ t.name }}</span>
          <span v-if="t.remark" class="vb-addjoin-remark" :title="t.remark">{{ t.remark }}</span>
        </div>
        <el-empty v-if="!addJoinLoading && !addJoinCandidates.length" description="没有可加的表" :image-size="40" />
      </div>
    </el-dialog>

    <!-- ═══ 探索向导弹窗 ═══ -->
    <!-- 向导与构建器是「一次性回写」关系:只有点生成才写回,中途取消不动当前配置 -->
    <!-- 空状态点树进来时只带那一张预选表;其余入口回显当前已配的源表,让向导从现状继续 -->
    <ExploreWizard
      v-model="wizardVisible"
      :conn-id="connId"
      :database="database"
      :start-step="wizardStartStep"
      :existing-tables="wizardPreset.length ? wizardPreset : vq.sources.value.map((s) => s.table)"
      :existing-sources="wizardExistingSources"
      :existing-fields="wizardExistingFields"
      @apply="onWizardApply"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import {
  DocumentCopy,
  EditPen,
  MagicStick,
  Expand,
  Fold,
  InfoFilled,
  Plus,
  Refresh,
  Search,
  VideoPlay,
  Warning
} from '@element-plus/icons-vue'
import SqlCode from '@/components/SqlCode.vue'
import SourceTreePanel from './SourceTreePanel.vue'
import ExploreWizard from './ExploreWizard.vue'
import FieldRuleTable from './FieldRuleTable.vue'
import FilterRuleList from './FilterRuleList.vue'
import OrderRuleList from './OrderRuleList.vue'
import FieldPipelineEditor from './FieldPipelineEditor.vue'
import { useVisualQuery, type BuilderColumn } from '@/composables/useVisualQuery'
import { workspaceApi } from '@/api'
import { opShortName, PIPELINE_OP_MAP, type ValueKind } from '@/constants/queryBuilderMeta'
import type { QueryPipelineStep, QueryWorkflowWhere } from '@/types'
import { mergeWizardFields } from '@/utils/wizardFields'

const props = defineProps<{
  executionEnabled?: boolean
  /**
   * 上层(数据探索页)下发的「全部收起」信号:数字一变就把「字段选择」「字段配置」两块一起收起。
   * 用途:点「运行」时自动收起字段面板,把高度立刻还给下面的查询结果。
   */
  collapseSignal?: number
  connId: number
  database: string
  /** SQL 模式编辑器里的内容,用于检测「手改过 SQL」导致两边不一致 */
  externalSql?: string
}>()

const emit = defineEmits<{
  (e: 'open-in-sql', sql: string): void
  (e: 'run', sql: string): void
  /** 配置变化(JSON),父组件用于写本地草稿 */
  (e: 'change', json: string): void
  /** 字段面板的折叠状态:是否有块展开着(收起时把高度让给结果区) */
  (e: 'field-panel', anyOpen: boolean): void
}>()


const vq = useVisualQuery(
  computed(() => props.connId),
  computed(() => props.database)
)
const treeRef = ref<any>(null)
const dropActive = ref(false)
const rightCollapsed = ref(false)
/** 输出字段 / 过滤条件 / 排序 的选项卡 */
const fieldTab = ref<'fields' | 'join' | 'filter' | 'order'>('fields')


// —— 探索向导 ——
const wizardVisible = ref(false)
/** 向导打开时要跳到的步骤(2 = 直接进关系图) */
const wizardStartStep = ref(1)
// 只有一个弹窗实例,靠 start-step 决定落在哪一步:1 = 从选表起步,2 = 直奔关联关系图
function openWizardAt(step: number) {
  wizardStartStep.value = step
  wizardVisible.value = true
}
/**
 * 带入构建器已有的关联配置(键 + 处理链)。
 *
 * ⚠️ 不带的话:向导重建表对象后 joinCol/joinSteps 都是空,
 *    点「生成查询」回写就会把已经配好的关联抹掉。
 */
const wizardExistingSources = computed(() =>
  vq.sources.value.map((s) => ({
    table: s.table,
    joinCol: s.joinCol,
    joinSteps: s.joinSteps
  }))
)
/** 带入已选输出字段(向导第 3 步回显勾选;表名 → 列名由向导自己按表反查) */
const wizardExistingFields = computed(() =>
  vq.fields.value
    .map((f) => ({
      table: vq.sources.value[f.sourceIdx ?? 0]?.table ?? '',
      col: f.sourceCol
    }))
    .filter((f) => !!f.table && !!f.col)
)
/** 空状态点表时带入的预选表(传给向导) */
const wizardPreset = ref<string[]>([])

/** 向导「生成查询」:写入构建器(过滤/排序由向导带回) */
async function onWizardApply(
  picked: { name: string; joinCol: string; joinSteps?: QueryPipelineStep[] }[],
  wizFields: { table: string; col: string; alias: string }[],
  wizWhere: { column: string; op: string; value: string; value2?: string }[],
  wizOrder: { column: string; desc: boolean }[]
) {
  // 「表名」→ sourceIdx,向导勾选顺序即输出顺序
  const idxByTable = new Map(picked.map((p, i) => [p.name, i]))
  // 增量模式:向导没勾输出列(只在关联页改键/加表时可能发生)→ 保留旧字段
  const keepFields = !wizFields.length && vq.fields.value.length > 0
  // 向导第 3 步只勾「要哪些列」,不知道列上还挂着什么 ——
  // 必须把【同一列】(源表名 + 列名)上已有的 hidden / 处理链 / 程序算子 / 聚合
  // 原样带过来,否则跑一趟向导就把脱敏链静默清空、SQL 退回明文投影。
  // 合并规则与「哪些列的配置被丢弃」的计数都在 mergeWizardFields 里(有单测)。
  const { fields, droppedConfigured } = mergeWizardFields(
    vq.fields.value,
    (i) => vq.sources.value[i]?.table ?? '',
    wizFields,
    idxByTable
  )
  // 增量模式(向导没勾输出列)下传下去的 fields 被忽略,丢弃计数也就没有意义
  const dropped = keepFields ? 0 : droppedConfigured
  // 向导带回的过滤/排序:没配时保留旧的(增量模式);带了就覆盖
  // 无论新旧,条件引用的 alias 都必须还在输出列里 —— 向导里取消勾选的列会留下悬空条件,
  // 后端会以「引用了不存在的列」直接拒绝,所以统一按 alias 集合先过滤一道
  const oldAliases = new Set(vq.fields.value.map((f) => f.alias))
  const validAlias = fields.length ? new Set(fields.map((f) => f.alias)) : oldAliases
  const where = wizWhere.length
    ? wizWhere
        .filter((w) => validAlias.has(w.column))
        .map((w) => ({
          column: w.column,
          op: w.op,
          value: w.value,
          ...(w.value2 !== undefined ? { value2: w.value2 } : {})
        }))
    : vq.where.value.filter((w) => validAlias.has(w.column))
  const orderBy = wizOrder.length
    ? wizOrder.filter((o) => validAlias.has(o.column))
    : vq.orderBy.value.filter((o) => validAlias.has(o.column))
  // fields 传 undefined 才是「保留原字段」:传空数组会被 applyWizard 当成清空输出列
  await vq.applyWizard(picked, {
    fields: keepFields ? undefined : fields,
    where,
    orderBy
  })
  // 清掉预选:下次从别的入口打开向导时,不该还带着上一次点过的表
  wizardPreset.value = []
  ElMessage.success(
    (picked.length > 1
      ? `已生成 ${picked.length} 表关联 × ${fields.length} 列${where.length ? ` · ${where.length} 条过滤` : ''},可直接运行`
      : `已生成 ${fields.length} 列查询,可直接运行`) +
      (dropped > 0 ? `(${dropped} 列原有的处理链/聚合因不在本次勾选里已移除)` : '')
  )
  // 落到「输出字段」页签:用户刚勾完列,直接看到结果,也避免停在多表时才有内容的「关联」页签
  fieldTab.value = 'fields'
}

/**
 * 关联页签只暴露「关联键 + 处理链」—— JOIN 类型/ON 条件不再给 UI。
 *
 * 原因:多表按各表关联键归一化后对齐(后端默认把各表键归一化成 mdn_norm),
 * 这已经是大多数场景的最优解;暴露 JOIN 类型/CROSS/逐表 ON 只会把术语和
 * 易错配置推给用户。真要自定义 ON,走 SQL 模式。
 */

// ── 关联键处理链编辑(复用字段处理链的编辑器,只保留 SQL 段)──
const joinStepsIdx = ref<number | null>(null)

// —— 关联页签的「加关联表」——
const addJoinTableOpen = ref(false)
const addJoinKw = ref('')
const addJoinLoading = ref(false)
const joinTables = ref<{ name: string; remark?: string }[]>([])
/** 候选表 = 库里已有的表 − 已在关联里的(重复加会被 addSource 拒掉),关键字同时匹配表名与注释 */
const addJoinCandidates = computed(() => {
  const inUse = new Set(vq.sources.value.map((s) => s.table))
  const k = addJoinKw.value.trim().toLowerCase()
  return joinTables.value.filter(
    (t) => !inUse.has(t.name) && (!k || t.name.toLowerCase().includes(k) || (t.remark ?? '').toLowerCase().includes(k))
  )
})
watch(addJoinTableOpen, async (open) => {
  if (!open) return
  addJoinKw.value = ''
  // 表清单拉过一次就复用:每次开弹窗都重拉会明显卡顿,库里新建了表刷新页面即可
  if (joinTables.value.length) return
  addJoinLoading.value = true
  try {
    joinTables.value = await workspaceApi.tables(props.connId, props.database)
  } finally {
    addJoinLoading.value = false
  }
})
/** 追加一张关联表(不动已有配置;自动猜关联键) */
async function onAddJoinTable(name: string) {
  const idx = await vq.addSource(name)
  if (idx < 0) {
    // addSource 对「表重复」和「超过 6 张」都返回 -1,只能按当前表数反推是哪种,否则提示会答非所问
    ElMessage.warning(vq.sources.value.length >= 6 ? '最多 6 张表' : '这张表已在关联中')
    return
  }
  addJoinTableOpen.value = false
  ElMessage.success(`已加入 ${name},请选它的关联键列;输出字段可在「② 输出字段」分组区勾选`)
}
const joinStepsVisible = computed({
  get: () => joinStepsIdx.value !== null,
  set: (v: boolean) => {
    // 只用一个下标同时表达「开没开」和「编辑谁」:分成两个变量存,迟早会出现「开着但没有目标」
    if (!v) joinStepsIdx.value = null
  }
})
/** 正在编辑处理链的源表;表刚被删导致下标越界时返回 null,弹窗内容随之消失而不是渲染报错 */
const joinStepsSource = computed(() =>
  joinStepsIdx.value === null ? null : (vq.sources.value[joinStepsIdx.value] ?? null)
)
/** 处理链里单个算子展示名:优先中文 label,未收录则回退短名 */
function opLabel(fn: string): string {
  return PIPELINE_OP_MAP[fn]?.label ?? opShortName(fn)
}

/** 处理链悬停提示:按顺序列出完整算子名(如 trim → MD5 · 32位) */
function chainTitle(steps?: QueryPipelineStep[]): string {
  if (!steps?.length) return '点击配置关联键归一化(如明文 → MD5)'
  return '处理链:' + steps.map((st) => opLabel(st.fn)).join(' → ')
}

function openJoinSteps(si: number) {
  // 处理链是作用在关联键上的:键没选就配归一化,配了也不会有任何效果,先拦住
  if (!vq.sources.value[si]?.joinCol) {
    ElMessage.warning('请先为该表选择关联键列')
    return
  }
  joinStepsIdx.value = si
}
function setJoinSteps(v: QueryPipelineStep[]) {
  const i = joinStepsIdx.value
  if (i === null) return
  vq.setJoinSteps(i, v)
}

// 配置快照(读到的都是 ref,任意改动都会触发)→ 通知父组件持久化草稿
/** SQL 模式被手改过 → 可视化配置与编辑器内容不再一致 */
// 两边都有内容才比较:SQL 还没生成出来时(空串)比出「不同」是误报
const diverged = computed(() => !!props.externalSql && !!vq.sql.value && props.externalSql !== vq.sql.value)

const configJson = computed(() => JSON.stringify(vq.toConfig()))
// 用序列化后的字符串当 watch 源:toConfig() 每次都返回新对象,直接 watch 对象会因为引用变化而反复误触发
watch(configJson, (v) => emit('change', v))

/** 「未选表/无字段」由各卡片的空态承担,这里不重复提示 */
const visibleWarnings = computed(() =>
  vq.warnings.value.filter((w) => !w.includes('请先选择一张表') && !w.includes('请至少添加'))
)

// 生成中排在最先:此时 SQL 可能还是上一版甚至为空,显示旧错误只会误导
const sqlStatusText = computed(() => {
  if (vq.generating.value) return '生成中…'
  if (vq.sqlError.value) return '出错'
  return vq.sql.value ? '就绪' : '待配置'
})
const sqlStatusClass = computed(() => ({
  err: !!vq.sqlError.value,
  ok: !vq.sqlError.value && !!vq.sql.value
}))

// ⚠️ 这些都带 sourceIdx —— 多表时同名列在不同表里类型/注释可能不同,
//    只按列名查会取错表,进而选错过滤操作符与可用算子。
function kindOf(col: string, sourceIdx = 0): ValueKind {
  return vq.kindOf(col, sourceIdx)
}
function colType(col: string, sourceIdx = 0): string {
  return vq.columnMaps.value[sourceIdx]?.get(col)?.type ?? ''
}
function colComment(col: string, sourceIdx = 0): string {
  return vq.columnMaps.value[sourceIdx]?.get(col)?.comment ?? ''
}

/** 某张源表的列清单(多表时字段表的「字段」列用它做换列下拉) */
function colsOf(sourceIdx = 0) {
  return vq.sources.value[sourceIdx]?.columns ?? []
}

/**
 * 可供过滤 / 排序选择的字段。
 * ⚠️ 排除程序算子列 —— ClickHouse 看不到它们的值,下推不了(后端也会校验拒绝)。
 */
const selectableFields = computed(() => vq.fields.value.filter((f) => !vq.progAliases.value.has(f.alias)))

/**
 * 左侧树点表名。
 *
 * ⚠️ 语义已改变(探索向导方案):
 * - 已有配置(存在源表)→ 只把该表【设为关联表】(addSource,不覆盖任何配置);
 *   不在树里反复点同名表时 addSource 自己会去重,安全。
 * - 空状态 → 弹探索向导,让用户一次性走完选表→关联键→输出列→过滤,避免「点一张清一张」。
 */
function onSelectTable(name: string) {
  if (!vq.table.value) {
    wizardVisible.value = true
    wizardPreset.value = [name]
    return
  }
  // 已有主表:点其它表 = 追加为关联表(不再清空!)
  void onJoinTable(name)
}

/** 把左侧表树里的表加为【关联表】(JOIN) */
async function onJoinTable(name: string) {
  if (!vq.table.value) {
    ElMessage.warning('请先点一张表设为主表,再添加关联表')
    return
  }
  const idx = await vq.addSource(name)
  if (idx < 0) {
    ElMessage.warning(vq.sources.value.length >= 6 ? '最多关联 6 张表' : '这张表已经在列表里了')
    return
  }
  ElMessage.success(`已加入关联表:${name},请为它选择「关联键列」`)
  // 自动切到「关联」页签,让用户马上看到要填什么
  fieldTab.value = 'join'
}

function onAddField(col: BuilderColumn, table?: string) {
  if (!vq.table.value) {
    ElMessage.warning('请先点击左侧表名设为主表')
    return
  }
  // ⚠️ 按【表名】反查 sourceIdx,不能用 activeSourceIdx ——
  //    左侧树可以展开非主表的字段列表(点小三角,不改主表),
  //    那些列属于关联表,用 activeSourceIdx 会错录到主表名下。
  const idx = table ? vq.sources.value.findIndex((s) => s.table === table) : -1
  vq.addField(col, undefined, idx >= 0 ? idx : vq.activeSourceIdx.value)
}

/**
 * 已经加为输出字段的列,按「表名 → 列名清单」。
 * 左侧树靠它算「还剩几个没加」,避免重复添加、并在加完后禁用按钮。
 */
const addedColsByTable = computed(() => {
  const out: Record<string, string[]> = {}
  const srcs = vq.sources.value
  for (const f of vq.fields.value) {
    const t = srcs[f.sourceIdx ?? 0]?.table
    if (!t) continue
    ;(out[t] ??= []).push(f.sourceCol)
  }
  return out
})

/**
 * 批量添加:把某张表的多个列一次加为输出字段。
 *
 * ⚠️ 必须按【表名】反查 sourceIdx,不能用 activeSourceIdx ——
 *    左侧树只要展开过另一张表,活动下标就不是这张表了。
 */
function onAddFields(table: string, cols: BuilderColumn[]) {
  const idx = vq.sources.value.findIndex((s) => s.table === table)
  if (idx < 0) {
    ElMessage.warning(`请先把「${table}」选为主表或加为关联表`)
    return
  }
  const n = vq.addAllFields(idx, cols)
  // 用新增数量区分两种结果:0 说明这些列早已在输出里,不能笼统地报「已添加」
  if (n > 0) ElMessage.success(`已添加 ${n} 个字段`)
  else ElMessage.info('这些字段已经都在输出字段里了')
}

function onAddFirst() {
  const first = vq.tableColumns.value[0]
  if (!first) {
    ElMessage.warning('请先在左侧选择一张表')
    return
  }
  vq.addField(first, undefined, vq.activeSourceIdx.value)
}

/** 分组勾选:勾/取消某表的某列 */
function onToggleCol(si: number, col: string, checked: boolean) {
  vq.setFieldChecked(si, col, checked)
}

/** 列读取失败 → 重新拉该表的列 */
function onRetryCols(si: number) {
  void vq.retryColumns(si)
}

/** 分组勾选:某表全选/全不选 */
function onToggleAll(si: number, checked: boolean) {
  const cols = vq.sources.value[si]?.columns ?? []
  if (checked) {
    const added = vq.addAllFields(si, cols)
    if (added) ElMessage.success(`已加入 ${added} 列`)
  } else {
    // 全不选:按表过滤移除该表全部字段(倒序 splice,别名清理交给 removeField)
    // 必须倒序:removeField 内部是 splice,正序删时后面的元素前移,下一次循环会跳过相邻的一条
    for (let i = vq.fields.value.length - 1; i >= 0; i--) {
      if (vq.fields.value[i].sourceIdx === si) vq.removeField(i)
    }
  }
}

function onDragOver(e: DragEvent) {
  if (e.dataTransfer?.types.includes('application/x-el2ck-col')) dropActive.value = true
}
function onDragLeave(e: DragEvent) {
  // dragleave 只要进入子元素就会触发;用 relatedTarget 判断是否真的离开了整个容器,否则高亮会一闪一闪
  const related = e.relatedTarget as Node | null
  const cur = e.currentTarget as Node
  if (!related || !cur.contains(related)) dropActive.value = false
}
function onDrop(e: DragEvent) {
  dropActive.value = false
  const raw = e.dataTransfer?.getData('application/x-el2ck-col')
  if (!raw) return
  if (!vq.table.value) {
    ElMessage.warning('请先点击左侧表名设为主表')
    return
  }
  try {
    // dataTransfer 里只是字符串,格式不受类型系统保护 —— 解析失败当作「没拖过」,不能让它打断投放处理
    // 拖拽 payload 里带着来源表 → 关联表的列不会错录到主表
    onAddField(JSON.parse(raw) as BuilderColumn, e.dataTransfer?.getData('application/x-el2ck-table') || undefined)
  } catch {
    /* ignore */
  }
}

function setWhereField(i: number, key: keyof QueryWorkflowWhere, v: any) {
  const w = vq.where.value[i]
  if (!w) return
  ;(w as any)[key] = v
  if (key === 'op' && v === 'distinct') {
    w.preset = ''
    w.value = ''
    w.value2 = ''
  }
  // 切到非自定义的预设时把旧的手填值清掉:预设表达式会取代手填值,留着就是一个不生效的残留
  if (key === 'preset' && v && v !== 'custom') {
    w.value = ''
    w.value2 = ''
  }
  vq.scheduleRefresh()
}
/** HAVING 与 WHERE 结构完全一致,处理方式也照搬 */
function setHavingField(i: number, key: keyof QueryWorkflowWhere, v: any) {
  const h = vq.having.value[i]
  if (!h) return
  ;(h as any)[key] = v
  if (key === 'op' && v === 'distinct') {
    h.preset = ''
    h.value = ''
    h.value2 = ''
  }
  if (key === 'preset' && v && v !== 'custom') {
    h.value = ''
    h.value2 = ''
  }
  vq.scheduleRefresh()
}
function setOrderField(i: number, key: 'column' | 'desc', v: any) {
  const o = vq.orderBy.value[i]
  if (!o) return
  ;(o as any)[key] = v
  vq.scheduleRefresh()
}

async function copySql() {
  try {
    await navigator.clipboard.writeText(vq.sql.value)
    ElMessage.success('已复制 SQL')
  } catch {
    // clipboard 在非 HTTPS、无权限或页面失焦时会 reject:退回「手动选择」而不是静默失败
    ElMessage.warning('复制失败,请手动选择')
  }
}

async function onRun() {
  if (props.executionEnabled === false) return
  // 绕开 300ms 防抖同步生成一次:刚改完配置就点运行,不这样做会带着上一版 SQL 去执行
  const s = await vq.refreshSQLNow()
  if (!s) {
    ElMessage.error(vq.sqlError.value || 'SQL 未就绪')
    return
  }
  // 不清除「配置已改」标记 —— 由父组件在【执行成功后】回调 markFresh()
  emit('run', s)
}

defineExpose({
  getSQL: () => vq.sql.value,
  /** 程序算子后处理计划(运行/分页/导出都要带上) */
  getProgCols: () => vq.progCols.value,
  refreshSQLNow: () => vq.refreshSQLNow(),
  /** 由父组件在【执行成功后】调用,清除「配置已改」标记 */
  markFresh: () => vq.markFresh(),
  /** 空状态时自动弹探索向导(新建探索后直接进入配置流程) */
  openWizardIfEmpty: () => {
    if (!vq.sources.value.length) wizardVisible.value = true
  },
  reset: () => {
    vq.reset()
    // 顺带让左侧数据源树丢掉列缓存:重建探索时表结构可能已经变了,留着旧缓存会显示过期列
    treeRef.value?.invalidateCache?.()
  },
  loadConfig: (cfg: any) => vq.loadConfig(cfg),
  toConfig: () => vq.toConfig(),
  get hasContent() {
    return !!vq.table.value || vq.fields.value.length > 0
  }
})
</script>

<style scoped>
/* ── 关联(多表 JOIN)卡片 ── */
.vb-join-hint {
  margin: 0 0 12px;
  font-size: 13.5px;
  line-height: 1.7;
  color: var(--el-text-color-secondary);
}
.vb-join-hint code {
  padding: 1px 5px;
  border-radius: 4px;
  background: var(--el-fill-color);
  font-family: var(--font-mono);
}
.vb-join-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 6px 0;
  border-bottom: 1px dashed var(--el-border-color-lighter);
}
.vb-join-head {
  font-size: 12px;
  color: var(--el-text-color-placeholder);
  border-bottom: 1px solid var(--el-border-color-lighter);
}
.vb-join-t {
  flex: 1;
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 6px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.vb-join-k {
  width: 180px;
  flex: none;
}
.vb-join-o {
  width: 48px;
  flex: none;
  text-align: right;
}
.vb-join-form {
  margin-top: 14px;
}
.vb-on-row {
  display: flex;
  align-items: center;
  gap: 8px;
}
/* 逐表 ON:缩进在对应源表行下面 */
.vb-on-per {
  padding: 2px 0 6px 10px;
  border-bottom: 1px dashed var(--el-border-color-lighter);
}
.vb-on-label {
  font-size: 12px;
  color: var(--el-text-color-placeholder);
  width: 62px;
  flex: none;
}
.vb-on-tip {
  font-size: 12px;
  line-height: 1.6;
  margin-top: 4px;
}
.vb-wrap {
  display: flex;
  height: 100%;
  /* 关键:自身作为 flex item 时必须显式占满,否则宽度由内容决定 → 右侧留空 */
  width: 100%;
  flex: 1 1 auto;
  min-width: 0;
  min-height: 0;
  gap: 0;
}

/* ── 左栏:数据源 ── */
.vb-side {
  width: 216px;
  flex: 0 0 216px;
  min-height: 0;
  overflow: hidden;
  padding-right: 10px;
  border-right: 1px solid var(--el-border-color-lighter);
}

/* ── 中栏:构建区 ── */
.vb-center {
  flex: 1;
  min-width: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
  padding: 0 12px;
}


/*
  ⚠️ 这里【不】给页签内部再加滚动。中列本来就有一个滚动容器 .vb-scroll
  (flex:1; min-height:0; overflow-y:auto),它才是唯一的滚动层。
  我一度又在页签内部套了一层 flex+overflow-y:auto,结果两层嵌套后内层不再受父级高度约束,
  展开「字段配置」时整个 .vb-scroll 会被内容顶开、直接展开(用户实测反馈)。
  撤掉这层,内容自然在 .vb-scroll 里滚动,高度保持"自动伸缩"。
*/
.vb-bar {
  display: flex;
  align-items: center;
  gap: 4px;
  padding-bottom: 8px;
  flex: 0 0 auto;
}
.vb-bar-spacer {
  flex: 1;
}
/*
  探索向导入口 = 【次要动作】,不再是亮的那一个。
  以前是 实心主色 + 加粗 + 字距:同一栏里和「运行」两个实心蓝按钮并列,
  眼睛不知道该先看谁(用户反馈:向导按钮不用那么显眼)。
  现在只靠 plain(主色淡底 + 主色字)表示"次要可点",加粗与字距都去掉;
  整栏只保留「运行」一个实心主色。
*/
/*
  运行按钮:整栏里唯一的主色实心 —— 只有它需要抢注意力(不做花哨动效)。

  ⚠️ 这里换过三版,记一下为什么:
  ① 最早是从按钮【外面】扩散的光晕(box-shadow 0 0 0 9px)——
     配置区为了收口加了 overflow:hidden,光晕被裁掉,看着像"效果被遮罩"。
  ② 改成【腔内白环】inset 0 0 0 5px —— 也不对:这个按钮是 size="small"(24px 高),
     5px 内白边上下各吃掉 5px,按钮面整个糊白,像渲染坏了;而且 2.2s 永久循环,
     底部一直"在闪"。用户反馈"有问题,不好看"。
  ③ 现在 = 轻微、慢速的亮度呼吸(filter 不占盒模型、不会被外层 overflow 裁掉,
     也不会和 el-button 自己的 hover 背景色打架)。要"引导点击"的意味,不要糊在按钮面上。
*/
.vb-run-btn {
  font-weight: 600;
  animation: vb-run-breathe 3.2s ease-in-out infinite alternate;
}
@keyframes vb-run-breathe {
  from { filter: brightness(1); }
  to { filter: brightness(1.12); }
}
/* 不能生成 SQL 时(按钮置灰)不要继续闪,否则像“可以点” */
.vb-run-btn.is-disabled {
  animation: none;
}
@media (prefers-reduced-motion: reduce) {
  .vb-run-btn { animation: none; }
}
/* —— 关联页签的加表选择器 —— */
.vb-addjoin-item {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 5px 6px;
  cursor: pointer;
  border-radius: 4px;
}
.vb-addjoin-item:hover {
  background: var(--el-fill-color);
}
.vb-addjoin-remark {
  font-size: 13px;
  color: var(--el-text-color-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.vb-table-tag :deep(b) {
  font-weight: 600;
}
.vb-stale {
  margin-left: 2px;
}
.vb-tips {
  flex: 0 0 auto;
  margin-bottom: 8px;
  border: 1px solid var(--el-color-warning-light-7);
  background: var(--el-color-warning-light-9);
  border-radius: 6px;
  padding: 5px 10px;
}
.vb-tip {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  color: var(--el-color-warning-dark-2);
  line-height: 1.8;
}
/* 非阻塞提示:改用中性色,与真正会拦住执行的警告区分开 */
.vb-tip.info {
  color: var(--el-text-color-secondary);
  align-items: flex-start;
}
/* 只有非阻塞提示(没有任何真警告)时,外框也别用警告色 —— 否则看着像出错了 */
.vb-tips.only-info {
  border-color: var(--el-border-color-lighter);
  background: var(--el-fill-color-lighter);
}
.vb-scroll {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding-right: 2px;
  border-radius: 8px;
  transition: box-shadow 0.15s ease;
}
.vb-scroll.drop-active {
  box-shadow: inset 0 0 0 2px rgba(234, 179, 8, 0.55);
  background: rgba(234, 179, 8, 0.04);
}
/* —— 关联页签:关系图 —— */
.vb-join-top {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}
.vb-join-acts {
  flex: none;
  display: flex;
  gap: 8px;
}
.vb-graph {
  margin-top: 10px;
  padding: 10px;
  background: var(--el-fill-color-light);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  overflow-x: auto;
}
/* 内容整体居中:图窄时居中好看,图宽时内层涨到内容宽度,左侧依然滚得到 */
.vb-graph-inner {
  display: flex;
  gap: 0;
  justify-content: center;
  min-width: min-content;
}
.vb-graph-left {
  flex: none;
  align-self: center;
  padding-right: 12px;
  border-right: 2px solid var(--el-border-color-light);
}
.vb-graph-right {
  flex: 0 1 auto;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.vb-gpair {
  display: flex;
  align-items: center;
}
.vb-glink {
  flex: none;
  width: 100px;
  height: 2px;
  position: relative;
  background: repeating-linear-gradient(90deg, var(--el-border-color) 0 6px, transparent 6px 10px);
}
.vb-glink.ok {
  background: repeating-linear-gradient(90deg, var(--el-color-success) 0 6px, transparent 6px 10px);
}
.vb-glink-label {
  position: absolute;
  top: -15px;
  left: 50%;
  transform: translateX(-50%);
  font-size: 12px;
  color: var(--el-text-color-secondary);
  white-space: nowrap;
}
.vb-gnode {
  flex: none;
  width: 300px;
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color);
  border-radius: 8px;
  padding: 6px 8px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.vb-gnode.main {
  border-color: var(--el-color-warning-light-5);
  box-shadow: 0 1px 4px var(--el-color-warning-light-7);
}
.vb-gnode-head {
  display: flex;
  align-items: center;
  gap: 6px;
}
.vb-gnode-name {
  flex: 1;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.vb-gnode-row {
  display: flex;
  align-items: center;
  gap: 6px;
}
.vb-gnode-label {
  flex: none;
  width: 48px;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
.vb-gnode-sel {
  flex: 1;
  min-width: 0;
}
/* 处理链展示:直接列出已配算子(而不是「N 步」) */
.vb-gnode-row-chain {
  align-items: flex-start;
}
.vb-chain {
  flex: 1;
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 4px;
  flex-wrap: wrap;
  min-height: 22px;
  padding: 1px 6px;
  border: 1px dashed var(--el-border-color);
  border-radius: 5px;
  cursor: pointer;
  font-size: 13px;
  color: var(--el-text-color-secondary);
  transition: border-color 0.15s, background 0.15s;
}
.vb-chain:hover {
  border-color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
}
.vb-chain.empty {
  font-style: normal;
}
.vb-chain-chip {
  padding: 0 5px;
  border-radius: 4px;
  background: var(--el-color-primary-light-8);
  color: var(--el-color-primary);
  font-size: 12px;
  line-height: 18px;
}
.vb-chain-edit {
  margin-left: auto;
  opacity: 0.6;
  font-size: 13px;
}
.vb-join-note {
  margin: 10px 0 0;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

.vb-footnote {
  font-size: 12px;
  color: var(--el-text-color-placeholder);
  line-height: 1.7;
  padding: 4px 2px 12px;
}

/* ── 卡片 ── */
.vb-tabs :deep(.el-tabs__header) {
  margin-bottom: 8px;
}
.vb-tabs :deep(.el-tabs__item) {
  height: 34px;
  line-height: 34px;
  font-size: 14px;
}
.vb-tab {
  display: inline-flex;
  align-items: center;
  gap: 5px;
}
.vb-tab-no {
  width: 15px;
  height: 15px;
  border-radius: 50%;
  background: var(--brand, #eab308);
  color: #1f2937;
  font-size: 11px;
  line-height: 15px;
  text-align: center;
  font-weight: 700;
}
.vb-tab-count {
  font-size: 11px;
  padding: 0 5px;
  border-radius: 8px;
  background: var(--el-fill-color);
  color: var(--el-text-color-secondary);
  line-height: 15px;
}
.vb-card {
  padding: 0;
  margin-bottom: 8px;
  background: transparent;
}
.vb-card :deep(.ft-wrap),
.vb-card :deep(.fr-wrap),
.vb-card :deep(.or-wrap) {
  margin-bottom: 0;
}
.vb-card-head {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 8px;
}
.vb-card-no {
  width: 16px;
  height: 16px;
  border-radius: 50%;
  background: var(--brand, #eab308);
  color: #1f2937;
  font-size: 11px;
  line-height: 16px;
  text-align: center;
  font-weight: 700;
  flex: 0 0 16px;
}
.vb-card-title {
  font-size: 14px;
  font-weight: 600;
}
/* 卡片标题旁的补充说明(比标题淡一级) */
.vb-card-hint {
  font-size: 12px;
  color: var(--el-text-color-placeholder);
}
/* 卡片内的说明块:用于「这个功能当前不可用,原因是…」这类前置条件提示 */
.vb-note {
  font-size: 13px;
  line-height: 1.7;
  color: var(--el-color-warning-dark-2);
  background: var(--el-color-warning-light-9);
  border: 1px solid var(--el-color-warning-light-5);
  border-radius: 6px;
  padding: 7px 10px;
}
/* 说明块里的补救操作行(如「已残留 N 条 → 全部清除」) */
.vb-note-act {
  margin-top: 4px;
  display: flex;
  align-items: center;
  gap: 2px;
  color: var(--el-text-color-regular);
}
.vb-card-body {
  padding-top: 2px;
}

/* ── 右栏:设置 + SQL ── */
.vb-right {
  width: 300px;
  flex: 0 0 300px;
  min-height: 0;
  display: flex;
  flex-direction: column;
  border-left: 1px solid var(--el-border-color-lighter);
  padding-left: 10px;
  position: relative;
  transition: flex-basis 0.18s ease, width 0.18s ease;
}
.vb-right.collapsed {
  width: 34px;
  flex: 0 0 34px;
  padding-left: 0;
}
/* 右栏头部:标题 + 收起按钮,占一行,不与卡片重叠 */
.vb-right-head {
  flex: 0 0 auto;
  height: 26px;
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 6px;
}
.vb-right-title {
  font-size: 13.5px;
  font-weight: 600;
  color: var(--el-text-color-regular);
  flex: 1;
}
.vb-right.collapsed .vb-right-head {
  justify-content: center;
  margin-bottom: 0;
}
.vb-right-toggle {
  width: 22px;
  height: 22px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 5px;
  background: var(--el-fill-color-light);
  color: var(--el-text-color-secondary);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 13px;
  padding: 0;
  flex: 0 0 auto;
  transition: color 0.15s ease, border-color 0.15s ease;
}
.vb-right-toggle:hover {
  color: var(--brand-strong, #b45309);
  border-color: var(--brand, #eab308);
  background: rgba(234, 179, 8, 0.08);
}
.vb-right-inner {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
}

.vb-grain {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 2px;
}
.vb-grain :deep(.el-radio) {
  height: 24px;
  margin-right: 0;
}
.vb-grain-note {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.7;
  margin-top: 6px;
  padding: 6px 8px;
  background: var(--el-fill-color-light);
  border-radius: 5px;
}
.vb-set-row {
  display: flex;
  align-items: center;
  gap: 8px;
}
.vb-set-label {
  font-size: 13.5px;
  color: var(--el-text-color-regular);
  flex: 0 0 auto;
}
.vb-limit {
  flex: 1;
}
.vb-set-hint {
  font-size: 12px;
  color: var(--el-text-color-placeholder);
  margin-top: 6px;
}

.vb-card-sql {
  display: flex;
  flex-direction: column;
}
.vb-sql-status {
  margin-left: auto;
  font-size: 12px;
  color: var(--el-text-color-placeholder);
}
.vb-sql-status.ok {
  color: var(--el-color-success);
}
.vb-sql-status.err {
  color: var(--el-color-danger);
}
.vb-sql-body {
  max-height: 340px;
}
.vb-sql-empty {
  margin: 0;
  background: #0d1117;
  color: #56606b;
  border-radius: 6px;
  padding: 9px 10px;
  font-size: 12px;
}
/* SQL 生成失败时的错误原文 */
.vb-sql-err {
  margin: 0 0 6px;
  padding: 7px 10px;
  font-size: 13px;
  line-height: 1.65;
  color: var(--el-color-danger-dark-2);
  background: var(--el-color-danger-light-9);
  border: 1px solid var(--el-color-danger-light-5);
  border-radius: 6px;
  word-break: break-word;
}
.mono {
  font-family: var(--font-mono, monospace);
}

.vb-dedup { margin-top: 10px; }

</style>
