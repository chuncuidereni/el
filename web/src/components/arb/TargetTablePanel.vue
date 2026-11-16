<!--
  TargetTablePanel.vue

  职责：目标表使用面板：按当前筛选条件预览命中数据并展示画像统计。
  边界：按 connId/db/table 读取目标表和画像；筛选、画像字段变化会触发查询，ResizeObserver 在卸载时断开。
-->

<template>
  <!--
    目标表的使用面板。

    这张表是给【圈选人群 + 画像分析】用的,不是给人改数据的(它是工作流的产物,
    改了下次摊平就覆盖)。所以这里:
      · 圈选页:条件构建 + 命中条数/占比 + 结果只读展示 + 导出命中结果
      · 画像页:按某个维度看分布(值/条数/占比 + 条形),条件沿用圈选页
      · 字段页:这个表有哪些字段、什么含义(只读)
    通用表浏览器(可增删改 / 表结构 / DDL)留在「全部表」入口里,两者互不干扰。
  -->
  <div class="ttp">
    <el-tabs v-model="tab" class="ttp-tabs">
      <!-- ═══ 圈选 ═══ -->
      <el-tab-pane label="圈选" name="pick">
        <div class="ttp-bar">
          <span class="ttp-toggle" @click="filterOpen = !filterOpen">
            <span class="ttp-arrow" :class="{ open: filterOpen }">▸</span>筛选条件
          </span>
          <span class="ttp-note">{{ conds.length ? `已选 ${conds.length} 个` : '未设条件（全部数据）' }}</span>

          <!-- 只看部分字段:不选就是全部 -->
          <span class="ttp-label">显示列</span>
          <el-select
            v-model="showCols"
            multiple
            filterable
            collapse-tags
            collapse-tags-tooltip
            clearable
            placeholder="全部列"
            class="ttp-showcols"
          >
            <el-option v-for="c in columns" :key="c" :value="c" :label="c">
              <div class="ttp-opt">
                <span class="mono">{{ c }}</span>
                <span class="ttp-opt-note">{{ colComments[c] }}</span>
              </div>
            </el-option>
          </el-select>

          <!-- 排序:后端 Data 接口本来就吃 orders,只是之前没接 -->
          <span class="ttp-label">排序</span>
          <el-select v-model="sortCol" clearable filterable placeholder="不排序" class="ttp-sortcol" @change="onSortChange">
            <el-option v-for="c in columns" :key="c" :value="c" :label="c">
              <div class="ttp-opt">
                <span class="mono">{{ c }}</span>
                <span class="ttp-opt-note">{{ colComments[c] }}</span>
              </div>
            </el-option>
          </el-select>
          <el-radio-group v-if="sortCol" v-model="sortDesc" @change="onSortChange">
            <el-radio-button :value="true">降序</el-radio-button>
            <el-radio-button :value="false">升序</el-radio-button>
          </el-radio-group>

          <span class="ttp-gap"></span>

          <!-- 条件改动不会自动查(见 schedule),所以必须有个显式按钮把半成品条件挡在门外 -->
          <el-button type="primary" :icon="Search" :loading="pickLoading" @click="runQuery()">
            执行查询
          </el-button>
          <el-button @click="clearConds">重置</el-button>

          <!-- 没有命中的行,导出与落表都是空转,直接禁用比让人点进去才发现好 -->
          <el-button :icon="Download" :disabled="!pickTotal" @click="openExport">
            导出命中结果
          </el-button>
          <el-button :icon="FolderAdd" type="primary" plain :disabled="!pickTotal" @click="openCrowd">
            存为数据包
          </el-button>
        </div>

        <!--
          已选条件摘要 —— 【只在条件区收起时显示】。
          条件区默认展开,那时每条条件就是一行,再叠一行 chips 是纯重复、白占高度;
          收起来(点「筛选条件 ▸」)时才需要它:一眼看到筛了什么,且每条能单独删
          (「去掉那个城市再看看」是圈选里最频繁的动作)。
        -->
        <div v-if="!filterOpen && chips.length" class="ttp-chips">
          <el-tag
            v-for="c in chips"
            :key="c.id"
            size="small"
            closable
            effect="plain"
            @close="removeCond(c.id)"
          >{{ c.text }}</el-tag>
        </div>

        <div v-show="filterOpen" class="ttp-filter">
          <div v-for="c in conds" :key="c.id" class="ttp-cond">
            <el-select
              v-model="c.column"
              filterable
              placeholder="选择字段"
              class="ttp-c-col"
              @change="onColChange(c)"
            >
              <el-option v-for="x in columns" :key="x" :value="x" :label="x">
                <div class="ttp-opt">
                  <span class="mono">{{ x }}</span>
                  <span class="ttp-opt-note">{{ colComments[x] }}</span>
                </div>
              </el-option>
            </el-select>

            <el-select v-model="c.op" class="ttp-c-op" @change="onCondChange(c)">
              <el-option v-for="o in opsFor(c.column)" :key="o.value" :value="o.value" :label="o.label" />
            </el-select>

            <div class="ttp-c-val">
              <!--
                值控件按【列类型优先、关系次之】分派:同样是 between,日期列给日期选择器、
                数值列给两个数字框、文本列才给两个文本框 —— 少了哪个分支就会退化成裸文本框。
              -->
              <!-- 为空/不为空:不需要值 -->
              <template v-if="opKind(c.op) === 'none'">
                <span class="ttp-note">{{ c.op === 'distinct' ? '筛选后去重；多个字段按组合值保留一条' : '（不需要值）' }}</span>
              </template>

              <!-- 日期列:用日期选择器,不要让人从一堆候选值里翻 -->
              <template v-else-if="colKind(c.column) === 'date' && opKind(c.op) === 'range'">
                <el-date-picker
                  v-model="c.lo"
                  :type="datePickerType(c.column)"
                  :value-format="dateFormat(c.column)"
                  placeholder="开始"
                  class="ttp-c-half"
                  @change="() => onDateBound(c, 'lo')"
                />
                <span class="ttp-note">到</span>
                <el-date-picker
                  v-model="c.hi"
                  :type="datePickerType(c.column)"
                  :value-format="dateFormat(c.column)"
                  placeholder="结束"
                  class="ttp-c-half"
                  @change="() => onDateBound(c, 'hi')"
                />
              </template>
              <template v-else-if="colKind(c.column) === 'date'">
                <el-date-picker
                  v-model="c.value"
                  :type="datePickerType(c.column)"
                  :value-format="dateFormat(c.column)"
                  placeholder="选日期"
                  class="ttp-c-one"
                  @change="() => onDateBound(c, 'value')"
                />
              </template>

              <!-- 数值列:区间/大小比较直接用数字输入(候选值下拉在这里只会添乱) -->
              <template v-else-if="colKind(c.column) === 'number' && opKind(c.op) === 'range'">
                <el-input v-model="c.lo" class="ttp-c-half" placeholder="最小值" @change="onCondChange(c)" />
                <span class="ttp-note">到</span>
                <el-input v-model="c.hi" class="ttp-c-half" placeholder="最大值" @change="onCondChange(c)" />
              </template>
              <template v-else-if="colKind(c.column) === 'number' && opKind(c.op) === 'one'">
                <el-input v-model="c.value" class="ttp-c-one" placeholder="输入数字" @change="onCondChange(c)" />
              </template>
              <template v-else-if="colKind(c.column) === 'number'">
                <el-input
                  v-model="c.values"
                  class="ttp-c-many"
                  placeholder="多个数值用逗号分隔，如 30,40"
                  @change="onCondChange(c)"
                />
              </template>

              <!-- 布尔列:只能 true / false -->
              <template v-else-if="colKind(c.column) === 'bool'">
                <el-select v-model="c.value" class="ttp-c-one" placeholder="选 true / false" @change="onCondChange(c)">
                  <el-option value="true" label="true（是）" />
                  <el-option value="false" label="false（否）" />
                </el-select>
              </template>

              <!-- 文本列:候选值(带条数)+ 可手输 -->
              <template v-else-if="opKind(c.op) === 'range'">
                <el-input v-model="c.lo" class="ttp-c-half" placeholder="从" @change="onCondChange(c)" />
                <span class="ttp-note">到</span>
                <el-input v-model="c.hi" class="ttp-c-half" placeholder="到" @change="onCondChange(c)" />
              </template>
              <template v-else-if="opKind(c.op) === 'many'">
                <el-input
                  v-model="c.values"
                  class="ttp-c-many"
                  placeholder="多个值用逗号分隔，如 北京,上海"
                  @change="onCondChange(c)"
                />
              </template>
              <template v-else>
                <el-input v-model="c.value" class="ttp-c-one" placeholder="输入值" @change="onCondChange(c)" />
              </template>
            </div>
            <!-- 只加这一个条件时的命中条数:加之前就知道它筛掉多少 -->
            <!-- 紧跟条件后面(原来被自适应宽度推到最右边,隔着老远) -->
            <el-button
              class="ttp-c-del"
              size="small"
              text
              type="danger"
              :icon="Delete"
              title="删除这个条件"
              @click="removeCond(c.id)"
            />
            <span class="ttp-c-cnt" title="只加这一个条件时的命中条数">
              <!-- 「…」= 正在算;「—」= 条件还不完整或算失败 —— 都不是"0 条" -->
              <template v-if="c.cntLoading">…</template>
              <template v-else-if="c.cnt !== null && c.cnt !== undefined">{{ fmtNum(c.cnt) }} 条</template>
              <template v-else>—</template>
            </span>
          </div>

          <div class="ttp-cond-add">
            <el-button size="small" :icon="Plus" @click="addCond">添加条件</el-button>
            <span class="ttp-note">
              条件之间是「并且」；同一字段多值用「包含于」，多个值逗号分隔（如 北京,上海）
            </span>
          </div>
          <div v-if="numericHint" class="ttp-warn-note">{{ numericHint }}</div>
        </div>

        <!-- 命中规模:圈选时第一眼要看的东西 -->
        <!-- 单独成块而不是塞进工具栏:它是这一页的结论,条件区收起来时也必须在 -->
        <div class="ttp-hit" :class="{ 'ttp-hit-empty': !conds.length && !pickTotal }">
          <template v-if="conds.length || pickTotal">
            <div class="ttp-hit-num">{{ fmtNum(pickTotal) }}</div>
            <div class="ttp-hit-unit">条命中</div>
            <div class="ttp-hit-sub">
              <template v-if="tableTotal">
                占全表 {{ pct(pickTotal, tableTotal) }}%（全表 {{ fmtNum(tableTotal) }} 行）
              </template>
            </div>
          </template>
          <template v-else>
            <div class="ttp-hit-sub">当前是全部数据;加条件会自动重新圈选。</div>
          </template>
        </div>

        <!--
          ⚠️ ResultGrid 的 max-height 会被 parseFloat 成【数字】,传 '100%' 会变成 100px。
          所以这里用容器实测高度(ResizeObserver),把剩余空间按像素喂进去 —— 拖动窗口也跟得上。
        -->
        <div ref="gridWrap" class="ttp-grid">
          <ResultGrid
            :result="pickResult"
            :max-height="gridH"
            :index-start="(page - 1) * pageSize + 1"
            sortable
            resizable
            :sort-column="sortCol"
            :sort-descending="sortDesc"
            @sort="onGridSort"
          />
        </div>
        <!-- 只在有命中时给分页器:空结果下挂一排页码只是干扰 -->
        <div v-if="pickTotal" class="ttp-pager">
          <el-pagination
            background
            small
            layout="prev, pager, next, jumper, total"
            :total="pickTotal"
            :current-page="page"
            :page-size="pageSize"
            @current-change="onPage"
          />
        </div>
      </el-tab-pane>

      <!-- ═══ 画像 ═══ -->
      <el-tab-pane label="画像" name="profile">
        <div class="ttp-bar">
          <span class="ttp-label">看哪个维度</span>
          <el-select v-model="profCol" filterable placeholder="选一个字段,如 city / sex / age" style="width: 260px">
            <el-option v-for="c in columns" :key="c" :value="c" :label="c">
              <div class="ttp-opt">
                <span class="mono">{{ c }}</span>
                <span class="ttp-opt-note">{{ colComments[c] }}</span>
              </div>
            </el-option>
          </el-select>
          <span class="ttp-label">分组方式</span>
          <el-select v-model="profBucket" style="width: 120px" @change="onBucketChange">
            <el-option value="raw" label="按原值" />
            <el-option value="number" label="数值区间" />
            <el-option value="day" label="按天" />
            <el-option value="month" label="按月" />
            <el-option value="year" label="按年" />
          </el-select>
          <template v-if="profBucket === 'number'">
            <!-- 档位只在按数值分桶时有意义;其余分桶方式粒度由字段本身(天/月/年)决定 -->
            <span class="ttp-label">每档</span>
            <el-input-number
              v-model="profBucketSize"
              :min="1"
              :max="100000"
              controls-position="right"
              style="width: 120px"
            />
          </template>
          <span class="ttp-label">取前</span>
          <el-select v-model="profLimit" style="width: 96px">
            <el-option :value="10" label="10" />
            <el-option :value="20" label="20" />
            <el-option :value="50" label="50" />
            <el-option :value="100" label="100" />
          </el-select>
          <el-button type="primary" :icon="Histogram" :loading="profLoading" @click="runProfile">
            统计分布
          </el-button>
          <span class="ttp-note">条件沿用「圈选」页的条件；分布里点「下钻」按该取值圈选</span>
        </div>

        <!-- 分布统计的是【当前圈选条件内】的行:条件在页签之间共享(condResult),切过来不用重配 -->
        <div v-if="prof" class="ttp-prof">
          <div class="ttp-prof-head">
            <span>
              <b class="mono">{{ prof.column }}</b> 的分布
              <span v-if="profBucketLabel" class="ttp-dim">（{{ profBucketLabel }}）</span>
              <span class="ttp-dim">
                · 共 {{ fmtNum(prof.total) }} 条 ·
                显示前 {{ prof.buckets.length }} 个取值
                <template v-if="profTruncated">（还有更多未显示）</template>
              </span>
            </span>
            <el-button size="small" text @click="showSql = !showSql">{{ showSql ? '收起 SQL' : '看 SQL' }}</el-button>
          </div>
          <pre v-if="showSql" class="ttp-sql">{{ prof.sql }}</pre>

          <el-table :data="prof.buckets" size="small" border class="ttp-prof-table">
            <el-table-column label="取值" min-width="160">
              <template #default="{ row }">
                <span class="mono">{{ row.value === '' ? '(空)' : row.value }}</span>
              </template>
            </el-table-column>
            <el-table-column label="条数" width="100" align="right">
              <template #default="{ row }">{{ fmtNum(row.count) }}</template>
            </el-table-column>
            <el-table-column label="占比" width="90" align="right">
              <template #default="{ row }">{{ pct(row.count, prof!.total) }}%</template>
            </el-table-column>
            <!-- 条形:不引图表库,一根 div 就够了 -->
            <el-table-column label="" min-width="200">
              <template #default="{ row }">
                <div class="ttp-bar-cell">
                  <!-- 按【最大档】归一而不是按总数:最长的条固定满格,档与档之间的长短差才看得出来 -->
                  <div class="ttp-bar-fill" :style="{ width: pct(row.count, maxBucket) + '%' }"></div>
                </div>
              </template>
            </el-table-column>
            <!--
              下钻:点一行 → 按这个取值加条件,跳到圈选页看这批人。
              这是「画像 → 圈选」的闭环 —— 比二维交叉更常用:
              先看整体分布,再针对某一块人群细看。
            -->
            <el-table-column label="" width="72" align="right">
              <template #default="{ row }">
                <el-button size="small" text type="primary" title="按这个取值圈选这批人" @click="drillDown(row)">
                  下钻
                </el-button>
              </template>
            </el-table-column>
          </el-table>
        </div>
        <div v-else class="ttp-empty">
          选一个维度点「统计分布」,看这些数据在这维度上的分布(先在上面的「圈选」里加条件,就是"这批").
        </div>
      </el-tab-pane>

      <!-- 导出配置:列 / 列名 / 表头 / 分隔符 / 行数(与通用浏览器那套一致) -->
    <el-dialog v-model="exportVisible" title="导出命中结果" width="600px" :close-on-click-modal="false">
      <el-form label-width="88px" label-position="left">
        <el-form-item label="导出列">
          <el-select
            v-model="exCols"
            multiple
            filterable
            collapse-tags
            collapse-tags-tooltip
            placeholder="默认：当前显示列"
            style="width: 100%"
          >
            <el-option v-for="c in columns" :key="c" :value="c" :label="c">
              <div class="ttp-opt">
                <span class="mono">{{ c }}</span>
                <span class="ttp-opt-note">{{ colComments[c] }}</span>
              </div>
            </el-option>
          </el-select>
          <div class="ttp-note">不选 = 全部列（含未在界面上显示的列）</div>
        </el-form-item>

        <el-form-item v-if="exCols.length" label="导出列名">
          <!-- 没选具体列 = 导出全部列,那时列表可能几十行,逐列改名反而是负担 -->
          <el-button size="small" text type="primary" @click="fillNamesFromComments">
            用列注释当列名
          </el-button>
          <el-button size="small" text @click="exNames = exCols.map(() => '')">恢复原列名</el-button>
          <div class="ttp-rename">
            <div v-for="(c, i) in exCols" :key="c" class="ttp-rename-row">
              <span class="ttp-rename-orig mono" :title="c">{{ c }}</span>
              <el-input v-model="exNames[i]" size="small" placeholder="留空则用原列名" />
            </div>
          </div>
          <div class="ttp-note">给列改个业务名（如 mdn → 手机号），导出给别人看更清楚</div>
        </el-form-item>

        <el-form-item label="表头">
          <el-switch v-model="exHeader" />
          <span class="ttp-note">第一行输出列名</span>
        </el-form-item>
        <el-form-item label="分隔符">
          <el-radio-group v-model="exDelim">
            <el-radio value=",">逗号 ,</el-radio>
            <el-radio value="\t">制表符 Tab</el-radio>
            <el-radio value=";">分号 ;</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="行数上限">
          <el-input-number v-model="exLimit" :min="0" :max="MAX_EXPORT_ROWS" :step="10000" />
          <span class="ttp-note">0 = 全部（{{ fmtNum(pickTotal) }} 条）</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="exportVisible = false">取消</el-button>
        <el-button type="primary" :loading="exporting" @click="doExport">导出</el-button>
      </template>
    </el-dialog>

    <!--
      存为数据包:把当前圈选结果落成一张表。
      这是圈选的【终点】—— 圈出来的这批人要拿去投放/再分析/当别的工作流的源表,
      只导出 CSV 的话下次还得重新圈。落成表之后它就是一个稳定的数据产物,
      会自动出现在「全部表」里。
    -->
    <el-dialog v-model="crowdVisible" title="存为数据包" width="640px" :close-on-click-modal="false">
      <el-form label-width="88px" label-position="left">
        <el-form-item label="表名">
          <el-input v-model="crowdName" placeholder="如 pack_male_bj" style="width: 100%" @input="previewCrowd" />
          <div class="ttp-note">
            会创建一张新表（跟这张目标表放在同一个地方，库名不用管）。
            建好后在「数据包」页和「全部表」里都能看到，也能被别的工作流当源表用。
          </div>
        </el-form-item>

        <el-form-item label="存哪些列">
          <el-select
            v-model="crowdCols"
            multiple
            filterable
            collapse-tags
            collapse-tags-tooltip
            placeholder="不选 = 全部列"
            style="width: 100%"
            @change="previewCrowd"
          >
            <el-option v-for="c in columns" :key="c" :value="c" :label="c">
              <div class="ttp-opt">
                <span class="mono">{{ c }}</span>
                <span class="ttp-opt-note">{{ colComments[c] }}</span>
              </div>
            </el-option>
          </el-select>
          <div class="ttp-note">
            默认只带<b>号码列</b>（如 mdn）—— 数据包回答的是「这批人是谁」，
            号码就是身份：少了它后续没法按人用，多带列只会让表更大更慢。
            需要别的列在这里加上就行；一个都不选 = 存全部列。
          </div>
        </el-form-item>

        <el-form-item label="条件">
          <!-- 直接把 chips 的文本拼出来:和「圈选」页看到的是同一份条件,不会出现两处措辞不一致 -->
          <span class="ttp-crowd-cond">
            <template v-if="chips.length">{{ chips.map((c) => c.text).join('；') }}</template>
            <template v-else>无（= 全表 {{ fmtNum(pickTotal) }} 行）</template>
          </span>
          <div class="ttp-note">与「圈选」页当前生效的条件完全一致，存进去的就是你看到的那批行。</div>
        </el-form-item>

        <el-form-item label="同名表">
          <!-- 覆盖是这里唯一不可逆的动作(先删再建),所以默认关着,且打开后要立刻重出预览让人核对 -->
          <el-switch v-model="crowdOverwrite" @change="previewCrowd" />
          <span class="ttp-note">
            覆盖同名表（先删再建）。不勾选时若表已存在会直接报错，不会动它。
          </span>
        </el-form-item>

        <el-form-item label="将要执行">
          <!-- 默认收起:SQL 是技术细节,弹窗的主体信息是「表名/列/条件/重名」四行 -->
          <el-button size="small" text type="primary" @click="crowdSqlOpen = !crowdSqlOpen">
            {{ crowdSqlOpen ? '收起 SQL' : '看将要执行的 SQL' }}
          </el-button>
          <span v-if="crowdPreviewing" class="ttp-note">生成中…</span>
          <span v-else-if="!crowdName.trim()" class="ttp-note">填个表名就有了</span>
          <pre v-if="crowdSqlOpen && crowdSql" class="ttp-sql ttp-crowd-sql">{{ crowdSql }}</pre>
          <div v-if="crowdExists" class="ttp-warn-note">
            ⚠️ 数据包 {{ crowdName }} 已经存在
            {{ crowdOverwrite ? '，执行时会先把它删掉。' : '，不勾「覆盖同名表」的话会报错。' }}
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="crowdVisible = false">取消</el-button>
        <el-button type="primary" :loading="crowding" :disabled="!crowdName" @click="doSaveCrowd">
          创建数据包
        </el-button>
      </template>
    </el-dialog>

    <!-- ═══ 字段 ═══ -->
      <el-tab-pane label="字段" name="schema">
        <div class="ttp-note ttp-schema-note">
          这个表的字段 —— 由工作流的目标列决定;注释是配字段时填的,用来对照「这一列是什么」。
        </div>
        <el-table :data="schemaRows" size="small" border>
          <el-table-column label="字段" width="180">
            <template #default="{ row }"><span class="mono">{{ row.name }}</span></template>
          </el-table-column>
          <el-table-column label="类型" width="140">
            <template #default="{ row }"><span class="mono dim">{{ row.type }}</span></template>
          </el-table-column>
          <el-table-column label="注释" min-width="220" show-overflow-tooltip prop="comment" />
        </el-table>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Delete, Download, FolderAdd, Histogram, Plus, Search } from '@element-plus/icons-vue'
