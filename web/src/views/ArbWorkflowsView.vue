<!--
  ArbWorkflowsView.vue

  职责：聚合工作流页面：创建/启动工作流，轮询运行状态并查看历史与产物。
  边界：管理仲裁工作流配置、探测和运行；运行、重建、摊平观察使用 AbortController，导航变化或组件卸载时统一停止旧观察。
-->

<template>
  <div class="aw" :class="{ 'aw-list': !editing }">
    <!-- ═══════════════ 列表 ═══════════════ -->
    <ListPageTemplate v-if="!editing" v-model:page="page" v-model:page-size="pageSize" :total="shownWorkflows.length">
      <template #header>
      <div class="aw-head">
        <div>
          <h2>工作流</h2>
          <p class="aw-sub">
            把多张贴源表按号码合并成<b>一张聚合表</b>：同名列按
            <code>(非空, 来源优先级, 更新时间)</code> 仲裁，每源一个物化视图接增量。
          </p>
        </div>
      </div>

      <ListQueryBar
        v-model:keyword="query.draftKeyword.value"
        keyword-placeholder="搜索工作流、目标表或说明"
        @search="query.search"
        @reset="resetListQuery"
      />

      <div class="list-table-ops">
        <div class="list-table-ops-start">
          <el-button type="primary" :icon="Plus" @click="openCreate">新建工作流</el-button>
          <el-button v-if="selectedWfs.length" type="danger" :icon="Delete" :loading="batching" @click="batchRemoveWfs">批量删除({{ selectedWfs.length }})</el-button>
        </div>
      </div>

      </template>
      <el-table ref="wfTableRef" v-loading="loading" :data="pagedWorkflows" height="100%" border stripe scrollbar-always-on class="aw-list-table" @selection-change="onWfSelectionChange">
        <!-- 正在执行的行不许勾选:跑一半删掉会留下半套产物 -->
        <el-table-column type="selection" width="42" align="center" :selectable="(r: ArbWorkflow) => !busyIds.has(r.id)" />
        <el-table-column label="工作流" min-width="200">
          <template #default="{ row }">
            <div class="aw-name">
              <button type="button" class="aw-name-link" :title="`查看目标表 ${row.targetTable}_svc`" @click="goToResultTable(row)">{{ row.name }}</button>
              <el-tag v-if="WIDE_TABLE_ENABLED && row.isWide" size="small" type="success" effect="plain" class="aw-wide-tag">宽表</el-tag>
            </div>
            <div v-if="row.description" class="aw-dim">{{ row.description }}</div>
            <div class="aw-dim">
              {{ row.sources?.length || 0 }} 个源 · {{ outNamesOf(row).length }} 个目标列
            </div>
          </template>
        </el-table-column>
        <el-table-column label="聚合表" min-width="200">
          <template #default="{ row }">
            <!-- 聚合表 = 中间层(存仲裁状态),业务不能直接查,所以这里只展示名与各源的优先级 -->
            <span class="mono">{{ row.targetTable }}_agg</span>
            <div class="aw-chips">
              <!-- 每源一个 chip:pri 越大越权威,没配时间列的源要显式提示(见第②步的说明) -->
              <span v-for="s in row.sources || []" :key="s.id" class="aw-chip">
                {{ s.table }} <b class="pri">pri={{ s.pri }}</b>
                <span v-if="!s.timeCol" class="aw-noTime">无时间列</span>
              </span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="最近执行" width="175">
          <template #default="{ row }">
            <div v-if="row.lastRunAt">
              <el-tag :type="statusType(row.lastStatus)" size="small">{{ statusLabel(row.lastStatus) }}</el-tag>
              <span class="aw-dim"> {{ fmtRows(row.lastRows) }}</span>
              <div class="aw-dim">{{ fmtDt(row.lastRunAt) }}</div>
            </div>
            <span v-else class="aw-dim">未执行</span>
            <div v-if="row.lastError" class="aw-err" :title="row.lastError">{{ shortErr(row.lastError) }}</div>
          </template>
        </el-table-column>
        <el-table-column label="启用" width="70" align="center">
          <template #default="{ row }">
            <!-- 只传 model-value 不双向绑定:开关姿态以刷新回来的列表为准(见 toggleEnabled) -->
            <el-switch :model-value="row.enabled" @change="toggleEnabled(row)" />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="420" align="center">
          <template #default="{ row }">
            <ActionIconBar :actions="workflowActions" :busy="busyIds.has(row.id)" @command="(c: string) => onCommand(c, row)" />
          </template>
        </el-table-column>
      </el-table>
    </ListPageTemplate>

    <!-- ═══════════════ 向导 ═══════════════ -->
    <template v-else>
      <div class="wz-head">
        <el-button text :icon="Back" @click="closeWizard">返回列表</el-button>
        <div class="wz-title">{{ wizardTitle }}</div>
        <div class="wz-target mono" v-if="form.targetTable">目标表 {{ svcTableFullName }}</div>
      </div>

      <el-steps :active="step" finish-status="success" align-center class="wz-steps">
        <el-step title="基本信息" description="名字与落库位置" @click="goToStep(0)" />
        <el-step title="数据源" description="选表 · 号码列 · 优先级" @click="goToStep(1)" />
        <el-step title="字段" description="源列 → 目标列" @click="goToStep(2)" />
        <el-step title="取值方式" description="仲裁 / 聚合" @click="goToStep(3)" />
        <el-step title="生成" description="预览并执行" @click="goToStep(4)" />
      </el-steps>

      <div class="wz-body">
        <!-- ── ① 基本信息 ── -->
        <section v-if="step === 0" class="wz-sec">
          <h4>这一步在做什么</h4>
          <p class="wz-tip">
            给工作流起个名字，并指定<b>目标表</b>叫什么（业务最终查的就是它）。执行后会有：
            <b>目标表</b> <code>{{ svcTableFullName }}</code>（摊平后的普通列，一个号码一行）、
            <b>聚合表</b> <code>{{ tableFullName }}</code>（中间层，存仲裁状态），
            以及<b>每个源一个物化视图</b>（贴着源表，源表写入就自动流进聚合表）。
          </p>
          <el-form label-width="110px" class="wz-form">
            <el-form-item label="工作流名称" required>
              <el-input v-model="form.name" placeholder="如：手机号画像宽表" />
            </el-form-item>
            <el-form-item label="说明">
              <el-input v-model="form.description" placeholder="可选" />
            </el-form-item>
            <el-form-item v-if="WIDE_TABLE_ENABLED" label="作为宽表">
              <el-switch v-model="form.isWide" />
              <div class="aw-dim wz-hint">
                宽表 = 全平台汇总的那一张（所有贴源表并成一个大宽表）。
                「目标库表（dwd_data）」页的<b>宽表页签</b>会直接显示它的数据。<br />
                同一时刻只能有一个：标记新的会自动取消旧的（只是改标记，不动数据）。
              </div>
            </el-form-item>
            <el-form-item label="目标表名" required>
              <!-- 库与前后缀都不让用户操心：库固定、dwd_ 前缀与 _agg / _svc 后缀后端拼 -->
              <el-input v-model="form.targetTable" style="width: 300px" placeholder="自定义名字，如 mdn_profile" />
              <div class="aw-dim wz-hint">
                会生成两张表：<br />
                目标表（业务查这张）<code>{{ svcTableFullName }}</code><br />
                聚合表（中间层）<code>{{ tableFullName }}</code>
              </div>
            </el-form-item>
          </el-form>
        </section>

        <!-- ── ② 数据源 ── -->
        <section v-if="step === 1" class="wz-sec">
          <h4>选要合并哪几张表</h4>
          <p class="wz-tip">
            <b>号码列已经由「映射同步」定好了</b>（那里是必选的主键，列注释固定写着「手机号」），
            所以这里自动带出、不用再选。要配的是<b>时间列</b>（配了才能在同一个源的多个行里取最新）
            和<b>优先级</b>（<b>越大越权威</b>，挂在源上而不是字段上）。
          </p>

          <div v-for="(s, si) in form.sources" :key="s.id" class="wz-src">
            <!-- 第一行：选表 -->
            <div class="wz-src-head">
              <span class="wz-idx">{{ si + 1 }}</span>
              <el-select
                v-model="s.table"
                filterable
                placeholder="选择贴源表"
                class="wz-tbl"
                @change="(v: string) => onSourceTableChange(s, v)"
              >
                <!-- 带注释；已被其它源选过的禁掉（每源一个物化视图，重复选会撞名字） -->
                <el-option
                  v-for="t in tablesOf(s.database)"
                  :key="t.name"
                  :value="t.name"
                  :label="t.remark ? `${t.name} ${t.remark}` : t.name"
                  :disabled="tableUsedBy(t.name, s.id)"
                >
                  <div class="wz-opt">
                    <span class="mono">{{ t.name }}</span>
                    <span class="wz-opt-note">{{ tableUsedBy(t.name, s.id) ? '已被其它源选' : t.remark }}</span>
                  </div>
                </el-option>
              </el-select>
              <span v-if="tableRemark(s.table)" class="wz-comment">{{ tableRemark(s.table) }}</span>
              <el-button
                class="wz-src-rm"
                size="small"
                text
                type="danger"
                :icon="Delete"
                :disabled="form.sources.length <= 1"
                @click="removeSource(si)"
              >
                移除
              </el-button>
            </div>

            <div v-if="s.table" class="wz-src-body">
              <!-- 号码列：只读 + 来源标记 -->
              <div class="wz-line">
                <span class="wz-label">号码列</span>
                <template v-if="!mdnEditing[s.id]">
                  <span class="mono wz-val">{{ s.mdnCol || '未识别' }}</span>
                  <el-tag v-if="mdnSource[s.id] === 'comment'" size="small" type="success" effect="plain">
                    同步已指定
                  </el-tag>
                  <el-tag v-else-if="mdnSource[s.id] === 'default'" size="small" type="info" effect="plain">
                    默认列名
                  </el-tag>
                  <el-tag v-else size="small" type="warning" effect="plain">未识别，请手选</el-tag>
                  <span v-if="commentOf(s.id, s.mdnCol)" class="wz-comment">{{ commentOf(s.id, s.mdnCol) }}</span>
                </template>
                <el-select v-else v-model="s.mdnCol" filterable placeholder="选择号码列" class="wz-ctl-mid">
                  <el-option
                    v-for="c in colsOf(s.id)"
                    :key="c.name"
                    :value="c.name"
                    :label="c.comment ? `${c.name} ${c.comment}` : c.name"
                  >
                    <div class="wz-opt">
                      <span class="mono">{{ c.name }}</span>
                      <span class="wz-opt-note">{{ c.comment }}</span>
                    </div>
                  </el-option>
                </el-select>
                <span class="wz-line-ops">
                  <el-button size="small" text @click="toggleMdnEdit(s)">
                    {{ mdnEditing[s.id] ? '完成' : '改' }}
                  </el-button>
                  <el-button size="small" text :icon="MagicStick" @click="openMdnSteps(s)">处理链</el-button>
                </span>
              </div>

              <!-- 时间列 + 优先级 -->
              <div class="wz-line">
                <span class="wz-label">时间列</span>
                <el-select
                  v-model="s.timeCol"
                  filterable
                  clearable
                  placeholder="不配则只比优先级"
                  class="wz-ctl-mid"
                >
                  <el-option
                    v-for="c in timeColsOf(s.id)"
                    :key="c.name"
                    :value="c.name"
                    :label="c.comment ? `${c.name} ${c.comment}` : c.name"
                  >
                    <div class="wz-opt">
                      <span class="mono">{{ c.name }}</span>
                      <span class="wz-opt-note">{{ c.comment }}</span>
                    </div>
                  </el-option>
                </el-select>
                <span v-if="commentOf(s.id, s.timeCol)" class="wz-comment">
                  {{ commentOf(s.id, s.timeCol) }}
                </span>
                <span class="wz-label wz-label-2">优先级</span>
                <el-input-number v-model="s.pri" :min="0" :max="255" size="small" controls-position="right" />
                <span class="wz-comment">越大越权威</span>
              </div>

              <!-- 过滤 -->
              <div class="wz-line">
                <span class="wz-label">过滤</span>
                <el-input
                  v-model="s.where"
                  size="small"
                  class="wz-ctl-grow"
                  placeholder="可选，如 region = '北京'（不要写 WHERE）"
                />
              </div>

              <div v-if="!s.timeCol || mdnIsHashed(s)" class="wz-notes">
                <div v-if="!s.timeCol" class="wz-note">
                  没配时间列 → 该源同一号码有多行时，只能按「值大小」取舍，取不到最新那条。
                  <template v-if="timeColsOf(s.id).length">
                    可选：{{ timeColsOf(s.id).map((c) => c.name).join('、') }}
                  </template>
                </div>
                <div v-if="mdnIsHashed(s)" class="wz-note">
                  ⚠️ 号码列是<b>哈希值</b>（不是明文）：别的源用明文 mdn 时，同一个人在这儿会被当成两个号码。
                  要么两边口径一致，要么在这个源上配处理链统一。
                </div>
              </div>
            </div>
          </div>

          <el-button :icon="Plus" @click="addSource">再加一个源</el-button>
        </section>

        <!-- ── ③ 字段 ── -->
        <section v-if="step === 2" class="wz-sec">
          <h4>把源表的字段挂到目标列上</h4>
          <p class="wz-tip">
            勾选要从每个源取哪些列。<b>同名的列自动汇到同一个目标列</b>（比如三个源都有 city，会按优先级仲裁出一个）；
            列名不一样的（<code>operator.phone</code> ↔ <code>app.mdn</code>）把「目标列名」改成一致即可。
            号码列不在这里选 —— 它就是聚合表的 <code>mdn</code>。
          </p>
          <div class="wz-toolbar">
            <el-button size="small" type="primary" plain @click="autoMap">按同名列自动挂载</el-button>
            <el-button size="small" @click="clearFields">全部清空</el-button>
            <span class="aw-dim">已挂 {{ form.fields.length }} 个源字段 → {{ outNames.length }} 个目标列</span>
          </div>

          <div v-for="s in form.sources" :key="`f-${s.id}`" class="wz-fieldcard">
            <div class="wz-fieldcard-head">
              <b>{{ s.table || '(未选表)' }}</b>
              <span class="aw-dim">pri={{ s.pri }} · 已挂 {{ fieldsOf(s.id).length }} 列</span>
            </div>
            <div v-if="!colsOf(s.id).length" class="aw-dim">先回上一步把源表选上</div>
            <el-table v-else :data="colsOf(s.id)" size="small" border max-height="300">
              <el-table-column width="46" align="center">
                <template #default="{ row }">
                  <el-checkbox
                    :model-value="!!fieldOf(s.id, row.name)"
                    :disabled="row.name === s.mdnCol"
                    @change="toggleField(s, row)"
                  />
                </template>
              </el-table-column>
              <el-table-column label="源列" width="200">
                <template #default="{ row }">
                  <span class="mono">{{ row.name }}</span>
                  <el-tag v-if="row.name === s.mdnCol" size="small" type="warning" class="wz-tag">号码列</el-tag>
                  <div v-if="row.comment" class="aw-dim">{{ row.comment }}</div>
                </template>
              </el-table-column>
              <el-table-column label="类型" width="150">
                <template #default="{ row }"><span class="mono aw-dim">{{ row.type }}</span></template>
              </el-table-column>
              <el-table-column label="目标列名(不同源要一致)">
                <template #default="{ row }">
                  <el-input
                    :model-value="fieldOf(s.id, row.name)?.outName ?? ''"
                    size="small"
                    :disabled="!fieldOf(s.id, row.name)"
                    placeholder="= 源列名"
                    @update:model-value="(v: string) => setOutName(s, row.name, v)"
                  />
                </template>
              </el-table-column>
              <el-table-column label="处理链" width="120" align="center">
                <template #default="{ row }">
                  <el-button size="small" text :disabled="!fieldOf(s.id, row.name)" @click="openFieldSteps(s, row)">
                    {{ stepLabel(s.id, row.name) }}
                  </el-button>
                </template>
              </el-table-column>
            </el-table>
          </div>
        </section>

        <!-- ── ④ 取值方式 ── -->
        <section v-if="step === 3" class="wz-sec">
          <h4>每个目标列怎么算出来</h4>
          <p class="wz-tip">
            <b>仲裁</b>：多源都提供时，按「非空 → 优先级 → 时间」选一个（默认，绝大多数字段用它）。
            <b>sum / max / min</b>：把各源的值再聚合一次（金额、次数、最近时间），<b>不看优先级</b>。
          </p>

          <!--
            把仲裁键摊开写清楚 —— 用户问得最多的就是「时间列到底什么时候才起作用」。
            四个位置是【字典序】：从左往右比，谁先分出胜负就停，后面根本不看。
          -->
          <div class="wz-rule">
            <div class="wz-rule-title">仲裁怎么比（四个位置从左到右比，先分出胜负就停）</div>
            <div class="wz-rule-flow">
              <span class="wz-pos"><b>① 非空</b><em>有值的赢</em></span>
              <span class="wz-arrow">→</span>
              <span class="wz-pos"><b>② 来源优先级</b><em>pri 大的赢</em></span>
              <span class="wz-arrow">→</span>
              <span class="wz-pos"><b>③ 更新时间</b><em>同 pri 才比，新的赢</em></span>
              <span class="wz-arrow">→</span>
              <span class="wz-pos"><b>④ 值本身</b><em>兜底，取字典序最大</em></span>
            </div>
            <div class="wz-rule-note">
              所以<b>时间列只在「同一个优先级内部」起作用</b>（同一张表一个号码多行时取最新那行）；
              跨源时若 pri 不同，时间完全不参与 —— 高优先级源的旧值也胜过新值。
              <br />
              三个规则就是「把不参与比较的那一位填 0」：
              <b>只看优先级</b> = 时间位填 0（宁旧不假）；<b>只看时间</b> = 优先级位填 0。
            </div>
          </div>
          <el-alert
            type="warning"
            :closable="false"
            show-icon
            title="数值列请不要用仲裁：仲裁列一律按字符串存，范围比较会出错（'9' > '100' 成立）。数值列用 sum/max/min。"
            class="wz-alert"
          />
          <el-table :data="outRows" size="small" border>
            <el-table-column label="目标列" width="170">
              <template #default="{ row }"><span class="mono">{{ row.name }}</span></template>
            </el-table-column>
            <el-table-column label="来源" min-width="260">
              <template #default="{ row }">
                <span v-for="(o, i) in row.origins" :key="i" class="aw-chip">
                  {{ o.src }}.<span class="mono">{{ o.col }}</span>
                </span>
                <span v-if="row.origins.length > 1" class="aw-dim">→ 按优先级仲裁</span>
              </template>
            </el-table-column>
            <el-table-column label="取值方式" width="150">
              <template #default="{ row }">
                <el-select
                  :model-value="cfgOf(row.name).mode"
                  size="small"
                  @update:model-value="(v: ArbMode) => setMode(row.name, v)"
                >
                  <el-option value="arbitrate" label="仲裁(取一个)" />
                  <el-option value="sum" label="sum(求和)" :disabled="!!sumIssue(row.name)" />
                  <el-option value="max" label="max(取最大)" />
                  <el-option value="min" label="min(取最小)" />
                </el-select>
                <div v-if="sumIssue(row.name)" class="aw-dim">该列含非数值来源，不能求和</div>
              </template>
            </el-table-column>
            <el-table-column label="仲裁规则" width="200">
              <template #default="{ row }">
                <el-select
                  :model-value="cfgOf(row.name).rule"
                  size="small"
                  :disabled="cfgOf(row.name).mode !== 'arbitrate'"
                  @update:model-value="(v: ArbRule) => setRule(row.name, v)"
                >
                  <el-option value="pri_time" label="非空 > 优先级 > 时间" />
                  <el-option value="pri" label="只看优先级(宁旧不假)" />
                  <el-option value="time" label="只看时间(取最新)" />
                </el-select>
              </template>
            </el-table-column>
          </el-table>
        </section>

        <!-- ── ⑤ 生成 ── -->
        <section v-if="step === 4" class="wz-sec">
          <h4>确认并生成</h4>

          <div class="wz-toolbar">
            <!-- 现场状态按【已保存】的配置去读(probe 只认 id),表单里改了还没保存的东西不会体现在这里 -->
            <el-button size="small" :loading="probeLoading" :icon="Refresh" @click="loadProbe">
              刷新运行状态
            </el-button>
            <span class="aw-dim">
              {{ editingId ? '下面是这张图现在的真实状态(行数 / 物化视图在不在 / 服务表落后没)' : '保存后可以看到每张源表的行数与物化视图状态' }}
            </span>
          </div>
          <ArbTopology
            :sources="form.sources"
            :columns="buildColumns()"
            :agg-table-name="tableFullName"
            :svc-table-name="svcTableFullName"
            :probe="probe"
            class="wz-topo"
          />

          <div class="wz-summary">
            <div><span class="aw-dim">源</span> {{ form.sources.length }} 张 → <b>{{ form.sources.length }} 个物化视图</b></div>
            <div><span class="aw-dim">字段</span> {{ form.fields.length }} 个源字段 → <b>{{ outNames.length }} 个目标列</b></div>
            <div>
              <span class="aw-dim">将创建</span>
              <b class="mono">{{ tableFullName }}</b>（聚合层）+ <b class="mono">{{ svcTableFullName }}</b>（目标表）
            </div>
          </div>
          <div class="wz-toolbar">
            <el-button type="primary" plain :loading="previewing" @click="doPreview">生成 / 刷新 SQL 预览</el-button>
            <span class="aw-dim">先预览再执行；执行是「临时表灌数 → 原子换名」，失败不影响现有数据</span>
          </div>

          <template v-if="plan">
            <el-alert
              v-for="(w, i) in plan.warnings || []"
              :key="i"
              type="warning"
              :closable="false"
              class="wz-alert"
              :title="w"
            />
            <el-tabs v-model="planTab">
              <el-tab-pane label="聚合表" name="ddl">
                <pre class="wz-sql">{{ plan.aggDdl }}</pre>
              </el-tab-pane>
              <el-tab-pane :label="`物化视图(${plan.mvs.length})`" name="mv">
                <p class="wz-tip">贴着源表：源表插入数据时自动流进聚合表，不用重跑工作流。</p>
                <div v-for="(m, i) in plan.mvs" :key="i" class="wz-stmt">
                  <div class="wz-stmt-head">{{ m.source }}</div>
                  <pre class="wz-sql">{{ m.sql }}</pre>
                </div>
              </el-tab-pane>
              <el-tab-pane :label="`回填历史(${plan.backfills.length})`" name="back">
                <p class="wz-tip">
                  物化视图只接住<b>之后</b>的写入；这一步把源表里已有的数据补进聚合表。不跑它，聚合表是空的。
                </p>
                <div v-for="(b, i) in plan.backfills" :key="i" class="wz-stmt">
                  <div class="wz-stmt-head">{{ b.source }}</div>
                  <pre class="wz-sql">{{ b.sql }}</pre>
                </div>
              </el-tab-pane>
              <el-tab-pane label="服务表" name="svc">
                <p class="wz-tip">
                  聚合层存的是仲裁状态,业务不能直接查 —— 摊平成<b>目标表</b>（这张）才是给你查的。
                  点「生成 / 更新」的最后一步就会摊平;之后聚合层会被物化视图实时更新,
                  但<b>服务表要单独刷</b>(它必须全表 GROUP BY 才能摊,物化视图做不到)。
                </p>
                <div class="wz-stmt-head">{{ plan.svcTable }}</div>
                <pre class="wz-sql">{{ plan.svcDdl }}</pre>
                <div class="wz-stmt-head">摊平语句(走临时表 + EXCHANGE 原子换名)</div>
                <pre class="wz-sql">{{ plan.svcSelect }}</pre>
              </el-tab-pane>
              <el-tab-pane label="聚合表怎么读" name="read">
                <p class="wz-tip">聚合层存的是中间状态，<b>不能</b> <code>SELECT *</code>：未合并时一个号码会多行。</p>
                <el-table :data="plan.columns" size="small" border>
                  <el-table-column label="目标列" prop="name" width="160" />
                  <el-table-column label="方式" prop="mode" width="110" />
                  <el-table-column label="聚合表列类型" prop="stateType" min-width="280" />
                  <el-table-column label="读取写法" width="190">
                    <template #default="{ row }"><code>{{ row.read }}</code></template>
                  </el-table-column>
                </el-table>
                <pre class="wz-sql">SELECT mdn,
  {{ readList }}
 FROM {{ plan.aggTable }}
 GROUP BY mdn;</pre>
              </el-tab-pane>
            </el-tabs>
          </template>
        </section>
      </div>

      <div class="wz-foot">
        <el-button v-if="step > 0" :icon="Back" @click="step--">上一步</el-button>
        <div class="wz-foot-gap"></div>
        <span v-if="stepTip" class="aw-dim">{{ stepTip }}</span>
        <el-button v-if="step < 4" type="primary" @click="next">下一步</el-button>
        <!-- 前四步「下一步」只做校验、不落库;保存只在第⑤步出现,避免配到一半就建了表 -->
        <template v-else>
          <el-button :loading="saving" @click="save(false)">仅保存</el-button>
          <el-button type="success" :loading="saving" @click="save(true)">保存并生成</el-button>
        </template>
      </div>
    </template>

    <!-- ═══════════ 拓扑 ═══════════ -->
    <!-- 列表页点「查看拓扑」时开:结构从配置推导,现场状态(行数/物化视图)靠 probe 补 -->
    <el-dialog v-model="topoVisible" title="工作流拓扑" width="860px" top="5vh">
      <ArbTopology
        v-if="topoRow"
        :sources="topoRow.sources || []"
        :columns="topoRow.columns || []"
        :agg-table-name="topoRow.targetTable + '_agg'"
        :svc-table-name="topoRow.targetTable + '_svc'"
        :probe="topoProbe"
      />
    </el-dialog>

    <!-- ═══════════ 处理链 ═══════════ -->
    <!-- sql-only:这里只配"怎么加工这一列",不预览数据,所以编辑器只出 SQL -->
    <el-dialog v-model="stepsVisible" :title="stepsTitle" width="900px" top="6vh" append-to-body>
      <FieldPipelineEditor
        v-if="stepsCtx"
        :col="stepsCtx.col"
        :kind="kindOf(stepsCtx.type)"
        :steps="curSteps"
        :prog="[]"
        sql-only
        @update:steps="onStepsUpdate"
      />
      <template #footer>
        <el-button type="primary" @click="stepsVisible = false">完成</el-button>
      </template>
    </el-dialog>

    <!-- ═══════════ 执行历史 ═══════════ -->
    <el-dialog v-model="runsVisible" title="执行历史" width="1000px" top="6vh">
      <el-table v-loading="runsLoading" :data="runs" row-key="id" border size="small" max-height="520">
        <el-table-column label="时间" width="170">
          <template #default="{ row }">{{ fmtDt(row.startedAt) }}</template>
        </el-table-column>
        <el-table-column label="动作" width="90">
          <template #default="{ row }">{{ actionLabel(row.action) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="statusType(row.status)" size="small">{{ statusLabel(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="行数" width="100">
          <template #default="{ row }">{{ fmtRows(row.rows) }}</template>
        </el-table-column>
        <el-table-column label="说明" min-width="300">
          <template #default="{ row }">
            <div v-if="row.error" class="aw-err">{{ row.error }}</div>
            <div v-if="row.progress">{{ row.progress }}</div>
            <el-collapse v-if="row.sql && !row.progress">
              <el-collapse-item title="实际执行的 SQL">
                <pre class="wz-sql">{{ row.sql }}</pre>
              </el-collapse-item>
            </el-collapse>
          </template>
        </el-table-column>
      </el-table>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, h, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { confirmAction } from '@/utils/confirm'
