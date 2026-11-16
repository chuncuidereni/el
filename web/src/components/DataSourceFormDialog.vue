<!--
  DataSourceFormDialog.vue

  职责：数据源新增/编辑弹窗：按来源类型渲染动态字段并维护表单校验。
  边界：按 type 渲染本地文件、数据库或大数据源步骤；上传、结构检测、连接测试与保存彼此分离，编辑时留空密码表示保留原值。
-->

<template>
  <el-dialog
    :model-value="modelValue"
    :title="dialogTitle"
    width="720px"
    :close-on-click-modal="false"
    @update:model-value="(v: boolean) => emit('update:modelValue', v)"
  >
    <!-- ============ 第一步:选择类型(仅新增时) ============ -->
    <div v-if="!isEdit && step === 1" class="type-pick">
      <div class="type-pick-hint">请选择数据源类型</div>
      <p>创建后自动绑定当前登录用户，仅该用户可管理和用于同步任务。</p>
      <!-- 这一步只暂存 pickedType,真正写进 form.type 要等点「下一步」,留出反复改选的余地 -->
      <div class="type-grid">
        <div
          v-for="t in TYPE_OPTIONS"
          :key="t.value"
          class="type-card"
          :class="{ selected: pickedType === t.value, disabled: t.disabled }"
          :aria-disabled="t.disabled ? true : undefined"
          @click="pickType(t.value)"
        >
          <div class="type-icon">{{ t.icon }}</div>
          <div class="type-label">{{ t.label }}</div>
          <div class="type-desc">{{ t.desc }}</div>
        </div>
      </div>
    </div>

    <!-- ============ 第二步:基本信息 + 上传/连接配置 ============ -->
    <template v-else-if="!isEdit && step === 2">
      <div class="step-head">
        <el-tag :type="typeTag(form.type)" effect="plain">{{ typeLabel(form.type) }}</el-tag>
        <!-- 回退只切步号、不清 config;类型的真正切换发生在 goStep2 重建 config 时 -->
        <el-button link size="small" @click="backToStep1">重选类型</el-button>
      </div>

      <el-form ref="formRef" :model="form" :rules="rules" label-width="100px">
        <el-form-item label="唯一标识" prop="code">
          <el-input v-model="form.code" placeholder="字母/数字/下划线,如 orders_csv" />
        </el-form-item>
        <el-form-item label="名称" prop="name">
          <el-input v-model="form.name" placeholder="显示名称" />
        </el-form-item>
        <el-form-item label="所属分组">
          <el-select v-model="form.groupId" style="width: 100%" placeholder="不选=默认分组">
            <el-option :value="0" label="默认分组" />
            <el-option v-for="g in props.groups ?? []" :key="g.id ?? 0" :value="g.id ?? 0" :label="g.name" />
            <template #footer>
              <!-- 下拉底部就地新建:用户不必先中断录入跳去分组管理页 -->
              <div class="grp-quick">
                <el-input
                  v-model="newGroupName"
                  size="small"
                  placeholder="新分组名,回车直接创建"
                  clearable
                  @keyup.enter="quickCreateGroup"
                />
                <el-button
                  size="small"
                  type="primary"
                  plain
                  :loading="newGroupSaving"
                  :disabled="!newGroupName.trim()"
                  @click="quickCreateGroup"
                >新建</el-button>
              </div>
            </template>
          </el-select>
          <div class="grp-tip">若分组不存在,可在此下拉底部直接新建</div>
        </el-form-item>

        <!-- CSV / JSON:上传文件 -->
        <template v-if="isLocalFile">
          <el-form-item label="上传文件" prop="config.path" required>
            <div class="upload-area">
              <!-- path 不手填:文件先落服务端存储,再把返回的存储路径写进 config.path -->
              <el-upload
                :show-file-list="false"
                :http-request="doUploadFile"
                :accept="uploadAccept"
                :disabled="uploading"
              >
                <el-button :icon="UploadFilled" :loading="uploading">
                  {{ uploading ? '上传中…' : uploadBtnText }}
                </el-button>
              </el-upload>
              <div v-if="uploadedName" class="uploaded-info">
                <el-icon class="uploaded-icon"><Document /></el-icon>
                <span class="uploaded-name" :title="uploadedName">{{ uploadedName }}</span>
                <el-button link type="danger" size="small" @click="clearUpload">清除</el-button>
              </div>
              <div class="upload-hint">{{ uploadHint }}</div>
            </div>
          </el-form-item>
        </template>

        <!-- SFTP -->
        <template v-else-if="form.type === 'sftp'">
          <el-form-item label="主机" prop="config.host" required>
            <el-input v-model="form.config.host" placeholder="SFTP 服务器地址" />
          </el-form-item>
          <el-form-item label="端口" prop="config.port" required>
            <el-input-number v-model="portNum" :min="1" :max="65535" style="width: 100%" />
          </el-form-item>
          <el-form-item label="用户名" prop="config.username" required>
            <el-input v-model="form.config.username" placeholder="SSH 用户名" />
          </el-form-item>
          <!-- 编辑态且源已有密码:留空即表示保持原密码,故去掉必填星号,校验规则里同步放开 -->
          <el-form-item label="密码" prop="config.password" :required="!pwdKeep">
            <el-input v-model="form.config.password" type="password" show-password :placeholder="pwdPlaceholder" />
          </el-form-item>
        </template>

        <!-- 库内表:源库+源表,复用目标连接 -->
        <template v-else-if="form.type === 'ck_table'">
          <el-form-item label="源数据库" prop="config.database" required>
            <el-input v-model="form.config.database" placeholder="如 mydb" />
          </el-form-item>
          <el-form-item label="源表" prop="config.table" required>
            <el-input v-model="form.config.table" placeholder="源表名(如 customer)" />
          </el-form-item>
          <div class="conn-hint">库内表源不保存连接凭据:同步时使用该任务选定的目标连接读取此源表(源/目标同一库)。</div>
        </template>

        <!-- MySQL / PgSQL / 列式数据库 -->
        <template v-else-if="isSQLType">
          <ClickHouseSourceFields v-if="form.type === 'clickhouse'" :config="form.config" />
          <el-form-item label="主机" prop="config.host" required>
            <el-input v-model="form.config.host" placeholder="数据库地址" />
          </el-form-item>
          <el-form-item label="端口" prop="config.port" required>
            <el-input-number v-model="portNum" :min="1" :max="65535" style="width: 100%" />
          </el-form-item>
          <el-form-item label="用户名" prop="config.username" required>
            <el-input v-model="form.config.username" :placeholder="defaultUser" />
          </el-form-item>
          <el-form-item label="密码" prop="config.password" :required="!pwdKeep">
            <el-input v-model="form.config.password" type="password" show-password :placeholder="pwdPlaceholder" />
          </el-form-item>
          <el-form-item label="数据库" prop="config.database" required>
            <el-input v-model="form.config.database" placeholder="数据库名" />
          </el-form-item>
          <el-form-item label="表名">
            <el-input v-model="form.config.table" placeholder="可选,默认表(对象检查时也可单独输入)" />
          </el-form-item>
        </template>

        <!-- MongoDB -->
        <template v-else-if="form.type === 'mongodb'">
          <el-form-item label="主机" prop="config.host" required>
            <el-input v-model="form.config.host" placeholder="MongoDB 地址,也可填完整 mongodb:// 连接串" />
          </el-form-item>
          <el-form-item label="端口">
            <el-input-number v-model="portNum" :min="1" :max="65535" style="width: 100%" />
          </el-form-item>
          <el-form-item label="用户名">
            <el-input v-model="form.config.username" placeholder="可选(无认证场景)" />
          </el-form-item>
          <el-form-item label="密码">
            <el-input v-model="form.config.password" type="password" show-password placeholder="可选(无认证场景)" />
          </el-form-item>
          <el-form-item label="数据库" prop="config.database" required>
            <el-input v-model="form.config.database" placeholder="数据库名" />
          </el-form-item>
          <el-form-item label="认证数据库">
            <el-input v-model="form.config.authSource" placeholder="默认 admin；填写创建该用户的数据库" />
            <div class="form-tip">认证库可以与业务数据库不同。填写完整连接串时，请在连接串中设置 authSource。</div>
          </el-form-item>
          <el-form-item label="集合">
            <el-input v-model="form.config.collection" placeholder="可选,默认集合" />
          </el-form-item>
        </template>

        <el-form-item label="描述">
          <el-input v-model="form.description" type="textarea" :rows="2" placeholder="可选" />
        </el-form-item>
        <BigDataSourceFields v-if="form.type === 'kafka' || form.type === 'hive'" :type="form.type" :config="form.config" :keep-password="pwdKeep" />
      </el-form>
    </template>

    <!-- ============ 第三步(本地文件):映射与预览 ============ -->
    <template v-else-if="!isEdit && step === 3">
      <div class="step-head">
        <el-tag :type="typeTag(form.type)" effect="plain">{{ typeLabel(form.type) }} · 映射与预览</el-tag>
        <el-button link size="small" @click="step = 2">上一步</el-button>
      </div>

      <div class="preview-config" v-loading="previewLoading">
        <!-- 只有用户手动切过行头开关才回传明确值,否则让后端自动探测(见 loadPreview 的 headerParam) -->
        <span class="pc-label">行头</span>
        <el-switch v-model="previewHeader" :disabled="form.type === 'json'" @change="onHeaderToggle" />
        <span v-if="form.type === 'json'" class="pc-hint-inline">JSON 自带字段名,无需行头</span>
        <template v-if="form.type === 'csv' && previewData">
          <span class="pc-label">字段</span>
          <div class="pc-fields-bar">
            <el-button size="small" type="primary" plain :icon="Setting" @click="openFieldCfg">字段配置</el-button>
            <span class="pc-fields-stat">已保留 {{ keptCount }}/{{ previewNames.length }} 列
              <span v-if="duplicateNameIdx.size>0" class="dup-warn">· 字段名有重复</span>
            </span>
            <span class="pc-fields-hint">在弹出的框中勾选保留列、重命名字段</span>
          </div>
          <div class="pc-phone-bar">
            <!-- 手机号列既是同步主键、也是号码脱敏形态的依据;此处只记录标识,不做加解密 -->
            <span class="pc-label">手机号</span>
            <el-select v-model="phoneField" placeholder="选择手机号列(可选)" clearable size="small" class="pc-phone-col">
              <el-option v-for="c in phoneColOptions" :key="c" :value="c" :label="c" />
            </el-select>
            <el-select v-model="phoneType" size="small" class="pc-phone-type">
              <el-option v-for="o in PHONE_TYPE_OPTIONS" :key="o.value" :value="o.value" :label="o.label" />
            </el-select>
            <span class="pc-hint-inline">选哪列是手机号(主键)及其展示形态(明文/是否被算法加工),只标识不处理</span>
          </div>
        </template>
      </div>

      <div v-if="previewData" class="preview-meta">
        <el-tag size="small" effect="plain">预览 {{ previewData.rows.rows.length }} 条</el-tag>
        <el-tag size="small" type="success" effect="plain">共 {{ previewData.total }} 行</el-tag>
        <el-tag size="small" effect="plain">{{ previewData.columns.length }} 列</el-tag>
        <span class="preview-hint">{{ form.type === 'csv' ? '可在上方修改字段名后点「应用」' : '字段名来自 JSON 的 key' }}</span>
      </div>
      <!-- 行数据不随列配置重建:模板按索引回查 rows,故这里只需一个行数 -->
      <el-table
        v-if="previewData"
        :data="previewTableRows"
        border
        size="small"
        max-height="320"
        class="wizard-preview-table"
      >
        <el-table-column type="index" label="序号" width="60" align="center" />
        <el-table-column v-for="(col, ci) in keptPreviewCols" :key="col.idx" min-width="120">
          <template #header>
            <div class="preview-col-head">
              <span class="preview-col-name">{{ col.name }}</span>
              <span class="preview-col-type">{{ col.type }}</span>
            </div>
          </template>
          <!-- 用 col.idx 取原始列号:剔除/重命名都会改变展示顺序,唯原始下标能定位到正确单元格 -->
          <template #default="{ $index }">
            <span class="mono" :title="previewData.rows.rows[$index]?.[col.idx] ?? ''">{{ previewData.rows.rows[$index]?.[col.idx] ?? '' }}</span>
          </template>
        </el-table-column>
      </el-table>
      <div v-if="previewError" class="preview-error">{{ previewError }}</div>
    </template>

    <!-- ============ 编辑模式:直接表单 ============ -->
    <template v-else>
      <el-form ref="formRef" :model="form" :rules="rules" label-width="100px">
        <!-- 标识是数据源对外的引用键,编辑期不可改,否则已建的同步任务会失去指向 -->
        <el-form-item label="唯一标识" prop="code">
          <el-input v-model="form.code" placeholder="字母/数字/下划线" disabled />
        </el-form-item>
        <el-form-item label="名称" prop="name">
          <el-input v-model="form.name" placeholder="显示名称" />
        </el-form-item>
        <el-form-item label="所属分组">
          <el-select v-model="form.groupId" style="width: 100%" placeholder="不选=默认分组">
            <el-option :value="0" label="默认分组" />
            <el-option v-for="g in props.groups ?? []" :key="g.id ?? 0" :value="g.id ?? 0" :label="g.name" />
            <template #footer>
              <!-- 下拉底部就地新建:用户不必先中断录入跳去分组管理页 -->
              <div class="grp-quick">
                <el-input
                  v-model="newGroupName"
                  size="small"
                  placeholder="新分组名,回车直接创建"
                  clearable
                  @keyup.enter="quickCreateGroup"
                />
                <el-button
                  size="small"
                  type="primary"
                  plain
                  :loading="newGroupSaving"
                  :disabled="!newGroupName.trim()"
                  @click="quickCreateGroup"
                >新建</el-button>
              </div>
            </template>
          </el-select>
          <div class="grp-tip">若分组不存在,可在此下拉底部直接新建</div>
        </el-form-item>

        <!-- CSV / JSON -->
        <template v-if="isLocalFile">
          <el-form-item label="已上传文件">
            <div class="upload-area">
              <div v-if="uploadedName" class="uploaded-info">
                <el-icon class="uploaded-icon"><Document /></el-icon>
                <span class="uploaded-name" :title="uploadedName">{{ uploadedName }}</span>
              </div>
              <el-upload :show-file-list="false" :http-request="doUploadFile" :accept="uploadAccept" :disabled="uploading">
                <el-button size="small" :icon="UploadFilled" :loading="uploading">重新上传替换</el-button>
              </el-upload>
              <div class="upload-hint">重新上传新的预览</div>
            </div>
          </el-form-item>

          <!-- 字段映射与预览(编辑本地文件时) -->
          <el-form-item v-if="form.type === 'csv'" label="行头">
            <el-switch v-model="previewHeader" @change="onHeaderToggle" />
          </el-form-item>
          <el-form-item v-if="form.type === 'csv' && previewData" label="字段">
            <div class="pc-edit-col">
              <div class="pc-fields-bar">
                <el-button size="small" type="primary" plain :icon="Setting" @click="openFieldCfg">字段配置</el-button>
                <span class="pc-fields-stat">已保留 {{ keptCount }}/{{ previewNames.length }} 列
                  <span v-if="duplicateNameIdx.size>0" class="dup-warn">· 字段名有重复</span>
                </span>
              </div>
              <div class="pc-fields-hint">弹出框中勾选保留列、重命名字段</div>
            </div>
          </el-form-item>
          <el-form-item v-if="previewData" label="预览">
            <div class="edit-preview-wrap">
              <div class="preview-meta">
                <el-tag size="small" effect="plain">预览 {{ previewData.rows.rows.length }} 条</el-tag>
                <el-tag size="small" type="success" effect="plain">共 {{ previewData.total }} 行</el-tag>
                <el-tag size="small" effect="plain">{{ previewData.columns.length }} 列</el-tag>
              </div>
              <el-table :data="previewTableRows" border size="small" max-height="260" class="wizard-preview-table">
                <el-table-column type="index" label="序号" width="60" align="center" />
                <!-- 编辑态预览刻意不过滤保留列:与原始文件同序展示,便于逐列核对映射是否错位 -->
                <el-table-column v-for="(col, ci) in previewData.columns" :key="ci" min-width="120">
                  <template #header>
                    <div class="preview-col-head">
                      <span class="preview-col-name">{{ col.name }}</span>
                      <span class="preview-col-type">{{ col.type }}</span>
                    </div>
                  </template>
                  <template #default="{ $index }">
                    <span class="mono" :title="previewData.rows.rows[$index]?.[ci] ?? ''">{{ previewData.rows.rows[$index]?.[ci] ?? '' }}</span>
                  </template>
                </el-table-column>
              </el-table>
            </div>
          </el-form-item>
        </template>

        <!-- 库内表(编辑) -->
        <template v-else-if="form.type === 'ck_table'">
          <el-form-item label="源数据库" prop="config.database" required>
            <el-input v-model="form.config.database" placeholder="如 mydb" />
          </el-form-item>
          <el-form-item label="源表" prop="config.table" required>
            <el-input v-model="form.config.table" placeholder="源表名" />
          </el-form-item>
        </template>

        <!-- SFTP -->
        <template v-else-if="form.type === 'sftp'">
          <el-form-item label="主机" prop="config.host" required>
            <el-input v-model="form.config.host" />
          </el-form-item>
          <el-form-item label="端口" prop="config.port" required>
            <el-input-number v-model="portNum" :min="1" :max="65535" style="width: 100%" />
          </el-form-item>
          <el-form-item label="用户名" prop="config.username" required>
            <el-input v-model="form.config.username" />
          </el-form-item>
          <el-form-item label="密码" prop="config.password" :required="!pwdKeep">
            <el-input v-model="form.config.password" type="password" show-password :placeholder="pwdPlaceholder" />
          </el-form-item>
        </template>

        <!-- SQL 系 -->
        <template v-else-if="isSQLType">
          <ClickHouseSourceFields v-if="form.type === 'clickhouse'" :config="form.config" />
          <el-form-item label="主机" prop="config.host" required>
            <el-input v-model="form.config.host" />
          </el-form-item>
          <el-form-item label="端口" prop="config.port" required>
            <el-input-number v-model="portNum" :min="1" :max="65535" style="width: 100%" />
          </el-form-item>
          <el-form-item label="用户名" prop="config.username" required>
            <el-input v-model="form.config.username" />
          </el-form-item>
          <el-form-item label="密码" prop="config.password" :required="!pwdKeep">
            <el-input v-model="form.config.password" type="password" show-password :placeholder="pwdPlaceholder" />
          </el-form-item>
          <el-form-item label="数据库" prop="config.database" required>
            <el-input v-model="form.config.database" />
          </el-form-item>
          <el-form-item label="表名">
            <el-input v-model="form.config.table" />
          </el-form-item>
        </template>

        <!-- MongoDB -->
        <template v-else-if="form.type === 'mongodb'">
          <el-form-item label="主机" prop="config.host" required>
            <el-input v-model="form.config.host" />
          </el-form-item>
          <el-form-item label="端口">
            <el-input-number v-model="portNum" :min="1" :max="65535" style="width: 100%" />
          </el-form-item>
          <el-form-item label="用户名">
            <el-input v-model="form.config.username" />
          </el-form-item>
          <el-form-item label="密码">
            <el-input v-model="form.config.password" type="password" show-password placeholder="可选(无认证)" />
          </el-form-item>
          <el-form-item label="数据库" prop="config.database" required>
            <el-input v-model="form.config.database" />
          </el-form-item>
          <el-form-item label="认证数据库">
            <el-input v-model="form.config.authSource" placeholder="默认 admin；填写创建该用户的数据库" />
            <div class="form-tip">认证库可以与业务数据库不同。填写完整连接串时，请在连接串中设置 authSource。</div>
          </el-form-item>
          <el-form-item label="集合">
            <el-input v-model="form.config.collection" />
          </el-form-item>
        </template>

        <el-form-item label="描述">
          <el-input v-model="form.description" type="textarea" :rows="2" />
        </el-form-item>
        <BigDataSourceFields v-if="form.type === 'kafka' || form.type === 'hive'" :type="form.type" :config="form.config" :keep-password="pwdKeep" />
      </el-form>
    </template>

    <template #footer>
      <!-- Step1 -->
      <template v-if="!isEdit && step === 1">
        <el-button @click="emit('update:modelValue', false)">取消</el-button>
        <!-- 未选类型就没有 form.type,默认端口、默认用户名都无从计算,故禁用 -->
        <el-button type="primary" :disabled="!pickedType" @click="goStep2">下一步</el-button>
      </template>
      <!-- Step2 -->
      <template v-else-if="!isEdit && step === 2">
        <!-- 本地文件无远端连接可测;库内表复用任务的连接,故这两类不提供通信测试 -->
        <el-button v-if="!isLocalFile && form.type !== 'ck_table'" @click="testConn" :loading="testing">通信测试</el-button>
        <el-button @click="backToStep1">上一步</el-button>
        <el-button @click="emit('update:modelValue', false)">取消</el-button>
        <el-button v-if="isLocalFile" type="primary" :disabled="!canGoStep3" @click="goStep3">下一步:映射预览</el-button>
        <el-button v-else type="primary" :loading="saving" @click="onSave">保存</el-button>
      </template>
      <!-- Step3(本地文件映射预览) -->
      <template v-else-if="!isEdit && step === 3">
        <el-button @click="step = 2">上一步</el-button>
        <el-button @click="emit('update:modelValue', false)">取消</el-button>
        <!-- 预览未返回时列名映射还是空的,此刻保存会把空映射固化进配置 -->
        <el-button type="primary" :loading="saving" :disabled="previewLoading" @click="onSave">确认保存</el-button>
      </template>
      <!-- 编辑模式 -->
      <template v-else>
        <el-button v-if="!isLocalFile && form.type !== 'ck_table'" @click="testConn" :loading="testing">通信测试</el-button>
        <el-button @click="emit('update:modelValue', false)">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onSave">保存</el-button>
      </template>
    </template>

    <!-- 字段配置弹窗 -->
    <!-- append-to-body 必需:否则会被父对话框的滚动容器/层级裁切,长列表无法完整展开 -->
    <el-dialog v-model="cfgDlgVisible" title="字段配置" width="720px" top="6vh" append-to-body :close-on-click-modal="false">
      <div class="cfg-bar">
        <el-input v-model="cfgSearch" placeholder="搜索字段名" clearable size="small" style="width:220px" :prefix-icon="Search" />
        <div class="cfg-bar-right">
          <span class="cfg-count">保留 {{ cfgKeptCount }}/{{ cfgRows.length }}</span>
          <el-button size="small" @click="cfgSelectAll">全选</el-button>
          <el-button size="small" @click="cfgSelectNone">全不选</el-button>
        </div>
      </div>
      <el-table :data="cfgFiltered" border size="small" max-height="360">
        <el-table-column label="保留" width="60" align="center">
          <template #default="{ row }">
            <el-checkbox v-model="row.keep" />
          </template>
        </el-table-column>
        <el-table-column type="index" label="序号" width="60" align="center" />
        <el-table-column label="字段名">
          <template #default="{ row }">
            <el-input v-model="row.name" size="small" :placeholder="row.src" />
          </template>
        </el-table-column>
        <el-table-column label="原始列名" min-width="120">
          <template #default="{ row }"><span class="mono">{{ row.src }}</span></template>
        </el-table-column>
      </el-table>
      <div class="cfg-selected">
        <span class="cfg-selected-label">已选字段</span>
        <span v-if="cfgSelectedList.length" class="cfg-selected-list">{{ cfgSelectedList.join('、') }}</span>
        <span v-else class="cfg-selected-empty">未选择任何字段</span>
      </div>
      <template #footer>
        <el-button @click="cfgDlgVisible=false">取消</el-button>
        <el-button type="primary" @click="cfgApply">应用</el-button>
      </template>
    </el-dialog>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { Document, Search, Setting, UploadFilled } from '@element-plus/icons-vue'
