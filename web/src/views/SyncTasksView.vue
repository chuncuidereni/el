<!--
  SyncTasksView.vue

  职责：同步任务页面：配置字段映射、启动同步、轮询运行进度并查看历史。
  边界：多步配置同步任务和字段映射；运行状态按任务独立轮询并在卸载时逐项清理，快速切换源或目标时旧类型响应不得覆盖当前选择。
-->

<template>
  <div class="sync-tasks-view">
    <ListPageTemplate v-model:page="page" v-model:page-size="pageSize" :total="listTotal">
    <template #header>
    <div class="page-head">
      <div>
        <h2>同步任务</h2>
        <p class="page-sub">连接数据源，按字段映射同步到目标库，支持全量与增量同步。</p>
      </div>
    </div>

    <ListQueryBar
      v-model:keyword="query.draftKeyword.value"
      keyword-placeholder="搜索任务、数据源或目标表"
      @search="searchListQuery"
      @reset="resetListQuery"
    />

    <div class="list-table-ops">
      <div class="list-table-ops-start">
        <el-button type="primary" :icon="Plus" @click="openCreate">新增任务</el-button>
        <el-button v-if="selectedTasks.length" type="danger" :icon="Delete" @click="batchRemoveTasks">批量删除({{ selectedTasks.length }})</el-button>
      </div>
    </div>

    </template>
    <div class="st-table-wrap">
    <el-table :data="list" v-loading="loading" class="st-table" height="100%" scrollbar-always-on ref="taskTableRef" @selection-change="onTaskSelectionChange">
      <el-table-column type="selection" width="42" align="center" />
      <el-table-column label="任务" min-width="190">
        <template #default="{ row }">
          <el-tooltip :content="row.name" placement="top" :show-after="400">
            <span class="cell-fill cell-ellipsis task-name">{{ row.name }}</span>
          </el-tooltip>
        </template>
      </el-table-column>
      <el-table-column label="流向" min-width="250">
        <template #default="{ row }">
          <el-tooltip :content="`${srcLabel(row)} → ${tgtLabel(row)}`" placement="top" :show-after="400">
            <span class="cell-fill flow-cell">
              <el-tag v-if="sourceTypeOf(row)" size="small" :type="srcTypeTag(sourceTypeOf(row))" effect="plain" class="flow-type">{{ srcTypeLabel(sourceTypeOf(row)) }}</el-tag>
              <button type="button" class="flow-src go-link" :aria-label="`查看数据源：${srcLabelHead(row)}`" @click.stop="goSource(row)">{{ srcLabelHead(row) }}</button>
              <span class="flow-arrow">→</span>
              <button type="button" class="flow-tgt go-link" :aria-label="`查看目标表：${tgtLabel(row)}`" @click.stop="goTarget(row)">{{ tgtLabel(row) }}</button>
            </span>
          </el-tooltip>
        </template>
      </el-table-column>
      <el-table-column label="策略 / 调度" width="136" align="left">
        <template #default="{ row }">
          <span class="meta-pair">
            <el-tag size="small" effect="plain" :type="strategyTagType(row)">{{ strategyLabel(row) }}</el-tag>
            <el-tooltip v-if="row.scheduleType === 'scheduled' && row.cronExpression" placement="top" width="320" trigger="hover" effect="light">
              <template #content>
                <CronHint :task-id="row.id" :expr="row.cronExpression" />
              </template>
              <el-tag size="small" type="success" effect="plain">定时</el-tag>
            </el-tooltip>
            <el-tag v-else size="small" type="info" effect="plain">手动</el-tag>
          </span>
        </template>
      </el-table-column>
      <el-table-column label="启用" width="64" align="center">
        <template #default="{ row }">
          <!-- 受控开关:用 :model-value + @change 而非 v-model —— 用户在确认框里点取消时开关会自己弹回原状态,不必再手动回写 row -->
          <el-tooltip :content="row.enabled ? '启用(定时/手动可执行)' : '已停用,不会自动执行,可手动运行前需先启用'" placement="top" trigger="hover">
            <el-switch size="small" :model-value="row.enabled" @change="confirmToggleEnabled(row)" />
          </el-tooltip>
        </template>
      </el-table-column>
      <el-table-column label="最近执行" width="280">
        <template #default="{ row }">
          <!-- lastRun 由后端在运行时写入:从未跑过(或列表尚未带上)时留空,这里用占位符而不是空单元格 -->
          <el-tooltip v-if="row.lastRun" placement="top" width="360" trigger="hover" effect="light">
            <template #content>
              <div class="run-hover">
                <div class="run-hover-line"><b>状态</b> {{ lastRunStatus(row.lastRun) }}</div>
                <div class="run-hover-line"><b>{{ row.lastRun.status === 'queued' ? '排队时间' : '开始' }}</b> {{ fmtDt(row.lastRun.startedAt) }}</div>
                <div v-if="row.lastRun.finishedAt" class="run-hover-line"><b>结束</b> {{ fmtDt(row.lastRun.finishedAt) }}</div>
                <div class="run-hover-line"><b>读取/写入</b> {{ row.lastRun.rowsRead }} / {{ row.lastRun.rowsWritten }} 行</div>
                <div v-if="row.lastRun.resumed" class="run-hover-line"><b>续传</b> 是（从断点继续）</div>
                <div v-if="row.lastRun.note" class="run-hover-line"><b>说明</b> {{ row.lastRun.note }}</div>
                <div v-if="row.lastRun.error" class="run-hover-line err"><b>错误</b> {{ row.lastRun.error }}</div>
              </div>
            </template>
            <span class="lastrun-cell">
              <span class="status-dot" :class="'dot-' + row.lastRun.status"></span>
              <span class="lastrun-label">{{ lastRunStatus(row.lastRun) }}</span>
              <el-tag v-if="row.queued && row.lastRun.status === 'running'" size="small" type="info">下次已排队</el-tag>
              <span class="lastrun-time">{{ fmtDt(row.lastRun.startedAt) }}</span>
              <el-tag v-if="row.lastRun.resumed" size="small" type="warning" effect="plain" class="resume-mini">续传</el-tag>
            </span>
          </el-tooltip>
          <span v-else class="muted">—</span>
        </template>
      </el-table-column>
      <!-- 表格内保留简洁进度列；鼠标悬停时在该单元格正上方显示完整阶段详情。 -->
      <el-table-column label="进度" width="150">
        <template #default="{ row }">
          <el-tooltip v-if="row.lastRun" placement="top" width="360" trigger="hover" effect="light">
            <template #content>
              <SyncProgressPanel :progress="row.lastRun.progress" :status="row.lastRun.status"
                :rows-read="row.lastRun.rowsRead" :rows-written="row.lastRun.rowsWritten" />
            </template>
            <div
              class="prog-cell"
              role="button"
              tabindex="0"
              :aria-label="`查看任务“${row.name}”的进度和执行日志`"
              @click="openHistory(row)"
              @keydown.enter.prevent="openHistory(row)"
              @keydown.space.prevent="openHistory(row)"
            >
              <template v-if="row.lastRun.status === 'running' || row.lastRun.status === 'queued'">
                <el-progress :percentage="runPercent(row.lastRun)" :stroke-width="4" :show-text="false" />
                <span class="prog-label">{{ runPercentLabel(row.lastRun) }}</span>
              </template>
              <span v-else-if="row.lastRun.status === 'failed'" class="prog-failed">失败</span>
              <span v-else class="prog-done">完成</span>
            </div>
          </el-tooltip>
          <span v-else class="muted">—</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="305" align="center" fixed="right">
        <template #default="{ row }">
          <ActionIconBar :actions="taskActions(row)" @command="(cmd: string) => onCommand(cmd, row)" />
        </template>
      </el-table-column>
    </el-table>
    </div>

    </ListPageTemplate>

    <!-- 新建/编辑对话框 -->
    <el-drawer v-model="dialogVisible" :title="editing ? `编辑任务 · ${editing.name}` : '新增同步任务'" size="min(1080px, 96vw)" direction="rtl" class="wizard-drawer" :close-on-click-modal="false" destroy-on-close>
      <el-form ref="formRef" :model="form" :rules="rules" label-width="104px" class="task-wizard-form">
        <el-steps :active="step" align-center finish-status="success" process-status="process" class="wizard-steps">
          <el-step>
            <template #title><button type="button" class="wizard-step-button" :aria-current="step === 0 ? 'step' : undefined" :disabled="sftpDetecting || nextStepBusy" @click="goToStep(0)">选择配置</button></template>
          </el-step>
          <el-step>
            <template #title><button type="button" class="wizard-step-button" :aria-current="step === 1 ? 'step' : undefined" :disabled="sftpDetecting || nextStepBusy" @click="goToStep(1)">字段映射</button></template>
          </el-step>
        </el-steps>
        <div v-if="dateBaselineLocked" class="baseline-lock-note" role="status">
          继承原全量断点：来源、日期规则、字段映射、目标和批大小已锁定，结束日期自动清空；新表策略与定时设置仍可调整。
          <template v-if="dateBaselineDifferences.length">
            <span class="baseline-lock-error">已修改：{{ dateBaselineDifferences.join('、') }}。</span>
            <el-button link type="primary" :loading="sourceColsLoading" @click="restoreDateBaseline">恢复原同步配置</el-button>
          </template>
        </div>

        <div v-if="step === 0" class="wizard-pane">
          <section class="config-section">
          <h3 class="config-section-title">基本信息</h3>
          <el-row :gutter="16">
              <el-col :xs="24" :sm="12">
                <el-form-item label="任务名称" prop="name">
                  <el-input v-model="form.name" placeholder="如:号段表每日同步" />
                </el-form-item>
              </el-col>
              <el-col :xs="24" :sm="12">
                <el-form-item label="描述">
                  <el-input v-model="form.description" placeholder="可选" />
                </el-form-item>
              </el-col>
            </el-row>
          </section>
          <section class="config-section">
            <h3 class="config-section-title">来源设置</h3>
            <div class="config-grid">
            <el-form-item label="数据源分组" required>
              <el-select v-model="dsGroupVal" :disabled="dateBaselineLocked" filterable placeholder="先选数据源分组" style="width: 100%" @change="onDsGroupChange">
                <el-option value="default" :label="`默认分组 (${dsInGroup('default').length})`" />
                <el-option v-for="g in dsGroups" :key="g.id" :value="String(g.id)" :label="`${g.name} (${dsInGroup(String(g.id)).length})`" />
              </el-select>
            </el-form-item>
            <el-form-item label="数据源" prop="dataSourceId">
              <el-select v-model="form.dataSourceId" :disabled="dateBaselineLocked" filterable placeholder="选择分组下的数据源" style="width: 100%" @change="onDataSourceChange">
                <el-option v-for="ds in dsInGroup(dsGroupVal)" :key="ds.id" :value="ds.id" :label="`${ds.name} (${ds.code})`">
                  <span class="ds-opt"><el-tag size="small" :type="srcTypeTag(ds.type)" effect="plain" class="ds-opt-tag">{{ srcTypeLabel(ds.type) }}</el-tag>{{ ds.name }}<span class="muted mono">({{ ds.code }})</span></span>
                </el-option>
              </el-select>
            </el-form-item>
            </div>
            <el-form-item v-if="curSrcType === 'mysql'" label="源表方式">
              <el-radio-group :model-value="!!form.mysqlDateTables" :disabled="dateBaselineLocked" @change="toggleMysqlDateTables">
                <el-radio :value="false">单张表</el-radio>
                <el-radio :value="true">按日期分表</el-radio>
              </el-radio-group>
            </el-form-item>
            <template v-if="sourceUi.dateTables && form.mysqlDateTables">
              <div class="config-grid date-rule-row">
                  <el-form-item label="表名规则" required>
                    <el-input
                      v-model="mysqlDateTablePrefix"
                      :disabled="dateBaselineLocked"
                      placeholder="如 xx_xx"
                      @input="invalidateDateTablePreview"
                    >
                      <template #suffix>
                        <span class="date-pattern-suffix">_{yyyyMMdd}</span>
                      </template>
                    </el-input>
                  </el-form-item>
                  <el-form-item label="日期区间">
                    <OptionalDateRangeInput
                      v-model:start-date="form.mysqlDateTables.startDate"
                      :disabled="dateBaselineLocked"
                      v-model:end-date="form.mysqlDateTables.endDate"
                      @change="invalidateDateTablePreview"
                    />
                  </el-form-item>
              </div>
              <el-form-item label="匹配预览">
                <div class="date-preview-row">
                  <el-button :loading="sourceColsLoading" @click="loadSourceCols(form.dataSourceId, true, true)">预览匹配表</el-button>
                  <span v-if="dateTablePreview" class="tbl-hint">匹配 {{ dateTablePreview.tables.length }} 张表<span v-if="dateTablePreview.primaryKey"> · 参考主键：{{ dateTablePreview.primaryKey }}</span></span>
                </div>
                <el-scrollbar v-if="dateTablePreview?.tables.length" max-height="140px" class="date-preview-list">
                  <div class="date-table-names"><OverflowText v-for="name in dateTablePreview.tables" :key="name" :text="name" /></div>
                </el-scrollbar>
              </el-form-item>
            </template>
            <!-- 单表模式保留原来的源表选择。 -->
            <el-form-item v-if="isObjectSource(curSrcType) && !sourceUi.dateTables" :label="sourceUi.objectLabel" prop="sourceObject">
              <el-select v-model="form.sourceObject" filterable clearable :placeholder="curSrcType === 'kafka' ? '选择 Topic' : curSrcType === 'mongodb' ? '选择该库下的集合' : '选择该库下的源表'" style="width: 100%" :loading="sqlTablesLoading" @change="onSqlTableChange">
                <el-option v-for="t in sqlTableOptions" :key="t" :value="t" :label="t" />
              </el-select>
            </el-form-item>
            <details v-if="sourceCols.length && curSrcType !== 'sftp'" class="source-fields-preview">
              <summary>源字段 · {{ sourceCols.length }} 列</summary>
              <div class="src-col-tags">
                <el-tag v-for="c in sourceCols" :key="c" size="small" effect="plain" class="src-col-tag"><OverflowText :text="c" /></el-tag>
              </div>
            </details>
            <!-- SFTP:按结构分类选择要同步的结构 -->
            <template v-if="curSrcType === 'sftp'">
              <el-form-item label="远端目录" required>
                <div class="config-inline">
                <el-input v-model="form.remotePath" readonly placeholder="选择远端目录" />
                <el-button size="small" :icon="FolderOpened" @click="openSftpDir">浏览</el-button>
                </div>
              </el-form-item>
              <div class="config-grid">
              <el-form-item label="首行标题">
                <el-select v-model="form.hasHeader" @change="detectSftp">
                  <el-option :value="true" label="有(首行为列名)" />
                  <el-option :value="false" label="无(无表头,按 column0..N 取名)" />
                </el-select>
              </el-form-item>
              <el-form-item label="文件匹配">
                <div class="config-inline">
                <el-input v-model="form.filePattern" placeholder="*.csv / cust_*.csv" @change="detectSftp" />
                <el-button v-if="form.remotePath" size="small" type="primary" :loading="sftpDetecting" @click="detectSftp">检测结构</el-button>
                </div>
              </el-form-item>
              </div>
              <el-form-item v-if="sftpGroups.length" label="文件结构" required>
              <div class="sftp-groups">
                <el-radio-group v-model="sftpSelIdx" @change="pickSftpGroup">
                  <el-radio v-for="pg in pagedGroups" :key="pg.gi" :value="pg.gi" class="sftp-group-opt">
                    <span class="sftp-group-name">
                      <el-tag size="small" effect="plain">{{ pg.g.fileCount }} 文件</el-tag>
                      <span class="sftp-group-count">{{ pg.g.columns.length }} 列</span>
                      <OverflowText :text="pg.g.name" :detail="pg.g.columns.map((c, i) => `${i + 1}. ${c.name} · ${c.type}`).join('\n')" />
                    </span>
                  </el-radio>
                </el-radio-group>
                <el-pagination
                  v-if="sftpGroups.length > sftpPageSize"
                  class="sftp-group-pager"
                  background layout="prev, pager, next"
                  small
                  :total="sftpGroups.length"
                  :page-size="sftpPageSize"
                  :current-page="sftpPage"
                  @current-change="(p: number) => sftpPage = p"
                />
              </div>
              </el-form-item>
              <el-form-item label="忽略额外尾列">
                <el-switch v-model="form.ignoreExtraCsvColumns" @change="detectSftp" />
              </el-form-item>
              <el-form-item label="CSV 编码">
                <el-select v-model="form.csvEncoding" @change="detectSftp" style="width: 220px">
                  <el-option label="自动识别" value="auto" />
                  <el-option v-for="encoding in ['UTF-8', 'GB18030', 'GBK', 'UTF-16LE', 'UTF-16BE']" :key="encoding" :label="encoding" :value="encoding" />
                </el-select>
                <span class="muted">识别不正确时可手动选择；已有断点保留原读取规则。</span>
              </el-form-item>
              <el-alert v-if="sftpReadSummary" :title="sftpReadSummary" type="info" :closable="false" />
              <el-alert v-if="editing?.checkpointPending" type="warning" :closable="false"
                title="当前有未完成断点，继续传输会保留本轮读取规则。切换编码或解析器需重新传输；重新传输会清空目标表并全量重建。" />
              <el-alert v-if="sftpReadErrors.length" type="warning" :closable="false" :title="`${sftpReadErrors.length} 个文件预览失败`">
                <OverflowText :text="sftpReadErrors.join('；')" :detail="sftpReadErrors.join('\n')" />
              </el-alert>
            </template>
            <div v-if="sourceColsLoading" class="source-state" role="status">正在读取源字段…</div>
            <el-alert v-else-if="sourceColsError" :title="sourceColsError" type="error" :closable="false" show-icon />
            <el-button v-if="sourceColsError && form.mysqlDateTables && form.sourceObject" link type="primary" :loading="sourceColsLoading" @click="reselectDateReference" :disabled="dateBaselineLocked">重新选择参考表</el-button>
          </section>
        </div>

        <section v-if="step === 0 && sourceUi.selected" class="config-section">
          <h3 class="config-section-title">目标设置</h3>
          <el-form-item label="目标表" prop="targetTable" required>
            <el-select v-model="form.targetTable" :disabled="dateBaselineLocked" filterable allow-create default-first-option placeholder="下拉选已有贴源表,或输入新表名(自动创建)" style="width: 100%" @change="onTargetTableChange">
              <el-option v-for="t in tables" :key="t.name" :value="t.name" :label="tableOptLabel(t)" />
            </el-select>
          </el-form-item>
        </section>

        <div v-if="step === 1" class="wizard-pane">
            <el-divider content-position="left" class="sched-divider">建表与字段映射</el-divider>
            <!-- 目标表为新建时:建表设置(分区/排序/主键)。默认按贴源规范:按 ods_insert_at 按日分区。 -->
            <!--
          建表设置。
          ⚠️ 以前这里挂的是 v-if="!targetExisting" —— 表一旦建好,整块就消失,
             用户既看不到自己配过的分区/排序/主键,也看不懂为什么编辑时改不了。
             现在分两种形态:表不存在 → 可编辑;表已存在 → 只读展示 + 说明原因。
        -->
        <el-collapse v-if="form.isOds" v-model="tblCfgOpen" class="tbl-config">
          <el-collapse-item :title="targetExisting ? '建表设置(只读 · 目标表已存在)' : '建表设置(分区键 / 排序键 / 主键)'" name="cfg">
            <!-- 表已存在:这些设置不再生效,只能看(分区键建完就改不了,要改必须删表重建) -->
            <div v-if="targetExisting" class="tbl-config-grid">
              <div class="tblcfg-item">
                <div class="tblcfg-label">分区键</div>
                <div class="tblcfg-value">{{ form.partitionExpr || '(不分区)' }}</div>
              </div>
              <div class="tblcfg-item">
                <div class="tblcfg-label">排序键</div>
                <div class="tblcfg-value mono">{{ form.sortingKeys?.length ? form.sortingKeys.join(', ') : '(自动:第一个映射目标列)' }}</div>
              </div>
              <div class="tblcfg-item">
                <div class="tblcfg-label">主键</div>
                <div class="tblcfg-value mono">{{ form.primaryKeys?.length ? form.primaryKeys.join(', ') : '(无)' }}</div>
              </div>
            </div>

            <template v-else>
              <div class="tbl-config-grid">
                <div class="tblcfg-item">
                  <div class="tblcfg-label">分区键</div>
                  <el-select v-model="form.partitionExpr" :disabled="dateBaselineLocked" filterable allow-create default-first-option clearable
                    placeholder="默认 toYYYYMMDD(ods_insert_at)" style="width: 100%">
                    <el-option v-for="p in partitionOptions" :key="p" :value="p" :label="p" />
                  </el-select>
                </div>
                <div class="tblcfg-item">
                  <div class="tblcfg-label">排序键</div>
                  <el-select v-model="form.sortingKeys" :disabled="dateBaselineLocked" multiple filterable collapse-tags style="width: 100%" placeholder="默认第一映射目标列">
                    <el-option v-for="c in targetColCandidates" :key="c" :value="c" :label="c" />
                  </el-select>
                </div>
                <div class="tblcfg-item">
                  <div class="tblcfg-label">主键</div>
                  <el-select v-model="form.primaryKeys" :disabled="dateBaselineLocked" multiple filterable collapse-tags style="width: 100%" placeholder="可选,须为排序键前缀">
                    <el-option v-for="c in targetColCandidates" :key="c" :value="c" :label="c" />
                  </el-select>
                </div>
              </div>
              <!--
                说明统一放在三列下面,不放某一格里 —— 之前分区键那格塞了两行长说明,
                那一列比旁边两列高出一大截,看着就是“排版乱”。
              -->
              <div v-if="partitionIsPhone" class="tblcfg-danger">
                不能按号码分区，否则会写废表；改用按天分区。
              </div>
            </template>
          </el-collapse-item>
        </el-collapse>

            <!-- 手机号列:锁定源(CSV 上传已定)直接展示;未锁定可在此选择源列作为手机号 -->
            <div v-if="sourceCols.length && mapRows.length" class="map-phone-sel">
              <span class="map-phone-label">手机号列(主键)</span>
              <el-tag v-if="isPhoneLocked && lockedPhone" type="danger" effect="dark" size="small" class="map-phone-lock">{{ lockedPhone }}</el-tag>
              <el-select v-else v-model="phoneModel" :disabled="dateBaselineLocked" clearable filterable placeholder="选择源列作为手机号" size="small" style="width: 200px">
                <el-option v-for="c in sourceCols" :key="c" :value="c" :label="c" />
              </el-select>
            </div>

            <!--
              宽表口径:管"这张源表要不要进那张大宽表、它多权威、号码怎么归一化"。
              字段级的取值方式在下面映射表的「宽表取值方式」列里配。
            -->
            <div v-if="phoneMapping" class="map-phone-sel">
              <span class="map-phone-label">明文手机号自动转 MD5</span>
              <el-switch v-model="phoneAutoMd5" :disabled="dateBaselineLocked" />
            </div>
            <div v-if="WIDE_TABLE_ENABLED && form.isOds" class="map-phone-sel">
              <span class="map-phone-label">纳入宽表</span>
              <el-switch v-model="form.arbInclude" />
              <template v-if="form.arbInclude">
                <span class="map-phone-label">源优先级</span>
                <el-input-number v-model="form.arbPriority" :min="0" :max="99" size="small" controls-position="right" style="width: 110px" />
                <span class="map-phone-label">号码归一化</span>
                <el-select v-model="form.arbMdnNorm" size="small" clearable placeholder="原样" style="width: 160px">
                  <el-option value="" label="原样(默认)" />
                  <el-option value="md5" label="转 MD5" />
                  <el-option value="prefix7" label="取前 7 位" />
                </el-select>
              </template>
            </div>

            <el-alert v-if="mappingErrorCount" type="error" :closable="false" show-icon class="mapping-errors"
              :title="`有 ${mappingErrorCount} 行字段映射需要调整，请查看目标列或映射类型下方提示。`" />
            <el-alert v-if="targetExisting && targetColsError" type="error" :closable="false" show-icon class="mapping-errors" :title="targetColsError">
              <el-button size="small" @click="loadTargetCols">重新读取目标字段</el-button>
            </el-alert>
            <el-alert v-for="error in systemTypeErrors" :key="error" type="error" :closable="false" show-icon class="mapping-errors" :title="error" />
            <el-alert v-for="error in systemWriteErrors" :key="error" type="error" :closable="false" show-icon class="mapping-errors" :title="error" />
            <el-alert v-if="targetFillGroups.length" type="info" :closable="false" show-icon class="mapping-errors target-fill-note" title="以下目标列不直接读取源数据">
              <div v-for="group in targetFillGroups" :key="group.label" class="target-fill-group">
                <span>{{ group.label }}（{{ group.columns.length }} 列）：</span>
                <OverflowText :text="group.columns.map(c => c.name).join('、')" :detail="group.columns.map(c => `${c.name} · ${c.type}${c.defaultKind ? ` · ${c.defaultKind} ${c.defaultExpression}` : ''}`).join('\n')" />
              </div>
              <el-checkbox v-if="unmappedWritableColumns.length" v-model="targetDefaultsAcknowledged">
                允许以上 {{ unmappedWritableColumns.length }} 列按目标表默认规则填充
              </el-checkbox>
            </el-alert>
            <el-alert v-if="mappingServerError" type="error" :closable="false" show-icon class="mapping-errors" :title="mappingServerError" />
            <el-alert v-if="sourceColsError" type="error" :closable="false" show-icon class="mapping-errors" :title="sourceColsError">
              <el-button size="small" @click="loadSourceCols(form.dataSourceId, true, true)">重新读取源字段</el-button>
            </el-alert>
            <el-table :data="mapRows" border size="small" class="map-table" :row-class-name="rowAutoCls">
              <el-table-column type="index" label="序号" width="60" align="center" />
              <el-table-column label="源列" min-width="150">
                <template #default="{ row, $index }">
                  <el-input v-if="row._auto" :model-value="row.sourceColumn" size="small" disabled />
                  <div v-else class="src-cell">
                    <el-tooltip v-if="isPhoneRow(row)" placement="top">
                      <template #content>手机号源列为「{{ row.sourceColumn }}」。{{ targetExisting ? '请选择能完整保存手机号的目标列和类型，建议使用 String。' : `新建表的目标列固定为 ${dimPhoneField}、类型 String。` }}</template>
                      <el-tag size="small" type="danger" effect="dark" class="src-phone-tag">手机</el-tag>
                    </el-tooltip>
                    <el-select v-model="row.sourceColumn" filterable size="small" placeholder="源列" style="width: 100%" :disabled="dateBaselineLocked || isPhoneRow(row)" @change="onMappingSourceChanged(row)">
                      <el-option v-for="c in srcOptions($index)" :key="c" :value="c" :label="c" />
                    </el-select>
                  </div>
                  <div v-if="!row._auto && sourceColumnType(row.sourceColumn)" class="mapping-type-label">源类型：{{ sourceColumnType(row.sourceColumn) }}</div>
                </template>
              </el-table-column>
              <el-table-column label="目标列" min-width="160">
                <template #default="{ row, $index }">
                  <!-- 贴源自动列:锁定展示 -->
                  <el-input v-if="row._auto" :model-value="row.targetColumn" size="small" disabled class="mono" />
                  <div v-else class="mapping-target-cell" :class="{ 'is-invalid': mappingTargetErrors[$index] }">
                    <el-select v-if="targetExisting" v-model="row.targetColumn" :disabled="dateBaselineLocked" filterable size="small" placeholder="选择目标表列" style="width:100%" :aria-invalid="!!mappingTargetErrors[$index]">
                      <el-option v-for="tc in targetColOptions($index)" :key="tc.name" :value="tc.name"
                        :disabled="!!systemColumnError(tc.name) || !targetColumnWritable(tc)" :label="targetColLabel(tc)" />
                    </el-select>
                    <el-input v-else v-model="row.targetColumn" size="small" placeholder="目标列名(自动创建)" :disabled="dateBaselineLocked || isPhoneRow(row)" :aria-invalid="!!mappingTargetErrors[$index]" />
                    <div v-if="mappingTargetErrors[$index]" class="mapping-field-error" role="alert">{{ mappingTargetErrors[$index] }}</div>
                  </div>
                </template>
              </el-table-column>
              <el-table-column label="映射类型" min-width="190">
                <template #default="{ row, $index }">
                  <el-input v-if="row._auto" :model-value="row.type" size="small" disabled class="mono" />
                  <div v-else class="mapping-target-cell" :class="{ 'is-invalid': mappingTypeErrors[$index] }">
                    <el-select v-model="row.type" filterable allow-create size="small" style="width: 100%" :disabled="dateBaselineLocked || (isPhoneRow(row) && !targetExisting)" :aria-invalid="!!mappingTypeErrors[$index]">
                      <el-option v-for="t in ckTypes" :key="t" :value="t" :label="t" />
                    </el-select>
                    <div v-if="actualTargetType(row.targetColumn)" class="mapping-type-label">实际目标：{{ actualTargetType(row.targetColumn) }}</div>
                    <div v-else-if="targetExisting && targetColsLoading" class="mapping-type-label">正在读取实际目标类型…</div>
                    <div v-if="mappingTypeErrors[$index]" class="mapping-field-error" role="alert">{{ mappingTypeErrors[$index] }}</div>
                    <div v-if="mappingTypeWarnings[$index]" class="mapping-type-warning" role="status">{{ mappingTypeWarnings[$index] }}</div>
                  </div>
                </template>
              </el-table-column>
              <el-table-column v-if="!targetExisting" label="可空" width="60" align="center">
                <template #default="{ row }">
                  <el-switch v-model="row.nullable" :disabled="dateBaselineLocked || row._auto || isPhoneRow(row)" />
                </template>
              </el-table-column>
              <el-table-column v-if="!targetExisting" label="默认值" min-width="130">
                <template #default="{ row }">
                  <el-input v-model="row.defaultValue" size="small" placeholder="字面量 / now() / today()" title="空值时使用；now()/today() 按同步服务所在时区求值，仅用于日期时间/日期列；不支持其他 SQL 表达式" :disabled="dateBaselineLocked || row._auto || isPhoneRow(row)" />
                </template>
              </el-table-column>
              <el-table-column v-if="!targetExisting" label="注释" min-width="140">
                <template #default="{ row }">
                  <el-input v-model="row.comment" size="small" placeholder="可选" :disabled="dateBaselineLocked || row._auto || isPhoneRow(row)" />
                </template>
              </el-table-column>
              <!--
                宽表取值方式:这一列进宽表时怎么取值。
                配在这里(而不是去工作流里配)是因为宽表的目标列名/业务名本来就配在这张表上,
                新增一张源表的人一次配完。号码列不用取值方式(它是骨架)。
              -->
              <el-table-column v-if="WIDE_TABLE_ENABLED && form.isOds" label="宽表取值方式" min-width="140">
                <template #default="{ row }">
                  <el-select v-model="row.aggMode" size="small" placeholder="仲裁(默认)" clearable
                    :disabled="dateBaselineLocked || row._auto || isPhoneRow(row)" style="width: 100%">
                    <el-option value="arbitrate" label="仲裁(挑一个最可信的)" />
                    <el-option value="sum" label="求和(按号码累加)" />
                    <el-option value="max" label="取最大" />
                    <el-option value="min" label="取最小" />
                  </el-select>
                </template>
              </el-table-column>
              <el-table-column width="56" align="center">
                <template #default="{ $index, row }">
                  <el-button v-if="!row._auto && !isPhoneRow(row)" size="small" type="danger" text :icon="Delete" @click="removeMapRow($index)" :disabled="dateBaselineLocked" :aria-label="`删除字段映射：${row.sourceColumn} → ${row.targetColumn}`" />
                </template>
              </el-table-column>
            </el-table>
            <div class="map-actions">
              <el-button size="small" :icon="Plus" @click="addMappingRow" :disabled="dateBaselineLocked">添加映射</el-button>
            </div>
        </div>

        <div v-if="step === 0 && sourceUi.selected">
          <section class="config-section">
          <h3 class="config-section-title">同步策略</h3>
          <div class="config-grid">
                <el-form-item label="同步方式">
                  <span v-if="!sourceUi.incremental" class="fixed-mode">{{ sourceUi.fixedMode }}</span>
                  <template v-else>
                  <el-radio-group v-model="form.mode" @change="onSyncModeChange">
                    <el-radio value="full">全量</el-radio>
                    <el-radio value="incremental">增量</el-radio>
                  </el-radio-group>
                  <el-tooltip v-if="form.mode === 'incremental'" placement="top" :show-after="250">
                    <template #content><div class="incremental-contract-detail">当前按已确认水位追加读取，适用于水位持续递增、旧记录不变的数据。低于断点的迟到或回填数据不会自动补读；已有记录的更新不会覆盖目标旧行。</div></template>
                    <span class="incremental-contract" tabindex="0">按水位追加</span>
                  </el-tooltip>
                  </template>
                </el-form-item>
                <el-form-item v-if="sourceUi.incrementalKey" label="增量键" required>
                  <el-select v-model="form.incrColumn" :disabled="dateBaselineLocked" filterable placeholder="选择源列作为增量键(按该列过滤新增)" style="width: 100%">
                    <el-option v-for="c in srcIncrOptions" :key="c" :value="c" :label="c" />
                  </el-select>
                </el-form-item>
                <el-form-item v-if="sourceUi.truncate" label="写入前">
                  <el-switch v-model="form.truncate" active-text="清空目标表(TRUNCATE)" />
                </el-form-item>
                <el-form-item v-if="sourceUi.stableKey" label="分段键" :required="sourceUi.dateTables">
                  <el-select v-model="form.stableKey" :disabled="dateBaselineLocked" clearable filterable :placeholder="sourceUi.dateTables ? '各表共同的整数单列主键' : '选择稳定的整数键'" @change="onStableKeyChange">
                    <el-option v-for="c in stableKeyOptions" :key="c.name" :value="c.name" :label="c.name" />
                  </el-select>
                </el-form-item>
          </div>
          <template v-if="sourceUi.dateTables && form.mysqlDateTables">
            <el-form-item v-if="sourceUi.incrementalKey" label="发现新表">
              <div class="date-policy-block">
                <el-radio-group v-model="form.mysqlDateTables.newTablePolicy">
                  <el-radio value="full">完整导入</el-radio>
                  <el-radio value="">仅接后续数据</el-radio>
                </el-radio-group>
                <div class="tbl-hint">{{ dateTablePolicyHint }}</div>
              </div>
            </el-form-item>
            <el-alert v-if="dateTableFullToIncremental" :title="dateTableModeHint" :type="editing?.checkpointPending ? 'warning' : 'info'" :closable="false" show-icon class="date-mode-hint" />
          </template>
          </section>
          <section class="config-section">
          <h3 class="config-section-title">执行设置</h3>
          <div class="config-grid">
              <el-form-item label="任务超时">
                <div class="config-inline">
                <el-input-number v-model="form.timeoutMinutes" :min="0" :max="1440" :precision="0" controls-position="right" />
                <span class="field-unit">分钟，0 为不限时</span>
                </div>
              </el-form-item>
              <el-form-item label="每批行数">
                <el-input-number v-model="form.batchSize" :disabled="dateBaselineLocked" :min="100" :max="10000" :precision="0" :step="100" controls-position="right" />
              </el-form-item>
          </div>
          <div class="config-grid">
          <el-form-item label="执行方式">
            <el-radio-group v-model="form.scheduleType">
              <el-radio value="manual">手动执行</el-radio>
              <el-radio value="scheduled">定时执行</el-radio>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="启用任务">
            <el-switch v-model="form.enabled" />
          </el-form-item>
          </div>
          <el-form-item v-if="form.scheduleType === 'scheduled'" label="执行时间">
            <div class="cron-gen">
              <!-- 频率选择:每隔多少(分钟/小时)或每天/每周/每月 -->
              <div class="cron-gen-row">
                <el-radio-group v-model="cronRule.unit" class="cron-freq" @change="cronAdvanced = false">
                    <el-radio-button value="minute">分钟</el-radio-button>
                    <el-radio-button value="hour">小时</el-radio-button>
                    <el-radio-button value="day">天</el-radio-button>
                    <el-radio-button value="week">周</el-radio-button>
                    <el-radio-button value="month">月</el-radio-button>
                  </el-radio-group>
                </div>
                <!-- 每 N 分钟/小时 -->
                <div v-if="cronRule.unit === 'minute' || cronRule.unit === 'hour'" class="cron-gen-row">
                  <span class="cron-hint">每</span>
                  <el-input-number v-model="cronRule.every" :min="1" :max="everyMax" controls-position="right" style="width: 96px" />
                  <span class="cron-hint">{{ cronRule.unit === 'minute' ? '分钟执行一次' : '小时执行一次(整点)' }}</span>
                </div>
                <!-- 每天:时刻 -->
                <div v-else-if="cronRule.unit === 'day'" class="cron-gen-row">
                  <span class="cron-hint">每天</span>
                  <el-time-select v-model="cronRule.time" start="00:00" step="00:05" end="23:55" placeholder="时间(时分)" style="width: 130px" />
                  <span class="cron-hint">执行一次</span>
                </div>
                <!-- 每周:星期几 + 时刻 -->
                <div v-else-if="cronRule.unit === 'week'" class="cron-gen-row">
                  <span class="cron-hint">每周</span>
                  <el-select v-model="cronRule.weekdays" multiple collapse-tags placeholder="星期几" style="width: 190px">
                    <el-option v-for="(lb, k) in WEEKDAY_LABELS" :key="k" :value="k" :label="lb" />
                  </el-select>
                  <el-time-select v-model="cronRule.time" start="00:00" step="00:05" end="23:55" placeholder="时间" style="width: 130px" />
                </div>
                <!-- 每月:几号 + 时刻 -->
                <div v-else-if="cronRule.unit === 'month'" class="cron-gen-row">
                  <span class="cron-hint">每月</span>
                  <el-select v-model="cronRule.day" placeholder="几号" style="width: 100px">
                    <el-option v-for="d in 31" :key="d" :value="d" :label="`${d} 号`" />
                  </el-select>
                  <el-time-select v-model="cronRule.time" start="00:00" step="00:05" end="23:55" placeholder="时间" style="width: 130px" />
                </div>
                <!-- 生成的 cron 预览 + 一键填入 -->
                <div class="cron-gen-preview">
                  <span class="cron-hint">生成结果</span>
                  <code class="cron-preview mono">{{ cronGenExpr || '(请选择完整参数)' }}</code>
                  <el-button size="small" type="primary" plain :disabled="!cronGenExpr" @click="applyCronGen">填入表达式</el-button>
                </div>
                <!-- 高级:自定义表达式(用生成的填入后仍可手改) -->
                <div class="cron-gen-adv">
                  <span class="cron-hint">自定义</span>
                  <el-input v-model="form.cronExpression" placeholder="或直接写:分 时 日 月 周" style="width: 340px" @focus="cronAdvanced = true" />
                </div>
              </div>
          </el-form-item>

          </section>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button v-if="step > 0" @click="step--">上一步</el-button>
        <!-- SFTP 在配置步骤正处于结构检测:整只按钮禁用,避免结构还没定就冲进字段映射 -->
        <el-button v-if="step < 1" type="primary" :loading="nextStepBusy || sourceColsLoading || sftpDetecting" :disabled="!sourceUi.selected || nextStepBusy || sourceColsLoading || sftpDetecting" @click="nextStep">下一步</el-button>
        <el-button v-if="step === 1" type="primary" :loading="saving" @click="onSave">{{ editing ? '保存' : '创建' }}</el-button>
      </template>
    </el-drawer>

    <!-- 执行日志抽屉:按运行记录分页查询,支持状态/续传/时间/关键字过滤 -->
    <el-drawer v-model="historyVisible" :title="`执行日志 · ${historyTask?.name ?? ''}`" size="min(1060px, 96vw)"
      body-class="sync-run-log-body" @closed="onHistoryClosed">
      <ListPageTemplate v-loading="historyLoading" class="run-log" :total="logTotal" :page="logPage" :page-size="logSize"
        @update:page="onLogPageChange" @update:page-size="onLogSizeChange">
        <template #header>
        <!-- 所有最新失败均提供恢复入口，不依赖错误文案是否包含“中断”。 -->
        <div v-if="failedRun && historyTask && !runningNow(historyTask)" class="intr-banner">
          <el-icon class="intr-ico"><WarningFilled /></el-icon>
          <div class="intr-txt">
            <b>上一次传输失败，任务已保留，可选择继续传输或重新传输。</b>
            <div class="intr-sub">继续传输：优先使用已有断点，没有断点时从头开始。重新传输：清除旧断点并全量重建目标数据，无需删除任务。</div>
          </div>
          <div v-if="historyTask" class="intr-actions">
            <el-button size="small" type="primary" plain :disabled="runningNow(historyTask) || historyTask.queued || !historyTask.enabled" @click="rerunFromHistory">继续传输</el-button>
            <el-button size="small" type="danger" plain :disabled="runningNow(historyTask) || historyTask.queued || !historyTask.enabled" @click="restartFromHistory">重新传输</el-button>
          </div>
        </div>

        <ListQueryBar
          v-model:keyword="logKeyword"
          keyword-placeholder="说明 / 错误 / 断点 key"
          @search="applyLogFilter"
          @reset="resetLogQuery"
        >
          <template #extra>
            <span class="log-filter-label">状态</span>
            <el-select v-model="logStatus" class="log-filter" @change="applyLogFilter">
              <el-option label="全部状态" value="all" />
              <el-option label="成功" value="success" />
              <el-option label="失败" value="failed" />
              <el-option label="运行中" value="running" />
              <el-option label="等待执行" value="queued" />
            </el-select>
            <span class="log-filter-label">续传</span>
            <el-select v-model="logResumed" class="log-filter" @change="applyLogFilter">
              <el-option label="全部运行" value="all" />
              <el-option label="仅断点续传" value="yes" />
              <el-option label="仅全新运行" value="no" />
            </el-select>
            <span class="log-filter-label">时间</span>
            <el-select v-model="logDays" class="log-filter" @change="applyLogFilter">
              <el-option label="全部时间" :value="0" />
              <el-option label="最近 24 小时" :value="1" />
              <el-option label="最近 7 天" :value="7" />
              <el-option label="最近 30 天" :value="30" />
            </el-select>
          </template>
        </ListQueryBar>

        </template>
        <el-table :data="logRows" row-key="ID" height="100%" v-loading="logLoading" border stripe class="log-table" empty-text="暂无运行记录">
          <el-table-column
            type="index"
            label="序号"
            width="80"
            align="center"
            :index="runLogIndex"
          />
          <el-table-column label="状态" width="130">
            <template #default="{ row }">
              <el-tag size="small" :type="statusTagType(row.Status)">{{ lastRunStatus({ status: row.Status }) }}</el-tag>
              <el-tag v-if="row.Resumed" size="small" type="warning" effect="plain" class="rs-tag log-resume-tag">续传</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="开始时间" width="175">
            <template #default="{ row }">{{ fmtDt(row.StartedAt) }}</template>
          </el-table-column>
          <el-table-column label="总耗时" width="110">
            <template #default="{ row }">{{ runDuration(row) }}</template>
          </el-table-column>
          <el-table-column label="读取/写入" width="110">
            <template #default="{ row }">{{ row.RowsRead }} / {{ row.RowsWritten }}</template>
          </el-table-column>
          <el-table-column label="当前进度" min-width="260">
            <template #default="{ row }">
              <div class="sync-progress-summary">
                <SyncProgressPanel :progress="row.Progress" :status="row.Status"
                  :rows-read="row.RowsRead" :rows-written="row.RowsWritten" />
              </div>
            </template>
          </el-table-column>
          <el-table-column label="平均速度" width="125">
            <template #default="{ row }">{{ runSpeed(row) }}</template>
          </el-table-column>
          <el-table-column label="断点" width="200">
            <template #default="{ row }">
              <span v-if="row.Resumed" class="mono">key={{ row.CheckpointFrom || '—' }} · {{ row.CheckpointRows ?? 0 }} 行</span>
              <span v-else class="muted">—</span>
            </template>
          </el-table-column>
          <el-table-column label="执行信息" min-width="180">
            <template #default="{ row }">
              <div v-if="row.Note" class="log-cell-note">{{ row.Note }}</div>
              <div v-if="row.Error" class="log-cell-err">{{ row.Error }}</div>
              <span v-if="!row.Note && !row.Error" class="muted">—</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="104" align="center" fixed="right">
            <template #default="{ row }">
              <ActionIconBar :actions="logActions(row)" @command="(command: string) => onLogAction(command, row)" />
            </template>
          </el-table-column>
        </el-table>

      </ListPageTemplate>
    </el-drawer>

    <el-dialog v-model="logDetailVisible" title="执行日志详情" width="min(900px, 94vw)" top="6vh" append-to-body destroy-on-close>
      <div v-if="logDetail" class="log-detail">
        <div class="sr-only" aria-live="polite" aria-atomic="true">{{ logDetailVisible ? `${logDetail.Status === 'success' ? '已完成' : logDetail.Status === 'failed' ? '已停止' : logDetail.Progress?.phase || '准备传输'}` : '' }}</div>
        <div v-if="logDetail.Note"><b>说明</b><span class="mono">{{ logDetail.Note }}</span></div>
        <div v-if="logDetail.Progress?.phase"><b>当前阶段</b><span>{{ logDetail.Progress.phase }}</span></div>
        <div v-if="logDetail.Progress?.currentObject"><b>处理对象</b><span class="mono">{{ logDetail.Progress.currentObject }}</span></div>
        <div>
          <b>断点</b>
          <span class="mono">{{ logDetail.Resumed ? `从 key=${logDetail.CheckpointFrom || '—'} 继续，起始已确认 ${logDetail.CheckpointRows ?? 0} 行` : '全新运行（未使用断点）' }}</span>
        </div>
        <div><b>读取/写入</b><span>{{ logDetail.RowsRead }} / {{ logDetail.RowsWritten }} 行</span></div>
        <div v-if="logDetail.Progress">
          <b>当前进度</b><span>{{ runProgressText(logDetail) }}</span>
          <b class="log-detail-gap">本次新增</b><span>{{ logDetail.Progress.runRows.toLocaleString() }} 行</span>
        </div>
        <div v-if="logDetail.Progress">
          <b>平均速度</b><span>{{ runSpeed(logDetail) }}</span>
          <b class="log-detail-gap">预计剩余</b><span>{{ runEta(logDetail) }}</span>
          <b class="log-detail-gap">自动重试</b><span>{{ logDetail.Progress.retryCount }} 次</span>
        </div>
        <div v-if="logDetail.Progress?.currentKey"><b>当前断点</b><span class="mono">{{ logDetail.Progress.currentKey }}</span></div>
        <div v-if="logDetail.Progress?.completedUnits != null">
          <b>阶段进度</b><span>{{ progressUnitsText(logDetail.Progress) }}</span>
          <span v-if="logDetail.Progress.unit === '位点'" class="muted">位点跨度不等于消息行数，扫描完成不代表全部写入。</span>
          <span v-else-if="logDetail.Progress.unit === '文件'" class="muted">含跳过、排除和失败文件，结果以本次运行状态为准。</span>
          <span v-else class="muted">阶段处理数量不等于目标表已确认写入行数。</span>
        </div>
        <div v-if="logDetail.Progress?.updatedAtMs"><b>最近更新</b><span>{{ new Date(logDetail.Progress.updatedAtMs).toLocaleString() }}</span></div>
        <div>
          <b>开始</b><span>{{ fmtDt(logDetail.StartedAt) }}</span>
          <b class="log-detail-gap">结束</b><span>{{ logDetail.FinishedAt ? fmtDt(logDetail.FinishedAt) : '—' }}</span>
          <b class="log-detail-gap">本次总耗时</b><span>{{ runDuration(logDetail) }}</span>
        </div>
        <div class="muted">速度按本次新增的已确认行数计算，包含重试等待；总量及剩余时间为估算，统计不可用时不展示估算值。</div>
        <div v-if="logDetail.Error"><b>错误</b><pre class="log-error">{{ logDetail.Error }}</pre></div>
        <div v-else-if="isInterrupted(logDetail)" class="run-intr-hint">{{ historyTask?.resumable ? '本次因中断未跑完；已记录断点，再次运行将从断点继续。' : '本次因中断未跑完；请检查已写入数据后按任务策略重新运行。' }}</div>
      </div>
    </el-dialog>

    <!-- SFTP 远端目录选择(选目录,不做结构检测) -->
    <SftpBrowserDialog
      v-model="sftpDirVisible"
      :conn="{ id: form.dataSourceId }"
      mode="dir"
      title="选择远端目录"
      @ok="onSftpDirPicked"
    />

    <!-- 点击“数据源”列:SFTP 直接打开该数据源的文件系统浏览/预览 -->
    <SftpFilePreview v-model="srcPvVisible" :id="srcPvTask?.dataSourceId ?? 0" :name="srcPvTask?.dataSourceName ?? ''"
      :csv-encoding="srcPvTask?.csvEncoding" :header="srcPvTask?.hasHeader" :ignore-extra-csv-columns="srcPvTask?.ignoreExtraCsvColumns" />
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { DataAnalysis, Delete, FolderOpened, Plus, WarningFilled } from '@element-plus/icons-vue'
import type { FormInstance, FormRules } from 'element-plus'
import { connectionsApi, dataSourcesApi, dsGroupsApi, settingsApi, syncTasksApi, workspaceApi } from '@/api'
import SftpBrowserDialog from '@/components/SftpBrowserDialog.vue'
import SftpFilePreview from '@/components/SftpFilePreview.vue'
import CronHint from '@/components/CronHint.vue'
import type { Connection, DataSource, DatabaseInfo, SyncRun, SyncTask, SyncTaskInput, TableInfo, WideCheckResult } from '@/types'
import { WIDE_TABLE_ENABLED } from '@/utils/features'
import { confirmAction, confirmDestructive } from '@/utils/confirm'
import { fmtDt } from '@/utils/format'
import { TYPE_OPTIONS } from '@/utils/createTable'
import { inferSourceType as inferType, canBeIncrementalKey, fillAutoTargetColumns, mappingTargetIssues, systemTargetColumnError, systemTargetTypeIssues } from '@/utils/syncTypes'
import { mappingTypeIssue } from '@/utils/syncMappingTypes'
import { mentionsBareIdent } from '@/utils/sqlIdent'
import { fileSourceColumns } from '@/utils/fileColumns'