import FilterBuilder from '@/components/FilterBuilder.vue'
import ResultGrid from '@/components/ResultGrid.vue'
import { workspaceApi } from '@/api'
import { downloadBlob } from '@/utils/download'
import { MAX_EXPORT_ROWS } from '@/constants/export'
import { arbDrillConds, arbDrillTip } from '@/utils/arbFields'
import type { FilterNode, GroupByRequest, GroupByResult, QueryResult, SortSpec } from '@/types'
import { confirmAction, confirmDestructive } from '@/utils/confirm'

const props = defineProps<{
  connId: number
  db: string
  table: string
}>()

const tab = ref('pick')

/**
 * 结果表格的高度(像素)。
 * 不能用百分比:ResultGrid 内部 parseFloat('100%') = 100,表格会只剩 100px 高
 * —— 这就是「查询结果列表没撑满」的原因。实测容器高度最稳。
 */
const gridWrap = ref<HTMLElement | null>(null)
const gridH = ref(360)
let ro: ResizeObserver | null = null

// ─────────── 字段清单(三个页共用) ───────────
// 三份派生数据各自服务不同用途:columns 是控件的数据源,colTypes 决定算子与值控件,
// colComments 既是下拉里的说明、也是猜语义(类型/分桶)的线索
const columns = ref<string[]>([])
const colTypes = ref<Record<string, string>>({})
const colComments = ref<Record<string, string>>({})
const schemaRows = ref<{ name: string; type: string; comment: string }[]>([])