import { dataSourcesApi, dsGroupsApi } from '@/api'
import type { DataSource, DataSourceInput, DataSourceType } from '@/types'
import { confirmAction } from '@/utils/confirm'
import BigDataSourceFields from './BigDataSourceFields.vue'
import ClickHouseSourceFields from './ClickHouseSourceFields.vue'

const props = defineProps<{
  modelValue: boolean
  datasource: DataSource | null
  /** 数据源分组选项(新增时选择归属) */
  groups?: { id?: number; name: string }[]
  /** 新增时的默认分组 id(0=默认分组;通常传当前左栏选中的分组) */
  defaultGroupId?: number
}>()
const emit = defineEmits<{
  (e: 'update:modelValue', v: boolean): void
  (e: 'saved'): void
  (e: 'tested'): void
  /** 在当前下拉里新建了分组(父组件需刷新分组列表) */
  (e: 'group-created'): void
  /** 本地文件数据源新建成功,携带新数据源 id(引导去同步用) */
  (e: 'created-local', id: number): void
}>()

/** 下拉底部“就地新建分组”状态 */
const newGroupName = ref('')
const newGroupSaving = ref(false)

async function quickCreateGroup() {
  const name = newGroupName.value.trim()
  if (!name) {
    ElMessage.warning('请输入分组名称')
    return
  }
  if (!(await confirmAction('新建数据源分组', `确认新建分组「${name}」？`, '确认新建'))) return
  newGroupSaving.value = true
  try {
    const g = await dsGroupsApi.create(name)
    form.groupId = g.id ?? 0 // 建好后自动选中
    newGroupName.value = ''
    ElMessage.success(`已创建分组「${g.name}」`)
    emit('group-created')
  } catch {
    /* 拦截器已提示 */
  } finally {
    newGroupSaving.value = false
  }
}