import { Back, Delete, MagicStick, Plus, Refresh } from '@element-plus/icons-vue'
import { arbWorkflowsApi, connectionsApi, workspaceApi } from '@/api'
import { WIDE_TABLE_ENABLED } from '@/utils/features'
import { chTypeToKind, type ValueKind } from '@/constants/queryBuilderMeta'
import FieldPipelineEditor from '@/components/visual/FieldPipelineEditor.vue'
import ArbTopology from '@/components/arb/ArbTopology.vue'
import ActionIconBar from '@/components/ActionIconBar.vue'
import ListQueryBar from '@/components/ListQueryBar.vue'
import ListPageTemplate from '@/components/ListPageTemplate.vue'
import { useListQuery } from '@/composables/useListQuery'
import { ARB_DEFAULT_CFG, arbAutoMap, arbBuildColumns, arbDetectMdn, arbOutNames, arbSumTypeIssue } from '@/utils/arbFields'
import { fmtDt } from '@/utils/format'
import type {
  ArbColumn,
  ArbField,
  ArbMode,
  ArbPlan,
  ArbProbe,
  ArbRule,
  ArbSource,
  ArbWorkflow,
  ArbWorkflowInput,
  ArbWorkflowRun,
  Connection,
  QueryPipelineStep,
  TableInfo,
} from '@/types'