import ActionIconBar from '@/components/ActionIconBar.vue'
import SyncProgressPanel from '@/components/SyncProgressPanel.vue'
import ListQueryBar from '@/components/ListQueryBar.vue'
import ListPageTemplate from '@/components/ListPageTemplate.vue'
import OptionalDateRangeInput from '@/components/OptionalDateRangeInput.vue'
import OverflowText from '@/components/OverflowText.vue'
import { useListQuery } from '@/composables/useListQuery'

function taskActions(row: SyncTask) {
  const busy = runningNow(row) || !!row.queued || adoptingIds.value.has(row.id)
  const unavailable = busy || !row.enabled
  const canResume = row.checkpointPending || row.lastRun?.status === 'failed'
  return [
    ...(runningNow(row) ? [{ command: 'cancel', label: '取消运行', danger: true }] : []),
    ...(row.queued ? [{ command: 'cancelqueued', label: '取消排队', danger: true }] : []),
    { command: 'run', label: row.enabled ? (canResume ? '继续传输' : '执行（默认续传）') : '停用，需先启用', primary: true, disabled: unavailable },
    { command: 'runforce', label: row.enabled ? '重新传输（全量重建）' : '停用，需先启用后重新传输', danger: true, disabled: unavailable },
    { command: 'history', label: '运行历史' },
    ...(canAdoptBaseline(row) ? [{
      command: 'adopt',
      label: row.canAdoptBaseline ? '接管已有全量数据' : row.baselineAdoptionBlockedReason || '请刷新后查看接管条件',
      disabled: !row.canAdoptBaseline || runningNow(row) || row.queued || adoptingIds.value.has(row.id)
    }] : []),
    { command: 'edit', label: '编辑', disabled: busy },
    { command: 'toggle', label: row.enabled ? '停用' : '启用', disabled: busy },
    { command: 'remove', label: '删除', danger: true, disabled: busy },
  ]
}