const TYPE_OPTIONS: { label: string; value: DataSourceType; icon: string; desc: string; disabled?: boolean }[] = [
  { label: 'Kafka', value: 'kafka', icon: '📨', desc: 'Kafka 消息队列与 Topic' },
  { label: 'Hive', value: 'hive', icon: '🐝', desc: 'HiveServer2 数据仓库' },
  { label: '本地 CSV 文件', value: 'csv', icon: '📄', desc: '读取本地 CSV 数据文件' },
  { label: '远程服务 (SFTP)', value: 'sftp', icon: '🌐', desc: '通过 SFTP 读取远程文件' },
  { label: '库内表', value: 'ck_table', disabled: true, icon: '🪜', desc: '平台里已有的表作为源' },
  { label: 'MySQL', value: 'mysql', icon: '🐬', desc: 'MySQL 关系型数据库' },
  { label: 'PostgreSQL', value: 'pgsql', icon: '🐘', desc: 'PostgreSQL 关系型数据库' },
  { label: 'ClickHouse', value: 'clickhouse', icon: '⚡', desc: '配置 CK 连接，读取表结构和数据' },
  { label: 'MongoDB', value: 'mongodb', icon: '🍃', desc: 'MongoDB 文档数据库' }
]

/** 引导步骤:1=选类型,2=信息+上传,3=映射预览(仅本地文件);编辑直接 2 */
const step = ref(1)
const pickedType = ref<DataSourceType | ''>('')