const router = useRouter()

async function goToResultTable(row: ArbWorkflow) {
  const table = `${row.targetTable}_svc`
  try {
    const tables = await workspaceApi.tables(row.connectionId, row.targetDb)
    if (!tables.some((item) => item.name === table)) {
      ElMessage.warning(`目标表 ${table} 尚未生成，请先执行工作流`)
      return
    }
  } catch {
    ElMessage.warning('目标表校验失败，请确认连接和工作流状态')
    return
  }
  if (row.targetDb === 'dwd_data') {
    void router.push({ path: '/dwd', query: { table, view: 'target', conn: String(row.connectionId) } })
  } else {
    void router.push({ path: '/workspace', query: { conn: String(row.connectionId), db: row.targetDb, tbl: table } })
  }
}

// ─────────── 列表 ───────────
const workflowActions = [
  { command: 'setup', label: '生成 / 更新', primary: true },
  { command: 'edit', label: '编辑配置' },
  { command: 'topology', label: '查看拓扑' },
  { command: 'rebuild', label: '重建聚合层' },
  { command: 'flatten', label: '刷新服务表' },
  { command: 'probe', label: '查看现状' },
  { command: 'runs', label: '执行历史' },
  { command: 'drop', label: '删除产物', danger: true },
  { command: 'remove', label: '删除工作流', danger: true },
]