async function loadSchema() {
  try {
    const res = await workspaceApi.schema(props.connId, props.db, props.table)
    // 不假定后端返回的列顺序:按列名定位下标,找不到才退回默认位置(否则某一版后端换个顺序就全错位)
    const iName = res.columns.indexOf('name')
    const iType = res.columns.indexOf('type')
    const iCmt = res.columns.indexOf('comment')
    schemaRows.value = res.rows.map((r) => ({
      name: String(r[iName >= 0 ? iName : 0] ?? ''),
      type: String(r[iType >= 0 ? iType : 1] ?? ''),
      comment: iCmt >= 0 ? String(r[iCmt] ?? '') : '',
    }))
    columns.value = schemaRows.value.map((x) => x.name)
    const t: Record<string, string> = {}
    const c: Record<string, string> = {}
    for (const x of schemaRows.value) {
      t[x.name] = x.type
      if (x.comment) c[x.name] = x.comment
    }
    colTypes.value = t
    colComments.value = c
  } catch (e: any) {
    // 只提示不抛出:这个方法在 onMounted 里被 await,异常冒出去会让后面的默认查询也一起不跑
    ElMessage.error(e?.message || '读字段失败')
  }
}

// ─────────── 圈选 ───────────
//
// 条件用【扁平一行一个】而不是嵌套树:
//   圈选的语义几乎永远是「这些条件【并且】」,同一字段的多值用「包含于」表达
//   (城市 ∈ {北京,上海}) —— 这比让人搭 AND/OR 树快得多,也少出错。
//   嵌套树的能力仍留在「全部表」的通用浏览器里(那边用的是 FilterBuilder)。
interface Cond {
  id: number
  column: string
  op: string
  /** 单值条件的值(eq/ne/gt…/like) */
  value: string
  /** 多值条件的值(in/notin):逗号分隔的字符串(后端按逗号拆) */
  values: string
  /** 区间条件的上下限(between) */
  lo: string
  hi: string
  /** 该条件是"只加它一个"时的命中条数(懒加载,加之前就知道筛掉多少) */
  cnt: number | null
  cntLoading: boolean
  /** 数值比较:目标表里仲裁列都是 String,区间/大小比较要走 toFloat64OrZero */
  numeric: boolean
}