/** 新增/编辑的分水岭:传了 datasource 即为编辑,决定步骤流与哪些字段可改 */
const isEdit = computed(() => !!props.datasource)
const isLocalFile = computed(() => form.type === 'csv' || form.type === 'json')
const isSQLType = computed(() => ['mysql', 'pgsql', 'clickhouse'].includes(form.type))
const dialogTitle = computed(() => {
  if (isEdit.value) return '编辑数据源'
  if (step.value === 1) return '新增数据源 · 选择类型'
  if (step.value === 2) return `新增数据源 · 基本信息(${typeLabel(form.type)})`
  return `新增数据源 · 映射与预览`
})
const defaultPort = computed(() => {
  switch (form.type) {
    case 'hive': return 10000
    case 'sftp':
      return 22
    case 'mysql':
      return 3306
    case 'pgsql':
      return 5432
    case 'clickhouse':
      return 9000
    case 'mongodb':
      return 27017
    default:
      return 0
  }
})
const defaultUser = computed(() => {
  switch (form.type) {
    case 'mysql':
      return 'root'
    case 'pgsql':
      return 'postgres'
    case 'clickhouse':
      return 'default'
    default:
      return ''
  }
})
const pwdPlaceholder = computed(() => (isEdit.value && props.datasource?.hasPassword ? '留空保持原密码' : '密码'))
/** 编辑且源已有密码:允许留空以保持原密码(此时密码不作必填) */
const pwdKeep = computed(() => !!isEdit.value && !!props.datasource?.hasPassword)
const uploadAccept = computed(() => (form.type === 'json' ? '.json' : '.csv'))
const uploadBtnText = computed(() => (form.type === 'json' ? '选择 JSON 文件上传' : '选择 CSV 文件上传'))
const uploadHint = computed(() =>
  form.type === 'json'
    ? 'JSON 文件上传到服务端存储;下一步预览字段与数据;限制 200MB'
    : 'CSV 文件上传到服务端存储;下一步可设置行头与列名映射并预览;限制 200MB'
)

// 端口用 number 绑定(config 里存 number)
const portNum = computed({
  // 历史配置里 port 可能是字符串或缺省,统一 Number 归一化,避免 el-input-number 收到非数字
  get: () => Number(form.config.port) || 0,
  set: (v: number) => {
    form.config.port = v
  }
})

function pickType(t: DataSourceType) {
  if (TYPE_OPTIONS.find((option) => option.value === t)?.disabled) return
  pickedType.value = t
}

function goStep2() {
  if (!pickedType.value || TYPE_OPTIONS.find((option) => option.value === pickedType.value)?.disabled) return
  form.type = pickedType.value
  // 换类型必须清空 config:否则上一类型填的 host/port 等会残留,被当成当前类型的配置提交
  form.config = {}
  if (form.type === 'clickhouse') { form.config.protocol = 'native'; form.config.secure = false; form.config.database = 'default'; form.config.username = 'default' }
  if (form.type === 'kafka') { form.config.securityProtocol = 'SASL_PLAINTEXT'; form.config.saslMechanism = 'PLAIN' }
  if (form.type === 'hive') { form.config.database = 'default'; form.config.authMode = 'PASSWORD' }
  uploadedName.value = ''
  if (defaultPort.value) form.config.port = defaultPort.value
  step.value = 2
}