function workflowError(e: any, fallback: string): string {
  const message = String(e?.message || fallback)
  if (message.includes('未知函数') && message.includes('aes256_dec')) {
    return '当前 Java 服务尚未加载 AES 解密算子 aes256_dec，请重启后端再试；号码列本身没有类型错误'
  }
  return message
}

const loading = ref(false)
/** 当前正在观察的工作流 id，用于该行按钮和勾选的忙碌态；它是单个值，不是全局运行锁。 */
const busyIds = ref(new Set<number>())
// 批量删除勾选
const wfTableRef = ref<any>(null)
const selectedWfs = ref<ArbWorkflow[]>([])
/** 批量删除进行中不许再点(与单条删除同口径:只删配置,产物不动) */
const batching = ref(false)
function onWfSelectionChange(sel: ArbWorkflow[]) {
  selectedWfs.value = sel
}
const list = ref<ArbWorkflow[]>([])
const query = useListQuery()
function resetListQuery() {
  query.reset()
  page.value = 1
  void load()
}
const shownWorkflows = computed(() => {
  const words = query.keyword.value.toLowerCase().split(/\s+/).filter(Boolean)
  if (!words.length) return list.value
  return list.value.filter((row) => {
    const text = `${row.name} ${row.description ?? ''} ${row.targetTable} ${(row.sources ?? []).map((source) => source.table).join(' ')}`.toLowerCase()
    return words.every((word) => text.includes(word))
  })
})
const conns = ref<Connection[]>([])
const page = ref(1)
const pageSize = ref(10)
const pagedWorkflows = computed(() => shownWorkflows.value.slice((page.value - 1) * pageSize.value, page.value * pageSize.value))
watch(query.keyword, () => { page.value = 1 })
let loadSeq = 0

async function load() {
  const current = ++loadSeq
  loading.value = true
  try {
    const rows = await arbWorkflowsApi.list()
    if (current === loadSeq) list.value = rows
  } catch (e: any) {
    // 列表是这一页的全部内容,失败必须自己说话(不能只靠拦截器)
    if (current === loadSeq) ElMessage.error(e?.message || '加载工作流失败')
  } finally {
    if (current === loadSeq) loading.value = false
  }
}

function removeDeletedWfs(ids: Set<number>) {
  ++loadSeq // 已发出的列表请求不能把刚删掉的行重新带回来
  loading.value = false
  list.value = list.value.filter((item) => !ids.has(item.id))
}

/** 执行状态 → tag 类型;未知(空/从未执行)兜 info */
function statusType(s?: string) {
  return s === 'success' ? 'success' : s === 'failed' ? 'danger' : s === 'running' ? 'warning' : 'info'
}
function statusLabel(s?: string) {
  return s === 'success' ? '成功' : s === 'failed' ? '失败' : s === 'running' ? '执行中' : '未执行'
}
/** 执行动作的中文名。三种动作得分清楚:flatten 以前被显示成「生成」,看起来像重建了。 */
function actionLabel(a?: string) {
  return a === 'rebuild' ? '重建聚合层' : a === 'flatten' ? '摊平服务表' : '生成'
}
/** 行数:上万就换算成"万行"(宽表常有几百万行,原样显示读不出量级) */
function fmtRows(n?: number) {
  if (!n) return '—'
  return n >= 10000 ? `${(n / 10000).toFixed(1)} 万行` : `${n} 行`
}
/** 表格里只放错误前 60 字:整段报错会撑破行高,完整内容在 title 里 */
function shortErr(s: string) {
  return s.length > 60 ? s.slice(0, 60) + '…' : s
}
/** 列表页要显示"几个目标列",与向导共用同一套推导(arbOutNames) */
function outNamesOf(row: ArbWorkflow) {
  return arbOutNames(row.fields || [])
}

// ─────────── 向导状态 ───────────
const editing = ref(false)
const step = ref(0)
const editingId = ref(0)
const saving = ref(false)
const previewing = ref(false)
const plan = ref<ArbPlan | null>(null)
const planTab = ref('ddl')

const wizardTitle = computed(() =>
  editingId.value ? `编辑工作流 · ${form.name || ''}` : '新建工作流'
)
/** 聚合表全名:库概念对用户隐藏,只给表名(前端带前缀、后端拼 _agg) */
const tableFullName = computed(() => (form.targetTable ? `dwd_${form.targetTable}_agg` : '?'))
/** 服务表全名(摊平后的普通列表) */
const svcTableFullName = computed(() => (form.targetTable ? `dwd_${form.targetTable}_svc` : '?'))
/** 底栏中间的步骤提示;首末两步不显示(第 1 步没有"上一步",第 5 步有别的说明) */
const stepTip = computed(() => {
  if (step.value === 0) return ''
  if (step.value === 4) return ''
  return `第 ${step.value + 1} / 5 步`
})

let seq = 0
// 源 id 是字段/结构缓存的挂载点。加时间戳再带回自增:连点「再加一个源」不会在同一毫秒里撞 id
const nextSrcId = () => `s${Date.now().toString(36)}${seq++}`

const form = reactive({
  name: '',
  description: '',
  enabled: true,
  /** 标记为宽表:目标库表（dwd_data）页的「宽表」页签直接显示这张表的数据 */
  isWide: false,
  connectionId: 0,
  targetDb: 'dwd_data',
  targetTable: '',
  sources: [] as ArbSource[],
  fields: [] as ArbField[],
})

/** 每个目标列的取值方式(字典:改目标列名时不会错位) */
const colCfg = reactive<Record<string, { mode: ArbMode; rule: ArbRule }>>({})
/**
 * 源表结构缓存(按源 id)。换表 / 换连接时必须一起清 ——
 * 留着的话③里会挂出源表根本没有的列,而报错要等到生成 SQL 那一步。
 */
const srcCols = reactive<Record<string, { name: string; type: string; comment?: string }[]>>({})
/** 库 → 表清单(下拉选项),拉一次就够 */
const tablesByDb = reactive<Record<string, TableInfo[]>>({})

const cfgOf = (name: string) => colCfg[name] || ARB_DEFAULT_CFG
const sumIssue = (name: string) => arbSumTypeIssue(form.fields, name)
/** 改取值方式时保留已选的仲裁规则(两个字段是一组配置,不能各改各的丢一个) */
function setMode(name: string, mode: ArbMode) {
  if (mode === 'sum') {
    const issue = sumIssue(name)
    if (issue) {
      ElMessage.warning(issue)
      return
    }
  }
  colCfg[name] = { mode, rule: cfgOf(name).rule }
}
function setRule(name: string, rule: ArbRule) {
  colCfg[name] = { mode: cfgOf(name).mode, rule }
}

const outNames = computed(() => arbOutNames(form.fields))
/**
 * 第④步那张表:每个目标列 + 谁在提供它。
 * 归属判定必须用 `outName || col`(与 arbOutNames 同一口径),只看 outName 会让没改过名的列来源显示成空;
 * 源查不到时退化成 '?',不要把整行丢掉 —— 少一个目标列比拼错更糟。
 */
const outRows = computed(() =>
  outNames.value.map((name) => ({
    name,
    origins: form.fields
      .filter((f) => (f.outName || f.col) === name)
      .map((f) => ({ src: form.sources.find((x) => x.id === f.sourceId)?.table || '?', col: f.col })),
  }))
)
/** ⑤ 里那段示例 SQL 的读取列表:还没预览时给 '...',避免出现半截语句 */
const readList = computed(
  () => (plan.value?.columns || []).map((c) => `${c.read} AS ${c.name}`).join(',\n  ') || '...'
)

// 三个取值器都做成函数而不是 computed:它们的入参(源 id / 库名)是动态的,
// 模板里按行调用,放进 computed 反而要建一堆缓存。
const tablesOf = (db: string) => tablesByDb[db] || []
const colsOf = (srcId: string) => srcCols[srcId] || []
/** 只有日期/时间类型的列能当时间列 */
const timeColsOf = (srcId: string) => colsOf(srcId).filter((c) => /Date|Time/i.test(c.type))
/** timeColsOf 的等价别名(语义化命名),取值逻辑完全一样,不要在这里另写一套过滤 */
const dateTimeColsOf = (srcId: string) => timeColsOf(srcId)
/** 表注释(下拉与选中后都显示) */
function tableRemark(name: string) {
  if (!name) return ''
  for (const list of Object.values(tablesByDb)) {
    const t = list?.find((x) => x.name === name)
    if (t) return t.remark || ''
  }
  return ''
}
/**
 * 这张表是否已被【别的源】选过。
 * 同一个源重复选同一张表没有意义(每源一个物化视图,两次会撞名字),
 * 所以直接在下拉里禁掉 —— 比选完再报错好。
 */
function tableUsedBy(name: string, exceptSourceId: string) {
  return form.sources.some((s) => s.id !== exceptSourceId && s.table === name)
}
const fieldsOf = (srcId: string) => form.fields.filter((f) => f.sourceId === srcId)
const fieldOf = (srcId: string, col: string) => form.fields.find((f) => f.sourceId === srcId && f.col === col)
/** 字段行上那个按钮的文案:配过几步就直接显示步数,让人不用点开也知道动过它 */
function stepLabel(srcId: string, col: string) {
  const n = fieldOf(srcId, col)?.steps?.length || 0
  return n ? `已配 ${n} 步` : '处理链'
}
/** 列类型 → 处理链编辑器认识的取值类别(类型推导只有一处,别在模板里各写一份) */
const kindOf = (t: string): ValueKind => chTypeToKind(t)

// ─────────── 打开 / 关闭 ───────────
/** 连接只在需要时拉一次;失败就留空数组,表单里的 connectionId 会退化成 0(不阻塞向导) */
async function ensureConns() {
  if (conns.value.length) return
  try {
    conns.value = await connectionsApi.list()
  } catch {
    conns.value = []
  }
}

function resetForm() {
  form.name = ''
  form.description = ''
  form.enabled = true
  form.isWide = false
  // 默认选默认连接,没有就取第一个(与其它页面同一套顺序)
  form.connectionId = conns.value.find((c) => c.isDefault)?.id || conns.value[0]?.id || 0
  form.targetDb = 'dwd_data'
  form.targetTable = '' // 只填中间那段;前后缀后端拼(见 tableFullName)
  form.sources = []
  form.fields = []
  // 逐键删除而不是整体赋值:colCfg/srcCols 是 reactive 对象,换引用会让已建立的响应连接不上
  Object.keys(colCfg).forEach((k) => delete colCfg[k])
  Object.keys(srcCols).forEach((k) => delete srcCols[k])
  plan.value = null
}

