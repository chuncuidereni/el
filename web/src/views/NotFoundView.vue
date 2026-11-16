<!--
  NotFoundView.vue

  职责：404 兜底页面：展示无效路径并提供返回工作台的入口。
  边界：没有数据请求、轮询或卸载副作用；只展示当前无效路径，并允许导航回工作台。
-->

<template>
  <!-- 兜底路由:列出的路径本身就是排查线索(手敲错的 / 失效的旧链接一眼能看出) -->
  <div class="not-found">
    <div class="nf-code">404</div>
    <div class="nf-text">页面不存在</div>
    <div class="nf-path">当前路径:{{ route.fullPath }}</div>
    <el-button type="primary" @click="router.replace('/workspace')">返回工作台</el-button>
  </div>
</template>

<script setup lang="ts">
// 只读 route/router,不引入任何状态:这一页应当在任何异常下都能渲染出来
import { useRoute, useRouter } from 'vue-router'

const route = useRoute()
const router = useRouter()
// 按钮用 router.replace 而不是 push:404 是"走错了路",不该留在前进/后退历史里让人再撞一次
</script>

<style scoped>
.not-found {
  height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
}
.nf-code {
  font-family: var(--font-mono);
  font-size: 72px;
  font-weight: 700;
  color: var(--el-text-color-placeholder);
  line-height: 1;
}
.nf-text {
  font-size: 16px;
  color: var(--el-text-color-primary);
}
.nf-path {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-bottom: 8px;
}
</style>