const adoptingIds = ref(new Set<number>())
function canAdoptBaseline(row: SyncTask) {
  return row.mode === 'incremental' && ['mysql', 'pgsql', 'clickhouse'].includes(sourceTypeOf(row))
}


/** 接管只建立起点，不触发清表或运行；用户可随后手动执行，也可等待原定时调度。 */
async function adoptBaseline(row: SyncTask) {
  if (!row.canAdoptBaseline || row.queued || runningNow(row) || runningIds.value.has(row.id) || adoptingIds.value.has(row.id)) return
  adoptingIds.value.add(row.id)
  try {
    const preview = await syncTasksApi.baselinePreview(row.id)
    try {
      await ElMessageBox.confirm(
        `目标表 ${preview.target} 现有 ${preview.targetRows.toLocaleString()} 行，数据源标识为 ${preview.sourceCode}。` +
        `将以 ${preview.incrementalColumn} 的水位 ${preview.watermark} 为起点继续增量。` +
        '请确认该水位及之前的历史数据已完整导入；接管后只同步水位之后的数据，不会补齐较小水位的缺失记录。现有目标数据会保留。',
        '接管已有全量数据',
        { type: 'warning', confirmButtonText: '确认历史数据完整并接管', cancelButtonText: '取消', closeOnClickModal: false },
      )
    } catch { return }
    await syncTasksApi.adoptBaseline(row.id, preview.confirmationToken)
    ElMessage.success('已保存增量起点，可点击执行或等待定时调度')
    await load()
  } catch { /* 接口拦截器展示具体原因；失败时保留现有数据和断点。 */ }
  finally { adoptingIds.value.delete(row.id) }
}

const router = useRouter()
const route = useRoute()
const list = ref<SyncTask[]>([])
const query = useListQuery()
function resetListQuery() {
  const before = listQueryKey()
  query.reset()
  page.value = 1
  if (before === listQueryKey()) void refreshListAfterMutation()
}

function searchListQuery() {
  const before = listQueryKey()
  query.search()
  page.value = 1
  if (before === listQueryKey()) void refreshListAfterMutation()
}

// 服务端分页；总数来自相同关键词及所有权条件，不再下载全部任务后切片。
const page = ref(1)
const pageSize = ref(10)
const listTotal = ref(0)
function listQueryKey() { return JSON.stringify([page.value, pageSize.value, query.keyword.value]) }
let appliedPageCorrection = ''
watch([page, pageSize, query.keyword], () => {
  if (appliedPageCorrection === listQueryKey()) { appliedPageCorrection = ''; return }
  appliedPageCorrection = ''
  selectedTasks.value = []
  taskTableRef.value?.clearSelection()
  void refreshListAfterMutation()
})
const loading = ref(false)
// 批量删除勾选
const taskTableRef = ref<any>(null)
const selectedTasks = ref<SyncTask[]>([])
function onTaskSelectionChange(sel: SyncTask[]) {
  selectedTasks.value = sel
}

/**
 * 同步任务运行状态机。
 * runningIds 记录“本页面已发起、尚未观察结束”的任务，用于阻止连点并为该行显示运行态；
 * expectedRunIds 保存启动接口返回的准确 runId，列表可能晚到或被后续运行替换，不能只看最近一条状态。
 * pollTimers 为每个任务各维护一个 2 秒定时器，pollBusy 防止单次请求尚未完成时继续叠加。
 * 注意：runningNow() 读取后端列表中的 lastRun，因此页面打开前由定时任务或其他会话启动的运行也会显示为运行中；
 * 重新进入页面会自动观察已运行任务；只有本页发起且持有 expectedRunId 的任务才弹完成提示。
 */
const runningIds = ref<Set<number>>(new Set())
/** 只等待本次请求返回的运行号，避免把旧运行结果当成完成。 */
const expectedRunIds = ref<Map<number, number>>(new Map())
/** 每次观察固定运行号和会话标识；刷新页面恢复的运行不弹本页完成提示。 */
const observedRuns = new Map<number, { runId: number }>()
const progressVersions = new Map<number, number>()
let progressVersion = 0
let pollsDisposed = false
/** 页面隐藏时只暂停前端定时请求，后端任务和观察状态继续保留。 */
let pollingPausedForVisibility = false
/** 每个运行中任务独立的轮询定时器 */
const pollTimers = ref<Map<number, ReturnType<typeof setInterval>>>(new Map())
/** 正在轮询(请求进行中)的任务集合,防止 interval 叠加请求 */
const pollBusy = ref<Set<number>>(new Set())

/** 任务是否正在运行:看后端返回的最新状态(不受本页是否启动影响,覆盖 cron/F5 触发的运行) */
function runningNow(row: SyncTask): boolean {
  return !!row.lastRun && row.lastRun.status === 'running'
}


const dialogVisible = ref(false)
const nextStepBusy = ref(false)
// 每次打开/关闭都废弃旧会话；即使旧请求晚到，也不能覆写当前任务或重新打开抽屉。
let editorSessionSeq = 0
let connGroupsSeq = 0
let tableOptionsSeq = 0
function invalidateEditorSession() {
  sftpReadSummary.value = ''
  sftpReadErrors.value = []
  editorSessionSeq++
  sourceColsSeq++
  targetColsSeq++
  sftpDetectSeq++
  connGroupsSeq++
  tableOptionsSeq++
  sourceColsLoading.value = false
  targetColsLoading.value = false
  sqlTablesLoading.value = false
  sftpDetecting.value = false
  saving.value = false
  nextStepBusy.value = false
  return editorSessionSeq
}
function editorCurrent(session: number) { return session === editorSessionSeq && !pollsDisposed }
watch(dialogVisible, (visible) => { if (!visible) invalidateEditorSession() }, { flush: 'sync' })
const saving = ref(false)
const editing = ref<SyncTask | null>(null)
const formRef = ref<FormInstance>()
const localDataSources = ref<DataSource[]>([])
/** SQL 关系源(MySQL/PgSQL)可选的源表列表(该数据源所在库的表) */
const sqlTableOptions = ref<string[]>([])
const sqlTablesLoading = ref(false)
/** 该 SQL 数据源自带表(config.table);用于「源表」下拉的提示与默认 */
const srcDefaultTable = computed(() => {
  const src = localDataSources.value.find((d) => d.id === form.value.dataSourceId)
  return String(src?.config?.table ?? '').trim()
})
/** 切换任务级源表后重新按所选表取源列(会重建映射) */
async function onSqlTableChange(table?: string) {
  if (typeof table === 'string') form.value.sourceObject = table
  if (!form.value.dataSourceId) return
  resetSourceFields()
  await loadSourceCols(form.value.dataSourceId, false)
}

const dsGroups = ref<{ id?: number; name: string; count?: number }[]>([])
const dsGroupVal = ref('default') // 数据源分组筛选(default/all/<gid>)
const connections = ref<Connection[]>([])
let connectionsLoading: Promise<Connection[]> | null = null
let connectionsLoaded = false
function loadConnections(): Promise<Connection[]> {
  if (connectionsLoaded) return Promise.resolve(connections.value)
  if (!connectionsLoading) {
    connectionsLoading = connectionsApi.list().then(rows => {
      connections.value = rows
      connectionsLoaded = true
      return rows
    }).finally(() => { connectionsLoading = null })
}

  return connectionsLoading
}


/** 数据源类型 → 中文标签(下拉区分用) */
const SRC_TYPE_LABEL: Record<string, string> = {
  csv: '本地CSV',
  json: '本地JSON',
  xlsx: '本地Excel',
  sftp: 'SFTP',
  ck_table: '库内表',
  mysql: 'MySQL',
  pgsql: 'PgSQL',
  clickhouse: 'ClickHouse',
  mongodb: 'MongoDB',
  kafka: 'Kafka',
  hive: 'Hive'
}

function isObjectSource(type: string): boolean {
  return ['mysql', 'pgsql', 'clickhouse', 'mongodb', 'kafka', 'hive'].includes(type)
}

function srcTypeLabel(t: string): string {
  return SRC_TYPE_LABEL[t] ?? t
}

/** 兼容尚未重启的后端：列表 DTO 缺类型时从已加载的数据源列表补齐。 */
function sourceTypeOf(row: SyncTask): string {
  return row.dataSourceType || localDataSources.value.find((d) => d.id === row.dataSourceId)?.type || ''
}

function srcTypeTag(t: string): 'success' | 'warning' | 'primary' | 'danger' | 'info' {
  if (t === 'sftp') return 'warning'
  if (t === 'csv' || t === 'json') return 'success'
  return 'info'
}


/** 某数据源分组下的数据源 */
function dsInGroup(v: string): DataSource[] {
  if (v === 'all') return localDataSources.value
  if (v === 'default') return localDataSources.value.filter((d) => !d.groupId)
  const gid = Number(v)
  return localDataSources.value.filter((d) => d.groupId === gid)
}

// ─────────── 宽表口径检查 ───────────
const wideVisible = ref(false)
const wideChecking = ref(false)
const wide = ref<WideCheckResult | null>(null)

async function runWideCheck() {
  wideVisible.value = true
  wideChecking.value = true
  try {
    wide.value = await syncTasksApi.wideCheck()
  } catch (e: any) {
    ElMessage.error(e?.message || '检查失败')
    wide.value = null
  } finally {
    wideChecking.value = false
}
}


async function loadDsGroups() {
  try {
    dsGroups.value = await dsGroupsApi.list()
  } catch {
    dsGroups.value = []
}
}

const sourceCols = ref<string[]>([])
/** 独立保存表头说明，不写入 schema，保持历史任务的结构和续传指纹不变。 */
const sourceColumnComments = ref<Record<string, string>>({})
const sourceColsLoading = ref(false)
const sourceColsError = ref('')
let sourceColsSeq = 0
/** SFTP 目录结构分类结果(供向导选一类作为同步结构) */
const sftpGroups = ref<{ name: string; columns: { name: string; type: string }[]; fileCount: number }[]>([])
const sftpReadSummary = ref('')
const sftpReadErrors = ref<string[]>([])
function showSftpReadInfo(det: { files?: { path: string; csvEncoding?: string; error?: string }[] }) {
  const encodings = [...new Set((det.files ?? []).map(f => f.csvEncoding).filter(Boolean))]
  sftpReadSummary.value = encodings.length ? `本次预览识别编码：${encodings.join('、')}。自动识别基于文件前缀，预览不代表全文件校验通过。` : ''
  sftpReadErrors.value = (det.files ?? []).filter(f => f.error).map(f => `${f.path}：${f.error}`)
}
const sftpSelIdx = ref(-1)
const sftpPageSize = 6
const sftpPage = ref(1)
/** 结构分类分页:当前页条目(带全局下标,供 radio 选中真实 index) */
const sftpTotalPages = computed(() => Math.max(1, Math.ceil(sftpGroups.value.length / sftpPageSize)))
const pagedGroups = computed(() => {
  const st = (sftpPage.value - 1) * sftpPageSize
  return sftpGroups.value.slice(st, st + sftpPageSize).map((g, k) => ({ g, gi: st + k }))
})
/** 结构列表变化(重检测)后回到第 1 页,若当前选中项在后续页则翻到其所在页 */
function syncSftpPage() {
  if (sftpSelIdx.value >= 0) {
    sftpPage.value = Math.min(sftpTotalPages.value, Math.floor(sftpSelIdx.value / sftpPageSize) + 1)
  } else {
    sftpPage.value = 1
}
}

const sftpDetecting = ref(false)
/** 当前选择的数据源类型 */
const curSrcType = ref('')
/** SFTP 目录选择弹窗可见 */
const sftpDirVisible = ref(false)
/** 点击“数据源”列 → 打开数据源文件系统预览(SFTP) */
const srcPvVisible = ref(false)
const srcPvTask = ref<SyncTask | null>(null)
const databases = ref<DatabaseInfo[]>([])
const tables = ref<TableInfo[]>([])
const ckTypes = TYPE_OPTIONS // 复用数仓建表的类型选项
/** 贴源目标固定落 ods_data(同步任务统一写入贴源库,弱化库存在) */
const odsDbName = 'ods_data'
/** 建表设置折叠区默认展开 */
const tblCfgOpen = ref<string[]>(['cfg'])
/** 目标表当前是否为“已存在表”(从下拉选中) — 已存在则不新建 */
const targetExisting = computed(
  () => !!form.value.targetTable.trim() && tables.value.some((t) => t.name === form.value.targetTable.trim())
)

/** 自动追加的贴源字段(只读展示用;不写入 form.mapping)。源为 SFTP 时多一列 path。 */
const odsAutoRow = (name: string, type: string, def: string, comment: string) => ({
  _auto: true,
  sourceColumn: '(自动)',
  targetColumn: name,
  type,
  defaultValue: def,
  comment,
  nullable: false
})
const odsAutoRows = computed(() => {
  // 仅自动建表(isOds 贴源)时展示只读自动列;已存在表或无 ODS 语义则不显示
  if (targetExisting.value || !form.value.isOds) return []
  const rows: ReturnType<typeof odsAutoRow>[] = [
    odsAutoRow('ods_insert_at', 'DateTime', 'now()', '入库时间'),
    odsAutoRow('ods_source_id', 'String', '', '来源(数据源标识)')
  ]
  if (curSrcType.value === 'sftp') rows.push(odsAutoRow('path', 'String', '', '来源文件路径'))
  return rows
})
/** 字段映射表展示行 = 业务映射行 + 只读贴源自动行 */
const mapRows = computed(() => [...form.value.mapping, ...odsAutoRows.value])
/** 建表设置:可选的排序/主键列 = 映射中已填的目标列 */
const targetColCandidates = computed(() =>
  Array.from(new Set(form.value.mapping.map((m) => m.targetColumn.trim()).filter(Boolean)))
)
/** 分区键候选项:只给「日期/时间列」及其常用日期函数表达式。
 *
 * 为什么不把所有列都列出来(真机踩过):有人选了手机号当分区键 → 一个号码一个分区,
 * 单次写入超过 100 个分区时 ClickHouse 直接拒写(code 252),而且分区键建完改不了。
 * 号码这类高基数列应该放排序键/主键,不是分区。
 */
const PARTITION_FNS = ['toYYYYMMDD', 'toDate', 'toMonday', 'toStartOfMonth', 'toYYYYMM']
const isDateCol = (t: string) => {
  const base = (t || '').replace(/^(Nullable|LowCardinality)\(|\)$/g, '')
  return base.startsWith('Date') || base.startsWith('DateTime')
}

const partitionOptions = computed(() => {
  const dateCols = form.value.mapping
    .filter((m) => isDateCol(m.type))
    .map((m) => m.targetColumn.trim())
    .filter(Boolean)
  const opts: string[] = []
  for (const c of Array.from(new Set(dateCols))) {
    opts.push(c)
    for (const fn of PARTITION_FNS) opts.push(`${fn}(${c})`)
}

  if (form.value.isOds) opts.push(...PARTITION_FNS.map((fn) => `${fn}(ods_insert_at)`))
  return Array.from(new Set(opts))
})
/** 当前分区键是不是选了号码列(选了要让用户当场看到,而不是等写入才报错) */
const partitionIsPhone = computed(() => {
  const e = (form.value.partitionExpr || '').trim()
  const p = (form.value.phoneColumn || '').trim()
  // 判定逻辑抽到 utils/sqlIdent(纯函数 + 单测):列名带正则元字符时不能抛、也不能误判
  return mentionsBareIdent(e, p)
})
/** 已存在目标表的列(用于“选择现有表”时目标列下拉,附注释便于理解) */
type TargetColumn = { name: string; comment: string; type: string; defaultKind: string; defaultExpression: string }
const targetCols = ref<TargetColumn[]>([])
function targetColumnWritable(column: TargetColumn): boolean {
  return !column.defaultKind || column.defaultKind === 'DEFAULT'
}
const systemWriteColumns = computed(() => [
  ...(form.value.isOds ? ['ods_source_id', 'ods_insert_at'] : ['source_id', 'create_time']),
  ...(curSrcType.value === 'sftp' ? ['path'] : [])
])
const systemWriteErrors = computed(() => targetExisting.value ? targetCols.value
  .filter(c => systemWriteColumns.value.includes(c.name) && !targetColumnWritable(c))
  .map(c => `系统字段「${c.name}」为 ${c.defaultKind} 列，不能写入同步值，请使用普通列或 DEFAULT 列`) : [])
/** 补值确认绑定目标连接/库/表，切换目标后不能沿用同名列的确认。 */
const targetDefaultsScope = ref('')
const targetDefaultsKey = () => JSON.stringify([form.value.connectionId, form.value.targetDb, form.value.targetTable])
const unmappedTargetColumns = computed(() => {
  if (!targetExisting.value || targetColsLoading.value || targetColsError.value) return []
  const written = new Set([...systemWriteColumns.value, ...form.value.mapping.map(m => m.targetColumn.trim())])
  return targetCols.value.filter(c => !written.has(c.name))
})
const unmappedWritableColumns = computed(() => unmappedTargetColumns.value.filter(targetColumnWritable))
const targetDefaultsAcknowledged = computed({
  get: () => targetDefaultsScope.value === targetDefaultsKey()
    && unmappedWritableColumns.value.every(c => (form.value.allowedTargetDefaults ?? []).includes(c.name)),
  set: (allowed: boolean) => {
    form.value.allowedTargetDefaults = allowed ? unmappedWritableColumns.value.map(c => c.name) : []
    targetDefaultsScope.value = targetDefaultsKey()
  }
})
/** 正常补值属于说明；只有未确认的可写列才阻止保存。 */
const targetFillGroups = computed(() => {
  const omitted = unmappedTargetColumns.value
  return [
    { label: '类型默认值（如空字符串、0 或 NULL）', columns: omitted.filter(c => !c.defaultKind) },
    { label: '执行 DEFAULT 表达式', columns: omitted.filter(c => c.defaultKind === 'DEFAULT') },
    { label: '按表定义计算或处理的列', columns: omitted.filter(c => !targetColumnWritable(c)) }
  ].filter(group => group.columns.length)
})
const targetColsLoading = ref(false)
const targetColsError = ref('')
const mappingServerError = ref('')
let targetColsSeq = 0
/** 是否已选择了一个已存在目标表(可选现有列) */
const targetTableExists = ref(false)

/** 选定目标表后加载其列(仅当“非自动创建”且表已存在) */
async function onTargetTableChange() {
  onTargetResolve()
}

async function loadTargetCols() {
  const request = ++targetColsSeq
  const db = form.value.targetDb,
    tbl = form.value.targetTable
  const connection = form.value.connectionId
  targetCols.value = []
  targetColsError.value = ''
  if (!targetExisting.value || !db || !tbl) {
    targetColsLoading.value = false
    targetTableExists.value = false
    return
}

  targetColsLoading.value = true
  try {
    const res = await workspaceApi.schema(connection, db, tbl)
    if (request !== targetColsSeq || db !== form.value.targetDb || tbl !== form.value.targetTable || connection !== form.value.connectionId) return
    // DESCRIBE 返回的结构里,表字段名在 rows 的每一行第 0 列,注释在第 4 列
    if ((res?.rows ?? []).some((r: unknown) => !Array.isArray(r) || r.length < 4)) throw new Error('目标默认值定义缺失')
    targetCols.value = (res?.rows ?? [])
      .map((r: any) => ({
        name: r && r[0] != null ? String(r[0]).trim() : '',
        type: r && r[1] != null ? String(r[1]).trim() : '',
        defaultKind: r && r[2] != null ? String(r[2]).trim().toUpperCase() : '',
        defaultExpression: r && r[3] != null ? String(r[3]) : '',
        comment: r && r[4] != null ? String(r[4]).trim() : ''
      }))
      .filter((c: any) => c.name)
    if (!targetCols.value.length || targetCols.value.some((c) => !c.type)) throw new Error('目标字段类型缺失')
    targetTableExists.value = targetCols.value.length > 0
    ensureAutoTargets()
  } catch {
    if (request !== targetColsSeq || db !== form.value.targetDb || tbl !== form.value.targetTable || connection !== form.value.connectionId) return
    targetCols.value = []
    targetTableExists.value = false
    targetColsError.value = '未能读取目标表字段及类型，无法检查类型匹配，请重新读取后继续'
  } finally {
    if (request === targetColsSeq) targetColsLoading.value = false
}
}

// 目标已存在时加载列,不存在则目标列走自定义输入
function onTargetResolve() {
  if (targetExisting.value) loadTargetCols()
  else {
    targetColsSeq++
    targetCols.value = []
    targetColsLoading.value = false
    targetColsError.value = ''
    targetTableExists.value = false
}
}

const step = ref(0) // 0=选择配置, 1=字段映射

const historyVisible = ref(false)
const historyLoading = ref(false)
const historyTask = ref<SyncTask | null>(null)
const runs = ref<SyncRun[]>([])
// 执行日志(服务端分页 + 过滤):与 runs(仅取最近 20 条用于顶部中断提示)分开管理
const logRows = ref<SyncRun[]>([])
const logTotal = ref(0)
const logPage = ref(1)
const logSize = ref(20)
const logStatus = ref('all')
const logResumed = ref('all')
const logDays = ref(0)
/** 执行日志跨分页连续序号；运行 ID 仍由 row-key 和详情逻辑使用。 */
function runLogIndex(index: number): number {
  return (logPage.value - 1) * logSize.value + index + 1
}
const logKeyword = ref('')
const logLoading = ref(false)
const deletingLog = ref(false)
const logDetailVisible = ref(false)
const logDetailSnapshot = ref<SyncRun | null>(null)
/** 复用历史轮询的最新记录，详情弹窗打开后仍能更新运行进度。 */
const logDetail = computed(() => {
  const current = logDetailSnapshot.value
  return current ? logRows.value.find(row => row.ID === current.ID)
    ?? runs.value.find(row => row.ID === current.ID) ?? current : null
})

function openLogDetail(row: SyncRun) {
  logDetailSnapshot.value = row
  logDetailVisible.value = true
}

function closeLogDetail() {
  logDetailVisible.value = false
  logDetailSnapshot.value = null
}

function canDeleteLog(row: SyncRun): boolean {
  return !deletingLog.value && (row.Status === 'success' || row.Status === 'failed')
}

function logActions(row: SyncRun) {
  return [
    { command: 'open', label: '详情', primary: true },
    { command: 'remove', label: '删除', danger: true, disabled: !canDeleteLog(row) },
  ]
}

function onLogAction(command: string, row: SyncRun) {
  if (command === 'open') openLogDetail(row)
  else if (command === 'remove') void deleteRunLog(row)
}