/**
 * 列的类型归类 —— 决定「能选哪些关系」与「值用什么控件」。
 *
 * 真实类型优先;但目标表里【仲裁列一律是 String】,光看类型分不出年龄/日期,
 * 所以对 String 列再按列名 + 列注释猜(和画像页分桶用的是同一套启发式)。
 */
type ColKind = 'number' | 'date' | 'bool' | 'text'
function colKind(col: string): ColKind {
  if (!col) return 'text'
  const t = (colTypes.value[col] || '').toLowerCase()
  if (/^u?int|^float|^double|decimal/.test(t)) return 'number'
  if (t.includes('date') || t.includes('time')) return 'date'
  if (t.includes('bool')) return 'bool'
  const hay = `${col.toLowerCase()} ${(colComments.value[col] || '').toLowerCase()}`
  if (/date|_at$|_time$|time$|日期|时间/.test(hay)) return 'date'
  if (/^(is_|has_|flag)|是否|标志/.test(hay)) return 'bool'
  if (/age|score|amount|price|cnt|num|count|金额|年龄|分数|数量|次数/.test(hay)) return 'number'
  return 'text'
}

/** 日期列用哪种选择器(带时分秒的列给 datetime,只到天的给 date) */
function datePickerType(col: string): 'date' | 'datetime' {
  const t = (colTypes.value[col] || '').toLowerCase()
  // 真实类型里已经写明 DateTime 的按类型走;String 存的日期列再靠列名/注释定精度
  if (t.includes('datetime') || t.includes('time')) return 'datetime'
  const hay = `${col.toLowerCase()} ${(colComments.value[col] || '').toLowerCase()}`
  return /_at$|时间/.test(hay) ? 'datetime' : 'date'
}

// 算子的唯一事实来源:标签在界面上显示、kind 决定值控件长什么样,都从这张表派生
const OPS = [
  { value: 'in', label: '包含于', kind: 'many' },
  { value: 'eq', label: '等于', kind: 'one' },
  { value: 'ne', label: '不等于', kind: 'one' },
  { value: 'notin', label: '不包含于', kind: 'many' },
  { value: 'between', label: '区间', kind: 'range' },
  { value: 'gt', label: '大于', kind: 'one' },
  { value: 'gte', label: '大于等于', kind: 'one' },
  { value: 'lt', label: '小于', kind: 'one' },
  { value: 'lte', label: '小于等于', kind: 'one' },
  { value: 'like', label: '包含文本', kind: 'one' },
  { value: 'isnull', label: '为空', kind: 'none' },
  { value: 'isnotnull', label: '不为空', kind: 'none' },
  { value: 'distinct', label: '字段去重', kind: 'none' },
] as const
type OpKind = 'one' | 'many' | 'range' | 'none'
function opKind(op: string): OpKind {
  // 查不到就按单值处理:配置里的算子可能来自更早的版本,不能让它把值控件搞成空白
  return (OPS.find((o) => o.value === op)?.kind ?? 'one') as OpKind
}
/** 「大于/区间」这类比较:目标表里数值列是 String,必须按数值比 —— 见 numeric 字段 */
function isCompareOp(op: string) {
  return ['between', 'gt', 'gte', 'lt', 'lte'].includes(op)
}

/** 日期选择器吐什么格式:'YYYY-MM-DD' 还是带时分秒 */
function dateFormat(col: string) {
  // 与 datePickerType 用同一判据:选择器类型和 value-format 不一致时,选完日期会回填成半截字符串
  return datePickerType(col) === 'datetime' ? 'YYYY-MM-DD HH:mm:ss' : 'YYYY-MM-DD'
}

/**
 * 日期值归一化 + 触发查询。
 *
 * ⚠️ 上界必须补到当天最后一秒:用户在日期范围里选 1/1 ~ 1/31,
 *    若下界写成 '2026-01-31'(00:00:00),1 月 31 日整天的数据会被漏掉,而且看着正常。
 *    (后端对真正的 Date/DateTime 列也会兜一层,但目标表里日期多是 String 存的,
 *     那边认不出来,所以前端这里必须补。)
 */