function backToStep1() {
  step.value = 1
  pickedType.value = ''
}

/** 是否满足进入第三步(本地文件:已上传) */
const canGoStep3 = computed(() => !!String(form.config.path ?? '').trim())

const formRef = ref<FormInstance>()

const form = reactive({
  code: '',
  name: '',
  type: 'mysql' as DataSourceType,
  description: '',
  groupId: 0, // 所属分组(0=默认分组)
  config: {} as Record<string, string | number | boolean>
})

/** 按类型动态必填规则 */
const rules = computed<FormRules>(() => {
  // 依赖 form.type 与 pwdKeep:切换类型或密码留存态变化时整体重算,避免旧规则残留拦住提交
  const r: FormRules = {
    code: [
      { required: true, transform: value => String(value ?? '').trim(), message: '请输入唯一标识', trigger: ['change', 'blur'] },
      { pattern: /^[A-Za-z_][A-Za-z0-9_]*$/, transform: value => String(value ?? '').trim(), message: '仅允许字母/数字/下划线,且不能以数字开头', trigger: ['change', 'blur'] }
    ],
    name: [{ required: true, message: '请输入名称', trigger: 'blur' }]
  }
  const req = (label: string) => ({ required: true, message: `请输入${label}`, trigger: 'blur' })
  if (['mysql', 'pgsql', 'clickhouse', 'sftp', 'hive'].includes(form.type)) {
    r['config.port'] = [{ required: true, type: 'integer', min: 1, max: 65535,
      message: '请输入 1 到 65535 之间的整数端口', trigger: ['change', 'blur'] }]
  }
  switch (form.type) {
    case 'kafka':
      r['config.bootstrapServers'] = [req('Broker 地址')]
      if (String(form.config.securityProtocol).startsWith('SASL_')) {
        r['config.username'] = [req('用户名')]
        if (!pwdKeep.value) r['config.password'] = [req('密码')]
      }
      break
    case 'hive':
      r['config.host'] = [req('HiveServer2 主机')]
      r['config.database'] = [req('数据库')]
      if (form.config.authMode === 'PASSWORD' || (!form.config.authMode && (pwdKeep.value || form.config.password))) {
        r['config.username'] = [req('用户名')]
        if (!pwdKeep.value) r['config.password'] = [req('密码')]
      }
      break
    case 'csv':
    case 'json':
      r['config.path'] = [req('文件')]
      break
    case 'sftp':
      r['config.host'] = [req('主机')]
      r['config.username'] = [req('用户名')]
      // 编辑已有密码时留空=保持原密码,不作必填
      if (!pwdKeep.value) r['config.password'] = [{ required: true, message: '请输入密码', trigger: 'blur' }]
      break
    case 'mysql':
    case 'pgsql':
    case 'clickhouse':
      r['config.host'] = [req('主机')]
      r['config.username'] = [req('用户名')]
      if (!pwdKeep.value) r['config.password'] = [{ required: true, message: '请输入密码', trigger: 'blur' }]
      r['config.database'] = [req('数据库')]
      break
    case 'mongodb':
      r['config.host'] = [{ required: true, message: '请输入主机或 mongodb:// 连接串', trigger: 'blur' }]
      r['config.database'] = [req('数据库')]
      break
  }
  return r
})

/** 类型显示 */
const TYPE_LABELS: Record<string, string> = {
  kafka: 'Kafka',
  hive: 'Hive',
  csv: '本地 CSV',
  json: '本地 JSON',
  sftp: 'SFTP',
  ck_table: '库内表',
  mysql: 'MySQL',
  pgsql: 'PostgreSQL',
  clickhouse: 'ClickHouse',
  mongodb: 'MongoDB'
}
function typeLabel(t: string): string {
  return TYPE_LABELS[t] ?? t
}
function typeTag(t: string): 'success' | 'warning' | 'primary' | 'danger' | 'info' {
  switch (t) {
    case 'csv':
      return 'success'
    case 'json':
      return 'success'
    case 'sftp':
      return 'warning'
    case 'ck_table':
      return 'primary'
    case 'clickhouse':
      return 'danger'
    case 'mongodb':
      return 'primary'
    default:
      return 'info'
  }
}

/** 重置弹窗 */
watch(
  () => props.modelValue,
  (v) => {
    if (!v) return
    // 每次打开都从零重置,避免上一次弹窗遗留的预览/新建分组名串进本次编辑
    previewData.value = null
    previewError.value = ''
    newGroupName.value = ''
    phoneField.value = ''
    phoneType.value = 'plain'
    if (props.datasource) {
      step.value = 2 // 编辑直接进配置
      form.code = props.datasource.code
      form.name = props.datasource.name
      form.type = props.datasource.type
      form.description = props.datasource.description
      form.groupId = props.datasource.groupId ?? 0
      form.config = { ...props.datasource.config }
      // 浅拷贝配置:后续 patch 表单不会改到列表里那份 datasource 对象
      uploadedName.value = nameFromPath(form.config.path as string)
      // 编辑本地文件:从 config 恢复行头/列名映射
      if (form.type === 'csv' || form.type === 'json') {
        const ch = props.datasource.config?.header
        previewHeader.value = typeof ch === 'boolean' ? ch : true
        previewHeaderManuallySet.value = true // 编辑:以已存配置为准,不再自动检测
        const cn = props.datasource.config?.names
        previewNames.value =
          typeof cn === 'string' && cn
            ? cn
                .split(',')
                .map((s) => s.trim())
                .filter(Boolean)
            : Array.isArray(cn)
              ? [...cn]
              : []
        // 恢复保留列勾选(config.keepColumns 为保留子集)
        const kc = props.datasource.config?.keepColumns
        const keptSet = new Set(
          typeof kc === 'string' && kc
            ? kc
                .split(',')
                .map((x: string) => x.trim())
                .filter(Boolean)
            : Array.isArray(kc)
              ? kc
              : []
        )
        // keepColumns 为空视为“全保留”:兼容未记录该字段的旧数据,免得整列被误剔除
        keepOn.value = previewNames.value.map((n) => keptSet.has(n.trim()) || keptSet.size === 0)
        // 恢复手机号列与展示形态标识(config.phoneField/phoneType)
        const pf = props.datasource.config?.phoneField
        phoneField.value = typeof pf === 'string' ? pf : ''
        const pt = props.datasource.config?.phoneType
        phoneType.value = typeof pt === 'string' ? pt : 'plain'
        // 编辑本地文件:自动加载预览,便于直接编辑字段映射
        loadPreview()
      } else {
        previewHeader.value = false
        previewHeaderManuallySet.value = false
        previewNames.value = []
        keepOn.value = []
      }
    } else {
      step.value = 1 // 新增从选类型开始
      pickedType.value = ''
      form.code = ''
      form.name = ''
      form.type = 'mysql'
      form.description = ''
      form.groupId = props.defaultGroupId ?? 0
      form.config = {}
      uploadedName.value = ''
      previewHeader.value = false // false = 未设置,由后端自动检测决定
      previewHeaderManuallySet.value = false
      previewNames.value = []
    }
  }
)