async function openCreate() {
  editingId.value = 0
  resetForm()
  step.value = 0
  editing.value = true
  addSource(false)
  if (form.connectionId) await loadTables('ods_data')
  openSnapshot.value = wizardSnapshot()
}

async function openEdit(row: ArbWorkflow) {
  editingId.value = row.id
  resetForm()
  form.name = row.name
  form.description = row.description
  form.enabled = row.enabled
  form.isWide = !!row.isWide
  form.connectionId = row.connectionId
  form.targetDb = row.targetDb
  // 只回显中间那段(库里存的是完整基础名 dwd_xxx)
  form.targetTable = row.targetTable.startsWith('dwd_') ? row.targetTable.slice(4) : row.targetTable
  // 深拷贝:直接引用列表里的对象会让「返回」也把改动留在列表上
  form.sources = JSON.parse(JSON.stringify(row.sources || []))
  form.fields = JSON.parse(JSON.stringify(row.fields || []))
  for (const c of row.columns || []) {
    colCfg[c.name] = { mode: c.mode, rule: (c.rule || 'pri_time') as ArbRule }
  }
  step.value = 0
  editing.value = true
  // 开编辑就逐源把表清单与列结构读回来:②③两步要立刻显示列名和注释,
  // 不能等用户翻到那一步才加载(那时下拉是空的,看起来像配丢了)
  for (const s of form.sources) {
    await loadTables(s.database)
    await loadSchema(s)
  }
  openSnapshot.value = wizardSnapshot()
}

function closeWizard() {
  if (wizardSnapshot() !== openSnapshot.value) {
    confirmDiscard()
    return
  }
  editing.value = false
}

/** 打开向导时的配置快照(JSON 串)。用来判断「返回」是不是真的会丢掉改动。 */
const openSnapshot = ref('')

/**
 * 当前配置的快照。只取会被保存的字段 —— srcCols(库表结构缓存)不算用户改动。
 */
function wizardSnapshot(): string {
  return JSON.stringify({
    name: form.name,
    description: form.description,
    enabled: form.enabled,
    isWide: form.isWide,
    targetDb: form.targetDb,
    targetTable: form.targetTable,
    sources: form.sources,
    fields: form.fields,
    colCfg,
  })
}

async function confirmDiscard() {
  try {
    await ElMessageBox.confirm('返回列表会丢掉这次未保存的改动,确定吗?', '未保存的改动', {
      type: 'warning',
      confirmButtonText: '丢掉改动',
      cancelButtonText: '继续编辑',
    })
  } catch {
    return
  }
  editing.value = false
}

async function onConnectionChange() {
  // 连接换了,旧连接的表清单与所有源的结构全部作废(留着会指到别的库上去)
  Object.keys(tablesByDb).forEach((k) => delete tablesByDb[k])
  Object.keys(srcCols).forEach((k) => delete srcCols[k])
  await loadTables('ods_data')
}

async function loadTables(db: string) {
  if (tablesByDb[db] || !form.connectionId) return
  try {
    tablesByDb[db] = await workspaceApi.tables(form.connectionId, db)
  } catch {
    tablesByDb[db] = []
  }
}

async function loadSchema(s: ArbSource) {
  if (!form.connectionId || !s.table) return
  try {
    const res = await workspaceApi.schema(form.connectionId, s.database, s.table)
    // 按【列名】找下标,不按固定位置取 —— 后端给 DESCRIBE 结果加列时不会静默串列
    const iName = res.columns.indexOf('name')
    const iType = res.columns.indexOf('type')
    // DESCRIBE TABLE 的输出里有 comment 列(列注释),有就带上
    const iCmt = res.columns.indexOf('comment')
    srcCols[s.id] = res.rows.map((r) => ({
      name: String(r[iName >= 0 ? iName : 0] ?? ''),
      type: String(r[iType >= 0 ? iType : 1] ?? ''),
      comment: iCmt >= 0 ? String(r[iCmt] ?? '') : '',
    }))
    // 号码列自动识别:只在还没配的时候填(已有配置说明用户/上次已经定过了)
    if (!s.mdnCol) {
      const d = detectMdn(s.id)
      if (d.col) {
        s.mdnCol = d.col
        mdnSource[s.id] = d.from === 'default' ? 'default' : 'comment'
      } else {
        // 认不出来(既没注释也不是 mdn)→ 直接让用户选,不要让他猜为什么空着
        mdnEditing[s.id] = true
        ElMessage.warning(`${s.table} 没认出号码列(列注释里没有「手机号」、也没有 mdn),请手动选一列`)
      }
    }
  } catch (e: any) {
    ElMessage.error(`读表结构失败(${s.table}):${e?.message || e}`)
    // 失败就清空:留着半截结构会让用户拿一组过期的列名一路配到生成 SQL 才报错
    srcCols[s.id] = []
  }
}

async function onSourceTableChange(s: ArbSource, table: string) {
  s.table = table
  // 换表 → 该源的字段全清(列名对不上了,留着只会生成错 SQL)
  form.fields = form.fields.filter((f) => f.sourceId !== s.id)
  delete srcCols[s.id]
  // 号码列 / 时间列是按【旧表】的列名选的,换表后多半不存在 —— 一起清掉重新识别
  s.mdnCol = ''
  s.timeCol = ''
  await loadSchema(s)
}

function addSource(autoload = true) {
  // 先加的源更权威:新源取「现有最小值 - 1」排在最后(第一个源给 2)。
  // 夹在 0..255 是因为 pri 会作为排名位进 SQL;降到 0 就不再降,要不要反超由用户在第②步自己调。
  const minPri = form.sources.length ? Math.min(...form.sources.map((x) => x.pri)) : 2
  form.sources.push({
    id: nextSrcId(),
    database: 'ods_data',
    table: '',
    mdnCol: '',
    timeCol: '',
    pri: Math.max(0, Math.min(255, minPri - 1)),
    where: '',
    enabled: true,
  })
  if (autoload) void loadTables('ods_data')
}

function removeSource(i: number) {
  const s = form.sources[i]
  form.sources.splice(i, 1)
  // 字段与结构缓存必须跟着源一起删 —— 留下的字段指向已不存在的源,保存会被后端拒,
  // 而且④里会凭空多出几个没有任何来源的目标列
  form.fields = form.fields.filter((f) => f.sourceId !== s.id)
  delete srcCols[s.id]
}

// ─────────── 字段 ───────────
function toggleField(s: ArbSource, col: { name: string; type: string; comment?: string }) {
  const idx = form.fields.findIndex((f) => f.sourceId === s.id && f.col === col.name)
  if (idx >= 0) {
    // 取消挂载只删字段,不动 colCfg:同名的目标列可能还有别的源挂着,
    // 列的取值方式该不该回收由 setOutName 判断
    form.fields.splice(idx, 1)
    return
  }
  // outName 默认 = 源列名。「同名即汇到同一目标列」这条规则就靠这个默认值成立。
  // 源列注释一并带过来当默认业务说明(后端把它写成 COMMENT);空的不带。
  const f: ArbField = { sourceId: s.id, col: col.name, outName: col.name, colType: col.type, steps: [] }
  if (col.comment) f.comment = col.comment
  form.fields.push(f)
}
function setOutName(s: ArbSource, col: string, v: string) {
  const f = fieldOf(s.id, col)
  if (!f) return
  const old = f.outName
  f.outName = v
  // 取值方式存在 colCfg 里,而它的键是【目标列名】。改列名时不把配置迁过去的话,
  // 刚在④里设好的 sum / max / min 会静默变回「仲裁」(数值列会按字符串比大小)。
  if (!old || old === v || !colCfg[old]) return
  // 复制一份再迁:两个源可能共用一个目标列名,直接传引用会让两列互相改到
  colCfg[v] = { ...colCfg[old] }
  // 旧列名还有别的源的字段挂着 → 留着;没人挂了 → 删掉,否则④里会多出一个幽灵列
  if (!form.fields.some((x) => x.outName === old)) delete colCfg[old]
}
function autoMap() {
  form.fields = arbAutoMap(form.sources, srcCols, form.fields)
  ElMessage.success(`已按列名挂载 ${form.fields.length} 个字段 → ${outNames.value.length} 个目标列`)
}
function clearFields() {
  form.fields = []
}

// ─────────── 处理链 ───────────
const stepsVisible = ref(false)
const stepsCtx = ref<{ kind: 'field' | 'mdn'; sourceId: string; col: string; type: string } | null>(null)
const stepsTitle = computed(() => {
  const c = stepsCtx.value
  if (!c) return '处理链'
  const s = form.sources.find((x) => x.id === c.sourceId)
  return c.kind === 'mdn' ? `号码列处理链 · ${s?.table}.${c.col}` : `字段处理链 · ${s?.table}.${c.col}`
})
const curSteps = computed<QueryPipelineStep[]>(() => {
  const c = stepsCtx.value
  if (!c) return []
  if (c.kind === 'mdn') return form.sources.find((s) => s.id === c.sourceId)?.mdnSteps || []
  return fieldOf(c.sourceId, c.col)?.steps || []
})
function openFieldSteps(s: ArbSource, col: { name: string; type: string }) {
  stepsCtx.value = { kind: 'field', sourceId: s.id, col: col.name, type: col.type }
  stepsVisible.value = true
}
function openMdnSteps(s: ArbSource) {
  if (!s.mdnCol) {
    ElMessage.warning('先选号码列')
    return
  }
  const c = colsOf(s.id).find((x) => x.name === s.mdnCol)
  stepsCtx.value = { kind: 'mdn', sourceId: s.id, col: s.mdnCol, type: c?.type || 'String' }
  stepsVisible.value = true
}
function onStepsUpdate(v: QueryPipelineStep[]) {
  const c = stepsCtx.value
  if (!c) return
  if (c.kind === 'mdn') {
    const s = form.sources.find((x) => x.id === c.sourceId)
    if (s) s.mdnSteps = v
    return
  }
  const f = fieldOf(c.sourceId, c.col)
  if (f) f.steps = v
}