function onDateBound(c: Cond, which: 'lo' | 'hi' | 'value') {
  const raw = String(c[which] ?? '')
  if (raw.length === 10) {
    // 只到天
    if (which === 'hi') c.hi = raw + ' 23:59:59'
    else c[which] = raw + ' 00:00:00'
  }
  onCondChange(c)
}

/*
  ⚠️ 默认算子【显式给出】,不要按算子表顺序推。
  以前是 "有 between 就选 between" —— 而 text 列表后来补进了 between(为了能表示下钻产出的区间),
  文本列选中字段后的默认算子就悄悄从「包含于」变成了「区间」,输入框也跟着变(评审 r5 #3,已核实)。
*/
const DEFAULT_OP: Record<ColKind, string> = {
  number: 'between',
  date: 'between',
  text: 'in',
  bool: 'eq'
}

/**
 * 每种列类型能用哪些关系(要全,也要都说得通):
 *   number 数值 —— 全,含区间与大小比较
 *   date   日期 —— 区间与大小比较【必须有】(原来漏了,用户提过);字符串按 ISO 字典序
 *                   与时序一致,所以日期列（哪怕是 String 存的）区间也成立
 *   bool   布尔 —— 只有等于/不等于
 *   text   文本 —— 包含于/等于/包含文本;不做区间(字典序区间对文本没有意义)
 */
// 每种列类型允许的算子白名单;它必须包含 DEFAULT_OP 里给该类型的默认值,否则选完字段下拉会停在非法值上
const OPS_BY_KIND: Record<ColKind, string[]> = {
  number: ['eq', 'ne', 'between', 'gt', 'gte', 'lt', 'lte', 'in', 'notin', 'isnull', 'isnotnull'],
  date: ['between', 'gte', 'lte', 'gt', 'lt', 'eq', 'ne', 'isnull', 'isnotnull'],
  bool: ['eq', 'ne', 'between', 'gt', 'gte', 'lt', 'lte', 'isnull', 'isnotnull'],
  /*
    ⚠️ 以前缺 between/gt/gte/lt/lte,而 arbDrillConds 对「按区间分桶」的列会返回 gte+lt,
    所以文本列(例如按数值区间分桶的号码列/日期字符串)下钻后,条件里的算子在这个表里没有
    → 下拉停在一个非法值上(评审 high,已核实)。
    ClickHouse 对字符串的比较是合法且确定的(字典序),补进表里即可;
    ⚠️ 不要在 drillDown 里做"不在表里就换成别的算子"的兜底 —— 那会悄悄改掉查询语义。
  */
  text: ['in', 'notin', 'eq', 'ne', 'like', 'between', 'gt', 'gte', 'lt', 'lte', 'isnull', 'isnotnull'],
}

/** 按字段给可选的关系 */
function opsFor(col: string) {
  // 还没选字段时给一组最通用的,避免下拉是空的(空下拉会让人以为功能坏了)
  if (!col) return OPS.filter((o) => ['in', 'eq', 'isnull', 'isnotnull', 'distinct'].includes(o.value))
  // 白名单顺序不决定显示顺序:按 OPS 的定义顺序输出,保证下拉里算子次序始终一致
  const allow = OPS_BY_KIND[colKind(col)]
  return OPS.filter((o) => o.value === 'distinct' || allow.includes(o.value))
}

const filterOpen = ref(true)
const conds = ref<Cond[]>([])
/** 只看部分字段(空 = 全部)。列多的时候圈选结果里只关心几列 */
const showCols = ref<string[]>([])
/**
 * 排序(单列)。后端 Data 接口本来就支持 orders,之前只是没接。
 * 换排序立即重查 —— 点排序就是要立刻看到结果,不像改条件那样需要"先配好再执行"。
 */
const sortCol = ref('')
const sortDesc = ref(true)
/** 排序参数由 computed 派生:分页、导出、刷新都读它,避免三处各拼一份 */
const sortOrders = computed(() =>
  sortCol.value
    ? [
        {
          column: sortCol.value,
          desc: sortDesc.value,
          // 数值列要按数值排:目标表里仲裁列是 String,'9' 按字符串排会大于 '80'
          numeric: colKind(sortCol.value) === 'number',
        },
      ]
    : []
)
// 上一次真正跑过的条件快照:导出/画像/落表都用它,保证三者作用在同一批行上
const condResult = ref<FilterNode>({ logic: 'and', children: [] }) // 真正生效的条件
const appliedOrders = ref<SortSpec[]>([]) // 与已显示结果一致的去重保留顺序
const pickResult = ref<QueryResult | null>(null)
const pickLoading = ref(false)
const pickTotal = ref(0)
const tableTotal = ref(0)
const page = ref(1)
const pageSize = ref(100)

// id 用自增序号而不是数组下标:条件可增可删,下标会重复,chips 的 :key 与删除都会串位
let condSeq = 0
function makeCond(): Cond {
  return {
    id: ++condSeq,
    column: '',
    op: 'in',
    value: '',
    values: '',
    lo: '',
    hi: '',
    cnt: null,
    cntLoading: false,
    numeric: false,
  }
}
function addCond() {
  if (!conds.value.length && columns.value.length) {
    // 第一条:默认选一个基数小的字段,省一步(选完就有值可选)
    const c = makeCond()
    conds.value.push(c)
  } else {
    conds.value.push(makeCond())
  }
}
function removeCond(id: number) {
  conds.value = conds.value.filter((c) => c.id !== id)
  // 用默认参数调 refresh,顺带把页码归 1 —— 删条件后命中的页数会变少,停在高页码会看到空列表
  void refresh() // 删条件是明确动作,直接重查(不用再点一次)
}
function clearConds() {
  conds.value = []
  void refresh()
}

/** 一行条件 → FilterNode(不完整/空值返回 null,不参与过滤) */
function toNode(c: Cond): FilterNode | null {
  // 没选字段的一行不是条件;返回 null 而不是造一个空条件,见 filterOf 里的统一过滤
  if (!c.column) return null
  if (c.op === 'distinct') return { column: c.column, op: c.op }
  const numeric = isCompareOp(c.op) && colKind(c.column) === 'number'
  if (c.op === 'isnull' || c.op === 'isnotnull') return { column: c.column, op: c.op, numeric }
  if (c.op === 'in' || c.op === 'notin') {
    if (!c.values.trim()) return null
    return { column: c.column, op: c.op, value: c.values, numeric }
  }
  if (c.op === 'between') {
    // 区间必须两端齐:只填一端若当成"大于某值"会让界面显示与查询语义对不上
    if (c.lo === '' || c.hi === '') return null
    return { column: c.column, op: 'between', value: `${c.lo},${c.hi}`, numeric }
  }
  // 其余都是单值算子;空值意味着用户还没填,不能当成"等于空串"去过滤
  if (c.value === '') return null
  return { column: c.column, op: c.op, value: c.value, numeric }
}
function filterOf(list: Cond[]): FilterNode {
  // logic 固定 and:扁平条件下多值用「包含于」表达,不需要 OR(见上面 conds 的说明)
  return { logic: 'and', children: list.map(toNode).filter((x): x is FilterNode => !!x) }
}

/** 已选条件摘要(收起条件区也能看到,每一条都能单删) */
const chips = computed(() =>
  conds.value
    // 与 toNode 用同一套判定:显示出来的必然是真在过滤的,不会出现"看得见但没生效"的假条件
    .filter((c) => toNode(c))
    .map((c) => {
      const label = OPS.find((o) => o.value === c.op)?.label ?? c.op
      // 摘要里取哪个值要与 toNode 一一对应,否则会出现"摘要写着城市包含北京、实际查的是别的"
      let v = c.value
      if (c.op === 'in' || c.op === 'notin') v = c.values
      else if (c.op === 'between') v = `${c.lo} ~ ${c.hi}`
      else if (opKind(c.op) === 'none') v = ''
      return { id: c.id, text: `${c.column} ${label}${v ? ' ' + v : ''}` }
    })
)