/** 删除只改变日志可见性；同时隔离刷新请求，避免删除后的列表被旧响应覆盖。 */
async function deleteRunLog(row: SyncRun) {
  if (!canDeleteLog(row) || !historyTask.value) return
  const taskId = historyTask.value.id, generation = historyGeneration
  try {
    await ElMessageBox.confirm('确定删除这条执行日志吗？仅隐藏日志，任务、同步数据和断点都会保留。',
      '删除执行日志', { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' })
  } catch { return }
  if (!isHistorySession(generation) || historyTask.value?.id !== taskId || deletingLog.value) return
  deletingLog.value = true
  stopHistoryRefresh()
  historySeq++
  logSeq++
  try {
    const result = await syncTasksApi.deleteRunLog(taskId, row.ID)
    if (!isHistorySession(generation) || historyTask.value?.id !== taskId) return
    if (logDetailSnapshot.value?.ID === row.ID) closeLogDetail()
    const remaining = Math.max(0, logTotal.value - result.deleted)
    logPage.value = Math.min(logPage.value, Math.max(1, Math.ceil(remaining / logSize.value)))
    ElMessage.success(result.deleted ? '执行日志已删除' : '该执行日志已删除')
    try { await refreshHistoryDetails(taskId, generation) }
    catch { if (isHistorySession(generation)) ElMessage.warning('日志已删除，列表刷新失败，请稍后刷新') }
  } catch (error: any) {
    if (isHistorySession(generation)) ElMessage.error(error?.message || '删除执行日志失败，请刷新后重试')
  } finally {
    if (isHistorySession(generation)) {
      deletingLog.value = false
      startHistoryRefresh(generation)
    }
  }
}

/**
 * historySeq/logSeq 是两组单调请求序号，分别保护运行列表和执行日志分页。
 * 每次新请求都会递增；响应回来时只有序号仍是最新、抽屉仍打开、任务 id 仍匹配时才允许写入状态。
 * 关闭抽屉和组件卸载也会递增序号，使尚未返回的旧请求自然失效，避免旧任务数据覆盖下一次打开的数据。
 */
let logSeq = 0

const emptyForm = (): SyncTaskInput => ({
  mysqlDateTables: null,
  timeoutMinutes: 0,
  resumable: true,
  stableKey: '',
  batchSize: 10000,
  name: '',
  description: '',
  dataSourceId: undefined as unknown as number,
  sourceObject: '',
  connectionId: 0,
  targetDb: '',
  targetTable: '',
  filePattern: '',
  remotePath: '',
  hasHeader: false,
  ignoreExtraCsvColumns: true,
  csvEncoding: 'auto',
  allowedTargetDefaults: [],
  schema: [],
  mapping: [],
  mode: 'full',
  incrColumn: '',
  orderKey: '',
  partitionExpr: '',
  sortingKeys: [],
  primaryKeys: [],
  enabled: true,
  truncate: true,
  autoCreateTable: true,
  isOds: false,
  arbInclude: false,
  arbPriority: 0,
  arbMdnNorm: '',
  scheduleType: 'manual',
  cronExpression: '',
  phoneColumn: ''
})
const form = ref<SyncTaskInput>(emptyForm())
const MYSQL_DATE_TABLE_TOKEN = '{yyyyMMdd}'
const MYSQL_DATE_TABLE_SUFFIX = `_${MYSQL_DATE_TABLE_TOKEN}`
/** 输入表名前缀；日期后缀由计算属性自动补全。 */
const mysqlDateTablePrefix = computed({
  get: () => {
    const pattern = form.value.mysqlDateTables?.tablePattern?.trim() ?? ''
    if (!pattern) return ''
    const tokenAt = pattern.indexOf(MYSQL_DATE_TABLE_TOKEN)
    const prefix = tokenAt >= 0 ? pattern.slice(0, tokenAt) : pattern
    return prefix.replace(/_+$/, '')
  },
  set: (value: string) => {
    if (!form.value.mysqlDateTables) return
    const tokenAt = value.indexOf(MYSQL_DATE_TABLE_TOKEN)
    const withoutToken = tokenAt >= 0 ? value.slice(0, tokenAt) : value
    const prefix = withoutToken.replace(/_+$/, '').trim()
    form.value.mysqlDateTables.tablePattern = prefix ? `${prefix}${MYSQL_DATE_TABLE_SUFFIX}` : ''
  }
})
const dateTablePreview = ref<Awaited<ReturnType<typeof syncTasksApi.previewMysqlDateTables>> | null>(null)
/** 新表策略不影响目录和列结构，切换策略不应丢弃正在返回的预览结果。 */
function dateTablePreviewKey() {
  const rule = form.value.mysqlDateTables
  if (!rule) return ''
  const { newTablePolicy: _policy, ...discovery } = rule
  return JSON.stringify(discovery)
}

/** 切换源表方式清除旧结构；仅修改日期规则时保留人工映射，待预览重新校验。 */
function invalidateDateTablePreview() {
  sourceColsSeq++
  sourceColsLoading.value = false
  dateTablePreview.value = null
  form.value.sourceObject = ''
  form.value.schema = []
  sourceCols.value = []
  sourceColsError.value = ''
}
function toggleMysqlDateTables(enabled: string | number | boolean | undefined) {
  form.value.mysqlDateTables = enabled === true ? {
    tablePattern: '', startDate: '', endDate: '',
    dateFormat: 'yyyyMMdd', timeZone: 'Asia/Shanghai', newTablePolicy: 'full'
  } : null
  invalidateDateTablePreview()
  resetSourceFields()
  sourceColsError.value = ''
  if (!form.value.mysqlDateTables) {
    form.value.sourceObject = srcDefaultTable.value
    void loadSourceCols(form.value.dataSourceId)
  }
}
/** 显示条件统一在这里计算，避免同一策略在模板、切换事件和提交校验中使用不同规则。 */
const sourceUi = computed(() => {
  const type = curSrcType.value
  const selected = !!type && !!form.value.dataSourceId
  const incremental = selected && !['sftp', 'hive', 'kafka'].includes(type)
  const dateTables = selected && type === 'mysql' && !!form.value.mysqlDateTables
  return {
    selected,
    dateTables,
    incremental,
    incrementalKey: incremental && form.value.mode === 'incremental',
    // 普通 JDBC 全量追加和 CK 增量走快照，不展示不会参与读取的分段键。
    stableKey: dateTables || selected && ['mysql', 'pgsql', 'clickhouse'].includes(type)
      && (form.value.mode === 'full' ? !!form.value.truncate : type !== 'clickhouse'),
    truncate: selected && type !== 'sftp' && form.value.mode === 'full' && targetExisting.value,
    fixedMode: type === 'sftp' ? '按文件增量' : '全量',
    objectLabel: type === 'kafka' ? '源 Topic' : type === 'mongodb' ? '源集合' : '源表'
  }
})
/** 只在用户更换来源/源表时清理，编辑回显和全量/增量切换保留原配置，避免改变断点指纹。 */
function resetSourceFields() {
  sourceCols.value = []
  sourceColumnComments.value = {}
  sourceColsError.value = ''
  mappingServerError.value = ''
  form.value.schema = []
  form.value.mapping = []
  form.value.stableKey = ''
  form.value.incrColumn = ''
  form.value.phoneColumn = ''
  lockedPhone.value = ''
  if (!targetExisting.value) {
    form.value.orderKey = ''
    form.value.sortingKeys = []
    form.value.primaryKeys = []
    form.value.partitionExpr = ''
  }
}
const stableKeyOptions = computed(() => (form.value.schema ?? []).filter(c => /^U?Int(8|16|32|64|128|256)$/.test(c.type) && c.name !== form.value.phoneColumn))
/**
 * stableKey 当前契约。
 * 候选来自源 schema 中的整数列，并排除已标记为手机号的源列；切换候选时，只有在同一源列映射行存在时，
 * 才同步该映射的目标类型并设为非空。清空 stableKey 表示不指定分段键，界面按“先保存源快照”的路径处理。
 * 分段键下拉目前仅对 MySQL、PgSQL、ClickHouse 展示；保存阶段前端只校验批次行数，未强制 stableKey 必填，
 * 源列类型、目标映射及具体来源是否可续传仍以后端校验为准。
 */
function applyStableKeyMapping() {
  const col = stableKeyOptions.value.find(c => c.name === form.value.stableKey)
  const mapping = form.value.mapping.find(m => m.sourceColumn === form.value.stableKey)
  if (col && mapping) { mapping.type = col.type; mapping.nullable = false }
}
/** 只响应用户切换，不使用 watch 改写编辑回显中的旧策略或旧水位。 */
const dateTableFullToIncremental = computed(() => !!form.value.mysqlDateTables
  && curSrcType.value === 'mysql' && form.value.dataSourceId === editing.value?.dataSourceId
  && !!editing.value?.mysqlDateTables && editing.value.mode === 'full' && form.value.mode === 'incremental')
// 不依赖当前已修改的源配置；换源后再选增量也不能绕过继承限制。
const dateBaselineLocked = computed(() => !!editing.value?.mysqlDateTables && editing.value.mode === 'full'
  && !!editing.value.dateTableBaselineReady && form.value.mode === 'incremental')
const baselineFields = [
  ['dataSourceId', '数据源'], ['sourceObject', '参考表'], ['connectionId', '目标连接'], ['targetDb', '目标库'],
  ['targetTable', '目标表'], ['schema', '参考结构'], ['mapping', '字段映射'], ['stableKey', '分段键'],
  ['batchSize', '批大小'], ['phoneColumn', '手机号列'], ['isOds', '目标类型'], ['orderKey', '排序键'],
  ['sortingKeys', '排序键'], ['primaryKeys', '主键'], ['partitionExpr', '分区表达式']
] as const
/** 与编辑回显使用相同默认值；历史 null 与空数组、空字符串不应被误判为修改。 */
function baselineValue(task: SyncTask | SyncTaskInput, key: typeof baselineFields[number][0]): unknown {
  if (key === 'batchSize') return task[key] ?? 10000
  if (key === 'isOds') return task[key] ?? false
  if (key === 'schema') return (task.schema ?? []).map(({ name, type }) => ({ name, type }))
  if (key === 'mapping' || key === 'sortingKeys' || key === 'primaryKeys') return task[key] ?? []
  return task[key] ?? ''
}
const dateBaselineDifferences = computed(() => {
  if (!dateBaselineLocked.value || !editing.value) return []
  const old = editing.value
  const changed: string[] = baselineFields.filter(([key]) => JSON.stringify(baselineValue(form.value, key)) !== JSON.stringify(baselineValue(old, key))).map(([, label]) => label)
  const rule = form.value.mysqlDateTables, previous = old.mysqlDateTables!
  if (!rule || ['tablePattern', 'startDate', 'dateFormat', 'timeZone'].some(key =>
    (rule[key as keyof typeof rule] ?? '') !== (previous[key as keyof typeof previous] ?? ''))) changed.push('日期分表规则')
  if (form.value.incrColumn !== old.stableKey) changed.push('增量键')
  return Array.from(new Set(changed))
})

async function restoreDateBaseline() {
  const old = editing.value
  if (!old || !dateBaselineLocked.value || sourceColsLoading.value) return
  const session = editorSessionSeq
  const policy = form.value.mysqlDateTables?.newTablePolicy ?? 'full'
  const restored = Object.fromEntries(baselineFields.map(([key]) => [key, baselineValue(old, key)]))
  Object.assign(form.value, JSON.parse(JSON.stringify(restored)))
  form.value.mysqlDateTables = { ...old.mysqlDateTables!, endDate: '', newTablePolicy: policy }
  form.value.incrColumn = old.stableKey ?? ''
  const ds = localDataSources.value.find(d => d.id === old.dataSourceId)
  dsGroupVal.value = ds?.groupId ? String(ds.groupId) : 'default'
  await loadSourceCols(old.dataSourceId, true, true)
  if (!editorCurrent(session)) return
  await onConnChange(old.connectionId, true)
  if (!editorCurrent(session)) return
  form.value.targetDb = old.targetDb
  await loadTables(old.targetDb)
  if (!editorCurrent(session)) return
  form.value.targetTable = old.targetTable
  onTargetResolve()
}
const dateTablePolicyHint = computed(() => {
  if (form.value.mysqlDateTables?.newTablePolicy === 'full')
    return '新表从头导入，已同步表从各自断点继续读取新增行。'
  const temporal = /date|time/i.test(sourceColumnType(form.value.incrColumn))
  const start = temporal ? '上次扫描开始时间' : '发现时的最大值'
  return `新表从${start}之后读取，不补历史；首次运行只接后续数据，早于上次扫描日期的新表会跳过。`
})
const dateTableModeHint = computed(() => {
  if (form.value.mode === 'full') return form.value.truncate
    ? '每次全量执行先清空目标一次，再合并所有匹配表。持续采集可选择增量，并将新表设为完整导入。'
    : '每次全量执行都会追加所有匹配表的数据，重复执行会重复写入。'
  if (dateTableFullToIncremental.value) {
    if (editing.value?.checkpointPending) return '全量尚未完成，请先按原配置继续传输，成功后再切换增量。'
    if (editing.value?.dateTableBaselineReady) return '将继承每张表已确认的主键断点，结束日期清空后持续发现后续日期表；开始日期、来源、映射、批大小和目标配置保持不变。'
    return '尚无可继承的完整逐表断点；未运行过的任务可从空目标开始增量。'
  }
  return '每次执行重新发现匹配表，各表独立保存断点；定时执行使用相同规则。已同步行的修改和删除不会自动同步。'
})
function onSyncModeChange() {
  if (sourceUi.value.incrementalKey && !form.value.incrColumn) {
    form.value.incrColumn = srcIncrOptions.value.includes(form.value.stableKey ?? '')
      ? form.value.stableKey! : srcIncrOptions.value[0] ?? ''
  }
  if (!form.value.mysqlDateTables || form.value.mode !== 'incremental') return
  // 全量常使用固定日期区间；改为增量后默认持续发现后续日期表。
  // 列结构和字段映射不变，只作废旧的表清单，后续保存/预览会重新发现范围。
  if (form.value.mysqlDateTables.endDate) {
    form.value.mysqlDateTables.endDate = ''
    dateTablePreview.value = null
    sourceColsSeq++
    sourceColsLoading.value = false
  }
  if (dateTableFullToIncremental.value && editing.value?.dateTableBaselineReady) {
    form.value.stableKey = editing.value.stableKey ?? ''
    form.value.incrColumn = form.value.stableKey
    // 全量时新表策略并未生效；主动切换后默认完整导入新表，避免跳过新表已有数据。
    form.value.mysqlDateTables.newTablePolicy = 'full'
    // 已完成全量的映射已经验证可用，切换模式不能顺带重写其类型或可空设置。
    return
  } else if (!form.value.incrColumn && form.value.stableKey) {
    form.value.incrColumn = form.value.stableKey
  }
  applyStableKeyMapping()
}
function onStableKeyChange() {
  // 新建日期分表默认以共同主键推进水位；编辑已有增量时保留用户选定的时间键。
  if (form.value.mysqlDateTables && !editing.value) form.value.incrColumn = form.value.stableKey ?? ''
  applyStableKeyMapping()
}
watch(() => [curSrcType.value, form.value.mode, form.value.truncate], () => {
  form.value.resumable = true
})

/** 手机号字典:目标表里手机号列使用的字段名(默认 mdn,可在字典页配置) */
const dimPhoneField = ref('mdn')
/** 当前数据源是否在“上传解析”已锁定手机号列(CSV):映射里该手机号列固定、不可改 */
const lockedPhone = ref('')
const isPhoneLocked = computed(() => !!lockedPhone.value)
/** 加载字典里的手机号字段名;失败则保持默认 mdn */
async function loadDimPhone() {
  try {
    const g = await settingsApi.get()
    if (g?.dimPhoneField) dimPhoneField.value = g.dimPhoneField
  } catch {
    /* 保留默认 mdn */
}
}


/** 可视化 cron 生成器状态(仅 UI,不入库;生成结果填入 form.cronExpression) */
const WEEKDAY_LABELS: Record<number, string> = {
  1: '周一',
  2: '周二',
  3: '周三',
  4: '周四',
  5: '周五',
  6: '周六',
  0: '周日'
}

const cronRule = reactive({
  unit: 'day' as 'minute' | 'hour' | 'day' | 'week' | 'month',
  every: 5, // 间隔 N(分钟/小时用)
  time: '02:00', // 每天/每周/每月的时刻
  weekdays: [] as number[],
  day: 1
})
const cronAdvanced = ref(false)
/** 分钟/小时间隔的约束 */
const everyUnit = computed(() => cronRule.unit)
const everyMax = computed(() => (cronRule.unit === 'minute' ? 59 : 23))
/** 由单位+间隔/时刻组合出的 cron 表达式预览 */
const cronGenExpr = computed(() => {
  const r = cronRule
  const pad = (s: string) => String(Number(s)).padStart(2, '0')
  // 数值再夹一次:el-input-number 的 min/max 只约束控件,边界值仍可能从外面带进来;
  // 间隔为 1 时特意写成 `* * * * *` / `0 * * * *` 而不是 `*/1`,好让 prefillCronRule 能原样还原
  if (r.unit === 'minute') {
    const n = Math.max(1, Math.min(59, Math.floor(r.every)))
    return n === 1 ? '* * * * *' : `*/${n} * * * *`
}

  if (r.unit === 'hour') {
    const n = Math.max(1, Math.min(23, Math.floor(r.every)))
    return n === 1 ? '0 * * * *' : `0 */${n} * * *`
}

  if (r.unit === 'day') {
    if (!r.time) return ''
    const [h, m] = r.time.split(':')
    return `${m} ${h} * * *`
}

  if (r.unit === 'week') {
    if (!r.time || !r.weekdays.length) return ''
    const [h, m] = r.time.split(':')
    const days = [...r.weekdays].sort().join(',')
    return `${m} ${h} * * ${days}`
}

  if (r.unit === 'month') {
    if (!r.time) return ''
    const [h, m] = r.time.split(':')
    return `${m} ${h} ${r.day} * *`
}

  return ''
})
/** 把生成表达式填入 form.cronExpression(用户可再手改) */
function applyCronGen() {
  if (!cronGenExpr.value) return
  form.value.cronExpression = cronGenExpr.value
  cronAdvanced.value = true
}


/** 重置可视化生成器为默认 */
function resetCronRule() {
  cronRule.unit = 'day'
  cronRule.every = 5
  cronRule.time = '02:00'
  cronRule.weekdays = []
  cronRule.day = 1
  cronAdvanced.value = false
}


/** 编辑回显:尝试从已存 cron 表达式还原生成器状态(还原不了就保持默认,用户仍可手改) */
function prefillCronRule(expr: string) {
  resetCronRule()
  const e = expr.trim()
  const p = e.split(/\s+/)
  if (p.length !== 5) return
  const [min, hour, dom, mon, dow] = p
  const st = (v: string) => (v.length === 1 ? '0' + v : v)
  // 每 N 分钟: */N * * * *
  const mMatch = min.match(/^\*\/(\d{1,2})$/) // */N or *
  const hMatch = hour.match(/^\*\/(\d{1,2})$/) // hour */N
  if (min === '*' && hour === '*' && dom === '*' && mon === '*' && dow === '*') {
    cronRule.unit = 'minute'
    cronRule.every = 1
  } else if (mMatch && hour === '*' && dom === '*' && mon === '*' && dow === '*') {
    cronRule.unit = 'minute'
    cronRule.every = Number(mMatch[1])
  } else if (/^\d{1,2}$/.test(min) && hMatch && dom === '*' && mon === '*' && dow === '*') {
    cronRule.unit = 'hour'
    cronRule.every = Number(hMatch[1])
}

  // 注意:形如 `30 * * * *`(固定分钟、每小时)无法用本生成器表达,
  // 不强行映射为整点每小时(否则再点“填入”会被改成 0 * * * *),保留默认即可。
  // 时刻
  if (/^\d{1,2}$/.test(hour) && /^\d{1,2}$/.test(min)) {
    cronRule.time = `${st(hour)}:${st(min)}`
}

  // 第二段按时/日/周的位置反推单位:只在「时、分都是具体数字」或「有 dow/dom」时命中,
  // 因此不会把上面刚判定的 每N分钟 / 每N小时 覆盖掉
  if (dom === '*' && mon === '*' && dow === '*' && /^\d{1,2}$/.test(hour)) {
    cronRule.unit = 'day'
  } else if (dom === '*' && mon === '*' && dow !== '*') {
    const days = dow
      .split(',')
      .map((s) => Number(s))
      .filter((n) => n >= 0 && n <= 6)
    if (days.length) {
      cronRule.unit = 'week'
      cronRule.weekdays = days
}

  } else if (dow === '*' && mon === '*') {
    const d = Number(dom)
    if (d >= 1 && d <= 31) {
      cronRule.unit = 'month'
      cronRule.day = d
}
  }
}


const rules: FormRules = {
  name: [{ required: true, message: '请输入任务名称', trigger: 'blur' }],
  dataSourceId: [{ required: true, message: '请选择数据源', trigger: 'change' }],
  connectionId: [{ required: true, message: '请选择目标连接', trigger: 'change' }],
  targetDb: [{ required: true, message: '请输入目标数据库', trigger: 'blur' }],
  targetTable: [{ required: true, message: '请输入目标表名', trigger: 'blur' }],
  mode: [{ required: true, message: '请选择同步策略', trigger: 'change' }]
}


/**
 * 刷新任务列表。
 *
 * silent=true 用于运行结束后的背景刷新，不打断用户阅读当前表格。
 * ⚠️ 加 seq 守卫:与「刷新」按钮/删除后重拉并发时,旧响应不能覆盖新数据。
 */
let listSeq = 0
let loadedListKey = ''
/** 写操作后的列表回拉；HTTP 拦截器已展示错误，这里吞掉拒绝避免未处理 Promise。 */
function refreshListAfterMutation(): Promise<void> {
  return load().catch(() => { /* HTTP 拦截器已提示 */ })
}


async function load(silent = false) {
  const my = ++listSeq
  const version = progressVersion
  const key = listQueryKey()
  if (!silent) loading.value = true
  try {
    const result = await syncTasksApi.page({ page: page.value, size: pageSize.value, keyword: query.keyword.value }, silent)
    if (pollsDisposed || my !== listSeq || key !== listQueryKey()) return // 切页/改条件后的旧响应也不能回写
    const rows = result.rows
    if (page.value !== result.page || pageSize.value !== result.size) {
      page.value = result.page
      pageSize.value = result.size
      appliedPageCorrection = listQueryKey()
    }
    listTotal.value = result.total
    if (loadedListKey !== listQueryKey()) {
      selectedTasks.value = []
      taskTableRef.value?.clearSelection()
    }
    loadedListKey = listQueryKey()
    // 列表请求发出后可能已有更新的进度响应，慢列表不能把同一次运行的进度倒退。
    const current = new Map(list.value.map(row => [row.id, row]))
    list.value = rows.map(row => {
      const previous = current.get(row.id)?.lastRun
      if (previous && (((previous.id ?? 0) > (row.lastRun?.id ?? 0) && row.lastRun?.status !== 'running')
        || ((previous.id ?? 0) === (row.lastRun?.id ?? 0)
          && ((progressVersions.get(row.id) ?? 0) > version
            || (['success', 'failed'].includes(previous.status) && row.lastRun?.status === 'running')))))
        return { ...row, lastRun: previous }
      return row
    })
    // 首次进入/F5/手动刷新后恢复观察。后台轮询不重新启动已因错误停止的定时器。
    if (!silent) list.value.filter(row => runningNow(row) || row.lastRun?.status === 'queued').forEach(row => startPolling(row.id))
    if (historyTask.value) historyTask.value = rows.find((row) => row.id === historyTask.value?.id) ?? historyTask.value
    // 翻页后停止无关观察，本页主动启动的运行仍追踪至结束，保留完成提醒。
    pruneOffPageObservers()
  } finally {
    if (my === listSeq) loading.value = false
}
}


/**
 * 仅在运行结束时刷新配置/断点；同时结束的多个任务共享请求。
 * 运行中的每轮查询只取精确 runId，不再刷新整份任务列表。
 */
let listInFlight: Promise<void> | null = null
let listInFlightKey = ''
function loadShared(): Promise<void> {
  const key = listQueryKey()
  if (listInFlight && listInFlightKey === key) return listInFlight
  const p = load(true).finally(() => {
    if (listInFlight === p) { listInFlight = null; listInFlightKey = '' }
  })
  listInFlight = p
  listInFlightKey = key
  return p
}


async function openCreate() {
  const session = invalidateEditorSession()
  // 新增入口可能早于页面初始化完成；先等默认目标连接，避免把 connectionId 留为 0。
  try { await loadConnections() } catch { /* 连接暂不可用时仍允许填写源配置，保存时校验目标连接 */ }
  if (!editorCurrent(session)) return
  editing.value = null
  form.value = emptyForm()
  targetDefaultsScope.value = ''
  sourceColumnComments.value = {}
  loadDimPhone() // 加载字典里手机号字段名(默认 mdn)
  tblCfgOpen.value = ['cfg'] // 建表设置默认展开
  resetCronRule()
  // 复位上一向导残留的附属状态,避免取消后再新增时闪现旧的 SFTP 规则/结构区
  dsGroupVal.value = 'default'
  curSrcType.value = ''
  sourceCols.value = []
  sourceColsSeq++
  sourceColsLoading.value = false
  sourceColsError.value = ''
  databases.value = []
  sftpDetecting.value = false
  tables.value = []
  sftpGroups.value = []
  sftpSelIdx.value = -1
  sftpPage.value = 1
  targetCols.value = []
  targetTableExists.value = false
  step.value = 0
  dialogVisible.value = true
  // 默认连接 = 目标数据源(默认连接或第一个连接),贴源表固定落 ods_data
  const def = connections.value.find((c) => c.isDefault) ?? connections.value[0]
  if (def) {
    form.value.connectionId = def.id
    form.value.targetDb = odsDbName
    form.value.isOds = true
    await onConnChange(def.id) // 会清空 targetDb/表,随后重设贴源目标
    if (!editorCurrent(session)) return
    form.value.targetDb = odsDbName
    await loadTables(odsDbName)
}
  return session
}


/** 从“数据源→去同步”跳转(?prefillDataSource=id):预选数据源分组/数据源并打开新增向导 */
async function prefillDataSourceFromQuery() {
  const raw = route.query?.prefillDataSource
  if (!raw) return
  const dsId = Number(raw)
  const ds = localDataSources.value.find((d) => d.id === dsId)
  if (!ds) return
  const session = await openCreate()
  if (session === undefined || !editorCurrent(session) || !dialogVisible.value || editing.value || form.value.dataSourceId) return
  // 定位数据源所在分组,让下拉能显示名称
  dsGroupVal.value = ds.groupId ? String(ds.groupId) : 'default'
  if (ds.groupId && !dsGroups.value.some((g) => g.id === ds.groupId)) {
    dsGroupVal.value = 'default' // 分组未加载/不存在则回退默认}
  }

  // 打新增向导并预填数据源
  form.value.dataSourceId = dsId
  await onDataSourceChange(dsId)
}


async function openEdit(row: SyncTask) {
  const session = invalidateEditorSession()
  editing.value = row
  sourceColumnComments.value = Object.fromEntries((row.mapping ?? []).map(m => [m.sourceColumn, m.comment ?? '']))
  form.value = {
    mysqlDateTables: row.mysqlDateTables
      ? { ...row.mysqlDateTables, newTablePolicy: row.mysqlDateTables.newTablePolicy ?? '' }
      : null,
    timeoutMinutes: row.timeoutMinutes ?? 0,
    resumable: true,
    stableKey: row.stableKey ?? '',
    batchSize: row.batchSize ?? 10000,
    name: row.name,
    description: row.description,
    dataSourceId: row.dataSourceId,
    sourceObject: row.sourceObject,
    connectionId: row.connectionId,
    targetDb: row.targetDb,
    targetTable: row.targetTable,
    filePattern: row.filePattern ?? '',
    remotePath: row.remotePath ?? '',
    hasHeader: row.hasHeader ?? true,
    ignoreExtraCsvColumns: row.ignoreExtraCsvColumns ?? true,
    csvEncoding: row.csvEncoding ?? 'auto',
    allowedTargetDefaults: [...(row.allowedTargetDefaults ?? [])],
    schema: (row.schema ?? []).map((c) => ({ ...c })),
    mapping: (row.mapping ?? []).map((m) => ({ ...m })),
    mode: row.mode,
    incrColumn: row.incrColumn,
    orderKey: row.orderKey,
    partitionExpr: row.partitionExpr ?? '',
    sortingKeys: row.sortingKeys ?? [],
    primaryKeys: row.primaryKeys ?? [],
    enabled: row.enabled,
    truncate: row.truncate,
    autoCreateTable: row.autoCreateTable ?? true,
    isOds: row.isOds ?? false,
    arbInclude: row.arbInclude ?? false,
    arbPriority: row.arbPriority ?? 0,
    arbMdnNorm: row.arbMdnNorm ?? '',
    scheduleType: row.scheduleType ?? 'manual',
    cronExpression: row.cronExpression ?? '',
    phoneColumn: row.phoneColumn ?? ''
}

  targetDefaultsScope.value = targetDefaultsKey()
  prefillCronRule(form.value.cronExpression || '')
  // 定位该数据源所在分组,让下拉能显示名称而非裸 ID(编辑回显)
  const editDs = localDataSources.value.find((d) => d.id === row.dataSourceId)
  dsGroupVal.value = editDs && editDs.groupId ? String(editDs.groupId) : 'default'
  step.value = 0
  dialogVisible.value = true
  await loadSourceCols(row.dataSourceId, true)
  if (!editorCurrent(session)) return
  // 编辑保留原目标库和 ODS 模式，新建默认值不能覆盖已保存的配置。
  if (row.connectionId) {
    form.value.connectionId = row.connectionId
    await onConnChange(row.connectionId, true) // 编辑回显:不清空已保存映射
    if (!editorCurrent(session)) return
    form.value.targetDb = row.targetDb
    form.value.isOds = row.isOds ?? false
    await loadTables(row.targetDb)
    if (!editorCurrent(session)) return
    form.value.targetTable = row.targetTable
    if (row.targetTable) onTargetResolve()
}
}


function validateDateTableTransition(): boolean {
  if (dateBaselineDifferences.value.length) {
    ElMessage.warning(`继承全量断点前请恢复：${dateBaselineDifferences.value.join('、')}`)
    return false
  }
  if (!dateTableFullToIncremental.value || !editing.value?.checkpointPending) return true
  ElMessage.warning('全量尚未完成，请先按原配置继续传输，成功后再切换增量')
  return false
}

/** 下一步:分步校验 */
async function nextStep() {
  if (nextStepBusy.value || step.value !== 0 || sftpDetecting.value) return
  const session = editorSessionSeq
  nextStepBusy.value = true
  try { await validateNextStep(session) }
  finally { if (editorCurrent(session)) nextStepBusy.value = false }
}

async function validateNextStep(session: number) {
  if (!validateDateTableTransition()) return
  if (step.value === 0) {
    if (!form.value.name.trim()) {
      ElMessage.warning('请输入任务名称')
      return
}

    if (!form.value.dataSourceId) {
      ElMessage.warning('请选择数据源')
      return
}

    // SQL 关系源:需确定源表(任务所选或数据源自带);否则无从取列
    if (isObjectSource(curSrcType.value)) {
      if (form.value.mysqlDateTables && (!form.value.sourceObject || sourceColsError.value)) {
        await loadSourceCols(form.value.dataSourceId, true)
        if (!editorCurrent(session)) return
      }
      const hasTable = !!String(form.value.sourceObject ?? '').trim()
      if (!hasTable) {
        ElMessage.warning(curSrcType.value === 'mongodb' ? '请选择源集合' : '请选择源表')
        return
}

      if (sourceColsLoading.value || !form.value.schema?.length || !form.value.mapping.length || sourceColsError.value) {
        await loadSourceCols(form.value.dataSourceId, true)
        if (!editorCurrent(session)) return
}

      if (sourceColsLoading.value || sourceColsError.value || !form.value.schema?.length || !form.value.mapping.length) {
        ElMessage.warning(sourceColsError.value || '源字段仍在读取，请稍后重试')
        return
}
    }

    if (curSrcType.value === 'sftp') {
      if (!form.value.remotePath) {
        ElMessage.warning('请先选择远端目录')
        return
}

      if (!form.value.schema || !form.value.schema.length) {
        ElMessage.warning('请先检测并选择要同步的结构类')
        return
}
    }

  }

  // 目标表名已整合到配置步骤：进入字段映射前统一校验目标表并自动补同名目标列。
  if (!form.value.connectionId) {
    ElMessage.warning('请选择目标连接')
    return
  }
  // 贴源:库固定 ods_data,只校验表名
  if (form.value.targetDb !== odsDbName) form.value.targetDb = odsDbName
  if (!form.value.targetTable.trim()) {
    ElMessage.warning('请选择或输入目标贴源表')
    return
  }
  ensureAutoTargets()

  // 选表与全量/增量联动：增量必须有可用增量键；日期分表必须有共同分段键；批大小合法。
  if (form.value.mode === 'incremental' && !srcIncrOptions.value.includes(form.value.incrColumn)) {
    ElMessage.warning('请选择已映射的数值、日期或时间戳列作为增量键')
    return
  }
  if (form.value.mysqlDateTables && (!form.value.stableKey || sourceColsError.value || !form.value.schema?.length)) {
    ElMessage.warning('请先预览日期分表结构，并选择各表共同的整数单列主键作为分段键')
    return
  }
  if (form.value.resumable) {
    const batch = Number(form.value.batchSize ?? 0)
    if (!Number.isInteger(batch) || batch < 100 || batch > 10000) {
      ElMessage.warning('断点续传的每批行数需为 100–10000')
      return
    }
  }

  // 双击或键盘重复激活也只能进入第 2 步，不能累加到不存在的步骤。
  step.value = 1
}


async function goToStep(target: number) {
  const session = editorSessionSeq
  if (target === step.value || target < 0 || target > 1 || sftpDetecting.value || nextStepBusy.value) return
  if (target < step.value) {
    step.value = target
    return
}

  while (step.value < target) {
    const before = step.value
    await nextStep()
    if (!editorCurrent(session)) return
    if (step.value === before) return
}
}


/** 校验定时 cron 表达式(简单 5 段:分 时 日 月 周);非法时弹提示并返回 false。
 *  末步是“保存”(不走 nextStep),故由 onSave 调用此函数完成同口径校验。 */
function validateCron(): boolean {
  if (form.value.scheduleType !== 'scheduled') return true
  const exp = form.value.cronExpression.trim()
  if (!exp) {
    ElMessage.warning('定时执行需填写 Cron 表达式')
    return false
}

  const parts = exp.split(/\s+/)
  if (parts.length !== 5 || parts.some((p) => !/^[0-9*,/\-?]+$/.test(p))) {
    ElMessage.warning('Cron 表达式无效(格式:分 时 日 月 周)')
    return false
}

  // 常见区间校验
  const r = (v: string, lo: number, hi: number) => {
    const m = v.match(/^(\*|(\d+)(?:-(\d+))?)$/)
    return m ? v === '*' || (Number(m[2]) >= lo && Number(m[2]) <= hi && (!m[3] || Number(m[3]) <= hi)) : true
}

  if (!r(parts[0], 0, 59) || !r(parts[1], 0, 23) || !r(parts[2], 1, 31) || !r(parts[3], 1, 12) || !r(parts[4], 0, 6)) {
    ElMessage.warning('Cron 表达式数值超出范围')
    return false
}

  return true
}


/** 选择目标连接后加载分组与库 */
async function onConnChange(id: number, preserveCols = false) {
  const session = editorSessionSeq
  form.value.targetDb = ''
  form.value.targetTable = ''
  tables.value = []
  groupOpts.value = []
  selGroupVal.value = ''
  allConnDbs.value = []
  allGroupDbs.value = []
  await loadConnGroups(id)
  if (!editorCurrent(session) || id !== form.value.connectionId) return
  // CK 表源:连接已定,重新读取源表列以生成映射(源=该连接内某库表)。
  // 编辑回显(preserveCols=true)时不要重建,避免清空已保存的映射。
  if (curSrcType.value === 'ck_table' && form.value.dataSourceId && form.value.connectionId) {
    await loadSourceCols(form.value.dataSourceId, preserveCols)
}
}


/** 分组选项:真实分组 + 默认分组(未分组的库) */
const groupOpts = ref<{ value: string; label: string }[]>([])
const selGroupVal = ref('')
/** 当前连接全部库名 */
const allConnDbs = ref<string[]>([])
/** 当前连接各库备注(name → remark,下拉带注释用) */
const dbRemark = ref<Record<string, string>>({})
/** 当前连接的分组(含成员) */
const allGroupDbs = ref<any[]>([])

async function loadConnGroups(id: number) {
  const session = editorSessionSeq
  const request = ++connGroupsSeq
  const current = () => editorCurrent(session) && request === connGroupsSeq && id === form.value.connectionId
  if (!id) {
    groupOpts.value = []
    return
}

  try {
    const [groups, dbs] = await Promise.all([
      workspaceApi
        .groups(id)
        .then((r: any) => r ?? [])
        .catch(() => []),
      workspaceApi.databases(id).catch(() => [])
    ])
    if (!current()) return
    const gs: any[] = groups ?? []
    allConnDbs.value = (dbs ?? []).map((d: any) => d.name)
    dbRemark.value = {}
    for (const d of dbs ?? []) {
      if (d?.name && d?.remark) dbRemark.value[d.name] = d.remark
}

    // 方案A:分组库与真实库求交集 — 已删掉的库不再出现在分组下拉里。
    // 分组白名单存于本地(group_dbs),删真实库不会自动清白名单,故展示层实时过滤。
    const real = new Set(allConnDbs.value)
    const clean = gs.map((g: any) => ({ ...g, dbs: (g.dbs ?? []).filter((n: string) => real.has(n)) }))
    allGroupDbs.value = clean
    // 分组选项
    const opts: { value: string; label: string }[] = clean.map((g: any) => ({
      value: `g:${g.id}`,
      label: g.name
    }))
    const ungrouped = allConnDbs.value.filter((name) => !clean.some((g: any) => (g.dbs ?? []).includes(name)))
    opts.push({ value: 'ungrouped', label: `默认分组 (${ungrouped.length})` })
    groupOpts.value = opts
    if (opts.length) {
      selGroupVal.value = opts[0].value
      applyGroupFilter()
    } else {
      databases.value = []
}

  } catch {
    if (!current()) return
    groupOpts.value = []
}
}


/** 选中分组后:可见库 = 该分组下库 */

function applyGroupFilter() {
  databases.value = visibleDbsFor(selGroupVal.value)
  // 分组定了才谈得上选库:这里重算可见库并清掉已选库/表 —— 否则 targetDb 可能停在上一组(或别的连接)的库上
  form.value.targetDb = ''
  form.value.targetTable = ''
  tables.value = []
}


function visibleDbsFor(v: string): DatabaseInfo[] {
  const mk = (name: string) => ({ name, remark: dbRemark.value[name] ?? '' })
  if (v.startsWith('g:')) {
    const g = allGroupDbs.value.find((x: any) => `g:${x.id}` === v)
    return (g?.dbs ?? []).map(mk)
}

  // 默认分组:属于连接但不属于任何分组的库
  const grouped = new Set(allGroupDbs.value.flatMap((g: any) => g.dbs ?? []))
  return allConnDbs.value.filter((n) => !grouped.has(n)).map(mk)
}


/** 目标表下拉文案:表名(引擎)(+备注) */
function tableOptLabel(t: TableInfo): string {
  let s = `${t.name} (${t.engine})`
  if (t.remark && t.remark !== t.name) s += ` · ${t.remark}`
  return s
}


async function loadTables(db: string) {
  const session = editorSessionSeq
  const request = ++tableOptionsSeq
  const connection = form.value.connectionId
  const current = () => editorCurrent(session) && request === tableOptionsSeq
    && connection === form.value.connectionId && db === form.value.targetDb
  if (!db || !form.value.connectionId) {
    tables.value = []
    return
}

  try {
    const result = await workspaceApi.tables(connection, db)
    if (current()) tables.value = result
  } catch {
    if (current()) tables.value = []
}
}


/** 切换数据源分组:清空已选源及源列/结构残留,待用户在改组内重新选择数据源 */
function onDsGroupChange() {
  form.value.dataSourceId = 0
  onDataSourceChange(0)
}


async function onDataSourceChange(id: number) {
  sftpReadSummary.value = ''
  sftpReadErrors.value = []
  form.value.csvEncoding = 'auto'
  sourceColumnComments.value = {}
  sourceColsSeq++
  sourceColsLoading.value = false
  sourceColsError.value = ''
  sftpDetecting.value = false
  sqlTablesLoading.value = false
  form.value.dataSourceId = id
  resetSourceFields()
  form.value.mysqlDateTables = null
  dateTablePreview.value = null
  form.value.stableKey = ''
  form.value.ignoreExtraCsvColumns = localDataSources.value.find((d) => d.id === id)?.type === 'sftp'
  form.value.phoneColumn = ''
  lockedPhone.value = ''
  form.value.mapping = []
  form.value.incrColumn = ''
  form.value.orderKey = ''
  form.value.remotePath = ''
  form.value.filePattern = ''
  form.value.hasHeader = true
  const src = localDataSources.value.find((d) => d.id === id)
  curSrcType.value = src?.type ?? ''
  form.value.sourceObject = isObjectSource(curSrcType.value)
    ? String(src?.config?.[curSrcType.value === 'kafka' ? 'topic' : curSrcType.value === 'mongodb' ? 'collection' : 'table'] ?? '').trim()
    : ''
  sqlTableOptions.value = []
  sftpGroups.value = []
  if (src?.type === 'kafka' || src?.type === 'hive') form.value.mode = 'full'
  sftpSelIdx.value = -1
  if (src?.type === 'sftp') {
    form.value.mode = 'full'
    form.value.incrColumn = ''
    // 新 SFTP 任务:需先选远端目录再检测结构
    form.value.remotePath = ''
    form.value.filePattern = '*.csv'
    form.value.schema = []
    sourceCols.value = []
    form.value.mapping = []
    return
}

  await loadSourceCols(id)
}


/** 从数据源详情拿文件列名(映射后列名优先;header=false 时列名即 names)。
 *  preserve=true(编辑回显)时只刷新 sourceCols/schema,不动用户已保存的 mapping 与 incrColumn。 */
async function loadSourceCols(id: number, preserve = false, refresh = false) {
  if (!preserve) sourceColumnComments.value = {}
  const request = ++sourceColsSeq
  dateTablePreview.value = null
  sourceColsError.value = ''
  if (!id) {
    sourceColsLoading.value = false
    sourceCols.value = []
    if (!preserve) form.value.mapping = []
    return
  }

  sourceColsLoading.value = true
  let sourceObject = String(form.value.sourceObject ?? '').trim()
  const dateRule = dateTablePreviewKey()
  const sourceConnection = form.value.connectionId
  const sourceCsvPolicy = form.value.ignoreExtraCsvColumns !== false
  const sourceCsvEncoding = form.value.csvEncoding ?? 'auto'
  const ckSource = localDataSources.value.find((d) => d.id === id)?.type === 'ck_table'
  const current = () => request === sourceColsSeq && id === form.value.dataSourceId
    && dateRule === dateTablePreviewKey()
    && sourceObject === String(form.value.sourceObject ?? '').trim()
    && (!ckSource || sourceConnection === form.value.connectionId)
    && sourceCsvPolicy === (form.value.ignoreExtraCsvColumns !== false)
    && sourceCsvEncoding === (form.value.csvEncoding ?? 'auto')
  if (!preserve) {
    sourceCols.value = []
    form.value.schema = []
    form.value.mapping = []
    form.value.incrColumn = ''
  }

  try {
    const src = localDataSources.value.find((d) => d.id === id)
    curSrcType.value = src?.type ?? ''
    if (!sourceUi.value.incremental) form.value.mode = 'full'
    // 数据源上传解析已锁定手机号列(CSV):同步任务里该手机号列固定不可改
    const srcPf = (src as any)?.config?.phoneField
    lockedPhone.value = typeof srcPf === 'string' ? srcPf : ''
    let cols: { name: string; type: string; comment?: string }[] = []

    if (src?.type === 'sftp') {
      form.value.mode = 'full'
      form.value.incrColumn = ''
      // 编辑回显(preserve)且已有保存的 schema:直接复用已存列,避免重测整个远端目录(慢);
      // 用户后续改了远端目录/匹配/首行时再点「检测结构」重新分析。
      const saved = form.value.schema ?? []
      if (preserve && saved.length) {
        cols = saved.map((c) => ({ name: c.name, type: c.type }))
        sftpGroups.value = []
        sftpSelIdx.value = -1
      } else {
        // SFTP:按结构分类,默认选第一类(或匹配已存 schema 的那类)作为同步结构
        const det = await dataSourcesApi.sftpPreviewById(id, {
          remotePath: form.value.remotePath || undefined,
          filePattern: form.value.filePattern || undefined,
          hasHeader: form.value.hasHeader !== false,
          ignoreExtraCsvColumns: form.value.ignoreExtraCsvColumns !== false,
          csvEncoding: sourceCsvEncoding
        })
        if (!current()) return
        showSftpReadInfo(det)
        sftpGroups.value = (det.groups ?? [])
          .filter((g) => !g.error && g.columns && g.columns.length)
          .map((g) => ({ name: (g.colNames || []).join(', '), columns: g.columns, fileCount: g.fileCount }))
        let pick = 0
        const want = new Set(saved.map((c) => c.name))
        if (want.size) {
          const mi = sftpGroups.value.findIndex(
            (g) => g.columns.length === want.size && g.columns.every((c) => want.has(c.name))
          )
          if (mi >= 0) pick = mi
        }
        sftpSelIdx.value = pick
        syncSftpPage()
        cols = sftpGroups.value[pick]?.columns ?? det.columns ?? []
      }
    } else if (src?.type === 'ck_table') {
      // 编辑时也读取当前类型，保留用户映射；CK 表源需要已选连接。
      if (sourceConnection && src.config) {
        const ckDb = String(src.config.database ?? '')
        const ckTable = String(src.config.table ?? '')
        if (ckDb && ckTable) cols = await readCkCols(sourceConnection, ckDb, ckTable)
      }
    } else if (src?.type === 'mysql' && form.value.mysqlDateTables) {
      const preview = await syncTasksApi.previewMysqlDateTables({
        dataSourceId: id,
        sourceObject,
        mysqlDateTables: { ...form.value.mysqlDateTables }
      })
      if (!current()) return
      dateTablePreview.value = preview
      sourceObject = preview.referenceTable ?? ''
      form.value.sourceObject = sourceObject
      cols = preview.columns
      if (!sourceObject) throw new Error('当前日期范围尚无匹配表，需先有一张参考表才能配置映射')
      if (!form.value.stableKey && preview.primaryKey
        && cols.some(c => c.name === preview.primaryKey && /^U?Int(8|16|32|64|128|256)$/.test(c.type))) {
        form.value.stableKey = preview.primaryKey
      }
    } else if (src?.type === 'mysql' || src?.type === 'pgsql') {
      // SQL 关系源:源列 = 源表(config.database + 任务所选源表/数据源自带表)的列。
      // 编辑时仍按所选表读取当前列和类型，保留用户映射。
      sqlTableOptions.value = []
      if (id) {
        sqlTablesLoading.value = true
        try {
          const tr = await dataSourcesApi.sqlTables(id, refresh)
          if (!current()) return
          sqlTableOptions.value = (tr?.tables ?? []).sort()
        } catch {
          if (current()) sqlTableOptions.value = []
        } finally {
          if (current()) sqlTablesLoading.value = false
        }
      }
      const table = sourceObject || String(src?.config?.table ?? '').trim()
      if (table) {
        try {
          const r = await dataSourcesApi.sqlColumns(id, table, refresh)
          cols = (r?.columns ?? []).map((c) => ({ name: c.name, type: c.type }))
        } catch {
          cols = []
        }
      }
    } else if (src?.type === 'clickhouse' || src?.type === 'mongodb' || src?.type === 'hive' || src?.type === 'kafka') {
      if (refresh || !sqlTableOptions.value.length) {
        sqlTablesLoading.value = true
        try {
          const objects = await dataSourcesApi.sourceObjects(id, refresh)
          if (!current()) return
          sqlTableOptions.value = objects.map((o) => o.name).sort()
        } catch (error) {
          if (!sourceObject) throw error
          // 已选择具体表时直接读取其字段，不让枚举表列表失败阻断读取。
        } finally {
          if (current()) sqlTablesLoading.value = false
        }
      }
      if (sourceObject) cols = await dataSourcesApi.sourceColumns(id, sourceObject, refresh)
    } else {
      const det = await dataSourcesApi.detail(id)
      cols = fileSourceColumns(det)
    }

    if (!current()) return
    if (cols.length && cols.every((c) => c.name?.trim() && c.type?.trim())) {
      const fileSource = ['csv', 'json', 'xlsx', 'sftp'].includes(src?.type ?? '')
      sourceColumnComments.value = Object.fromEntries(cols.map((c) => [c.name, fileSource
        ? c.comment ?? (src?.type === 'sftp' && form.value.hasHeader !== false ? c.name : '') : '']))
      form.value.schema = cols.map((c) => ({ name: c.name, type: c.type }))
      sourceCols.value = cols.map((c) => c.name)
      const rebuildMapping = !preserve || !form.value.mapping.length
      if (rebuildMapping) {
        form.value.mapping = cols.map((c) => ({
          sourceColumn: c.name,
          targetColumn: '',
          type: inferType(c.type),
          nullable: /nullable\(/i.test(c.type),
          defaultValue: '',
          comment: sourceColumnComments.value[c.name] ?? ''
        }))
        // 默认增量键取第一列:源表首列多为时间/自增列,先替用户选中,不合适可再改
        form.value.incrColumn = !sourceUi.value.incremental ? ''
          : (form.value.mapping.find((m, i) => canBeIncrementalKey(m.type) && !/^(bool|boolean)$/i.test(cols[i].type))?.sourceColumn ?? '')
        // CSV/JSON 数据源已在“上传解析”标了手机号列:自动带出为映射的手机号列 → 目标自动 mdn
        if (lockedPhone.value && form.value.mapping.some((m) => m.sourceColumn === lockedPhone.value)) {
          form.value.phoneColumn = lockedPhone.value
          applyPhoneDefault()
        }
      }
      if (rebuildMapping && form.value.targetTable.trim()) ensureAutoTargets()
      if (rebuildMapping && form.value.mysqlDateTables) {
        if (form.value.stableKey) form.value.incrColumn = form.value.stableKey
        applyStableKeyMapping()
      }
    } else {
      // 一列都没探到:清掉源列;编辑回显时保留已存 mapping(否则用户打开弹窗就发现映射被洗了)
      sourceCols.value = []
      form.value.schema = []
      // 刚选数据源、尚未选表属于正常填表状态，不提前展示读取失败。
      sourceColsError.value = isObjectSource(curSrcType.value) && !sourceObject && !form.value.mysqlDateTables
        ? '' : '未获取到源字段及类型，无法检查映射，请重新读取或检测源结构'
      if (!preserve) form.value.mapping = []
    }
  } catch (error) {
    if (!current()) return
    sourceCols.value = []
    form.value.schema = []
    const reason = error instanceof Error ? error.message : ''
    sourceColsError.value = `读取源字段及类型失败${reason ? '：' + reason : '，请重新读取或检测源结构'}`
    sftpGroups.value = []
    sftpSelIdx.value = -1
  } finally {
    if (request === sourceColsSeq) sourceColsLoading.value = false
  }
}
/** 原参考表失效时显式展示候选及结构差异，确认后才改变表单，不静默覆盖原映射。 */
async function reselectDateReference() {
  if (!form.value.mysqlDateTables || sourceColsLoading.value) return
  const session = editorSessionSeq
  const request = ++sourceColsSeq
  const sourceId = form.value.dataSourceId
  const original = form.value.sourceObject
  const rule = dateTablePreviewKey()
  const current = () => editorCurrent(session) && request === sourceColsSeq
    && sourceId === form.value.dataSourceId && original === form.value.sourceObject && rule === dateTablePreviewKey()
  sourceColsLoading.value = true
  try {
    const preview = await syncTasksApi.previewMysqlDateTables({
      dataSourceId: sourceId, sourceObject: '', mysqlDateTables: { ...form.value.mysqlDateTables }
    })
    if (!current()) return
    if (!preview.referenceTable) { ElMessage.warning('当前规则没有可选的参考表'); return }
    const before = form.value.schema?.length ? form.value.schema : editing.value?.schema ?? []
    const differences = Array.from({ length: Math.max(before.length, preview.columns.length) }, (_, i) => {
      const a = before[i], b = preview.columns[i]
      return a?.name === b?.name && a?.type === b?.type ? ''
        : `第 ${i + 1} 列：${a ? `${a.name} ${a.type}` : '无'} → ${b ? `${b.name} ${b.type}` : '无'}`
    }).filter(Boolean)
    const summary = differences.length ? `${differences.length} 处结构变化：\n${differences.slice(0, 12).join('\n')}${differences.length > 12 ? '\n其余差异请在字段映射中核对' : ''}` : '字段名称、顺序和类型一致。'
    if (!(await confirmAction('切换参考表', `${original} → ${preview.referenceTable}\n${summary}\n原字段映射会保留，请核对后保存。`, '确认切换')) || !current()) return
    form.value.sourceObject = preview.referenceTable
    await loadSourceCols(sourceId, true, true)
  } catch (error) {
    if (current()) sourceColsError.value = error instanceof Error ? error.message : '读取候选参考表失败，请重试'
  } finally {
    if (request === sourceColsSeq) sourceColsLoading.value = false
  }
}

/** 用户切换选择 SFTP 结构类:以其列为 schema,重建映射行 */
function pickSftpGroup(idx: number) {
  const g = sftpGroups.value[idx]
  if (!g || !g.columns.length) return
  sourceColsSeq++
  sourceColsLoading.value = false
  sourceColsError.value = ''
  sftpSelIdx.value = idx
  sourceColumnComments.value = Object.fromEntries(g.columns.map((c) => [c.name, form.value.hasHeader !== false ? c.name : '']))
  form.value.schema = g.columns.map((c) => ({ name: c.name, type: c.type }))
  sourceCols.value = g.columns.map((c) => c.name)
  form.value.mapping = g.columns.map((c) => ({
    sourceColumn: c.name,
    targetColumn: '',
    type: inferType(c.type),
    nullable: false,
    defaultValue: '',
    comment: sourceColumnComments.value[c.name] ?? ''
  }))
  form.value.mode = 'full'
  form.value.incrColumn = ''
  syncSftpPage()
}


/** 选择远端目录(仅选目录,不检测) */
function openSftpDir() {
  sftpDirVisible.value = true
}

function onSftpDirPicked(path: string) {
  form.value.remotePath = path
  sftpGroups.value = []
  sftpSelIdx.value = -1
  if (!form.value.filePattern) form.value.filePattern = '*.csv'
  detectSftp()
}


/** 按当前远端目录+匹配检测结构分类,并默认选中第一类 */
let sftpDetectSeq = 0
async function detectSftp() {
  if (!form.value.dataSourceId || !form.value.remotePath) return
  sftpReadSummary.value = ''
  sftpReadErrors.value = []
  const request = ++sftpDetectSeq
  const sourceRequest = ++sourceColsSeq
  const id = form.value.dataSourceId, path = form.value.remotePath
  const pattern = form.value.filePattern || '*.csv', header = form.value.hasHeader !== false
  const ignoreExtraCsvColumns = form.value.ignoreExtraCsvColumns !== false
  const csvEncoding = form.value.csvEncoding ?? 'auto'
  const current = () => request === sftpDetectSeq && sourceRequest === sourceColsSeq
    && id === form.value.dataSourceId && path === form.value.remotePath
    && pattern === (form.value.filePattern || '*.csv') && header === (form.value.hasHeader !== false)
    && ignoreExtraCsvColumns === (form.value.ignoreExtraCsvColumns !== false)
    && csvEncoding === (form.value.csvEncoding ?? 'auto')
  sftpDetecting.value = true
  sourceColsLoading.value = true
  sourceColsError.value = ''
  sftpGroups.value = []
  sftpSelIdx.value = -1
  try {
    const det = await dataSourcesApi.sftpPreviewById(id, {
      remotePath: path,
      filePattern: pattern,
      hasHeader: header,
      ignoreExtraCsvColumns,
      csvEncoding
    })
    if (!current()) return
    showSftpReadInfo(det)
    sftpGroups.value = (det.groups ?? [])
      .filter((g) => !g.error && g.columns && g.columns.length)
      .map((g) => ({ name: (g.colNames || []).join(', '), columns: g.columns, fileCount: g.fileCount }))
    if (sftpGroups.value.length) pickSftpGroup(0)
    else {
      sourceColumnComments.value = {}
      sourceCols.value = []
      form.value.schema = []
      sourceColsError.value = '该目录下未找到可用的字段结构，请检查目录与文件匹配后重新检测'
      form.value.mapping = []
      sftpPage.value = 1
      ElMessage.warning('该目录下未找到可用的结构(检查目录与文件匹配)')
}

  } catch {
    if (!current()) return
    sourceColumnComments.value = {}
    sourceCols.value = []
    form.value.schema = []
    sourceColsError.value = '读取远端文件字段及类型失败，请重新检测结构'
    form.value.mapping = []
  } finally {
    if (request === sftpDetectSeq) sftpDetecting.value = false
    if (sourceRequest === sourceColsSeq) sourceColsLoading.value = false
}
}


function addMappingRow() {
  form.value.mapping.push({
    sourceColumn: '',
    targetColumn: '',
    type: 'String',
    nullable: false,
    defaultValue: '',
    comment: ''
  })
}


/** 手动补映射时也带入原始表头；已输入的业务备注保留。 */
function onMappingSourceChanged(row: { sourceColumn: string; comment: string }) {
  if (!row.comment?.trim()) row.comment = sourceColumnComments.value[row.sourceColumn] ?? ''
}

/** 删除某行映射:仅当行下标落在业务映射段(前段)时才删,贴源自动行不可删 */
function removeMapRow(i: number) {
  if (i < form.value.mapping.length) {
    const removed = form.value.mapping[i]
    form.value.mapping.splice(i, 1)
    // 若删的是当前手机号行,清除手机号标记,避免残留无效 phoneColumn
    if (removed.sourceColumn && removed.sourceColumn === form.value.phoneColumn) {
      form.value.phoneColumn = ''
}
  }
}

/** 表格行样式:贴源自动行用灰底标识;手机号列用高亮背景标识 */
function rowAutoCls({ row }: { row: any }): string {
  const cls: string[] = []
  if (row && row._auto) cls.push('ods-auto-row')
  if (isPhoneRow(row)) cls.push('phone-row')
  return cls.join(' ')
}


/** 该映射行是否为手机号列(源列 === 当前手机号列) */
function isPhoneRow(row: any): boolean {
  return !!row && !row._auto && !!row.sourceColumn && row.sourceColumn === form.value.phoneColumn
}


/** 源列下拉可选项:排除已被其他映射行占用的源列(每个源列只能映射一次)。idx=当前行。 */
function srcOptions(idx: number): string[] {
  const used = new Set<string>()
  form.value.mapping.forEach((m, i) => {
    if (i === idx || !m.sourceColumn) return
    used.add(m.sourceColumn)
  })
  return sourceCols.value.filter((c) => !used.has(c))
}


// 修改配置后清除上次保存的服务端提示；重复映射保留原值，由行内校验说明冲突。
watch(
  () => [form.value.mapping, form.value.targetTable, form.value.targetDb, form.value.connectionId, form.value.dataSourceId, form.value.schema, dialogVisible.value],
  () => { mappingServerError.value = '' },
  { deep: true },
)

/** 目标列下拉可选项:排除已被其他行使用的目标列(每个目标列映射唯一)。 */
function targetColOptions(idx: number) {
  const used = new Set<string>()
  form.value.mapping.forEach((m, i) => {
    if (i === idx || !m.targetColumn) return
    used.add(m.targetColumn.trim())
  })
  return targetCols.value.filter((c) => !used.has(c.name))
}

function systemColumnError(column: string): string {
  return systemTargetColumnError(column, !!form.value.isOds, curSrcType.value === 'sftp')
}

function targetColLabel(column: TargetColumn): string {
  const label = column.comment ? `${column.name}(${column.comment})` : column.name
  if (systemColumnError(column.name)) return `${label}（系统维护，不可映射）`
  return targetColumnWritable(column) ? label : `${label}（${column.defaultKind}，不可映射）`
}

const srcIncrOptions = computed(() =>
  Array.from(new Set(form.value.mapping.filter((m) => canBeIncrementalKey(m.type))
    .filter((m) => mappingTypeIssue(sourceColumnType(m.sourceColumn), m.type, true)?.level !== 'error')
    .filter((m) => !actualTargetType(m.targetColumn) || mappingTypeIssue(m.type, actualTargetType(m.targetColumn), true)?.level !== 'error')
    .map((m) => m.sourceColumn).filter(Boolean)))
)

/** 读取目标连接里某 CK 库表的列(名+可映射类型),供 ck_table 源生成字段映射。 */
async function readCkCols(connId: number, db: string, table: string): Promise<{ name: string; type: string }[]> {
  try {
    const res = await workspaceApi.schema(connId, db, table)
    // schema 返回 DESCRIBE:rows 每行为 [name, type, ...]
    return (res.rows ?? [])
      .map((r: any[]) => ({ name: String(r?.[0] ?? ''), type: String(r?.[1] ?? '') }))
      .filter((c) => c.name)
  } catch {
    return []
}
}


/**
 * 自动建表时保留合法源列名，MongoDB 点分路径转下划线并避让冲突；已填目标列保留。
 */
function ensureAutoTargets() {
  if (dateBaselineLocked.value) return
  const reserved = ['source_id', 'create_time', 'sync_time', '_el_file_row', '_el_kafka_partition', '_el_kafka_offset', '_el_sync_round', '_el_sync_row',
    ...(form.value.isOds ? ['ods_insert_at', 'ods_source_id'] : []),
    ...(curSrcType.value === 'sftp' ? ['path'] : [])]
  if (targetExisting.value) {
    const available = new Set(targetCols.value.filter(targetColumnWritable).map(c => c.name))
    const used = new Set(form.value.mapping.map(m => m.targetColumn.trim()).filter(Boolean))
    for (const row of form.value.mapping) {
      const name = row.sourceColumn.trim()
      if (!row.targetColumn.trim() && available.has(name) && !used.has(name) && !reserved.includes(name)) {
        row.targetColumn = name
        used.add(name)
}
    }

    return
}

  fillAutoTargetColumns(form.value.mapping, curSrcType.value === 'mongodb', reserved)
  // 建表设置默认:ODS 贴源新表默认按 ods_insert_at 按日分区(排序键由用户手动选,不自动默认)
  if (form.value.isOds && !form.value.partitionExpr) {
    form.value.partitionExpr = 'toYYYYMMDD(ods_insert_at)'
}

  // 已标手机号:自动建表时把手机号行目标列固定为字典字段名并前置/默认主键排序键
  applyPhoneDefault()
}


/** 页面提示、步骤跳转和保存共用，覆盖新建及已有目标表。 */
const mappingTargetErrors = computed(() => mappingTargetIssues(form.value.mapping, {
  ods: !!form.value.isOds,
  directory: curSrcType.value === 'sftp',
  existingColumns: targetExisting.value ? targetCols.value.map((c) => c.name) : undefined,
  targetTable: form.value.targetTable,
  sourceColumns: sourceCols.value,
}).map((error, index) => {
  const column = targetExisting.value ? targetCols.value.find(c => c.name === form.value.mapping[index].targetColumn.trim()) : undefined
  return error || (column && !targetColumnWritable(column) ? `目标列「${column.name}」为 ${column.defaultKind} 列，请选择普通列或 DEFAULT 列` : '')
}))
const systemTypeErrors = computed(() => targetExisting.value && targetCols.value.length
  ? systemTargetTypeIssues(targetCols.value, !!form.value.isOds, curSrcType.value === 'sftp', form.value.autoCreateTable !== false,
    form.value.resumable ? curSrcType.value : '') : [])
function sourceColumnType(column: string): string {
  return form.value.schema?.find((c) => c.name === column.trim())?.type || ''
}

function actualTargetType(column: string): string {
  return targetExisting.value ? targetCols.value.find((c) => c.name === column.trim())?.type || '' : ''
}

const mappingTypeIssues = computed(() => form.value.mapping.map((row) => {
  if (!row.sourceColumn.trim() && !row.targetColumn.trim()) return []
  const incremental = curSrcType.value !== 'sftp' && form.value.mode === 'incremental' && row.sourceColumn === form.value.incrColumn
  const source = sourceColumnType(row.sourceColumn)
  const actual = actualTargetType(row.targetColumn)
  const nullIssues = []
  if (/nullable\s*\(/i.test(source) && !row.nullable)
    nullIssues.push({ level: 'warning' as const, message: '源列允许空值，当前不可空映射会使用配置默认值或类型默认值，请确认空值处理方式' })
  if (row.nullable && actual && !/nullable\s*\(/i.test(actual))
    nullIssues.push({ level: 'warning' as const, message: '映射允许空值，但实际目标列不可空；含空值的数据会写入失败，请改用可空目标列或先处理源空值' })
  return [mappingTypeIssue(source, row.type, incremental),
  ...(actual ? [mappingTypeIssue(row.type, actual, incremental)] : []), ...nullIssues].filter((issue) => !!issue)
}))
const mappingTypeErrors = computed(() => mappingTypeIssues.value.map((issues) =>
  issues.filter((issue) => issue.level === 'error').map((issue) => issue.message).join('；')))
const mappingTypeWarnings = computed(() => mappingTypeIssues.value.map((issues) =>
  issues.filter((issue) => issue.level === 'warning').map((issue) => issue.message).join('；')))
const mappingErrorCount = computed(() => form.value.mapping.filter((_, i) => mappingTargetErrors.value[i] || mappingTypeErrors.value[i]).length)
function validateMappingTargets(): boolean {
  if (sourceColsLoading.value || sourceColsError.value) {
    step.value = 1
    ElMessage.warning(sourceColsError.value || '正在读取源字段及类型，请稍后继续')
    return false
}

  if (targetExisting.value && (targetColsLoading.value || targetColsError.value)) {
    step.value = 1
    ElMessage.warning(targetColsError.value || '正在读取目标字段及类型，请稍后继续')
    return false
}

  const error = mappingTargetErrors.value.find(Boolean) || mappingTypeErrors.value.find(Boolean) || systemTypeErrors.value[0] || systemWriteErrors.value[0]
    || (unmappedWritableColumns.value.length && !targetDefaultsAcknowledged.value
      ? '存在未映射的目标列，请补充字段映射或勾选允许按目标表默认规则填充' : '')
  if (!error) return true
  step.value = 1
  ElMessage.warning(error)
  return false
}


/** 手机号列 radio 的双向值(源列名;绑定 form.phoneColumn) */
const phoneModel = computed<string>({
  get: () => form.value.phoneColumn ?? '',
  set: (v) => pickPhone(v)
})

/** 选中某列作为手机号列;再自动处理映射/主键/排序键(自动建表时)。 */
const phoneMapping = computed(() => form.value.mapping.find(m => m.sourceColumn === form.value.phoneColumn))
/** 开关随字段映射保存，编辑回显与定时执行复用同一配置。 */
const phoneAutoMd5 = computed({
  get: () => phoneMapping.value?.autoMdnMd5 === true,
  set: (enabled: boolean) => {
    if (phoneMapping.value) phoneMapping.value.autoMdnMd5 = enabled ? true : undefined
}

})

function pickPhone(col: string) {
  for (const mapping of form.value.mapping) {
    if (mapping.sourceColumn !== col) delete mapping.autoMdnMd5
}

  form.value.phoneColumn = col || ''
  if (!col) return
  const row = form.value.mapping.find((m) => m.sourceColumn === col)
  if (!row) return
  if (targetExisting.value) {
    // 已存在表:若表里有字典字段名 mdn,自动把该源列关联到 mdn 目标列
    if (targetCols.value.some((c) => c.name === dimPhoneField.value)) {
      row.targetColumn = dimPhoneField.value
}

  } else {
    applyPhoneDefault()
}
}


/** 自动建表 & 标了手机号:把手机号源行目标列固定为字典字段名,并默认主键/排序键。 */
function applyPhoneDefault() {
  if (dateBaselineLocked.value) return
  if (targetExisting.value || !form.value.phoneColumn) return
  const phoneCol = form.value.phoneColumn.trim()
  if (!phoneCol) return
  const pm = form.value.mapping.find((m) => m.sourceColumn === phoneCol)
  if (!pm) return
  // 目标列固定为字典字段名(如 mdn),类型 String;自动建表需英文列名
  pm.targetColumn = dimPhoneField.value
  pm.type = 'String'
  const originalHeader = sourceColumnComments.value[pm.sourceColumn]
  if (!pm.comment?.trim()) pm.comment = '手机号'
  else if (pm.comment === originalHeader && !pm.comment.includes('手机号')) pm.comment += '（手机号）'
  // 默认主键/排序键 = 手机号目标列(仅在用户尚未手选时;二者可由建表设置覆盖)
  if (!(form.value.primaryKeys ?? []).length) form.value.primaryKeys = [dimPhoneField.value]
  if (!(form.value.sortingKeys ?? []).length) form.value.sortingKeys = [dimPhoneField.value]
  // 手机号行移到最前 → 目标表首列为 mdn
  const i = form.value.mapping.indexOf(pm)
  if (i > 0) {
    form.value.mapping.splice(i, 1)
    form.value.mapping.unshift(pm)
}
}


async function onSave() {
  if (saving.value) return
  const session = editorSessionSeq
  const editingId = editing.value?.id
  if (!validateDateTableTransition()) { step.value = 0; return }
  mappingServerError.value = ''
  // 固定策略的数据源统一使用 full；已保存全量的隐藏参数保留，避免改变历史断点指纹。
  if (!sourceUi.value.incremental) {
    form.value.mode = 'full'
    if (curSrcType.value === 'sftp') form.value.incrColumn = ''
}

  try {
    await formRef.value?.validate()
    if (!editorCurrent(session)) return
  } catch {
    if (!editorCurrent(session)) return
    // 配置项校验失败（名称/数据源/目标表等）：回到配置步骤定位。
    step.value = 0
    return
  }
  if (!validateCron()) { step.value = 0; return }
  // 若手机号列已不在映射源列中(源列被改/删),清除无效标记
  const srcSet = new Set(form.value.mapping.map((m) => m.sourceColumn).filter(Boolean))
  if (form.value.phoneColumn && !srcSet.has(form.value.phoneColumn)) form.value.phoneColumn = ''
  // 自动建表 & 标了手机号:先应用 mdn 目标列/默认主键排序键,再走下方校验
  applyPhoneDefault()
  if (!validateMappingTargets()) return
  // 手机号列必选(主键):本地 CSV / SFTP / 数据库都需指定手机号列
  if (!form.value.phoneColumn || !form.value.mapping.some((m) => m.sourceColumn === form.value.phoneColumn)) {
    step.value = 1
    ElMessage.warning('请先在“字段映射”上方选择手机号列(主键)')
    return
}

  saving.value = true
  try {
    // 只提交两端都填了的行:映射表允许存在半填的空行,原样提交会让后端按空列建表
    const valid = form.value.mapping.filter((m) => m.sourceColumn.trim() && m.targetColumn.trim())
      .map((m) => ({ ...m, sourceColumn: m.sourceColumn.trim(), targetColumn: m.targetColumn.trim(), type: m.type.trim() }))
    if (!valid.length) {
      ElMessage.warning('至少配置一个字段映射')
      return
}

    if (form.value.mode === 'incremental' && !srcIncrOptions.value.includes(form.value.incrColumn)) {
      step.value = 0
      ElMessage.warning('请选择已映射的数值、日期或时间戳列作为增量键')
      return
}

    // 断点续传:前端先检查每批行数是否落在 100–10000。
    // stableKey 当前允许为空（空值表示不指定分段键）；源列类型、目标映射及是否可续传仍由后端做权威校验。
    if (form.value.resumable) {
      const batch = Number(form.value.batchSize ?? 0)
      if (!Number.isInteger(batch) || batch < 100 || batch > 10000) {
        step.value = 0
        ElMessage.warning('断点续传的每批行数需为 100–10000')
        return
}
    }

    // 目标为新建表时:必须选排序键(否则 CK 建表失败)
    if (!targetExisting.value && !(form.value.sortingKeys ?? []).length) {
      step.value = 1
      ElMessage.warning('目标表为新建,请在“建表设置”中选择至少一个排序键')
      return
}

    if (valid.some(m => m.autoMdnMd5 && m.type.toLowerCase() !== 'string')) {
      ElMessage.warning('开启手机号转 MD5 后，请将对应映射类型设置为 String')
      return
}

    const payload: SyncTaskInput = { ...form.value, mapping: valid,
      allowedTargetDefaults: unmappedWritableColumns.value.map(c => c.name) }
    if (form.value.mysqlDateTables && (!form.value.stableKey || sourceColsError.value || !form.value.schema?.length)) {
      step.value = 0
      ElMessage.warning('请先预览日期分表结构，并选择各表共同的整数单列主键作为分段键')
      return
    }
    if (!(await confirmAction(editing.value ? '保存同步任务' : '新建同步任务',
      editing.value ? '映射内容编辑完成是否需要保存' : `确认新建同步任务「${payload.name}」？`,
      editing.value ? '确认保存' : '确认新建'))) return
    if (!editorCurrent(session)) return
    if (editingId) {
      await syncTasksApi.update(editingId, payload)
      if (!editorCurrent(session)) { void refreshListAfterMutation(); return }
      ElMessage.success('已保存')
    } else {
      await syncTasksApi.create(payload)
      if (!editorCurrent(session)) { void refreshListAfterMutation(); return }
      query.reset()
      page.value = 1
      ElMessage.success('已创建')
}

    dialogVisible.value = false
    void refreshListAfterMutation()
  } catch (error: any) {
    if (!editorCurrent(session)) return
    const message = error?.response?.data?.message || error?.message || ''
    // HTTP 拦截器负责通用错误提示；字段校验错误保留在字段步骤，方便修正。
    if (/字段|映射|源结构|目标.*结构|目标表不存在/.test(message)) {
      mappingServerError.value = message
      step.value = 1
    }
  } finally {
    if (editorCurrent(session)) saving.value = false
}
}


/**
 * 启动协议分为“接单”和“观察完成”两段：启动接口返回只表示后端已经接受异步运行请求，
 * 此时先写入本次 runId、建立定时器并立即查询一次，不能把启动请求返回误当成同步已经完成。
 * 普通执行和“继续传输”始终走 /run 并优先复用断点；“重新传输”确认后走 /restart 全量重建。
 * 若页面在请求返回前卸载，就不再建立轮询；启动或即时查询失败时只清理本地运行态和定时器，
 * 通用错误由 HTTP 层提示，后台已经接单的运行仍可能继续，页面离开也不会取消服务端任务。
 */
async function startTask(row: SyncTask, force: boolean) {
  if (pollsDisposed) return
  if (runningIds.value.has(row.id)) return // 本会话已启动且未结束(双击/连点防抖)
  if (row.queued) {
    ElMessage.warning('任务已排队，将自动执行；如需手动运行，请先取消排队')
    return
  }
  if (row.enabled === false) {
    ElMessage.warning('任务已停用，请先启用后再运行')
    return
}

  runningIds.value.add(row.id) // 先标记再 await,防止并发连点双跑
  try {
    if (force) {
      const confirmed = await confirmDestructive(
        '重新传输并全量重建',
        row.name,
        '确认重新传输',
        `将删除旧断点，清空 ${row.targetDb}/${row.targetTable} 的现有目标数据，并从头重新读取全部源数据。任务配置和执行历史会保留。`,
      )
      if (!confirmed) {
        runningIds.value.delete(row.id)
        return
}

    } else {
      const mode = row.resumable && row.checkpointPending ? '断点续传' : sourceTypeOf(row) === 'sftp' ? '按文件增量' : row.mode === 'incremental' ? '增量' : '全量'
      const wipe = row.resumable && row.checkpointPending ? `（已确认 ${row.checkpointRows ?? 0} 行）` : sourceTypeOf(row) !== 'sftp' && row.mode === 'full' && row.truncate ? '（会先清空目标表）' : ''
      if (!(await confirmAction('执行同步任务', `确认执行任务「${row.name}」？默认优先使用已有断点，没有断点时按「${mode}」方式写入 ${row.targetDb}/${row.targetTable}${wipe}。传输故障会自动重试；持续失败后可选择“继续传输”或“重新传输”，无需删除任务。`, '确认执行'))) {
        runningIds.value.delete(row.id)
        return
}
    }

    if (pollsDisposed) {
      runningIds.value.delete(row.id)
      return
}

    const res = force ? await syncTasksApi.restart(row.id) : await syncTasksApi.run(row.id)
    if (pollsDisposed) {
      runningIds.value.delete(row.id)
      return
}

    expectedRunIds.value.set(row.id, res.runId)
    // 接单后立即展示本次运行，即使首个查询较慢或暂时失败，也不能继续显示上次成功。
    const current = list.value.find(task => task.id === row.id)
    if (current && (!current.lastRun || (current.lastRun.id ?? 0) <= res.runId)) {
      current.lastRun = {
        id: res.runId, status: 'running', rowsRead: 0, rowsWritten: 0,
        startedAt: '', finishedAt: '', error: ''
}
      progressVersions.set(row.id, ++progressVersion)
}

    // 启动接口返回只代表后端已接单(异步执行),所以要立刻开轮询盯状态,而不是等这次请求的结果
    startPolling(row.id)
    await pollUntilDone(row.id)
  } catch {
    /* 拦截器已提示 */
    runningIds.value.delete(row.id)
    stopPollingOf(row.id)
}
}


/** 默认运行：后端从已确认断点继续，不传 force，也不会删除旧断点。 */
async function runTask(row: SyncTask) {
  return startTask(row, false)
}


/** 明确的全量重建：二次确认后调用独立接口，由后端在锁内替换断点并覆盖目标。 */
async function restartTask(row: SyncTask) {
  return startTask(row, true)
}


/** 为某任务启动每 2s 轮询(同一任务不重复开) */
function startPolling(taskId: number) {
  if (pollsDisposed) return
  const runId = expectedRunIds.value.get(taskId) ?? list.value.find(row => row.id === taskId)?.lastRun?.id
  if (!runId) return
  if (pollTimers.value.has(taskId)) {
    if (observedRuns.get(taskId)?.runId === runId) return
    stopPollingOf(taskId, false)
  }
  observedRuns.set(taskId, { runId })
  if (pollingPausedForVisibility) return
  // 固定 tick，实际请求受失败次数与下一次重试时间约束；终止错误会主动清掉 timer。
  const timer = setInterval(() => pollUntilDone(taskId), 2000)
  pollTimers.value.set(taskId, timer)
}


/**
 * 单轮运行观察。
 * 每轮只查询正在观察的 runId；旧后端缺少单次运行接口时退回该任务的历史查找。
 * 4xx 终止观察；网络故障和 5xx 有限退避重试，五次连续失败后停止并提示。
 * 观察到终态后清理定时器；成功显示汇总，失败展示后端原因，已打开的历史立即更新。
 */
async function pollUntilDone(taskId: number) {
  const observation = observedRuns.get(taskId)
  if (!observation || pollsDisposed || pollingPausedForVisibility) return
  if (Date.now() < (pollRetryAt.get(taskId) ?? 0)) return
  if (pollBusy.value.has(taskId)) return // 上一轮请求未完成则跳过,避免叠加
  pollBusy.value.add(taskId)
  let terminalObserved = false
  try {
    let run: SyncRun | undefined
    try {
      run = await syncTasksApi.runStatus(taskId, observation.runId)
    } catch (e: any) {
      const status = e?.status ?? e?.response?.status
      if (status !== 404) throw e
      if (pollsDisposed || observedRuns.get(taskId) !== observation) return
      run = (await syncTasksApi.runs(taskId, true)).find(r => r.ID === observation.runId)
      if (!run) throw Object.assign(new Error('运行记录不存在，请刷新任务列表'), { status: 404 })
}

    if (pollsDisposed || observedRuns.get(taskId) !== observation) return
    if (!run || run.ID !== observation.runId) throw new Error('运行状态响应不匹配')
    const observed = {
      id: run.ID, status: run.Status, startedAt: run.StartedAt, finishedAt: run.FinishedAt,
      rowsRead: run.RowsRead, rowsWritten: run.RowsWritten, error: run.Error, progress: run.Progress,
      note: run.Note, resumed: run.Resumed, checkpointFrom: run.CheckpointFrom, checkpointRows: run.CheckpointRows
}
    const row = list.value.find(row => row.id === taskId)
    // 排队/取消排队的较大运行号不能盖住仍在执行的上一轮进度。
    const activeOtherRun = row?.lastRun?.status === 'running' && row.lastRun.id !== run.ID && observed.status !== 'running'
    if (row && !activeOtherRun && (!row.lastRun || (row.lastRun.id ?? 0) <= run.ID)) {
      row.lastRun = observed
      progressVersions.set(taskId, ++progressVersion)
}

    const st = observed.status
    pollFailures.delete(taskId)
    pollRetryAt.delete(taskId)
    if (st && st !== 'running' && st !== 'queued') {
      terminalObserved = true
      const finished = expectedRunIds.value.has(taskId)
      stopPollingOf(taskId)
      runningIds.value.delete(taskId)
      // 后台失败时这里同样停止轮询，但不弹通用完成/失败消息；错误摘要保留在运行历史中。
      // 仅对本会话启动的任务弹提示(避免把别人的/cron 运行也弹一遍)
      if (finished) {
        if (st === 'success')
          ElMessage.success(`同步完成: 读 ${observed?.rowsRead} 写 ${observed?.rowsWritten} 行`)
        else ElMessage.error(`同步未完成：${observed?.error || '请查看执行日志了解原因'}`)
}

      // 终态后才刷新断点和配置，避免在每轮进度查询中重读所有任务。
      await loadShared()
      if (pollsDisposed) return
      const latest = list.value.find(row => row.id === taskId)
      if (latest && (runningNow(latest) || latest.lastRun?.status === 'queued') && latest.lastRun?.id !== observation.runId) startPolling(taskId)
}

    // 日志抽屉有独立刷新周期；终态时再立即补一次，避免重复请求历史。
    if (st !== 'running' && st !== 'queued') await refreshHistory(taskId)
  } catch (error: any) {
    if (pollsDisposed) return
    // 终态已确认；附属刷新失败不能重新标记该次运行，更不能停止刚启动的下一次观察。
    if (terminalObserved) {
      ElMessage.error(`同步状态已更新，但列表或历史刷新失败：${error?.message || '请手动刷新'}`)
      return
}

    if (observedRuns.get(taskId) !== observation) return
    const status = error?.status ?? error?.response?.status
    const failures = (pollFailures.get(taskId) ?? 0) + 1
    if ((status && status < 500) || failures >= 5) {
      stopPollingOf(taskId)
      runningIds.value.delete(taskId)
      ElMessage.error(`已停止查询同步状态：${error?.message || '连续请求失败'}。可刷新列表后查看执行日志。`)
    } else {
      pollFailures.set(taskId, failures)
      pollRetryAt.set(taskId, Date.now() + Math.min(30000, 2000 * 2 ** (failures - 1)))
}

  } finally {
    pollBusy.value.delete(taskId)
}
}


const pollFailures = new Map<number, number>()
const pollRetryAt = new Map<number, number>()

function stopPollingOf(taskId: number, clearExpected = true) {
  observedRuns.delete(taskId)
  pollFailures.delete(taskId)
  pollRetryAt.delete(taskId)
  if (clearExpected) expectedRunIds.value.delete(taskId)
  const t = pollTimers.value.get(taskId)
  if (t) {
    clearInterval(t)
    pollTimers.value.delete(taskId)
}
}


function stopAllPolling() {
  runningIds.value.clear()
  observedRuns.clear()
  progressVersions.clear()
  pollTimers.value.forEach((t) => clearInterval(t))
  pollTimers.value.clear()
  expectedRunIds.value.clear()
  pollFailures.clear()
  pollRetryAt.clear()
}

/** 除当前页和已打开历史外，仅保留本会话主动启动的运行观察。 */
function pruneOffPageObservers() {
  const visible = new Set(list.value.map(row => row.id))
  for (const id of observedRuns.keys()) {
    if (!visible.has(id) && !expectedRunIds.value.has(id) && !(historyVisible.value && historyTask.value?.id === id)) {
      stopPollingOf(id)
      runningIds.value.delete(id)
      progressVersions.delete(id)
    }
  }
}


/**
 * 低频发现由 cron 或其他浏览器启动的运行。
 *
 * 每 15 秒仅查询当前页的运行摘要，发现定时/其他会话启动的运行，不传输映射或断点配置。
 * 页码、列表版本及进度版本共同保护响应；终态变化才刷新当前页的断点和操作条件。
 * 已有定时器由 startPolling() 去重，不会为同一个任务重复创建轮询。
 */
let runningDiscoveryTimer: ReturnType<typeof setInterval> | undefined
let runningDiscoveryBusy = false
async function discoverRunningRuns(pollImmediately = false) {
  if (pollsDisposed || pollingPausedForVisibility || runningDiscoveryBusy) return
  pruneOffPageObservers()
  const ids = list.value.map(row => row.id)
  if (!ids.length) return
  const key = listQueryKey(), version = progressVersion, sequence = listSeq
  runningDiscoveryBusy = true
  try {
    const snapshots = await syncTasksApi.statuses(ids)
    if (pollsDisposed || pollingPausedForVisibility || key !== listQueryKey() || sequence !== listSeq) return
    const current = new Map(list.value.map(row => [row.id, row]))
    // 被删除或失去所有权的任务会被状态接口省略，重新分页后移除旧行并修正总数。
    let refreshMetadata = snapshots.length !== ids.length
    for (const snapshot of snapshots) {
      const row = current.get(snapshot.id)
      if (!row) continue
      const previous = row.lastRun, next = snapshot.lastRun
      // 单条运行查询可能比批量发现更晚更新，不能让慢摘要把终态或进度倒退。
      if (previous && (((previous.id ?? 0) > (next?.id ?? 0) && next?.status !== 'running')
        || ((previous.id ?? 0) === (next?.id ?? 0) && ((progressVersions.get(row.id) ?? 0) > version
          || (['success', 'failed'].includes(previous.status) && next?.status === 'running'))))) continue
      if ((row.queued && !snapshot.queued) || (next && !['running', 'queued'].includes(next.status)
        && (next.id !== previous?.id || next.status !== previous?.status))) refreshMetadata = true
      row.queued = snapshot.queued
      row.lastRun = next
    }
    const running = list.value.filter(row => runningNow(row) || row.lastRun?.status === 'queued')
    const newlyObserved = running.filter(row => !observedRuns.has(row.id))
    running.forEach(row => startPolling(row.id))
    if (pollImmediately) newlyObserved.forEach(row => { void pollUntilDone(row.id) })
    if (refreshMetadata) await loadShared()
  } catch {
    // 静默发现失败不打扰用户；下次周期继续尝试，已有运行观察不受影响。
  } finally {
    runningDiscoveryBusy = false
}
}


function startRunningDiscovery() {
  if (runningDiscoveryTimer || pollsDisposed || pollingPausedForVisibility) return
  runningDiscoveryTimer = setInterval(() => { void discoverRunningRuns() }, 15000)
}


function stopRunningDiscovery(resetBusy = true) {
  if (runningDiscoveryTimer) clearInterval(runningDiscoveryTimer)
  runningDiscoveryTimer = undefined
  if (resetBusy) runningDiscoveryBusy = false
}


// 必须逐个 clearInterval，避免组件销毁后继续请求运行状态。
onBeforeUnmount(() => {
  pollsDisposed = true
  document.removeEventListener('visibilitychange', onVisibilityChange)
  stopRunningDiscovery()
  stopAllPolling()
  resetHistorySession()
})

let historyRefreshTimer: ReturnType<typeof setInterval> | undefined
let historyGeneration = 0
let historySeq = 0
let historyRefreshBusy = false
let historyFailures = 0
let historyRetryAt = 0
function stopHistoryRefresh() {
  if (historyRefreshTimer) clearInterval(historyRefreshTimer)
  historyRefreshTimer = undefined
}

/** 每次打开、关闭或卸载都隔离初始化、轮询和分页请求，也重置属于旧抽屉的忙碌状态。 */
function resetHistorySession() {
  stopHistoryRefresh()
  historyGeneration++
  historySeq++
  logSeq++
  historyRefreshBusy = false
  historyLoading.value = false
  logLoading.value = false
  closeLogDetail()
  deletingLog.value = false
  historyFailures = 0
  historyRetryAt = 0
  return historyGeneration
}


function isHistorySession(generation: number) {
  return !pollsDisposed && historyVisible.value && generation === historyGeneration
}


function historyErrorStatus(error: any): number | undefined {
  return error?.status ?? error?.response?.status
}


/** 等待两组请求都结束，避免一组先失败后就解除 busy，叠加尚未结束的另一组请求。 */
async function refreshHistoryDetails(taskId: number, generation: number) {
  const results = await Promise.allSettled([refreshHistory(taskId), loadRunLogs(true, true)])
  if (!isHistorySession(generation)) return
  const failures = results.filter((result): result is PromiseRejectedResult => result.status === 'rejected')
  // 一组 5xx、另一组 4xx 时，优先按不可重试的权限/参数错误停止。
  const failure = failures.find(result => {
    const status = historyErrorStatus(result.reason)
    return status && status < 500
  }) ?? failures[0]
  if (failure) throw failure.reason
}


// 抽屉打开期间每 3 秒刷新历史与日志。定时器捕获当前会话，旧请求不影响新任务的退避和 loading。
async function refreshHistoryTick(generation: number) {
  if (pollsDisposed || pollingPausedForVisibility || !isHistorySession(generation)
    || historyRefreshBusy || historyLoading.value || logLoading.value || !historyTask.value
    || Date.now() < historyRetryAt) return
  historyRefreshBusy = true
  try {
    await refreshHistoryDetails(historyTask.value.id, generation)
    if (isHistorySession(generation)) { historyFailures = 0; historyRetryAt = 0 }
  } catch (error: any) {
    if (!isHistorySession(generation)) return
    const status = historyErrorStatus(error)
    if ((status && status < 500) || ++historyFailures >= 5) {
      stopHistoryRefresh()
      ElMessage.error(error?.message || '历史记录刷新失败，请重新打开执行日志')
    } else historyRetryAt = Date.now() + Math.min(30000, 3000 * 2 ** (historyFailures - 1))
  } finally {
    if (isHistorySession(generation)) historyRefreshBusy = false
}
}


function startHistoryRefresh(generation: number) {
  stopHistoryRefresh()
  if (pollsDisposed || pollingPausedForVisibility) return
  historyRefreshTimer = setInterval(() => { void refreshHistoryTick(generation) }, 3000)
}


/** 隐藏标签页时只清前端定时器；观察状态和 expectedRunIds 必须保留到恢复或真正卸载。 */
function pausePollingForVisibility() {
  if (pollsDisposed || pollingPausedForVisibility) return
  pollingPausedForVisibility = true
  pollTimers.value.forEach(timer => clearInterval(timer))
  pollTimers.value.clear()
  stopRunningDiscovery(false)
  stopHistoryRefresh()
}


/** 恢复时立即查询现有观察，再发现隐藏期间由 cron/其他会话启动的新运行。 */
function resumePollingForVisibility() {
  if (pollsDisposed || document.hidden || !pollingPausedForVisibility) return
  pollingPausedForVisibility = false
  for (const taskId of [...observedRuns.keys()]) {
    startPolling(taskId)
    void pollUntilDone(taskId)
}

  startRunningDiscovery()
  void discoverRunningRuns(true)
  if (historyVisible.value && historyTask.value) {
    const generation = historyGeneration
    startHistoryRefresh(generation)
    void refreshHistoryTick(generation)
}
}


function onVisibilityChange() {
  if (document.hidden) pausePollingForVisibility()
  else resumePollingForVisibility()
}


// 在关闭动作发生时立即失效，而不是等待抽屉退出动画结束。
watch(historyVisible, visible => { if (!visible) onHistoryClosed() }, { flush: 'sync' })

/**
 * 刷新最近运行列表。request 在发请求前占位，返回后同时校验序号、抽屉可见性和当前任务 id；
 * 因此切换任务、关闭抽屉或组件卸载后迟到的响应不会覆盖新状态。
 */
async function refreshHistory(taskId: number) {
  if (!historyVisible.value || historyTask.value?.id !== taskId || pollsDisposed) return
  const generation = historyGeneration
  const request = ++historySeq
  try {
    const history = await syncTasksApi.runs(taskId, true)
    if (request === historySeq && isHistorySession(generation) && historyTask.value?.id === taskId)
      runs.value = history
  } catch (error) {
    if (request === historySeq && isHistorySession(generation) && historyTask.value?.id === taskId) throw error
}
}


/**
 * 加载执行日志当前页。silent=true 只表示本页不显示 loading，并继续交给 HTTP 层抑制全局错误；
 * 自动刷新/初始化传 propagateError=true，由外层统一决定退避或停止；手工筛选失败在本层提示，避免未处理拒绝。
 */
async function loadRunLogs(silent = false, propagateError = false) {
  const task = historyTask.value
  if (!task?.id || pollsDisposed || !historyVisible.value) return
  const generation = historyGeneration
  const request = ++logSeq
  if (!silent) logLoading.value = true
  try {
    const res = await syncTasksApi.runLogs(task.id, {
      page: logPage.value,
      size: logSize.value,
      status: logStatus.value === 'all' ? undefined : logStatus.value,
      resumed: logResumed.value === 'all' ? undefined : logResumed.value === 'yes',
      days: logDays.value || undefined,
      keyword: logKeyword.value.trim() || undefined,
    }, true)
    if (request !== logSeq || !isHistorySession(generation) || historyTask.value?.id !== task.id) return
    logRows.value = res.rows ?? []
    logTotal.value = res.total ?? 0
  } catch (error: any) {
    if (request === logSeq && isHistorySession(generation) && historyTask.value?.id === task.id) {
      if (propagateError) throw error
      stopHistoryRefresh()
      ElMessage.error(`执行日志加载失败：${error?.message || '请稍后重试'}`)
}

  } finally {
    if (request === logSeq && isHistorySession(generation)) logLoading.value = false
}
}

/** 任一过滤条件变化都回到第 1 页 */
function applyLogFilter() {
  logPage.value = 1
  loadRunLogs()
}

/** 重置全部筛选条件(与列表页的查询栏行为一致) */
function resetLogQuery() {
  logKeyword.value = ''
  logStatus.value = 'all'
  logResumed.value = 'all'
  logDays.value = 0
  logPage.value = 1
  loadRunLogs()
}

function onLogPageChange(page: number) {
  if (logPage.value === page) return
  logPage.value = page
  loadRunLogs()
}

function onLogSizeChange(size: number) {
  if (logSize.value === size) return
  logSize.value = size
  logPage.value = 1
  loadRunLogs()
}

/**
 * 关闭抽屉时同时让运行列表和日志的旧请求失效，并停止 3 秒刷新。
 * 这里只递增序号，不取消已经发出的 HTTP 请求；迟到响应会被上面的序号检查丢弃。
 */
function onHistoryClosed() {
  // 关闭动画可能晚于用户重新打开抽屉，不能用旧动画回调停止新会话。
  if (historyVisible.value) return
  resetHistorySession()
}


async function openHistory(row: SyncTask) {
  if (pollsDisposed) return
  const generation = resetHistorySession()
  historyTask.value = row
  historyVisible.value = true
  runs.value = []
  logRows.value = []
  logTotal.value = 0
  logPage.value = 1
  logStatus.value = 'all'
  logResumed.value = 'all'
  logDays.value = 0
  logKeyword.value = ''
  historyLoading.value = true
  startHistoryRefresh(generation)
  try {
    await refreshHistoryDetails(row.id, generation)
  } catch (error: any) {
    if (isHistorySession(generation)) {
      const status = historyErrorStatus(error)
      if (status && status < 500) stopHistoryRefresh()
      ElMessage.error(error?.message || '执行历史加载失败')
}

  } finally {
    if (isHistorySession(generation)) historyLoading.value = false
}
}


/** 判断某条运行记录是否为“进程中断/重启”导致未跑完 */
function isInterrupted(r: SyncRun): boolean {
  // 与后端启动恢复、取消及超时错误文案一致；“时限”用于兼容修复前写入的历史超时记录。
  return r.Status === 'failed' && /中断|重启|重置|已取消|超时|时限/.test(r.Error ?? '')
}


/** 只对最新失败提供恢复入口，历史筛选不会使旧失败覆盖新的成功或运行中状态。 */
const failedRun = computed(() => {
  const latest = runs.value[0]
  const current = historyTask.value?.lastRun
  if (current && latest && (current.id ?? 0) > latest.ID) return null
  return latest?.Status === 'failed' ? latest : null
})

/** 历史抽屉里“重新运行”当前任务(不关闭抽屉) */
async function rerunFromHistory() {
  if (!historyTask.value) return
  try {
    const id = historyTask.value.id
    const generation = historyGeneration
    const current = await syncTasksApi.detail(id)
    if (pollsDisposed || !historyVisible.value || historyGeneration !== generation || historyTask.value?.id !== id) return
    historyTask.value = current
    await runTask(current)
  } catch (error: any) { ElMessage.error(error?.message || '获取最新任务配置失败') }
}

/** 从历史中断提示发起全量重建；先刷新任务，避免使用停用或已修改的旧配置。 */
async function restartFromHistory() {
  if (!historyTask.value) return
  try {
    const id = historyTask.value.id
    const generation = historyGeneration
    const current = await syncTasksApi.detail(id)
    if (pollsDisposed || !historyVisible.value || historyGeneration !== generation || historyTask.value?.id !== id) return
    historyTask.value = current
    await restartTask(current)
  } catch (error: any) { ElMessage.error(error?.message || '获取最新任务配置失败') }
}

async function confirmToggleEnabled(row: SyncTask) {
  const next = !row.enabled
  const ok = await ElMessageBox.confirm(
    next
      ? `确定启用任务「${row.name}」吗?${row.scheduleType === 'scheduled' ? '启用后到点将自动执行。' : '启用后可从列表手动运行。'}`
      : `确定停用任务「${row.name}」吗?停用后不会自动执行(定时不触发),需启用后才能运行。`,
    next ? '启用任务' : '停用任务',
    { confirmButtonText: next ? '启用' : '停用', cancelButtonText: '取消', type: 'warning' }
  )
    .then(() => true)
    .catch(() => false)
  if (!ok) return
  await setEnabled(row, next)
}


async function setEnabled(row: SyncTask, next: boolean) {
  try {
    await syncTasksApi.setEnabled(row.id, next)
    ElMessage.success(next ? '已启用' : '已停用')
    void refreshListAfterMutation()
  } catch {
    // 失败也要重拉:后端没改成功,用列表数据把开关拉回真实状态
    void refreshListAfterMutation()
}
}


async function cancelTask(row: SyncTask) {
  const ok = await ElMessageBox.confirm(`确定取消任务「${row.name}」的本次运行吗?`, '取消运行', {
    confirmButtonText: '取消运行',
    cancelButtonText: '继续运行',
    type: 'warning'
  })
    .then(() => true)
    .catch(() => false)
  if (!ok) return
  try {
    const r = await syncTasksApi.cancel(row.id)
    // false 也可能是已进入收尾或由其他实例执行，不能据此断言任务没有运行。
    ElMessage.success(r.cancelled ? '已请求取消,正在停止…' : '未提交取消请求，请刷新查看任务状态')
    void refreshListAfterMutation()
  } catch {
    /* 拦截器已提示 */
}
}


async function cancelQueuedTask(row: SyncTask) {
  try {
    await ElMessageBox.confirm(`是否取消「${row.name}」的等待执行请求？`, '取消排队',
      { confirmButtonText: '取消排队', cancelButtonText: '保留排队', type: 'warning' })
    const result = await syncTasksApi.cancelQueued(row.id)
    ElMessage.success(result.cancelled ? '已取消排队，后续仍按原定时计划触发' : '请求已开始执行或已结束，请刷新查看')
    await refreshListAfterMutation()
  } catch { /* 取消确认或接口错误已处理 */
  }
}

async function remove(row: SyncTask) {
  try {
    await ElMessageBox.confirm(`是否删除映射任务「${row.name}」？`, '删除映射任务', {
      type: 'warning', confirmButtonText: '确认删除', cancelButtonText: '取消',
      confirmButtonClass: 'el-button--danger', closeOnClickModal: false,
    })
  } catch { return }
  await syncTasksApi.remove(row.id)
  ElMessage.success('已删除')
  void refreshListAfterMutation()
}


/** 批量删除勾选的任务 */
async function batchRemoveTasks() {
  const rows = [...selectedTasks.value]
  if (!rows.length) return
  try {
    await ElMessageBox.confirm(`是否删除映射任务？将删除选中的 ${rows.length} 个任务。`, '批量删除映射任务', {
      type: 'warning', confirmButtonText: '确认删除', cancelButtonText: '取消',
      confirmButtonClass: 'el-button--danger', closeOnClickModal: false,
    })
  } catch { return }
  let ok = 0
  const failed: { row: SyncTask; reason: string }[] = []
  // 没有批量接口,只能逐个删;单个失败不让整批中断,最后按成功数给结论
  for (const r of rows) {
    try {
      await syncTasksApi.remove(r.id, true)
      ok++
    } catch (e) {
      failed.push({ row: r, reason: e instanceof Error ? e.message : '删除失败' })
}
  }

  if (failed.length) {
    ElMessage.warning({
      message: `已删除 ${ok} 个，失败 ${failed.length} 个：` +
        failed.map(({ row, reason }) => `${row.name}：${reason}`).join('；'), duration: 8000, showClose: true
    })
  } else ElMessage.success(`已删除 ${ok} 个`)
  try { await load() } catch { /* 刷新失败由拦截器提示，删除结果仍按实际返回值显示 */ }
  await nextTick()
  // 刷新会重建行对象，按 ID 恢复失败项勾选，方便修正问题后继续操作。
  const failedIds = new Set(failed.map(({ row }) => row.id))
  taskTableRef.value?.clearSelection()
  for (const row of list.value) if (failedIds.has(row.id)) taskTableRef.value?.toggleRowSelection(row, true)
}


async function onCommand(cmd: string, row: SyncTask) {
  if (cmd === 'run') runTask(row)
  else if (cmd === 'runforce') restartTask(row)
  else if (cmd === 'adopt') adoptBaseline(row)
  else if (cmd === 'cancel') cancelTask(row)
  else if (cmd === 'cancelqueued') cancelQueuedTask(row)
  else if (cmd === 'history') openHistory(row)
  else if (cmd === 'edit') openEdit(row)
  else if (cmd === 'toggle') confirmToggleEnabled(row)
  else if (cmd === 'remove') remove(row)
}


/** 点击“数据源”列:SFTP 直接打开该数据源的文件系统浏览/预览;其它源仍跳数据源管理页定位。 */
async function goSource(row: SyncTask) {
  if (sourceTypeOf(row) === 'sftp') {
    srcPvTask.value = row
    srcPvVisible.value = true
    return
}

  router.push({ path: '/datasources', query: { dsId: String(row.dataSourceId) } })
}


/** 打开标准源表（ods_data）页并定位到目标贴源表(目标固定落 ods_data) */
async function goTarget(row: SyncTask) {
  // 目标表可能尚未创建(运行才自动建表):先确认存在,不存在则提示,不跳去空白表
  try {
    const tbs = await workspaceApi.tables(row.connectionId, row.targetDb)
    const exists = tbs.some((t) => t.name === row.targetTable)
    if (!exists) {
      ElMessage.warning(
        `目标表 ${row.targetDb}.${row.targetTable} 尚未创建。同步任务需先点「运行」才会自动建表并写入数据。`
      )
      return
}

  } catch {
    ElMessage.warning('目标表校验失败,请确认任务已运行创建表')
    return
}

  router.push(row.targetDb === 'ods_data'
    ? { path: '/raw', query: { conn: String(row.connectionId), table: row.targetTable } }
    : { path: '/workspace', query: { conn: String(row.connectionId), db: row.targetDb, tbl: row.targetTable } })
}


/** 数据源列完整文本(供 tooltip):分组/标识(描述) + SFTP 追加 /远端目录/文件匹配。
 *  去重 remotePath 自身以 / 开头导致的双斜杠。 */
function srcLabel(row: SyncTask): string {
  let s = ''
  if (row.sourceGroup) s += row.sourceGroup + '/'
  s += row.dataSourceCode || row.dataSourceName
  if (row.dataSourceDesc) s += `(${row.dataSourceDesc})`
  if (sourceTypeOf(row) === 'sftp') {
    const rp = (row.remotePath ?? '').replace(/\/+/g, '/')
    if (rp) {
      // 组/标识 末尾与远程路径之间只保留一个斜杠(remotePath 前导 / 不再重复)
      const sep = s.endsWith('/') ? '' : '/'
      s += sep + rp.replace(/^\/+/, '')
}

    if (row.filePattern) s += '/' + row.filePattern
}

  return s
}


/** 数据源列前段:分组/标识(描述)——用默认文字色显示 */
function srcLabelHead(row: SyncTask): string {
  let s = ''
  if (row.sourceGroup) s += row.sourceGroup + '/'
  s += row.dataSourceCode || row.dataSourceName
  if (row.dataSourceDesc) s += `(${row.dataSourceDesc})`
  return s
}


/** SFTP 数据源列的“远程路径/匹配”段(含单个前导 / )——用强调色区分显示。非 SFTP 返回 ''。 */
function srcLabelPath(row: SyncTask): string {
  if (sourceTypeOf(row) !== 'sftp') return ''
  let s = '/'
  const rp = (row.remotePath ?? '').replace(/\/+/g, '/').replace(/^\/+/, '')
  if (!rp) return ''
  s += rp
  if (row.filePattern) s += '/' + row.filePattern
  return s
}


/** 判断文本按估算宽度是否超长(决定是否显示省略号与 tooltip) */
function isOverflow(s: string, px: number): boolean {
  // 近似:ASCII≈0.6*px/字,中文≈1字;简化按平均 7px/字符
  return (s?.length ?? 0) * 7 > px - 10
}


/** 运行状态文案 */
function lastRunStatus(r: { status: string }): string {
  if (r.status === 'queued') return '等待执行'
  if (r.status === 'success') return '成功'
  if (r.status === 'failed') return '失败'
  return '运行中'
}

function statusTagType(s: string): 'success' | 'danger' | 'primary' | 'info' {
  if (s === 'queued') return 'info'
  if (s === 'success') return 'success'
  if (s === 'failed') return 'danger'
  return 'primary'
}


/** 策略列:SFTP 按文件增量;本地文件显示全量/增量 */
function strategyLabel(row: SyncTask): string {
  if (sourceTypeOf(row) === 'sftp') return '文件增量'
  return row.mode === 'full' ? '全量' : '增量'
}

function strategyTagType(row: SyncTask): 'primary' | 'warning' | 'success' {
  if (sourceTypeOf(row) === 'sftp') return 'success'
  return row.mode === 'full' ? 'primary' : 'warning'
}


/** 目标源列:分组/db/table */
function tgtLabel(row: SyncTask): string {
  let s = ''
  if (row.targetGroup) s += row.targetGroup + '/'
  s += `${row.targetDb}/${row.targetTable}`
  return s
}


type LastRunView = NonNullable<SyncTask['lastRun']>
/** 表格进度条百分比；成功固定为 100%，排队或尚无进度时保持 0%。 */
function runPercent(r: LastRunView): number {
  if (r.status === 'success') return 100
  if (r.status === 'queued') return 0
  const value = r.progress?.percent
  return value == null || !Number.isFinite(value) ? 0 : Math.round(Math.min(100, Math.max(0, value)))
}

/** 表格进度条旁的文字；总量未知的旧运行仍显示“进行中”，避免误报 0%。 */
function runPercentLabel(r: LastRunView): string {
  if (r.status === 'queued') return '等待'
  const value = r.progress?.percent
  if (value == null || !Number.isFinite(value)) return '进行中'
  return `${Math.round(value)}%`
}

/** 'YYYY-MM-DD HH:MM:SS'(后端 UTC)→ 毫秒;解析不了返回 null */
function toUtcMs(s?: string | null): number | null {
  if (!s) return null
  const m = s.match(/^(\d{4})-(\d{2})-(\d{2})[ T](\d{2}):(\d{2}):(\d{2})/)
  if (!m) return null
  const [, y, mo, d, h, mi, se] = m.map(Number)
  return Date.UTC(y, mo - 1, d, h, mi, se)
}


function durationText(sec: number): string {
  sec = Math.max(0, Math.floor(sec))
  const days = Math.floor(sec / 86400)
  const hours = Math.floor(sec % 86400 / 3600)
  const minutes = Math.floor(sec % 3600 / 60)
  return `${days ? `${days}天 ` : ''}${hours ? `${hours}小时 ` : ''}${minutes ? `${minutes}分 ` : ''}${sec % 60}秒`
}


/** 服务端计算耗时，避免用户电脑时区和时钟偏差；旧记录兼容开始/结束时间。 */
function runDuration(r: SyncRun): string {
  if (r.Status === 'queued') return '等待执行'
  if (r.DurationMs != null) return durationText(r.DurationMs / 1000)
  const start = toUtcMs(r.StartedAt)
  if (start == null) return '—'
  const end = toUtcMs(r.FinishedAt)
  if (end == null) return '运行中'
  return durationText((end - start) / 1000)
}

function runSpeed(r: SyncRun): string {
  if (r.Status === 'queued') return '—'
  if (!r.Progress) return '—'
  return `${r.Progress.averageRowsPerSecond.toLocaleString('zh-CN', { maximumFractionDigits: 1 })} 行/秒`
}

function runProgressText(r: SyncRun): string {
  return progressText(r.Progress, r.Status, r.RowsWritten)
}

/** 总量未知时仍展示确认行数；旧运行没有快照时明确说明，而不是隐藏整个进度区域。 */
function progressText(p: SyncRun['Progress'], status: string, rowsWritten = 0): string {
  if (status === 'queued') return '等待执行名额，空位释放后自动执行'
  if (!p) return status === 'running' ? `等待进度上报（已写入 ${rowsWritten.toLocaleString()} 行）`
    : `已写入 ${rowsWritten.toLocaleString()} 行（无详细进度记录）`
  const total = p.totalRows == null ? '总量未知' : `${p.estimated ? '约 ' : ''}${p.totalRows.toLocaleString()} 行`
  const percent = p.percent == null ? '' : `（${p.estimated ? '约 ' : ''}${p.percent.toFixed(1)}%）`
  return `已确认 ${p.confirmedRows.toLocaleString()} 行 / ${total}${percent}`
}

/** 快照采集也会在总数未知时累计行数；下载字节、文件和 Kafka 位点各用自己的单位。 */
function progressUnitsText(p: NonNullable<SyncRun['Progress']>): string {
  const label = p.unit === '字节' ? '已下载' : p.unit === '位点' ? '已扫描' : '已处理'
  const total = p.totalUnits == null ? '总量未知' : `${p.totalUnits.toLocaleString()} ${p.unit ?? ''}`
  return `${label} ${(p.completedUnits ?? 0).toLocaleString()} ${p.unit ?? ''} / ${total}`
}

function runEta(r: SyncRun): string {
  if (r.Status === 'queued') return '等待执行'
  if (r.Status === 'success') return '已完成'
  if (r.Status !== 'running') return '已停止'
  const seconds = r.Progress?.etaSeconds
  return seconds == null ? '暂不可估算' : `约 ${durationText(seconds)}`
}


onMounted(async () => {
  document.addEventListener('visibilitychange', onVisibilityChange)
  if (document.hidden) pausePollingForVisibility()
  startRunningDiscovery()
  void load().catch(() => { /* 拦截器已提示 */ })
  const [dss, conns, groups] = await Promise.all([
    dataSourcesApi.list().catch(() => []),
    loadConnections().catch(() => []),
    dsGroupsApi.list().catch(() => [])
  ])
  if (pollsDisposed) return
  // 与后端执行引擎支持的源类型保持一致。
  localDataSources.value = dss.filter(
    (d) =>
      d.type === 'csv' ||
      d.type === 'json' ||
      d.type === 'xlsx' ||
      d.type === 'sftp' ||
      d.type === 'ck_table' ||
      d.type === 'mysql' ||
      d.type === 'pgsql' ||
      d.type === 'clickhouse' ||
      d.type === 'mongodb' ||
      d.type === 'hive' ||
      d.type === 'kafka'
  )
  connections.value = conns
  dsGroups.value = groups as any
  // 从数据源页“去同步”跳转过来:预填该数据源并打开新增向导
  try { await prefillDataSourceFromQuery() } catch { /* 拦截器已提示，保留可重试的新增入口 */ }
})
</script>

<style scoped>
.baseline-lock-note { margin: 0 0 16px; padding: 10px 12px; background: var(--el-fill-color-light); color: var(--el-text-color-secondary); line-height: 1.7; font-size: 12px; border-radius: 6px; }
.baseline-lock-error { color: var(--el-color-danger); }
.incremental-contract { margin-left: 12px; color: var(--el-text-color-secondary); font-size: 12px; cursor: help; }
.incremental-contract-detail { max-width: 360px; line-height: 1.7; }
.config-section {
  padding: 0 0 2px;
  margin-bottom: 16px;
  min-width: 0;
}
.config-section-title {
  margin: 0 0 18px;
  color: var(--el-text-color-primary);
  font-size: 14px;
  font-weight: 600;
  line-height: 1.5;
}
.config-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 16px;
}
.config-grid > *, .config-inline > * { min-width: 0; }
.config-inline { display: flex; align-items: center; gap: 8px; width: 100%; }
.config-inline > .el-input { flex: 1; }
.config-inline > .el-button { flex-shrink: 0; }
.config-inline > .el-input-number { flex: 1; min-width: 90px; max-width: 150px; }
.config-section :deep(.el-form-item) { margin-bottom: 18px; }
.config-section :deep(.el-form-item__content) { min-width: 0; }
.config-section :deep(.el-select) { width: 100%; }
.field-unit { color: var(--el-text-color-secondary); font-size: 12px; white-space: nowrap; }
.fixed-mode { color: var(--el-text-color-regular); }
.source-fields-preview { margin: 0 0 18px 104px; font-size: 13px; }
.source-fields-preview summary { color: var(--el-text-color-secondary); cursor: pointer; }
.source-fields-preview .src-col-tags { margin-top: 10px; max-height: 112px; overflow-y: auto; }
.source-state { padding: 0 0 18px 104px; color: var(--el-text-color-secondary); font-size: 13px; }
.config-section > .el-alert { margin-bottom: 18px; }
@media (max-width: 760px) {
  .config-grid { grid-template-columns: minmax(0, 1fr); }
}
.date-policy-block { width: 100%; }
.date-mode-hint { margin-bottom: 16px; }
.date-pattern-suffix {
  color: var(--el-text-color-secondary);
  font-family: inherit;
  font-size: 12px;
  white-space: nowrap;
}
.date-preview-row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  width: 100%;
  min-width: 0;
}
.date-preview-row .tbl-hint {
  margin-top: 0;
  line-height: 20px;
}
.date-preview-list { width: 100%; }
.date-table-names {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 16px;
  padding: 8px 0 12px;
}
.sync-progress-summary {
  margin-top: 6px;
  font-size: 12px;
  line-height: 1.6;
  white-space: normal;
  overflow-wrap: anywhere;
}
 .sync-progress-summary .el-progress { margin-top: 5px;}
 /* 与数据源管理/连接管理页一致的页面骨架 */
.sync-tasks-view {
  height: 100%;
  display: flex;
  flex-direction: column;
  padding: 20px 24px;
  box-sizing: border-box;
  overflow: hidden;}

/* 表格占剩余高度并内部滚动,分页固定在底部 */
.st-table-wrap {
  flex: 1;
  min-height: 0;}

.st-table-wrap .st-table {
  height: 100%;}

/* 同步任务表:紧凑单行,弱化边框与斑马纹,用行悬停区分 */
.st-table :deep(.el-table__header-wrapper th.el-table__cell) {
  height: 40px;
  background: var(--el-fill-color-light);
  color: var(--el-text-color-secondary);
  font-weight: 600;}

.st-table :deep(.el-table__row td.el-table__cell) { padding: 7px 0;}
 .st-table :deep(.el-table__row:hover > td.el-table__cell) { background: var(--el-fill-color-lighter);}
 .task-name { font-weight: 500;}
 .flow-cell { display: flex; align-items: center; gap: 6px; min-width: 0;}
 .flow-type { flex: none;}
 .flow-src, .flow-tgt { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;}
 .flow-src { flex: 0 1 auto;}
 .flow-tgt { flex: 1 1 auto;}
 .flow-arrow { flex: none; color: var(--el-text-color-placeholder);}
 .meta-pair { display: inline-flex; align-items: center; gap: 4px; white-space: nowrap;}
 .meta-pair :deep(.el-tag) { margin: 0;}
 .lastrun-cell { display: inline-flex; align-items: center; gap: 6px; min-width: 0; white-space: nowrap;}
 .status-dot { flex: none; width: 6px; height: 6px; border-radius: 50%; background: var(--el-text-color-placeholder);}
 .status-dot.dot-running { background: var(--el-color-primary); box-shadow: 0 0 0 3px rgba(64, 158, 255, 0.16);}
.status-dot.dot-queued { background: var(--el-color-info); }
.status-dot.dot-success { background: var(--el-color-success); }
 .status-dot.dot-failed { background: var(--el-color-danger);}
 .lastrun-label { font-size: 13px;}
 .lastrun-time { overflow: hidden; text-overflow: ellipsis; font-size: 12px; color: var(--el-text-color-secondary);}
 .resume-mini { margin-left: 2px; transform: scale(0.9); transform-origin: left center;}
 .prog-cell { display: flex; align-items: center; gap: 8px; min-width: 0;}
.prog-cell { cursor: pointer; border-radius: 4px; }
.prog-cell:focus-visible { outline: 2px solid var(--el-color-primary-light-5); outline-offset: 2px; }
.prog-cell :deep(.el-progress) { flex: 1; min-width: 0; }
 .prog-label { flex: none; font-size: 12px; color: var(--el-text-color-secondary); font-variant-numeric: tabular-nums;}
 .prog-done { font-size: 12px; color: var(--el-color-success);}
 .prog-failed { font-size: 12px; color: var(--el-color-danger);}
 .page-head {
  flex: none;
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;}

.page-head h2 {
  margin: 0 0 4px;
  font-size: 18px;}


.page-sub {
  margin: 0;
  font-size: 13px;
  color: var(--el-text-color-secondary);}

.actions {
  display: flex;
  gap: 8px;}

.mono {
  font-family: var(--font-mono);
  font-size: 12.5px;}

.ds-src-cell { display: flex; align-items: center; gap: 6px; width: 100%; max-width: 100%; overflow: hidden;}
 .ds-src-cell .ds-src-tag { flex: none;}
 .mono12 { font-size: 12px;}
 .mono13 { font-size: 13px;}
 /* 单元格文本:不换行,放得下完整显示,溢出才省略;省略以列(单元格)宽度为准 */
.cell-ellipsis {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;}

.ds-src-cell > * { min-width: 0;}
 .go-link {
  border: 0;
  padding: 0;
  background: transparent;
  font: inherit;
  text-align: left;
  color: var(--el-color-primary);
  cursor: pointer;}

.go-link:hover { text-decoration: underline;}
 /* 数据源列:分组/标识(连接色) 与 SFTP 远程路径/匹配(绿色强调)区分 */
.src-head {
  color: var(--el-color-primary);}

.src-path {
  color: #0d9488; /* teal-600:与“组/标识”区分 */
  font-weight: 500;}

.run-status-inline { display: inline-flex; align-items: center; white-space: nowrap;}
 .run-hover { line-height: 1.7; max-width: 360px;}
 .run-hover-line { font-size: 12px; color: var(--el-text-color-regular); word-break: break-word;}
 .run-hover-line b { color: var(--el-text-color-secondary); font-weight: 600; margin-right: 6px;}
 .run-hover-line.err { color: var(--el-color-danger);}
 .ml6 { margin-left: 6px;}
 .op-arrow { margin-left: 2px;}
 .cron-cell { font-size: 12px; color: var(--el-text-color-regular); margin-left: 6px;}
 .cron-row { display: flex; align-items: center; gap: 8px; width: 100%;}
 .cron-row .el-input { flex: 1;}
 .cron-hint { font-size: 12px; color: var(--el-text-color-secondary); white-space: nowrap;}
 .cron-gen { display: flex; flex-direction: column; gap: 8px; width: 100%;}
 .cron-gen-row { display: flex; align-items: center; gap: 8px; flex-wrap: wrap;}
 .cron-gen-preview { display: flex; align-items: center; gap: 8px;}
 .cron-preview { font-size: 13px; font-weight: 600; color: var(--brand); background: var(--fill-soft); padding: 2px 8px; border-radius: 4px;}
 .cron-gen-adv { display: flex; align-items: center; gap: 8px;}
 .sched-divider { margin: 6px 0 10px;}
 .conn-hint {
  font-size: 12.5px;
  color: var(--el-text-color-secondary);
  margin: -2px 0 10px;
  padding: 6px 10px;
  background: var(--el-fill-color);
  border-radius: 6px;}

.tbl-row {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 4px;
  width: 100%;}

.tbl-hint {
  font-size: 11.5px;
  color: var(--el-text-color-placeholder);
  line-height: 1.6;
  margin-top: 2px;}

.opt-desc {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  font-weight: 400;}

.lastrun-time { font-size: 12px; color: var(--el-text-color-regular); margin-left: 6px;}
 .lastrun-rows { font-size: 11.5px; color: var(--el-text-color-secondary); margin-top: 2px;}
 .lastrun-err { font-size: 11.5px; color: var(--el-color-danger); margin-top: 2px; max-width: 240px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;}
 .src-meta { margin: -4px 0 12px 90px; display: flex; align-items: flex-start; gap: 6px;}
 .sftp-groups { margin: 2px 0 10px; width: 100%; min-width: 0;}
 .sftp-mode-hint { margin-bottom: 14px;}
 .sftp-mode-line { line-height: 1.7; font-size: 13px;}
 .sftp-mode-line b { color: var(--el-text-color-primary);}
 .sftp-group-pager { margin-top: 8px;}
 .sftp-group-pager :deep(.el-pagination) { --el-pagination-button-bg-color: var(--fill-soft);}
 .sftp-rule { display: flex; align-items: center; gap: 6px; margin: 0 0 8px 90px;}
 .sftp-rule .src-meta-label { flex-shrink: 0; font-size: 12px; color: var(--el-text-color-secondary);}
 .sftp-groups-hint { font-size: 12px; color: var(--el-text-color-secondary); margin-bottom: 6px;}
 .sftp-groups :deep(.el-radio-group) { display: flex; flex-direction: column; gap: 8px; width: 100%; min-width: 0;}
 .sftp-group-opt { margin-right: 0; height: auto; width: 100%; min-width: 0;}
 .sftp-group-opt :deep(.el-radio__label) { flex: 1; min-width: 0; overflow: hidden;}
 .sftp-group-name { display: flex; align-items: center; gap: 8px; min-width: 0; font-size: 12px;}
 .sftp-group-name :deep(.overflow-text) { flex: 1;}
 .sftp-group-count, .sftp-group-name :deep(.el-tag) { flex-shrink: 0;}
 .sftp-group-count { color: var(--el-text-color-secondary);}
 .src-col-tag { max-width: 240px;}
 .src-col-tag :deep(.el-tag__content) { min-width: 0; overflow: hidden;}
 .src-meta-label { flex-shrink: 0; font-size: 12px; color: var(--el-text-color-secondary); line-height: 22px;}
 .src-col-tags { display: flex; flex-wrap: wrap; gap: 4px; max-height: 132px; overflow: auto;}
 .ds-opt { display: flex; align-items: center; gap: 6px;}
 .ds-opt-tag { margin-right: 4px; flex-shrink: 0;}
 .auto-create-hint {
  display: block;
  margin-top: 4px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.4;}

.map-table { margin: 10px 0 8px;}
 .mapping-errors { margin: 10px 0;}
 .mapping-field-error { margin-top: 5px; color: var(--el-color-danger); font-size: 12px; line-height: 1.5; white-space: normal;}
 .mapping-type-label { margin-top: 4px; color: var(--el-text-color-secondary); font-size: 12px; line-height: 1.4;}
 .mapping-type-warning { margin-top: 5px; color: var(--el-color-warning-dark-2); font-size: 12px; line-height: 1.5; white-space: normal;}
 .mapping-target-cell.is-invalid :deep(.el-input__wrapper),
.mapping-target-cell.is-invalid :deep(.el-select__wrapper) { box-shadow: 0 0 0 1px var(--el-color-danger) inset;}
 /*
  统一行高与垂直居中。同一行里既有输入框、下拉框(源列还带个标签),
  也有开关(矮)和按钮 —— 不锁行高时每行高度都不一样,一句话就是"不整齐"。
  tr 上的 height 是"最小高度",内容高过它才会撇开,所以这里的 46px 不会压到控件。
*/
.map-table :deep(.el-table__row) { height: 42px;}
 .map-table :deep(td.el-table__cell) { vertical-align: middle; padding: 6px 0;}
 /* 表头高度跟随全站标准(theme.css 的 .el-table th.el-table__cell),这里不再单独覆盖 */
.map-table :deep(.ods-auto-row > td) {
  background: var(--el-fill-color-lighter) !important;}

.map-table :deep(.ods-auto-row .cell) {
  color: var(--el-text-color-secondary);}

/* 手机号行高亮标识 */
.map-table :deep(.phone-row > td) {
  background: var(--el-color-danger-light-9) !important;}

.src-cell { display: flex; align-items: center; gap: 4px; flex: 1; min-width: 0;}
 .src-cell .src-phone-tag { flex: none;}
 .map-phone-sel {
  display: flex; align-items: center; gap: 8px; flex-wrap: wrap;
  margin-bottom: 10px; padding: 8px 10px;
  background: var(--el-fill-color-lighter); border-radius: 6px;}

.map-phone-label { font-weight: 600; font-size: 13px;}
 .map-phone-lock { flex: none;}
 .map-phone-hint { flex: 1 1 100%; font-size: 12px; line-height: 1.5; color: var(--el-text-color-secondary);}
 .wizard-steps { margin-bottom: 22px; margin-top: 4px;}
 .wizard-steps :deep(.el-step__head),
.wizard-steps :deep(.el-step__main) { cursor: default;}
.wizard-step-button { padding: 2px 8px; border: 0; border-radius: 4px; background: transparent; color: inherit; font: inherit; cursor: pointer; }
.wizard-step-button:disabled { cursor: wait; opacity: .6; }
.go-link:focus-visible, .wizard-step-button:focus-visible { outline: 2px solid var(--el-color-primary); outline-offset: 2px; border-radius: 3px; }
.target-fill-note :deep(.el-alert__content) { min-width: 0; flex: 1; }
.target-fill-group { display: flex; flex-wrap: wrap; align-items: baseline; gap: 4px; min-width: 0; margin-top: 4px; }
.target-fill-group :deep(.overflow-text) { flex: 1 1 180px; }
.sr-only { position: absolute; width: 1px; height: 1px; padding: 0; margin: -1px; overflow: hidden; clip: rect(0, 0, 0, 0); clip-path: inset(50%); white-space: nowrap; border: 0; }
/* 编辑任务使用右侧抽屉，长表单由抽屉正文滚动，底部操作区保持可见。 */
.wizard-drawer :deep(.el-drawer__body) { padding-top: 8px; }

.map-actions { margin-bottom: 4px;}
 .tbl-config { margin-bottom: 10px;}
 /*
  el-collapse 的默认样式直接用在表单里会“空一大截”:内容区 padding-bottom:25px、
  左右却是 0(而表头有 padding-right:8px)、下面还多一条分割线 —— 于是看起来
  上面松、不对齐、末尾多根线。这里按表单的节奏收一下。
*/
/*
  折叠面板的高度/内边距已在全局主题(assets/theme.css)统一处理 —— 这里只留本页的口味:
  标题小一号加粗、去掉上下两条分割线(在弹窗表单里那两条线是多余的)。
*/
.tbl-config :deep(.el-collapse-item__header) {
  font-size: 13px;
  font-weight: 600;
  border-bottom: none;}

.tbl-config :deep(.el-collapse-item__wrap) { border-bottom: none;}
 /*
  三列等宽自适应,标签放在控件上方。
  之前这里用的是 el-form-item,继承了表单全局的 label-width:104px —— 在 200px 宽的列里
  下拉框只剩 90px,文字被截、tags 四处乱挤,这就是“排版有问题”的主因。
*/
.tbl-config-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(260px, 1fr));
  gap: 10px 14px;
  margin-bottom: 8px;}

.tblcfg-item { min-width: 0;}
 .tblcfg-label {
  font-size: 13px;
  font-weight: 600;
  line-height: 20px;
  margin-bottom: 4px;
  color: var(--el-text-color-regular);}

.tblcfg-hint { font-size: 12px; line-height: 1.6; color: var(--el-text-color-secondary);}
 /* ── 执行日志抽屉 ── */
/* 抽屉主体不再整块滚动，查询栏和底部分页固定，滚动只发生在表体内部。 */
:global(.sync-run-log-body) {
  display: flex;
  flex-direction: column;
  min-height: 0;
  overflow: hidden;
}
.run-log {
  flex: 1;
  height: 100%;
  min-height: 0;
  min-width: 0;
}
.log-filter-label {
  color: var(--el-text-color-secondary);
  font-size: 13px;
  white-space: nowrap;
}
.log-filter { width: 132px; }
.log-table {
  width: 100%;
  /* Keep fixed columns opaque so scrolling content cannot show through. */
  --el-table-tr-bg-color: var(--bg-panel);
  --el-table-row-hover-bg-color: var(--el-fill-color-light);
}

.log-detail { display: flex; flex-direction: column; gap: 6px; max-height: 72vh; overflow-y: auto; overflow-wrap: anywhere; padding: 4px 14px 10px; font-size: 12px; color: var(--el-text-color-regular);}
 .log-detail > div { display: flex; align-items: baseline; gap: 8px; flex-wrap: wrap;}
 .log-detail b { color: var(--el-text-color-secondary); font-weight: 600; flex: none;}
 .log-detail-gap { margin-left: 14px;}
 .log-error { margin: 0; flex: 1; white-space: pre-wrap; word-break: break-all; color: var(--el-color-danger); font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 12px;}
 .log-cell-err { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--el-color-danger);}
 .log-cell-note { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--el-text-color-regular);}
 .log-resume-tag { margin-left: 4px;}
 .run-intr-hint { margin-top: 6px; font-size: 12px; color: var(--el-color-warning); background: rgba(234,179,8,0.08); border: 1px solid rgba(234,179,8,0.25); border-radius: 6px; padding: 6px 8px; line-height: 1.6;}
 .intr-banner { display: flex; align-items: flex-start; gap: 10px; padding: 10px 12px; margin-bottom: 14px; background: rgba(234,179,8,0.08); border: 1px solid rgba(234,179,8,0.3); border-radius: 8px;}
 .intr-ico { color: var(--el-color-warning); font-size: 18px; margin-top: 1px; flex: none;}
 .intr-txt { flex: 1; font-size: 13px; color: var(--el-text-color-regular); line-height: 1.6;}
 .intr-txt b { color: var(--el-text-color-primary);}
 .intr-sub { font-size: 12px; color: var(--el-text-color-secondary);}
 .intr-actions { display: flex; align-items: center; justify-content: flex-end; flex-wrap: wrap; gap: 8px; flex: none;}
 .sync-recovery-actions { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 8px;}
 .sync-recovery-actions .el-button + .el-button { margin-left: 0;}