// ─────────── 分步校验 ───────────
/** 返回当前步骤的阻塞原因(空 = 可以通过)。 */
function stepError(s: number): string | null {
  if (s === 0) {
    if (!form.name.trim()) return '请填工作流名称'
    if (!form.targetTable.trim()) return '请填聚合表名'
    return null
  }
  if (s === 1) {
    if (!form.sources.length) return '至少要有 1 个源'
    for (const [i, src] of form.sources.entries()) {
      if (!src.table) return `源 ${i + 1} 还没选表`
      if (!src.mdnCol) return `源 ${i + 1}(${src.table}) 还没选号码列`
    }
    return null
  }
  if (s === 2) {
    if (!form.fields.length) return '至少挂一个字段(可以点「按同名列自动挂载」)'
    // 按源查:只查总数的话,「已有字段之后再新加一个源」会一路过到保存才被后端拒绝,
    // 而那个源不挂字段时它的物化视图只会往聚合层塞一行空号码。
    for (const [i, src] of form.sources.entries()) {
      if (!form.fields.some((f) => f.sourceId === src.id)) {
        return `源 ${i + 1}(${src.table || '未选表'}) 还没挂字段`
      }
    }
    return null
  }
  if (s === 3) {
    // 目标列名会直接当 DDL 里的标识符用,空名建表是语法错误 —— 拦在这一步,别等到执行
    for (const n of outNames.value) {
      if (!n.trim()) return '有目标列的列名是空的'
      if (cfgOf(n).mode === 'sum') {
        const issue = sumIssue(n)
        if (issue) return issue
      }
    }
    return null
  }
  return null
}

async function next() {
  const bad = stepError(step.value)
  if (bad) {
    ElMessage.warning(bad)
    return
  }
  // 进入「字段」步骤时,若还没挂字段就先自动挂一遍 —— 少点几下
  if (step.value === 1 && !form.fields.length) {
    form.fields = arbAutoMap(form.sources, srcCols, [])
    // 自动挂载不算用户改动,刷新快照 —— 否则「返回」时会凭空多一个「丢改动」确认
    openSnapshot.value = wizardSnapshot()
  }
  step.value++
  if (step.value === 4) {
    plan.value = null
    probe.value = null
    void loadProbe() // 编辑已有工作流时,进来就把真实状态拉出来
  }
}

async function goToStep(target: number) {
  if (target === step.value || target < 0 || target > 4) return
  if (target < step.value) {
    step.value = target
    return
  }
  while (step.value < target) {
    const before = step.value
    await next()
    if (step.value === before) return
  }
}

/** 当前表单对应的列配置(拓扑图与保存共用同一份推导) */
function buildColumns(): ArbColumn[] {
  return arbBuildColumns(outNames.value, colCfg)
}

// ─────────── 号码列:自动识别(映射同步已指定)───────────
/**
 * 每源号码列的识别来源(仅界面提示用,不落库):
 *
 *   comment  列注释里含「手机号」—— 映射同步写的标记,最可信
 *             (ods_user_md5 的号码列叫 mdn_md5,只能靠注释认出来)
 *   default  列名恰好是 mdn —— 字典里的默认字段名
 *   manual   用户手选的(或识别失败后选的)
 */
const mdnSource = reactive<Record<string, 'comment' | 'default' | 'manual'>>({})
const mdnEditing = reactive<Record<string, boolean>>({})

/** 从表结构里认出号码列(与「一键生成汇总草稿」共用同一套规则,别各写一份) */
function detectMdn(srcId: string) {
  return arbDetectMdn(colsOf(srcId))
}

/** 某列的注释(选中后在旁边显示,省得回表结构里翻)。 */
function commentOf(srcId: string, col?: string) {
  if (!col) return ''
  return colsOf(srcId).find((c) => c.name === col)?.comment || ''
}

/** 号码列:只读展示 ↔ 手选,来回切 */
function toggleMdnEdit(s: ArbSource) {
  mdnEditing[s.id] = !mdnEditing[s.id]
  // 手选完成就算人工确认过:即使后来自动识别出注释来源,也以这里为准
  if (!mdnEditing[s.id] && s.mdnCol) mdnSource[s.id] = 'manual'
}

/**
 * 该源的号码列是不是哈希值 —— 和明文源混用会合不到一起。
 * 判定只用列名与列注释(库里没有别的线索):名字里带 md5/sha 就警告。
 */
function mdnIsHashed(s: ArbSource) {
  if (!s.mdnCol) return false
  const c = colsOf(s.id).find((x) => x.name === s.mdnCol)
  const text = `${s.mdnCol} ${c?.comment || ''}`.toLowerCase()
  return text.includes('md5') || text.includes('sha')
}

// ─────────── 拓扑 / 现场状态 ───────────
const probe = ref<ArbProbe | null>(null)
const probeLoading = ref(false)
const topoVisible = ref(false)
const topoRow = ref<ArbWorkflow | null>(null)
const topoProbe = ref<ArbProbe | null>(null)

async function loadProbe() {
  // 还没保存过就没有 id,现状读不出来 —— 直接提示,别发一个注定失败的请求
  if (!editingId.value) {
    ElMessage.info('先保存工作流，才能读它的行数与物化视图状态')
    return
  }
  probeLoading.value = true
  try {
    probe.value = await arbWorkflowsApi.probe(editingId.value)
  } catch (e: any) {
    ElMessage.error(e?.message || '读取运行状态失败')
  } finally {
    probeLoading.value = false
  }
}

async function showTopology(row: ArbWorkflow) {
  topoRow.value = row
  // 先清空再拉:上一条工作流的行数/视图状态不能停在新拓扑上
  topoProbe.value = null
  topoVisible.value = true
  try {
    topoProbe.value = await arbWorkflowsApi.probe(row.id)
  } catch {
    // 拓扑结构是从配置推出来的,不依赖库;探不到现场状态就只画结构,不打扰用户
    topoProbe.value = null
  }
}

// ─────────── 保存 / 预览 ───────────
function buildInput(): ArbWorkflowInput {
  return {
    name: form.name,
    description: form.description,
    enabled: form.enabled,
    isWide: form.isWide,
    // 界面上不给选连接(用默认连接),但接口要带上
    connectionId: form.connectionId,
    targetDb: form.targetDb,
    targetTable: form.targetTable,
    sources: form.sources,
    fields: form.fields,
    columns: arbBuildColumns(outNames.value, colCfg),
  }
}

async function doPreview() {
  // 触发点在最后一步,但缺配置可能出在前面任意一步:逐张校验并把用户送回出错那一步,
  // 只弹一句提示的话他得自己猜是哪一屏填漏了
  for (const s of [0, 1, 2, 3]) {
    const bad = stepError(s)
    if (bad) {
      ElMessage.warning(bad)
      step.value = s
      return
    }
  }
  previewing.value = true
  try {
    plan.value = await arbWorkflowsApi.preview(buildInput())
    // 每次都回到第一个页签:上一次预览停在「服务表」的话,新 SQL 会看着像没生成
    planTab.value = 'ddl'
    ElMessage.success('SQL 已生成 —— 见下方各标签页')
  } catch (e: any) {
    // 预览失败要清掉旧 plan —— 留着上一版 SQL 会让人以为这版已经能跑了
    plan.value = null
    ElMessage.error(workflowError(e, '预览失败'))
  } finally {
    previewing.value = false
  }
}

async function save(andRun: boolean) {
  // 同 doPreview:保存前把前四步全过一遍,有错就跳回那一屏
  for (const s of [0, 1, 2, 3]) {
    const bad = stepError(s)
    if (bad) {
      ElMessage.warning(bad)
      step.value = s
      return
    }
  }
  const creating = !editingId.value
  const action = creating ? '新建工作流' : '保存工作流'
  const tip = andRun
    ? `工作流内容编辑完成是否需要保存？保存后将立即生成聚合表、物化视图并回填历史数据。`
    : creating
      ? `确认新建工作流「${form.targetTable}」？`
      : '工作流内容编辑完成是否需要保存'
  if (!(await confirmAction(action, tip, andRun ? '确认保存并生成' : '确认保存'))) return
  saving.value = true
  try {
    const input = buildInput()
    const saved = editingId.value
      ? await arbWorkflowsApi.update(editingId.value, input)
      : await arbWorkflowsApi.create(input)
    // 记住新建出来的 id:接着「保存并生成」才有东西可跑,再点一次「仅保存」也会走 update 而不是又插一条
    if (creating) query.reset()
    editingId.value = saved.id
    ElMessage.success('已保存')
    // 先回列表再执行:建表 + 回填要跑一会儿,列表里能看到「最近执行」在转、
    // 跑完那一列自己刷新;留在第⑤步反而没有任何进度反馈
    editing.value = false
    await load()
    if (andRun) await runById(saved.id, 'setup')
  } catch (e: any) {
    ElMessage.error(workflowError(e, '保存失败'))
  } finally {
    saving.value = false
  }
}

// ─────────── 执行 ───────────
/**
 * 每次 setup/rebuild/flatten 观察都持有一个 AbortController，集合用于组件卸载时统一停止前端请求。
 * abort 只结束启动/查询的 HTTP 观察，不会取消已经提交到服务端的后台工作流；任务失败信息仍由执行历史负责展示。
 */
const runObservers = new Set<AbortController>()
/**
 * 工作流后台运行的前端流程：先调用 start 取得 runId，再由 API 层轮询运行状态，完成后才回到这里提示结果。
 * busyIds 按工作流分别锁定当前行；finally 只清理本次 ID，不影响其他并发任务。
 * 页面离开时抑制观察器取消提示；后台终态失败必须显示后端原因。
 * 未中止时仍刷新列表，让“最近执行”列显示服务端记录的真实终态与错误摘要。
 */
async function runById(id: number, action: 'setup' | 'rebuild' | 'flatten') {
  if (busyIds.value.has(id)) return
  const observer = new AbortController()
  runObservers.add(observer)
  busyIds.value.add(id)
  try {
    const r =
      action === 'rebuild'
        ? await arbWorkflowsApi.rebuild(id, observer.signal)
        : action === 'flatten'
          ? await arbWorkflowsApi.flatten(id, observer.signal)
          : await arbWorkflowsApi.setup(id, observer.signal)
    const label = action === 'rebuild' ? '重建' : action === 'flatten' ? '摊平' : '生成'
    ElMessage.success(`${label}完成:${fmtRows(r.rows)}`)
  } catch (e: any) {
    if (!observer.signal.aborted) ElMessage.error(workflowError(e, '启动或查询任务失败'))
  } finally {
    // 失败也要刷新列表:「最近执行」那一列会记下失败与错误摘要,用户才知道到底跑没跑起来
    busyIds.value.delete(id)
    runObservers.delete(observer)
    if (!observer.signal.aborted) await load()
  }
}