/** 数值列做区间/大小比较的提示:目标表里它其实是字符串 */
const numericHint = computed(() => {
  const bad = conds.value.find((c) => isCompareOp(c.op) && c.column && colKind(c.column) === 'text')
  if (!bad) return ''
  return `「${bad.column}」是文本列,区间/大小比较会按字典序比 —— 数值列与日期列才建议用区间。`
})

// ── 值一律手输,不再拉候选值 ──
//
// 为什么不给候选值下拉:候选值要对该列做一次全表 GROUP BY(bucket=raw),
// 数据量大时又慢又只能取前 N 个 —— 拉 200 个值既不代表全量、又白扫一遍数据。
// 分类列本来就该由用的人自己知道有哪些值(城市、性别……),手输 + 逗号分隔足够。
function onColChange(c: Cond) {
  // 换字段必须清值:旧值是按旧字段的语义填的,留着会变成一条看着有效、实际误伤的过滤
  c.value = ''
  c.values = ''
  c.lo = ''
  c.hi = ''
  c.cnt = null
  /*
      默认算子按 kind 在 DEFAULT_OP 里【显式给出】(number/date→between,text→in,bool→eq)。
      ⚠️ 不要按算子表顺序推:以前是"有 between 就选 between",而 text 列表后来补进 between
      (为了能表示下钻产出的区间),文本列的默认算子就悄悄从「包含于」变成「区间」(评审 r5 #3)。
    */
  if (c.op !== 'distinct') c.op = DEFAULT_OP[colKind(c.column)] ?? 'eq'
  void onCondChange(c)
}
function onCondChange(c: Cond) {
  // 条件动过就把算出来的命中数作废:那个数字属于旧条件,继续显示等于给了个错的参考
  c.cnt = null
  schedule()
}

/**
 * 条件改动不自动查 —— 改完点「执行查询」。
 *
 * 为什么不做防抖自动查:条件多、值又是手输的,边输边查等于半成品条件也去扫一遍,
 * 数据量大时很浪费。
 */
function schedule() {
  /* 有意留空:查询由「执行查询」按钮触发 */
}

/** 只加这一个条件时的命中条数(带缓存) */
// 缓存而不是随条件变化实时算:每次刷新都要为每一行条件单独跑一次 count,数据量大时开销可观
const cntCache = new Map<string, number>()
async function loadCondCount(c: Cond) {
  const node = toNode(c)
  if (!node) {
    // 条件还不完整,没有数字可算 —— 置空让界面显示「—」而不是一个会误导人的 0
    c.cnt = null
    return
  }
  // 以序列化后的条件做键:同样的条件(如增删别的行之后重建)直接命中缓存
  const key = JSON.stringify(node)
  if (cntCache.has(key)) {
    c.cnt = cntCache.get(key)!
    return
  }
  c.cntLoading = true
  try {
    const res = await workspaceApi.data(props.connId, props.db, props.table, 0, 1, { logic: 'and', children: [node] }, [], true)
    c.cnt = res.total ?? 0
    cntCache.set(key, c.cnt)
  } catch {
    // 单条条件查不出条数不影响主结果,清空即可,不弹错打断圈选
    c.cnt = null
  } finally {
    c.cntLoading = false
  }
}

/** 点「执行查询」跑一次(带当前显示列) */
async function runQuery() {
  await refresh(true)
}

/** 重新圈选:更新命中规模 + 结果列表 + 每条条件自己的命中数 */
async function refresh(resetPage = true) {
  // 条件变了页码多半已越界(原来第 5 页,现在只剩 2 页),所以默认回到第 1 页;
  // 只有翻页本身(pick 分页器)才传 false
  if (resetPage) page.value = 1
  const snap = filterOf(conds.value)
  const orderSnap = sortOrders.value.map((order) => ({ ...order }))
  pickLoading.value = true
  try {
    const res = await workspaceApi.data(
      props.connId,
      props.db,
      props.table,
      (page.value - 1) * pageSize.value,
      pageSize.value,
      snap,
      showCols.value, // 空 = 全部列
      true,
      orderSnap
    )
    // 表数据接口只返回列和值;字段注释来自进入面板时读取的表结构。
    // 挂到结果上后,ResultGrid 会在圈选结果的列名下直接显示注释。
    condResult.value = snap
    appliedOrders.value = orderSnap
    pickResult.value = { ...res, columnComments: colComments.value } as QueryResult
    pickTotal.value = res.total ?? 0
  } catch (e: any) {
    // 查询失败保留上一次的结果与命中数:清空会让人以为"条件筛出 0 行",比报错更容易误判
    ElMessage.error(e?.message || '圈选失败')
  } finally {
    pickLoading.value = false
  }
  // 单条条件的命中数是另外的查询,放在主结果之后补:它慢一点不影响主体,失败也不影响
  for (const c of conds.value) void loadCondCount(c)
}

/** 全表总行数,只为算「占全表 x%」这一个用途;失败静默,不因此挡住圈选 */
async function loadTableTotal() {
  try {
    const res = await workspaceApi.data(props.connId, props.db, props.table, 0, 1, undefined, [], true)
    tableTotal.value = res.total ?? 0
  } catch {
    /* 全表规模拿不到就不显示占比 */
  }
}

/** 排序变更:立即重查(并回到第 1 页) */
function onSortChange() {
  void refresh(true)
}

function onGridSort(value: { column: string; desc: boolean }) {
  sortCol.value = value.column
  sortDesc.value = value.desc
  onSortChange()
}

function onPage(p: number) {
  page.value = p
  void refresh(false)
}

// ── 导出(弹窗配置:列 / 列名 / 表头 / 分隔符 / 行数) ──
const exportVisible = ref(false)
const exporting = ref(false)
const exCols = ref<string[]>([])
const exNames = ref<string[]>([])
const exHeader = ref(true)
const exDelim = ref(',')
const exLimit = ref(0)

function openExport() {
  // 每次都重置成默认值:上一次导出的改名/分隔符/行数上限不该被带进下一次
  // 默认导出【当前显示列】(没选显示列就是全部)
  exCols.value = showCols.value.length ? [...showCols.value] : [...columns.value]
  // 默认【不改名】:留空就是原列名。想改成业务名点「用列注释当列名」
  exNames.value = exCols.value.map(() => '')
  exHeader.value = true
  exDelim.value = ','
  exLimit.value = 0
  exportVisible.value = true
}

/** 用列注释当导出列名(注释里通常就是业务叫法) */
function fillNamesFromComments() {
  exNames.value = exCols.value.map((c) => colComments.value[c] || '')
}

async function doExport() {
  exporting.value = true
  try {
    const blob = await workspaceApi.exportCsv(props.connId, props.db, props.table, {
      columns: exCols.value,
      // 列名按【下标】与列一一对应:留空表示该列用原列名,所以不能按列名去查改名表
      headerNames: exCols.value.map((_, i) => exNames.value[i] ?? ''),
      filter: condResult.value,
      orders: appliedOrders.value, // 导出的顺序与已显示的结果一致
      withHeader: exHeader.value,
      delimiter: exDelim.value,
      limit: exLimit.value || undefined,
    })
    downloadBlob(blob, `${props.table}_圈选结果.csv`)
    exportVisible.value = false
    ElMessage.success('已导出')
  } catch (e: any) {
    ElMessage.error(e?.message || '导出失败')
  } finally {
    exporting.value = false
  }
}

