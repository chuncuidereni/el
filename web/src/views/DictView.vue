<!--
  DictView.vue

  职责：数据字典页面：浏览字段含义、类型说明和检索信息。
  边界：按连接、数据库、表和关键词加载数据字典并分页；筛选变化重置页码，详情字段按当前选择请求。
-->

<template>
  <div class="dict-view">
    <div class="page-head">
      <div>
        <h2>字典管理</h2>
        <p class="page-sub">业务字段字典:标识各数据源/同步中对手机号这类字段的统一命名(mdn)与号码展示形态(明文/是否被算法加工),供建表与映射复用。</p>
      </div>
    </div>

    <div v-loading="loading" class="dict-body">
      <!-- 单卡片表单:只有两个字段,没有列表,不需要页签或分组 -->
      <el-card shadow="never" class="dict-card">
        <template #header>
          <span class="card-title">手机号字段字典</span>
        </template>

        <el-form label-width="160px" class="dict-form">
          <el-form-item label="手机号字段名">
            <el-input v-model="form.dimPhoneField" size="default" class="dict-input mono"
              placeholder="默认 mdn" />
            <span class="hint">新建贴源表 / 映射目标表不存在时,自动把它作为首列(置灰主键)。</span>
          </el-form-item>

          <el-form-item label="号码展示形态">
            <el-select v-model="form.dimPhoneType" size="default" class="dict-input">
              <el-option v-for="o in phoneTypes" :key="o.value" :value="o.value" :label="o.label" />
            </el-select>
            <span class="hint">标识该手机号列在源数据里是明文还是被某算法(md5/sha256/3des/aes/sm4/sm3…)加工,只做记录供后续识别,不实际加解密。</span>
          </el-form-item>

          <el-form-item>
            <el-button type="primary" :loading="saving" @click="save">保存</el-button>
            <!--
              「重置」= 丢掉本地未保存的改动、回到服务端已存的值(走 load 重读),
              不是恢复成内置默认值 —— 按钮上不写"恢复默认"也是这个原因。
              保存中禁用,免得重置回来又被在途的写入覆盖。
            -->
            <el-button :disabled="saving" @click="load">重置</el-button>
          </el-form-item>
        </el-form>
      </el-card>
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * 字典管理 —— 全局设置里唯一一项「业务口径」。
 *
 * 为什么单独给它一页:这里存的是【跨模块共用的约定】,不是本页自己的状态 ——
 * 建表(新建贴源表时自动补首列)、映射同步(号码列落在哪)、工作流(认号码列)
 * 都读这两个值。改错了影响的是别人的页面,所以既要说清含义,也要挡住非法输入。
 *
 * 两个字段都只有"标识"作用,没有任何加解密行为:
 * 号码列名用来对齐列;展示形态只记录"源头是不是已经哈希过了",供后续识别。
 */
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { settingsApi } from '@/api'
import { confirmAction } from '@/utils/confirm'
import type { GlobalSettings } from '@/types'

/** 手机号列“展示形态”(标识记录用,不实际加解密):号码在源数据里是明文还是被某算法加工。 */
const phoneTypes = [
  { value: 'plain', label: '明文(未处理)' },
  { value: 'md5', label: 'MD5' },
  { value: 'sha256', label: 'SHA-256' },
  { value: 'sha1', label: 'SHA-1' },
  { value: '3des', label: '3DES' },
  { value: 'aes', label: 'AES' },
  { value: 'sm4', label: 'SM4' },
  { value: 'sm3', label: 'SM3' },
  { value: 'other', label: '其它' },
]

/** loading = 首次/重置读取中(盖住整卡);saving = 提交中。两者分开,重读时才盖遮罩 */
const loading = ref(false)
const saving = ref(false)
/** 表单初值给的就是后端缺省值,避免首屏(还没 load 完)闪一个空表单 */
const form = reactive<{ dimPhoneField: string; dimPhoneType: string }>({
  dimPhoneField: 'mdn',
  dimPhoneType: 'plain',
})

async function load() {
  loading.value = true
  try {
    const g: GlobalSettings = await settingsApi.get()
    // 后端可能返回空串(字段被清过),这里统一兜回默认值,保证界面上永远是有效值
    form.dimPhoneField = g.dimPhoneField || 'mdn'
    form.dimPhoneType = g.dimPhoneType || 'plain'
  } catch (e) {
    ElMessage.error((e as Error)?.message || '读取字典配置失败')
  } finally {
    loading.value = false
  }
}

async function save() {
  const field = form.dimPhoneField.trim()
  if (!field) {
    ElMessage.warning('请输入手机号字段名')
    return
  }
  // 这个值会被拼进 CREATE TABLE 的列定义里,所以按 ClickHouse 标识符规则挡在提交前:
  // 数字开头 / 带空格 / 带连字符的列名建表时会直接语法错误,不如在这里拦住。
  if (!/^[A-Za-z_][A-Za-z0-9_]*$/.test(field)) {
    ElMessage.warning('字段名需为英文/下划线开头,仅含字母数字下划线(库的列名要求)')
    return
  }
  if (!(await confirmAction('保存手机号字典', `确认把手机号字段保存为「${field}」、展示形态「${phoneTypes.find((t) => t.value === form.dimPhoneType)?.label ?? form.dimPhoneType}」？该配置会影响建表、同步与工作流。`, '确认保存'))) return
  saving.value = true
  try {
    // 读取当前完整设置(含连接默认),只改字典字段后全量回存,避免清空其它全局默认
    const cur: GlobalSettings = await settingsApi.get()
    await settingsApi.update({
      ...cur,
      dimPhoneField: field,
      dimPhoneType: form.dimPhoneType,
    })
    ElMessage.success('已保存手机号字典')
  } catch (e) {
    ElMessage.error((e as Error)?.message || '保存失败')
  } finally {
    saving.value = false
  }
}

// 进入页面就把当前值读出来:这一页没有"新建"语义,打开就是编辑现有配置
onMounted(load)
</script>

<style scoped>
.dict-view { padding: 16px 20px; }
.page-head { margin-bottom: 14px; }
.page-head h2 { margin: 0; font-size: 18px; }
.page-sub { margin: 6px 0 0; color: #8a8f98; font-size: 13px; }
/* 限宽:两个字段拉满整屏会长得不像表单,720px 与其它单列表单页一致 */
.dict-card { max-width: 720px; }
.card-title { font-weight: 600; }
.dict-input { width: 320px; }
/* 字段下方的说明文字:单独占一行(不能被 el-form-item 的 inline 布局挤到输入框右边) */
.hint { display: block; color: #8a8f98; font-size: 12px; line-height: 1.5; margin-top: 2px; }
/* 字段名要按字面校对(是列名),等宽字体便于看清下划线与大小写 */
.mono { font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; }
</style>