async function run(row: ArbWorkflow, action: 'setup' | 'rebuild' | 'flatten') {
  if (action !== 'flatten') {
    for (const column of row.columns || []) {
      if (column.mode !== 'sum') continue
      const issue = arbSumTypeIssue(row.fields || [], column.name)
      if (issue) {
        ElMessage.warning(`${issue}，请先编辑工作流`)
        return
      }
    }
  }
  const title = action === 'setup' ? '生成 / 更新' : action === 'rebuild' ? '重建聚合层' : '刷新服务表'
  const msg =
    action === 'setup'
      ? `将生成/更新聚合表 ${row.targetTable}_agg，为 ${row.sources.length} 个源建物化视图、` +
        `回填历史，最后摊平成服务表 ${row.targetTable}_svc。\n过程是「临时表灌数 → 原子换名」，失败不影响现有数据。继续？`
      : action === 'rebuild'
        ? '重建聚合层 = 按源表全量重算。用于修正 sum 翻倍、以及源表删号码后留下的旧值。继续？'
        : '只把聚合层的最新数据摊平到服务表(聚合层已经被物化视图实时更新了，服务表要单独刷)。继续？'
  try {
    await ElMessageBox.confirm(msg, title, { type: 'warning' })
  } catch {
    return
  }
  await runById(row.id, action)
}

async function toggleEnabled(row: ArbWorkflow) {
  const next = !row.enabled
  if (!(await confirmAction('启停工作流', `确认${next ? '启用' : '停用'}工作流「${row.name}」？`, next ? '确认启用' : '确认停用'))) return
  try {
    await arbWorkflowsApi.update(row.id, {
      name: row.name,
      description: row.description,
      // 只传翻转后的值,不本地翻 row.enabled —— 开关姿态以刷新回来的列表为准,
      // 请求失败时界面不会停在一个并没有生效的状态上
      enabled: !row.enabled,
      connectionId: row.connectionId,
      targetDb: row.targetDb,
      targetTable: row.targetTable,
      isWide: !!row.isWide, // 局部更新也要带上:漏了它会把宽表标记抹掉(后端现已兜底,双保险)
      sources: row.sources,
      fields: row.fields,
      columns: row.columns,
    })
    await load()
  } catch (e: any) {
    ElMessage.error(e?.message || '切换失败')
  }
}

async function showProbe(row: ArbWorkflow) {
  try {
    const p = await arbWorkflowsApi.probe(row.id)
    const lines = [
      `聚合表 ${row.targetTable}_agg:${p.aggExists ? '存在' : '不存在'}`,
      p.aggExists ? `行数:${fmtRows(p.rows)}` : '',
      `物化视图(${p.mvs?.length || 0}):${(p.mvs || []).join('、') || '无'}`,
      p.missingMvs?.length ? `缺失(${p.missingMvs.length}):${p.missingMvs.join('、')}` : '视图齐全',
      // 配置外的视图必须报出来:它们还在往聚合层写数据,而下面的「视图齐全」看不出它们
      p.orphanMvs?.length
        ? `⚠️ 配置外(${p.orphanMvs.length}):${p.orphanMvs.join('、')}\n这些是删掉的源留下的视图,仍在往聚合层写数据 —— 点「生成 / 更新」会先清掉它们`
        : '',
      // 列类型漂移:改过取值方式的列不会自动改类型,聚合状态对不上就是静默错值
      p.drift?.length
        ? `⚠️ 列类型漂移(${p.drift.length}):${p.drift.join('、')}\n这些列的取值方式改过,但聚合表里的列类型还是老的 —— 点「生成 / 更新」会被拦下,需先「清理已生成的数据」`
        : '',
    ].filter(Boolean)
    // 类型字符串可能含 Enum 标签等外部文本；交给 Vue 文本节点转义，不解析为 HTML。
    await ElMessageBox.alert(h('div', { style: { whiteSpace: 'pre-wrap' } }, lines.join('\n')), '聚合层现状')
  } catch (e: any) {
    ElMessage.error(e?.message || '查看失败')
  }
}

async function dropObjects(row: ArbWorkflow) {
  try {
    await ElMessageBox.confirm(
      `是否删除产物数据？\n将删除聚合表 ${row.targetTable}_agg 与它的 ${row.sources.length} 个物化视图。\n` +
        '仲裁结果会一起没掉，不可恢复（源表不受影响）。',
      '删除产物',
      { type: 'error', confirmButtonText: '确认删除' }
    )
  } catch {
    return
  }
  try {
    await arbWorkflowsApi.dropObjects(row.id)
    ElMessage.success('产物已删除')
    await load()
  } catch (e: any) {
    ElMessage.error(e?.message || '删除失败')
  }
}

async function removeWf(row: ArbWorkflow) {
  try {
    await ElMessageBox.confirm(`是否删除工作流任务「${row.name}」？(已生成的目标表、聚合表与视图都不会动)`, '删除工作流任务', {
      type: 'warning',
    })
  } catch {
    return
  }
  try {
    await arbWorkflowsApi.remove(row.id)
    ElMessage.success('已删除')
    removeDeletedWfs(new Set([row.id]))
  } catch (e: any) {
    ElMessage.error(e?.message || '删除失败')
  }
}

/**
 * 批量删除勾选的工作流:与单条删除同口径 —— 只删配置,已生成的目标表/聚合表/视图不动。
 * 单条失败不阻断其它条,末尾汇总结果;成功的行直接从当前列表移除。
 */
async function batchRemoveWfs() {
  const rows = [...selectedWfs.value]
  if (!rows.length) return
  try {
    await ElMessageBox.confirm(
      `是否删除工作流任务？将删除选中的 ${rows.length} 个工作流(已生成的目标表、聚合表与视图都不会动)`,
      '批量删除工作流任务',
      { type: 'warning' }
    )
  } catch {
    return
  }
  batching.value = true
  let ok = 0
  const removedIds = new Set<number>()
  const failed: string[] = []
  for (const r of rows) {
    try {
      await arbWorkflowsApi.remove(r.id)
      ok++
      removedIds.add(r.id)
    } catch (e: any) {
      failed.push(`${r.name}: ${e?.message || '删除失败'}`)
    }
  }
  batching.value = false
  if (ok > 0) {
    wfTableRef.value?.clearSelection()
    removeDeletedWfs(removedIds)
  }
  if (failed.length) ElMessage.error(`已删除 ${ok} 个,${failed.length} 个失败 —— ${failed[0]}`)
  else if (ok > 0) ElMessage.success(`已删除 ${ok} 个`)
}

function onCommand(cmd: string, row: ArbWorkflow) {
  // 双击/竞态:执行中不许再发危险指令(图标栏也会禁用当前行)
  if (busyIds.value.has(row.id)) return
  if (cmd === 'setup') void run(row, 'setup')
  else if (cmd === 'edit') void openEdit(row)
  else if (cmd === 'topology') void showTopology(row)
  else if (cmd === 'rebuild') void run(row, 'rebuild')
  else if (cmd === 'flatten') void run(row, 'flatten')
  else if (cmd === 'probe') void showProbe(row)
  else if (cmd === 'runs') void showRuns(row)
  else if (cmd === 'drop') void dropObjects(row)
  else if (cmd === 'remove') void removeWf(row)
}

// ─────────── 历史 ───────────
const runsVisible = ref(false)
const runsLoading = ref(false)
const runs = ref<ArbWorkflowRun[]>([])
/**
 * 执行历史采用“任务 id + generation + 单次 setTimeout”的刷新协议。
 * generation 在每次打开、关闭或卸载时变化；旧任务或已关闭弹窗的迟到响应不能覆盖当前历史。
 * 单次请求结束后才安排下一次 3 秒刷新，网络异常时保留最近一次进度。
 */
let runsWorkflowId = 0
let runsGeneration = 0
let runsTimer: ReturnType<typeof setTimeout> | undefined

async function refreshRuns(generation = runsGeneration) {
  const id = runsWorkflowId
  if (!runsVisible.value || !id || generation !== runsGeneration) return
  try {
    const result = await arbWorkflowsApi.runs(id, 20, true)
    if (runsVisible.value && generation === runsGeneration) runs.value = result
  } catch { /* 暂时断网时保留最近一次进度。 */ }
  finally {
    if (runsVisible.value && generation === runsGeneration) runsTimer = setTimeout(() => refreshRuns(generation), 3000)
  }
}

watch(runsVisible, (visible) => {
  if (!visible) { runsGeneration++; if (runsTimer) clearTimeout(runsTimer) }
})
/**
 * 卸载时先中止所有前端观察请求，再清理历史定时器和当前任务标记，并递增 generation 使迟到响应失效。
 * 这不会回滚或取消服务端任务。
 */
onUnmounted(() => {
  for (const observer of runObservers) observer.abort()
  if (runsTimer) clearTimeout(runsTimer)
  runsWorkflowId = 0
  runsGeneration++
})

/**
 * 打开指定工作流的历史时先生成新的 generation，再清掉旧定时器并写入当前工作流 id。
 * 首次请求和后续刷新都携带同一 generation；关闭弹窗或切换到别的工作流后，旧请求的结果会被丢弃。
 */
async function showRuns(row: ArbWorkflow) {
  const generation = ++runsGeneration
  if (runsTimer) clearTimeout(runsTimer)
  runsWorkflowId = row.id
  // 先开弹窗再拉数据:表格自带 loading,立刻有反馈,不必卡在列表页干等
  runsVisible.value = true
  runsLoading.value = true
  try {
    const result = await arbWorkflowsApi.runs(row.id, 20, true)
    if (runsVisible.value && generation === runsGeneration) runs.value = result
  } catch (e: any) {
    if (runsVisible.value && generation === runsGeneration) ElMessage.error(e?.message || '加载历史失败')
  } finally {
    if (generation === runsGeneration) {
      runsLoading.value = false
      if (runsVisible.value) runsTimer = setTimeout(() => refreshRuns(generation), 3000)
    }
  }
}