/** 组装入参(password 空则不传,后端保持原密码) */
function buildInput(): DataSourceInput {
  const config: Record<string, string | number | boolean> = {}
  // 剔除空值:空密码/空主机不能覆盖后端已存的值(编辑时“留空保持原密码”正是靠这一点生效)
  for (const [k, v] of Object.entries(form.config)) {
    if (v === '' || v === null || v === undefined) continue
    config[k] = v
  }
  // 本地文件:把预览阶段的行头/列名映射一并保存
  if (isLocalFile.value) {
    config.header = form.type === 'json' ? true : previewHeader.value
    if (form.type === 'csv') {
      // 无论是否有行头都保存列名映射:无行头=手动指定列名,有行头=重命名列
      const names = previewNames.value.map((n) => n.trim()).filter(Boolean)
      if (names.length) config.names = names.join(',')
      // 保留列:剔除未勾选列(全保留时不写)
      const kept = keptColumns()
      if (kept.length && kept.length < names.length) config.keepColumns = kept.join(',')
      // 手机号列与展示形态标识:仅当该列仍被保留时记录(否则视为未指定)
      if (phoneField.value && kept.includes(phoneField.value)) config.phoneField = phoneField.value
      if (phoneType.value) config.phoneType = phoneType.value
    }
  }
  return {
    code: form.code.trim(),
    name: form.name.trim(),
    type: form.type,
    description: form.description,
    groupId: form.groupId,
    config
  }
}

const testing = ref(false)
const saving = ref(false)

async function testConn() {
  testing.value = true
  try {
    // Test current edits; the server reuses the saved password when it is left blank.
    if (isEdit.value && props.datasource) {
      await dataSourcesApi.testConfig(props.datasource.id, buildInput())
    } else {
      await dataSourcesApi.test(buildInput())
    }
    ElMessage.success('通信测试通过 ✓')
  } catch {
    /* 拦截器已提示 */
  } finally {
    testing.value = false
    if (props.datasource) emit('tested')
  }
}

async function onSave() {
  // 先做表单规则管不了的业务校验(重复列名、手机号列),再走 el-form 规则,失败顺序对用户更友好
  // 本地文件:字段名重复时拦截
  if (isLocalFile.value && form.type === 'csv' && !checkDupNames()) return
  // CSV:必须先指定手机号列(主键),否则禁止上传/保存
  if (form.type === 'csv') {
    const kept = keptColumns()
    // 手机号列若被勾掉(不在保留列里)等同于没有主键,按未指定处理
    if (!phoneField.value || !kept.includes(phoneField.value)) {
      ElMessage.warning('请先指定手机号列(主键);手机号列需在保留字段中,否则不能上传')
      return
    }
  }
  // Step3(映射预览)时 el-form 不在 DOM,formRef 为 null — 此时手动校验关键字段
  if (formRef.value) {
    const valid = await formRef.value
      .validate()
      .then(() => true)
      .catch(() => false)
    if (!valid) return
  }
  // 第三步没有 el-form,规则校验不生效;code/name 是后端建立记录的关键字段,必须手工兜底
  if (!form.code.trim() || !form.name.trim()) {
    ElMessage.warning('请先填写唯一标识和名称')
    return
  }
  // 本地文件首次结构解析未完成时禁止保存:header 仍未定(未检测),提前保存会把错误的
  // header=无表头 固化进配置(有表头文件会被永久当无表头)。
  if (isLocalFile.value && !isEdit.value && previewLoading.value) {
    ElMessage.warning('文件结构解析中,请稍候再保存')
    return
  }
  const input = buildInput()
  const editTip = isEdit.value && props.datasource
    ? '内容编辑完成是否需要保存'
    : `确认新建数据源「${input.name}」？`
  if (!(await confirmAction(isEdit.value ? '保存数据源' : '新建数据源', editTip, isEdit.value ? '确认保存' : '确认新建'))) return
  saving.value = true
  try {
    if (isEdit.value && props.datasource) {
      const dto = await dataSourcesApi.update(props.datasource.id, input)
      ElMessage.success('已保存')
      // 后端结构基线警告(如换文件后列名与原不一致)
      if (dto?.warning) ElMessage.warning(dto.warning)
    } else {
      const dto = await dataSourcesApi.create(input)
      ElMessage.success('创建成功')
      if (dto?.warning) ElMessage.warning(dto.warning)
      // 本地文件(CSV/JSON)新建成功:告知父页,便于引导“去同步到目标库表（dwd_data）”
      if (isLocalFile.value && dto?.id) emit('created-local', dto.id)
    }
    emit('saved')
    emit('update:modelValue', false)
  } catch {
    /* 拦截器已提示 */
  } finally {
    saving.value = false
  }
}

/* ==================== 本地文件上传 ==================== */
const uploading = ref(false)
const uploadedName = ref('')

async function doUploadFile(opt: { file: File }) {
  if (!(await confirmAction('上传文件', `确认把文件「${opt.file.name}」(${Math.max(1, Math.round(opt.file.size / 1024))} KB)上传到服务端？`, '确认上传'))) return
  uploading.value = true
  try {
    const res = await dataSourcesApi.upload(opt.file)
    form.config.path = res.path
    uploadedName.value = res.name
    ElMessage.success('上传成功')
    // 换新文件(编辑本地 CSV):暂存旧字段配置,解析新文件后按“列序”携带,避免重做字段配置
    if (isLocalFile.value && isEdit.value) {
      if (form.type === 'csv') {
        const oNames = previewNames.value.slice()
        const oKeep = keepOn.value.slice()
        const oPhone = phoneField.value
        const oHeader = previewHeader.value
        const oCount = oNames.length
        // 清列名让后端对“新文件”自动解析(避免把旧列名硬套到不同结构的新文件)
        previewNames.value = []
        keepOn.value = []
        previewData.value = null
        previewHeaderManuallySet.value = true // 沿用旧行头,不自动重测(同结构重传不串列)
        previewHeader.value = oHeader
        await loadPreview()
        const newCols: { name: string; type: string }[] =
          (previewData.value as { columns?: { name: string; type: string }[] } | null)?.columns ?? []
        if (newCols.length) {
          // 按列序回带:沿用旧的重命名/保留;新增列默认保留、字段名=解析名
          const names = newCols.map((c, i) => (i < oNames.length && oNames[i] ? oNames[i] : c.name))
          const keep = newCols.map((_, i) => (i < oKeep.length ? oKeep[i] : true))
          previewNames.value = names
          keepOn.value = keep
          // 手机号列:原手机号列仍按同序保留则带回,否则清空待重选
          const pi = oNames.findIndex((n) => n && n.trim() === oPhone)
          // 手机号列随“原列位置”迁移:位置越界或该列已取消保留时清空,交给用户重选
          phoneField.value = oPhone && pi >= 0 && pi < newCols.length && keep[pi] ? oPhone : ''
          if (newCols.length === oCount) ElMessage.success('新文件结构与原一致,已沿用字段配置(含手机号)')
          else
            ElMessage.warning(
              `新文件列数由 ${oCount} 变为 ${newCols.length}:已按列序沿用可对齐项,新增列默认保留,手机号列${phoneField.value ? '已带回' : '需重选'}。请核对字段配置`
            )
        } else {
          // 解析失败(如新文件为空/未识别),保留原配置待用户核对
          previewNames.value = oNames
          keepOn.value = oKeep
          phoneField.value = oPhone
        }
      } else {
        // 其它本地文件(JSON):换新文件复位并重新解析
        previewNames.value = []
        keepOn.value = []
        previewHeaderManuallySet.value = false // 让后端对新文件重新自动检测行头
        previewData.value = null
        previewHeader.value = false
        loadPreview()
      }
    }
  } catch {
    /* 拦截器已提示 */
  } finally {
    uploading.value = false
  }
}

function clearUpload() {
  form.config.path = ''
  uploadedName.value = ''
}

/** 从存储路径提取文件名 */
function nameFromPath(p: string): string {
  if (!p) return ''
  // 路径可能来自 Windows 或 Linux,两种分隔符都要切
  const base = p.split(/[\\/]/).pop() ?? ''
  // 去掉服务端上传时加的 yyyyMMdd_HHmmss_ 前缀,还原用户原始文件名
  return base.replace(/^\d{8}_\d{6}_/, '')
}