// ── 存为数据包:把圈选结果落成一张表 ──
//
// 圈选的终点。之前只能导出 CSV —— 下次想再看这批人还得重新圈一遍;
// 落成表之后它就是一个稳定的数据产物,还能被别的工作流当源表。
const crowdVisible = ref(false)
const crowdName = ref('')
const crowdCols = ref<string[]>([])
const crowdOverwrite = ref(false)
const crowding = ref(false)
const crowdPreviewing = ref(false)
const crowdSql = ref('')
const crowdExists = ref(false)
/** SQL 预览默认收起:弹窗主体是表名/列/条件/重名四行,SQL 按需再展开 */
const crowdSqlOpen = ref(false)

/**
 * 给个默认表名。
 *
 * 用日期 + 序号而不是"男士北京"这种业务名:数据包往往是临时产物,
 * 名字里带日期最容易分辨新旧;业务含义让用户自己改。
 */
function suggestCrowdName() {
  const d = new Date()
  const p = (n: number) => String(n).padStart(2, '0')
  return `crowd_${d.getFullYear()}${p(d.getMonth() + 1)}${p(d.getDate())}_${p(d.getHours())}${p(d.getMinutes())}`
}

function openCrowd() {
  if (!crowdName.value) crowdName.value = suggestCrowdName()
  // 默认:号码列 + 当前显示列(显示列就是"我关心的那几列")
  const pick: string[] = []
  // 号码列在不同库里叫法不一,三种常见名都认;它是数据包的身份列,默认必须带上
  const mdn = columns.value.find((c) => ['mdn', 'phone', 'mobile'].includes(c))
  if (mdn) pick.push(mdn)
  for (const c of showCols.value) if (!pick.includes(c)) pick.push(c)
  crowdCols.value = pick
  crowdOverwrite.value = false
  crowdSqlOpen.value = false
  crowdVisible.value = true
  void previewCrowd()
}

/** 预览(只生成 SQL 不执行)—— 落库是写操作,先让人看清楚要跑什么 */
// 表名是边输边预览的,请求会重叠;用递增序号只认最后一次响应,过期响应直接丢弃
let previewSeq = 0
async function previewCrowd() {
  const name = crowdName.value.trim()
  if (!name) {
    // 清空输入框时也要清掉 SQL,不然会留着上一个表名的预览结果
    crowdSql.value = ''
    return
  }
  const seq = ++previewSeq
  crowdPreviewing.value = true
  try {
    const res = await workspaceApi.materialize(props.connId, props.db, props.table, {
      targetDb: props.db,
      targetTable: name,
      filter: condResult.value,
      orders: appliedOrders.value,
      columns: crowdCols.value,
      dropIfExists: crowdOverwrite.value,
      dryRun: true,
    })
    if (seq !== previewSeq) return // 手输表名时会有多次预览,只认最后一次
    crowdSql.value = res.sql
    crowdExists.value = res.exists
  } catch (e: any) {
    crowdSql.value = ''
    // 表名不合法的提示就在这里给(后端校验),别等点创建才报
    ElMessage.error(e?.message || '表名不可用')
  } finally {
    // 只有最后一次预览才有权关掉 loading,否则先发出的请求回来会把仍在加载的状态清掉
    if (seq === previewSeq) crowdPreviewing.value = false
  }
}

async function doSaveCrowd() {
  // 这是真正的写操作(dryRun 不传 = 落库);失败时弹窗保持打开,好让人改表名或勾覆盖重试
  const name = crowdName.value.trim()
  const ok = crowdOverwrite.value
    ? await confirmDestructive('创建数据包', name, '创建', '已勾选「覆盖同名表」:先删除同名表再重建,原表数据不可恢复。')
    : await confirmAction('创建数据包', `确认按当前圈选条件创建数据包「${name}」？`, '确认创建')
  if (!ok) return
  crowding.value = true
  try {
    const res = await workspaceApi.materialize(props.connId, props.db, props.table, {
      targetDb: props.db,
      targetTable: crowdName.value.trim(),
      filter: condResult.value,
      orders: appliedOrders.value,
      columns: crowdCols.value,
      dropIfExists: crowdOverwrite.value,
    })
    crowdVisible.value = false
    ElMessage.success(`已创建数据包 ${res.table}（${fmtNum(res.rows)} 行）—— 在「数据包」页可以看到`)
  } catch (e: any) {
    ElMessage.error(e?.message || '创建失败')
  } finally {
    crowding.value = false
  }
}

// ─────────── 画像 ───────────
const profCol = ref('')
const profLimit = ref(20)
/**
 * 分组方式。数值/时间维度按原值分组会出上百行(age 100 行、reg_date 365 行),
 * 所以选列时自动猜一个合适的:[年龄/金额这类] → 数值区间、[日期/时间列] → 按月。
 * 猜错也没关系,上面能改。
 */
const profBucket = ref<GroupByRequest['bucket']>('raw')
const profBucketSize = ref(10)

const profBucketLabel = computed(() => {
  switch (profBucket.value) {
    case 'number':
      return `每 ${profBucketSize.value} 一档`
    case 'day':
      return '按天'
    case 'month':
      return '按月'
    case 'year':
      return '按年'
    default:
      return ''
  }
})

/** 按列类型/列名/注释猜分组方式(目标表里仲裁列都是 String,光看类型猜不出) */
function guessBucket(col: string): { bucket: GroupByRequest['bucket']; size: number } {
  const t = (colTypes.value[col] || '').toLowerCase()
  const name = col.toLowerCase()
  const cmt = (colComments.value[col] || '').toLowerCase()
  const hay = `${name} ${cmt}`
  // 先看真实类型(能确定的别去猜名字);String 存的日期/数值列再靠名字与注释找线索
  const isTime = /date|time/.test(t) || /(date|_at|time)$/.test(name) || /时间|日期/.test(cmt)
  if (isTime) return { bucket: 'month', size: 10 }
  if (/int|float|decimal/.test(t)) return { bucket: 'number', size: 10 }
  if (/age|score|amount|price|cnt|num|金额|年龄|分数|数量|次数/.test(hay)) return { bucket: 'number', size: 10 }
  if (/时间|日期/.test(hay)) return { bucket: 'month', size: 10 }
  return { bucket: 'raw', size: 10 }
}
function onBucketChange() {
  // 手动改过之后就不再覆写
}

watch(profCol, (c) => {
  if (!c) return
  // 换维度就重猜一次分桶:age 按原值会出上百行、日期出几百行,默认给个"一眼能看完"的粒度
  const g = guessBucket(c)
  profBucket.value = g.bucket
  profBucketSize.value = g.size
})
const profLoading = ref(false)
const prof = ref<GroupByResult | null>(null)
const showSql = ref(false)

const maxBucket = computed(() => Math.max(1, ...(prof.value?.buckets ?? []).map((b) => b.count)))
/** 取满了 limit 就说明后面还有 —— 提示用户"未显示完" */
const profTruncated = computed(() => (prof.value?.buckets.length ?? 0) >= profLimit.value)

async function runProfile() {
  if (!profCol.value) {
    ElMessage.warning('先选一个维度')
    return
  }
  profLoading.value = true
  try {
    prof.value = await workspaceApi.groupBy(props.connId, props.db, props.table, {
      filter: condResult.value,
      orders: appliedOrders.value,
      column: profCol.value,
      limit: profLimit.value,
      bucket: profBucket.value,
      // 档位大小只对数值分桶有效;其余分桶方式下传了可能让后端当成非法参数
      bucketSize: profBucket.value === 'number' ? profBucketSize.value : undefined,
    })
  } catch (e: any) {
    ElMessage.error(e?.message || '统计失败')
  } finally {
    profLoading.value = false
  }
}