onMounted(async () => {
  await ensureConns()
  await load()
})
</script>

<style scoped>
/* 列表与向导共用一页(靠 editing 切换),所以这里给最小高度而不是写死高度:
   向导步骤内容长短不一,min-height 保证切换时页面不会"跳一下" */
.aw {
  padding: 16px 20px 0;
  min-height: calc(100vh - 120px);
  display: flex;
  flex-direction: column;
}
.aw.aw-list {
  height: 100%;
  min-height: 0;
  box-sizing: border-box;
  overflow: hidden;
  padding: 20px 24px;
}
.aw-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
  margin-bottom: 14px;
}
.aw-head h2 {
  margin: 0 0 6px;
}
/* 页头说明要讲清仲裁规则(四段字典序),限宽 + 放大行距才读得完 */
.aw-sub {
  margin: 0;
  font-size: 12px;
  line-height: 1.7;
  color: var(--el-text-color-secondary);
  max-width: 820px;
}
/* 说明里的 code 是列名/关键短语,淡底把它从正文里拎出来 */
.aw-sub code {
  background: var(--el-fill-color-light);
  padding: 0 3px;
  border-radius: 3px;
}
/* flex:none 保证窗口变窄时按钮不被压扁 */
.aw-head-ops {
  display: flex;
  gap: 8px;
  flex: none;
}
/* 宽表标记缩小一点:它是附注,不能比工作流名还显眼 */
.aw-wide-tag {
  margin-left: 6px;
  transform: scale(0.9);
}
.aw-name {
  font-weight: 600;
}
.aw-name-link {
  padding: 0;
  border: 0;
  background: transparent;
  color: var(--el-color-primary);
  font: inherit;
  font-weight: inherit;
  cursor: pointer;
  text-align: left;
}
.aw-name-link:hover {
  text-decoration: underline;
}
.aw-dim {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.aw-err {
  font-size: 12px;
  color: var(--el-color-danger);
}
.aw-chips {
  margin-top: 4px;
}
/* 源标签:淡底小片,一行能排下多个源 */
.aw-chip {
  display: inline-block;
  margin: 0 6px 4px 0;
  padding: 0 6px;
  font-size: 12px;
  background: var(--el-fill-color-light);
  border-radius: 3px;
}
.aw-chip .pri {
  color: var(--brand, #d97706);
}
/* 缺时间列是"这条配置有隐患",用警告色标出来(与第②步的黄色提示同义) */
.aw-noTime {
  margin-left: 4px;
  color: var(--el-color-warning);
}

/* ── 向导 ── */
/* 顶栏:返回按钮 + 标题 + 当前目标表名(目标表名是等宽的,便于核对) */
.wz-head {
  display: flex;
  align-items: center;
  gap: 14px;
  margin-bottom: 10px;
}
.wz-title {
  font-size: 16px;
  font-weight: 600;
}
.wz-target {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.wz-steps {
  margin: 6px 0 18px;
}
.wz-steps :deep(.el-step__head),
.wz-steps :deep(.el-step__main) {
  cursor: pointer;
}
.aw-list-table :deep(td .cell div:not(.action-icon-bar)) {
  display: inline;
  margin-right: 6px;
}
/* 向导正文居中限宽:表单行太长时"标签 → 控件"的对应关系会看不清 */
.wz-body {
  flex: 1;
  max-width: 1080px;
  width: 100%;
  margin: 0 auto;
  /*
    ⚠️ 底部要留够 sticky 底栏(.wz-foot,约 60px)的高度 ——
    只留 16px 时,滚到底部最后一行内容会被压在「上一步 / 下一步」按钮栏下面点不到(实测同类问题)。
  */
  padding-bottom: 76px;
}
.wz-sec h4 {
  margin: 0 0 6px;
  font-size: 15px;
}
/* 步骤说明:占位色 + 大行距,它与表单是主次关系 */
.wz-tip {
  margin: 0 0 14px;
  font-size: 13px;
  line-height: 1.8;
  color: var(--el-text-color-secondary);
  max-width: 900px;
}
.wz-tip code {
  background: var(--el-fill-color-light);
  padding: 0 3px;
  border-radius: 3px;
}
.wz-form {
  max-width: 720px;
}
.wz-row {
  display: flex;
  align-items: center;
  gap: 8px;
}
/* 表单控件右侧的补充说明(与输入框同一行) */
.wz-hint {
  margin-left: 10px;
}
.wz-hint code {
  background: var(--el-fill-color-light);
  padding: 0 3px;
  border-radius: 3px;
}
.wz-topo {
  margin-bottom: 12px;
}
/* ── 源卡片：对齐标签的行式布局(原来用 grid 塞不同宽度的控件,列宽对不齐显得很乱) ── */
.wz-src {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  padding: 10px 12px;
  margin-bottom: 10px;
}
.wz-src-head {
  display: flex;
  align-items: center;
  gap: 10px;
}
/* 移除按钮推到卡片最右:与左侧的序号/选表拉开距离,避免误点 */
.wz-src-rm {
  margin-left: auto;
}
/* 卡片正文用虚线分隔:说明它是同一源的附属配置,不是另一个源 */
.wz-src-body {
  margin-top: 8px;
  padding-top: 8px;
  border-top: 1px dashed var(--el-border-color-lighter);
}
/* 一行 = 标签 + 控件 + 说明;min-height 保证"有说明"与"没说明"的行高度一致 */
.wz-line {
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: 30px;
  flex-wrap: wrap;
}
.wz-line + .wz-line {
  margin-top: 2px;
}
/* 行标签:定宽 + 右对齐,让各行的控件左边缘在同一条竖线上 */
.wz-label {
  width: 52px;
  flex: none;
  text-align: right;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
/* 同一行里的第二个标签(优先级),与上一个控件拉开一点距离 */
.wz-label-2 {
  margin-left: 18px;
}
/* 号码列这一类"只读展示值":加粗突出,让人一眼看到系统认出了哪一列 */
.wz-val {
  font-weight: 600;
  font-size: 13px;
}
.wz-line-ops {
  display: inline-flex;
  align-items: center;
  margin-left: 4px;
}
/* 右侧的灰字说明(列注释 / 提示),太长就省略 */
.wz-comment {
  font-size: 12px;
  color: var(--el-text-color-placeholder);
  max-width: 280px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.wz-tbl {
  width: 320px;
}
/* 中间宽度的控件(号码列/时间列下拉) */
.wz-ctl-mid {
  width: 200px;
}
/* 过滤条件可能是一小段 SQL,给它吃掉剩余宽度并留上限 */
.wz-ctl-grow {
  flex: 1;
  max-width: 460px;
}
/* 提示区缩进:与上方控件左对齐(60px ≈ 标签宽 52px + 间距),看起来像它的注解 */
.wz-notes {
  margin-top: 6px;
  padding-left: 60px;
}
.wz-note {
  font-size: 12px;
  line-height: 1.7;
  color: var(--el-color-warning);
}
.wz-tbl-note {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  max-width: 220px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
/*
  下拉选项:左列名、右注释。
  ⚠️ 不能用 float —— Element Plus 的选项本身是 flex 容器,float 不生效,
     两列会直接贴在一起。
*/
.wz-opt {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  width: 100%;
}
.wz-opt-note {
  font-size: 12px;
  color: var(--el-text-color-placeholder);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 55%;
}
/* 序号圆标:纯装饰(第几个源),不参与交互 */
.wz-idx {
  width: 18px;
  height: 18px;
  line-height: 18px;
  flex: none;
  text-align: center;
  border-radius: 50%;
  font-size: 11px;
  background: var(--el-fill-color);
  color: var(--el-text-color-secondary);
}
/* 小工具条:按钮 + 右侧统计文字 */
.wz-toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
}
/* 每个源一张字段卡:源之间用卡片隔开,避免表格连成一片看不清归属 */
.wz-fieldcard {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  padding: 10px 12px;
  margin-bottom: 10px;
}
.wz-fieldcard-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
  font-size: 13px;
}
.wz-tag {
  margin-left: 6px;
}
.wz-alert {
  margin-bottom: 10px;
}
/* 仲裁规则说明块:四个位置是字典序,靠"箭头串"把顺序画出来 */
.wz-rule {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  padding: 10px 12px;
  margin-bottom: 12px;
}
.wz-rule-title {
  font-size: 13px;
  font-weight: 600;
  margin-bottom: 8px;
}
.wz-rule-flow {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
}
/* 一个比较位:上行是大字说明,下行是小字补充 */
.wz-pos {
  display: flex;
  flex-direction: column;
  padding: 4px 10px;
  background: var(--el-fill-color-light);
  border-radius: 6px;
  font-size: 12px;
}
.wz-pos em {
  font-style: normal; /* 默认斜体在中文下很难看,去掉 */
  color: var(--el-text-color-secondary);
  font-size: 11px;
}
.wz-arrow {
  color: var(--el-text-color-placeholder);
}
.wz-rule-note {
  margin-top: 8px;
  font-size: 12px;
  line-height: 1.8;
  color: var(--el-text-color-secondary);
}
/* 生成前的汇总:淡底块,把"会建什么"一次说清 */
.wz-summary {
  display: flex;
  flex-direction: column;
  gap: 6px;
  font-size: 13px;
  padding: 12px 14px;
  background: var(--el-fill-color-light);
  border-radius: 8px;
  margin-bottom: 12px;
}
.wz-stmt {
  margin-bottom: 10px;
}
.wz-stmt-head {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-bottom: 4px;
}
/*
  SQL 预览块:等宽 + pre-wrap(SQL 行很长,不折行就得一直横向拖) + 限高,
  避免一条建表语句把整个第⑤步撑到几屏高。
*/
.wz-sql {
  margin: 0;
  padding: 10px;
  background: var(--el-fill-color-light);
  border-radius: 6px;
  font-family: var(--font-mono, monospace);
  font-size: 12px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 340px;
  overflow: auto;
}
/* sticky 底栏:向导每一步都能直接点"下一步",不用先滚到底(见 .wz-body 的 padding-bottom) */
.wz-foot {
  position: sticky;
  bottom: 0;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 4px;
  background: var(--el-bg-color);
  border-top: 1px solid var(--el-border-color-lighter);
}
/* 把右侧按钮组推开,步骤提示留在中间 */
.wz-foot-gap {
  flex: 1;
}
</style>