/* ==================== 第三步:映射与预览 ==================== */
const previewLoading = ref(false)
const previewData = ref<{
  columns: { name: string; type: string }[]
  rows: { columns: string[]; types: string[]; rows: string[][] }
  total: number
  has_header?: boolean
} | null>(null)
const previewHeader = ref(false) // 默认 false = "未设置",由后端自动检测决定
const previewHeaderManuallySet = ref(false) // 用户是否手动切换过行头开关
const previewNames = ref<string[]>([])
/** 每列是否保留(保留列勾选;false=剔除该列) */
const keepOn = ref<boolean[]>([])

/** 号码展示形态选项(只做标识记录,不实际加解密):明文 / md5 / sha256 / sha1 / 3des / aes / sm4 / sm3 / 其它 */
const PHONE_TYPE_OPTIONS = [
  { value: 'plain', label: '明文(未处理)' },
  { value: 'md5', label: 'MD5' },
  { value: 'sha256', label: 'SHA-256' },
  { value: 'sha1', label: 'SHA-1' },
  { value: '3des', label: '3DES' },
  { value: 'aes', label: 'AES' },
  { value: 'sm4', label: 'SM4' },
  { value: 'sm3', label: 'SM3' },
  { value: 'other', label: '其它' }
]
/** 上传解析时指定的手机号列(列名,取自保留字段)与号码展示形态(随数据源持久化) */
const phoneField = ref('')
const phoneType = ref('plain')
/** 手机号列可选项 = 当前保留的字段名 */
const phoneColOptions = computed(() => keptColumns())

function onKeepToggle() {
  // 本地实时过滤显示(keptPreviewCols 随 keepOn 自动重算),不重新请求后端
}

/** 预览表格展示的列(剔除未保留的),保持原列索引;表头用当前(可能已重命名)的字段名 */
const keptPreviewCols = computed(() => {
  if (!previewData.value) return []
  // 以 columns 全列为基线再过滤,保证 type 与 idx 始终对应原始文件的列位置
  return previewData.value.columns
    .map((c, i) => ({
      name: (previewNames.value[i] ?? '').trim() || c.name,
      type: c.type,
      idx: i
    }))
    .filter((_, i) => keepOn.value[i] !== false)
})

/** 已保留列数 */
// keepOn 短于 previewNames(尚未同步)时按“保留”计,所以只判断 !== false 而不判断真值
const keptCount = computed(() => previewNames.value.filter((_, i) => keepOn.value[i] !== false).length)

// ---- 字段配置弹窗 ----
const cfgDlgVisible = ref(false)
const cfgRows = ref<{ keep: boolean; name: string; src: string }[]>([])
const cfgSearch = ref('')

function openFieldCfg() {
  cfgSearch.value = ''
  // 弹窗编辑的是副本:只有点「应用」才回写到 previewNames/keepOn,取消即丢弃
  cfgRows.value = previewNames.value.map((n, i) => ({
    keep: keepOn.value[i] !== false,
    name: n.trim() || '',
    src: n.trim() || (previewData.value?.columns[i]?.name ?? '')
  }))
  cfgDlgVisible.value = true
}

const cfgFiltered = computed(() => {
  const q = cfgSearch.value.trim().toLowerCase()
  if (!q) return cfgRows.value
  return cfgRows.value.filter((r) => r.name.toLowerCase().includes(q) || r.src.toLowerCase().includes(q))
})

const cfgKeptCount = computed(() => cfgRows.value.filter((r) => r.keep).length)

/** 已选字段名列表(随勾选实时更新;字段名为空则用原始列名) */
const cfgSelectedList = computed(() => cfgRows.value.filter((r) => r.keep).map((r) => r.name.trim() || r.src))

function cfgSelectAll() {
  cfgRows.value.forEach((r) => (r.keep = true))
}
function cfgSelectNone() {
  cfgRows.value.forEach((r) => (r.keep = false))
}

function cfgApply() {
  // 按全列顺序整体回写(未保留的列也保留其名称),维护与预览/提交一致的下标基线
  const names: string[] = []
  const keeps: boolean[] = []
  for (const r of cfgRows.value) {
    names.push(r.name.trim())
    keeps.push(r.keep)
  }
  previewNames.value = names
  keepOn.value = keeps
  cfgDlgVisible.value = false
}

/** 依据当前预览列名同步 keepOn 长度(缺省全保留) */
function syncKeepOn() {
  // 只在长度不匹配时重建,避免覆盖用户已逐列勾好的保留状态
  if (keepOn.value.length !== previewNames.value.length) {
    keepOn.value = previewNames.value.map(() => true)
  }
}

/** 当前保留的列名(按全列顺序,剔除未勾选) */
function keptColumns(): string[] {
  // 返回的是“最终列名”(可能已重命名),与 config.names 的语义保持一致
  return previewNames.value.map((n, i) => (keepOn.value[i] === false ? '' : n.trim())).filter(Boolean)
}

/** 重复的字段名输入框索引(去重提示) */
const duplicateNameIdx = computed<Set<number>>(() => {
  // 空名不计入重复:未命名的列交由后端按解析名兜底,不应该拦住保存
  const seen = new Map<string, number[]>()
  previewNames.value.forEach((n, i) => {
    const k = n.trim()
    if (!k) return
    if (!seen.has(k)) seen.set(k, [])
    seen.get(k)!.push(i)
  })
  const dup = new Set<number>()
  for (const idxs of seen.values()) {
    if (idxs.length > 1) idxs.forEach((i) => dup.add(i))
  }
  return dup
})

/** 找到与第 i 列同名的另一列序号(提示用) */
function duplicateRefIdx(i: number): number {
  const k = previewNames.value[i]?.trim()
  if (!k) return 0
  const j = previewNames.value.findIndex((n, idx) => idx !== i && n.trim() === k)
  return j + 1
}

/** 本地文件保存前校验:字段名重复则拦截 */
function checkDupNames(): boolean {
  if (duplicateNameIdx.value.size === 0) return true
  const first = previewNames.value.find((n, i) => n.trim() && duplicateNameIdx.value.has(i))
  ElMessage.warning(`字段名「${first}」重复,请改为唯一名称`)
  return false
}
const previewError = ref('')

const previewTableRows = computed(() => {
  // 只用行号占位,单元格由模板按索引回查 rows,免得为预览复制一份二维数组
  const n = previewData.value?.rows.rows.length ?? 0
  return Array.from({ length: n }, (_, i) => ({ __i: i }))
})

/** 行头开关切换：重置列名映射后重新预览 */
function onHeaderToggle() {
  previewHeaderManuallySet.value = true // 标记用户手动切换
  previewNames.value = [] // 让 loadPreview 用解析出的列名重新填充
  loadPreview()
}

/** 进入第三步:加载预览(应用当前行头/映射设置) */
async function goStep3() {
  if (!canGoStep3.value) return
  step.value = 3
  await loadPreview()
}