/**
 * 下钻:按分布里的某个取值加条件,跳到圈选页看这批人。
 *
 * 条件怎么给由【分桶方式】决定 —— 见 arbDrillConds(纯函数,已单测)。这里只负责
 * 落到界面上:先清掉该列已有条件(否则新旧条件 AND 起来必然查出 0 行),再跳到圈选页。
 */
function drillDown(row: { value: string }) {
  const col = prof.value?.column
  if (!col) return
  conds.value = conds.value.filter((c) => c.column !== col || c.op === 'distinct')
  const parts = arbDrillConds(col, row.value, profBucket.value ?? 'raw')
  // 一个分桶值可能展开成多条条件(数值区间 → gte + lt),所以是逐个 push,不是单条替换
  for (const d of parts) {
    const c = makeCond()
    c.column = col
    c.op = d.op
    if (d.value !== undefined) c.value = d.value
    if (d.lo !== undefined) c.lo = d.lo
    if (d.hi !== undefined) c.hi = d.hi
    conds.value.push(c)
  }
  tab.value = 'pick'
  void refresh(true)
  ElMessage.success(`已按「${arbDrillTip(col, row.value, profBucket.value ?? 'raw')}」圈选`)
}

// ─────────── 工具 ───────────
function fmtNum(n?: number | null) {
  if (n === undefined || n === null) return '—'
  return n.toLocaleString('en-US')
}
function pct(part: number, whole: number) {
  // 分母为 0 时给 '0' 而不是 NaN/Infinity —— 这个返回值会直接拼进内联样式的宽度里
  if (!whole) return '0'
  return ((part / whole) * 100).toFixed(1)
}

onMounted(async () => {
  await loadSchema()
  // 默认就把全部数据显示出来(圈选的前提是先看到东西,不该让人先点一下)
  await loadTableTotal()
  conds.value.push(makeCond()) // 给一行空条件,点两下就能用
  await refresh()
  if (gridWrap.value && typeof ResizeObserver !== 'undefined') {
    ro = new ResizeObserver(() => {
      const h = gridWrap.value?.clientHeight ?? 0
      // 小于 120px 的观测值一律忽略:页签切走/布局未稳时 observer 会先报一个极小高度,
      // 照着设会把表格压成一条缝
      if (h > 120) gridH.value = h
    })
    ro.observe(gridWrap.value)
  }
})
// 面板会被反复挂载卸载,不断开 observer 会在已经废弃的容器上持续回调
onBeforeUnmount(() => ro?.disconnect())
</script>

<style scoped>
.ttp {
  height: 100%;
  display: flex;
  flex-direction: column;
}
.ttp-tabs {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-height: 0;
}
.ttp-tabs :deep(.el-tabs__content) {
  flex: 1;
  min-height: 0;
}
/* 页签那一行压扁:默认 40px + 15px 下边距,一屏里白占 50 多像素 */
.ttp-tabs :deep(.el-tabs__header) {
  margin: 0 0 6px;
}
.ttp-tabs :deep(.el-tabs__item) {
  height: 30px;
  line-height: 30px;
  font-size: 14px;
}
.ttp-tabs :deep(.el-tab-pane) {
  height: 100%;
  display: flex;
  flex-direction: column;
  min-height: 0;
}
/* 结果表格:吃掉剩余空间(高度由 ResizeObserver 实测后喂给 ResultGrid) */
.ttp-grid {
  flex: 1;
  min-height: 160px;
}
.ttp-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-bottom: 8px;
}
/* 工具栏里所有可交互控件同高(选择框与按钮混排时不齐会很明显) */
.ttp-bar :deep(.el-button),
.ttp-bar :deep(.el-select .el-select__wrapper),
.ttp-bar :deep(.el-input-number) {
  height: 32px;
}
.ttp-label {
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
.ttp-note {
  font-size: 13px;
  color: var(--el-text-color-placeholder);
}
.ttp-gap {
  flex: 1;
}
.ttp-showcols {
  width: 200px;
}
.ttp-sortcol {
  width: 170px;
}
/* 导出弹窗里逐列改名 */
.ttp-rename {
  width: 100%;
  max-height: 200px;
  overflow: auto;
}
.ttp-rename-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 4px;
}
.ttp-rename-orig {
  width: 130px;
  flex: none;
  font-size: 13px;
  color: var(--el-text-color-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.ttp-toggle {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  user-select: none;
}
.ttp-arrow {
  display: inline-block;
  transition: transform 0.15s;
  color: var(--el-text-color-placeholder);
}
.ttp-arrow.open {
  transform: rotate(90deg);
}
/* 已选条件摘要:收起条件区也一眼看得到筛了什么 */
.ttp-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: 8px;
}
.ttp-filter {
  margin-bottom: 8px;
  /* 条件多了也不许把结果区挤没:条件区自己滚 */
  max-height: 200px;
  overflow: auto;
}
/* 一行一个条件:字段 · 关系 · 值 · 命中数 · 删除 */
.ttp-cond {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 6px;
}
.ttp-c-col {
  width: 150px;
  flex: none;
}
.ttp-c-op {
  width: 108px;
  flex: none;
}
/* 不再自适应撑满:条件从左往右排,删除按钮紧跟其后(不再靠到最右边) */
.ttp-c-val {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}
.ttp-c-one,
.ttp-c-many {
  width: 300px;
}
.ttp-c-del {
  flex: none;
  margin-left: 2px;
}
.ttp-c-half {
  width: 140px;
}
.ttp-c-cnt {
  flex: none;
  min-width: 74px;
  text-align: right;
  font-size: 13px;
  color: var(--el-text-color-secondary);
  font-variant-numeric: tabular-nums;
}
.ttp-cond-add {
  display: flex;
  align-items: center;
  gap: 10px;
}
.ttp-warn-note {
  margin-top: 6px;
  font-size: 13px;
  line-height: 1.7;
  color: var(--el-color-warning);
}
/* 命中规模:圈选时最想知道的数字 */
.ttp-hit {
  display: flex;
  align-items: baseline;
  gap: 8px;
  padding: 10px 14px;
  margin-bottom: 10px;
  border-radius: 8px;
  background: var(--el-fill-color-light);
}
.ttp-hit-empty {
  font-size: 14px;
  color: var(--el-text-color-secondary);
}
.ttp-hit-num {
  font-size: 30px;
  font-weight: 700;
  line-height: 1;
  color: var(--el-color-primary);
}
.ttp-hit-unit {
  font-size: 14px;
  color: var(--el-text-color-secondary);
}
.ttp-hit-sub {
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
.ttp-pager {
  display: flex;
  justify-content: flex-end;
  padding: 8px 0;
}
.tt-tp-prof {
  margin-top: 4px;
}
.ttp-prof {
  margin-top: 4px;
}
.ttp-prof-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 14px;
  margin-bottom: 8px;
}
.ttp-dim {
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
.ttp-sql {
  margin: 0 0 8px;
  padding: 8px 10px;
  background: var(--el-fill-color-light);
  border-radius: 6px;
  font-family: var(--font-mono, monospace);
  font-size: 13px;
  white-space: pre-wrap;
  word-break: break-all;
}
.ttp-prof-table {
  width: 100%;
}
.ttp-bar-cell {
  height: 12px;
  background: var(--el-fill-color);
  border-radius: 3px;
  overflow: hidden;
}
.ttp-bar-fill {
  height: 100%;
  background: var(--el-color-primary);
  opacity: 0.65;
  min-width: 2px;
}
.ttp-opt {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.ttp-opt-note {
  font-size: 13px;
  color: var(--el-text-color-placeholder);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 50%;
}
.ttp-empty {
  padding: 30px 0;
  text-align: center;
  font-size: 14px;
  color: var(--el-text-color-secondary);
}
.ttp-schema-note {
  margin-bottom: 8px;
}
</style>