/* ── 宽表口径检查弹窗 ── */
.wide-loading {
  padding: 24px;
  text-align: center;
  color: var(--el-text-color-secondary);}

.wide-sec {
  margin-bottom: 16px;}

.wide-sec > b {
  display: block;
  margin-bottom: 6px;}

.wide-err {
  color: var(--el-color-danger);}

.wide-dim {
  color: var(--el-text-color-secondary);
  font-size: 12px;}

.wide-srcs {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;}

/* 冲突条目:红底(必须改) / 黄底(提示) —— 用底色分开,扫一眼就知道哪条要动手 */
.wide-item {
  padding: 8px 10px;
  border-radius: 4px;
  margin-bottom: 6px;
  font-size: 13px;
  line-height: 1.6;}

.wide-item-err {
  background: var(--el-color-danger-light-9);
  border-left: 3px solid var(--el-color-danger);}

.wide-item-warn {
  background: var(--el-color-warning-light-9);
  border-left: 3px solid var(--el-color-warning);}

.wide-item-fix {
  color: var(--el-color-primary);
  margin-top: 2px;}


/* 分区键选错是「表写不进去 + 改不了」的级别,所以用红字当场提示 */
.tblcfg-danger {
  margin-bottom: 4px;
  color: var(--el-color-danger);
  font-size: 12px;
  line-height: 1.6;}


/* 表已存在时的只读展示(分区/排序/主键) */
.tblcfg-value {
  min-height: 24px;
  padding: 2px 0;
  font-size: 13px;
  line-height: 1.5;
  color: var(--el-text-color-primary);
  word-break: break-all;}

</style>