async function loadPreview() {
  // CSV 映射字段名重复时拦截(避免 DuckDB read_csv 报错)
  if (form.type === 'csv' && !checkDupNames()) {
    previewLoading.value = false
    return
  }
  previewLoading.value = true
  // 先清错误和旧数据:失败时页面不能残留上一次成功的预览,否则用户会照旧列映射去保存
  previewError.value = ''
  previewData.value = null
  try {
    const path = String(form.config.path ?? '')
    if (!path) throw new Error('请先上传文件')
    // header 参数：用户未手动切换过 → undefined(后端自动检测)；手动切换过 → 明确值
    const headerParam = form.type === 'json' || !previewHeaderManuallySet.value ? undefined : previewHeader.value
    // 构造 names 参数：无行头时始终传；有行头时仅在用户修改过列名才传
    const effectiveHeader = headerParam ?? previewHeader.value
    // 只有用户把列名改得与解析名不同才回传 names,否则没必要覆盖后端的自动解析结果
    const hasCustomNames = previewNames.value.some(
      (n, i) => n.trim() && n.trim() !== (previewData.value?.columns[i]?.name ?? '')
    )
    syncKeepOn()
    // 预览请求不传 keep:后端返回全列,使 columns 与 rows/previewNames/keepOn 的下标对齐,
    // 剔除列交给客户端 keptPreviewCols 过滤(避免 keep 子集与全列基线下标错位、表头串列)。
    previewData.value = await dataSourcesApi.previewFile(form.type as 'csv' | 'json', path, {
      limit: 100,
      header: headerParam,
      names:
        form.type === 'csv' && (effectiveHeader ? hasCustomNames : true)
          ? previewNames.value.filter((n) => n.trim())
          : undefined
    })
    // 首次加载时,用后端返回的 has_header 同步行头开关状态
    if (!previewHeaderManuallySet.value && previewData.value?.has_header !== undefined) {
      previewHeader.value = previewData.value.has_header
    }
    // 用解析出的列名维护全列基线 previewNames(始终全列;剔除由 keepOn 表达)
    if (previewData.value?.columns) {
      const cols = previewData.value.columns
      // 列数变了说明换了文件,旧的重命名已不适用,整体重置为解析名
      if (previewNames.value.length !== cols.length) {
        previewNames.value = cols.map((c) => c.name)
      } else {
        // 列数不变则保留用户改过的列名,只把空名回落到解析名
        previewNames.value = cols.map((c, i) => previewNames.value[i]?.trim() || c.name)
      }
      syncKeepOn()
    }
  } catch (e) {
    // 失败只落错误文案,不复用上一次的列基线,避免旧列名被套到结构不同的新文件上
    previewError.value = e instanceof Error ? e.message : '预览失败'
  } finally {
    previewLoading.value = false
  }
}
</script>

<style scoped>
.conn-hint { font-size: 12px; color: var(--el-text-color-secondary); line-height: 1.7; }
.type-pick {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.type-pick-hint {
  font-size: 13px;
  color: var(--el-text-color-secondary);
  text-align: center;
}
.type-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 10px;
}
.type-card {
  border: 1px solid var(--el-border-color);
  border-radius: 10px;
  padding: 14px 10px;
  text-align: center;
  cursor: pointer;
  transition: border-color 0.15s ease, background 0.15s ease, transform 0.1s ease;
  user-select: none;
}
.type-card:not(.disabled):hover {
  border-color: var(--brand);
  transform: translateY(-1px);
}
.type-card.disabled {
  cursor: not-allowed;
  filter: grayscale(1);
  opacity: 0.45;
  background: var(--el-fill-color-light);
}
.type-card.selected {
  border-color: var(--brand-strong);
  background: rgba(234, 179, 8, 0.07);
  box-shadow: 0 0 0 1px var(--brand-strong);
}
.type-icon {
  font-size: 26px;
  line-height: 1.2;
}
.type-label {
  margin-top: 6px;
  font-size: 13px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}
.type-desc {
  margin-top: 3px;
  font-size: 11px;
  color: var(--el-text-color-secondary);
}
.pc-fields-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.pc-phone-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  margin-top: 6px;
  padding-top: 6px;
  border-top: 1px dashed var(--el-border-color-lighter);
}
.pc-phone-col { width: 180px; }
.pc-phone-type { width: 150px; }
.pc-fields-stat {
  font-size: 12px;
  color: var(--el-text-color-regular);
}
.pc-fields-hint {
  font-size: 11px;
  color: var(--el-text-color-placeholder);
}
.dup-warn {
  color: var(--el-color-danger);
}
.cfg-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
  gap: 8px;
}
.cfg-bar-right {
  display: flex;
  align-items: center;
  gap: 8px;
}
.cfg-count {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.cfg-selected {
  margin-top: 10px;
  display: flex;
  gap: 8px;
  align-items: flex-start;
  font-size: 12px;
  padding: 8px 10px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  background: rgba(234,179,8,0.05);
}
.cfg-selected-label {
  flex: none;
  font-weight: 600;
  color: var(--brand-strong);
  white-space: nowrap;
  padding-top: 1px;
}
.cfg-selected-list {
  line-height: 1.7;
  word-break: break-all;
  color: var(--el-text-color-regular);
}
.cfg-selected-empty {
  color: var(--el-text-color-placeholder);
}
.step-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}
.upload-area {
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.uploaded-info {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 10px;
  background: rgba(52, 211, 153, 0.06);
  border: 1px dashed rgba(52, 211, 153, 0.35);
  border-radius: 6px;
}
.uploaded-icon {
  color: #34d399;
  flex: none;
}
.uploaded-name {
  flex: 1;
  min-width: 0;
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--el-text-color-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.upload-hint {
  font-size: 11px;
  color: var(--el-text-color-placeholder);
}
.sftp-path-row {
  display: flex;
  gap: 6px;
  width: 100%;
}
.grp-quick {
  display: flex;
  gap: 6px;
  align-items: center;
  padding: 6px 8px 8px;
  border-top: 1px solid var(--el-border-color-lighter);
  margin-top: 2px;
}
.grp-tip {
  font-size: 11px;
  color: var(--el-text-color-placeholder);
  line-height: 1.6;
  margin-top: 2px;
}
.preview-config {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 10px 12px;
  background: var(--fill-soft);
  border: 1px dashed var(--el-border-color);
  border-radius: 8px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}
.pc-label {
  font-size: 13px;
  color: var(--el-text-color-secondary);
  line-height: 32px;
  flex: none;
}
.pc-hint-inline {
  font-size: 12px;
  color: var(--el-text-color-placeholder);
  line-height: 32px;
}
.pc-edit-col {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 8px;
  width: 100%;
}
.pc-names {
  display: flex;
  flex-direction: column;
  gap: 4px;
  max-height: 140px;
  overflow: auto;
  min-width: 200px;
}
.pc-name-row {
  display: flex;
  align-items: center;
  gap: 6px;
}
/* 字段名重复的输入框标红提示 */
.pc-name-row .name-dup :deep(.el-input__wrapper),
.pc-name-row .name-dup :deep(.el-input__wrapper.is-focus) {
  box-shadow: 0 0 0 1px var(--el-color-danger) inset;
}
.name-dup-hint {
  flex-shrink: 0;
  font-size: 12px;
  color: var(--el-color-danger);
  white-space: nowrap;
}
.pc-col-idx {
  flex: none;
  width: 24px;
  text-align: center;
  font-family: var(--font-mono);
  font-size: 11px;
  color: var(--el-text-color-placeholder);
}
.edit-preview-wrap {
  width: 100%;
}
.preview-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
}
.preview-hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.wizard-preview-table {
  width: 100%;
}
/* 预览单元格:长值单行截断,不换行撑高 */
.wizard-preview-table .mono {
  display: block;
  max-width: 320px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.preview-col-head {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  line-height: 1.3;
}
.preview-col-name {
  font-family: var(--font-mono);
  font-size: 12px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}
.preview-col-type {
  font-family: var(--font-mono);
  font-size: 10px;
  color: var(--el-text-color-secondary);
}
.mono {
  font-family: var(--font-mono);
  font-size: 12px;
}
.preview-error {
  padding: 10px 12px;
  background: rgba(248, 113, 113, 0.08);
  border: 1px solid rgba(248, 113, 113, 0.35);
  border-radius: 6px;
  font-size: 12px;
  color: #f87171;
}
</style>
