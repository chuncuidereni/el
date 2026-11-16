<!--
  App.vue

  职责：应用根布局：协调登录状态、公共路由、侧栏导航、面包屑和受保护页面容器。
  边界：受保护页面仅在已登录且不是登录路由时渲染完整布局；侧栏折叠状态写入 localStorage，启动时会清理旧主题键。
-->

<template>
  <!-- 退出后到路由切换完成前，不在裸布局中重新挂载需要认证的业务页。 -->
  <div v-if="!showChrome" class="app-bare"><router-view v-if="route.meta.public" /></div>
  <!-- 整体两栏:左侧固定导航,右侧路由出口 -->
  <el-container v-else class="app">
    <el-aside :width="collapsed ? '64px' : '232px'" class="app-aside">
      <!-- 侧栏顶部:一行平台名,点击回首页。
           原先这里是「图标 + EL / DATA SYNC」两行品牌块,与下方菜单首项的图标观感重复,已移除;
           折叠态整行不渲染(v-if),把纵向空间让给菜单 -->
      <div v-if="!collapsed" class="brand" title="返回首页" @click="goHome">
        <span class="brand-title">数仓平台</span>
      </div>

      <!-- router 模式:index 直接当路由跳;default-active 跟随当前路由而非点击项,
           这样从别的入口(如详情页返回)进来高亮也是对的 -->
      <el-menu
        ref="menuRef"
        :default-active="activeMenu"
        router
        class="side-menu"
        :collapse="collapsed"
        :collapse-transition="false"
        :default-openeds="defaultOpeneds"
        @select="onMenuSelect"
      >
        <el-menu-item index="/home">
          <el-icon><HomeFilled /></el-icon>
          <template #title>首页</template>
        </el-menu-item>

        <el-sub-menu index="grp-datasource">
          <template #title>
            <el-icon><Files /></el-icon>
            <span>数源管理</span>
          </template>
          <el-menu-item index="/datasources">
            <el-icon><Files /></el-icon>
            <template #title>数据源</template>
          </el-menu-item>
          <el-menu-item index="/sync-tasks">
            <el-icon><RefreshRight /></el-icon>
            <template #title>映射同步</template>
          </el-menu-item>
          <el-menu-item index="/raw">
            <el-icon><Collection /></el-icon>
            <template #title>标准源表</template>
          </el-menu-item>
        </el-sub-menu>

        <el-sub-menu index="grp-workbench">
          <template #title>
            <el-icon><Share /></el-icon>
            <span>工作台</span>
          </template>
          <el-menu-item index="/sql-query">
            <el-icon><Search /></el-icon>
            <template #title>数据探索</template>
          </el-menu-item>
          <el-menu-item index="/workflows">
            <el-icon><Share /></el-icon>
            <template #title>工作流</template>
          </el-menu-item>
          <el-menu-item index="/dwd">
            <el-icon><Coin /></el-icon>
            <template #title>目标库表</template>
          </el-menu-item>
        </el-sub-menu>

        <el-sub-menu v-if="auth.isAdmin" index="grp-tools">
          <template #title>
            <el-icon><Tools /></el-icon>
            <span>工具</span>
          </template>
          <el-menu-item index="/tools/hash">
            <el-icon><Stamp /></el-icon>
            <template #title>哈希计算</template>
          </el-menu-item>
          <el-menu-item index="/tools/crypto">
            <el-icon><Lock /></el-icon>
            <template #title>加密解密</template>
          </el-menu-item>
          <el-menu-item index="/tools/ddl-check">
            <el-icon><FirstAidKit /></el-icon>
            <template #title>表体检</template>
          </el-menu-item>
        </el-sub-menu>

        <el-sub-menu v-if="auth.isAdmin" index="grp-system">
          <template #title>
            <el-icon><Monitor /></el-icon>
            <span>系统</span>
          </template>
          <el-menu-item index="/workspace">
            <el-icon><Connection /></el-icon>
            <template #title>数据库</template>
          </el-menu-item>
          <el-menu-item index="/connections">
            <el-icon><Setting /></el-icon>
            <template #title>连接管理</template>
          </el-menu-item>
          <el-menu-item index="/system">
            <el-icon><Odometer /></el-icon>
            <template #title>系统监控</template>
          </el-menu-item>
          <el-menu-item index="/app-users">
            <el-icon><Setting /></el-icon>
            <template #title>平台用户管理</template>
          </el-menu-item>
          <el-menu-item index="/dict">
            <el-icon><Notebook /></el-icon>
            <template #title>字典</template>
          </el-menu-item>
        </el-sub-menu>
      </el-menu>

      <div class="side-tools">
        <!-- 折叠开关:图标随状态切换,折叠态只显示 Expand -->
        <div class="side-toggle" :title="collapsed ? '展开菜单' : '折叠菜单'" @click="toggleSide">
          <el-icon><Expand v-if="collapsed" /><Fold v-else /></el-icon>
        </div>
      </div>
    </el-aside>

    <el-main class="app-main">
      <header class="app-topbar">
        <span class="topbar-title">{{ pageTitle }}</span>
        <div class="topbar-account" :title="auth.username">
          <span class="account-avatar">{{ auth.username.slice(0, 1).toUpperCase() }}</span>
          <span class="account-name">{{ auth.username }}</span>
          <span class="account-role">{{ auth.isAdmin ? '管理员' : '普通用户' }}</span>
          <button class="account-logout" type="button" @click="router.push('/account')">账号安全</button>
          <button class="account-logout account-signout" type="button" @click="logout">退出登录</button>
        </div>
      </header>
      <div class="app-content"><router-view /></div>
    </el-main>
  </el-container>

</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Coin, Collection, Connection, Expand, Files, FirstAidKit, Fold, HomeFilled, Lock, Monitor, Notebook, Odometer, RefreshRight, Search, Setting, Share, Stamp, Tools } from '@element-plus/icons-vue'
import { bumpNavReset } from './composables/useNavReset'
import { useAuthStore } from './stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const showChrome = computed(() => auth.isAuthed && route.path !== '/login')
const PAGE_TITLES: Record<string, string> = {
  '/account': '账号安全', '/app-users': '平台用户管理',
  '/home': '首页', '/datasources': '数据源', '/sync-tasks': '映射同步',
  '/raw': '标准源表（ods_data）', '/sql-query': '数据探索', '/workflows': '工作流',
  '/dwd': '目标库表（dwd_data）', '/tools/hash': '哈希计算', '/tools/crypto': '加密解密',
  '/tools/ddl-check': '表体检', '/workspace': '数据库',
  '/connections': '连接管理', '/system': '系统监控', '/dict': '字典',
}
const pageTitle = computed(() => PAGE_TITLES[route.path] ?? '数仓平台')

/**
 * 菜单点击。
 *
 * ⚠️ 点的是【当前所在页】时,路由不会变(重复导航被 vue-router 忽略),
 *    用户若停在该页的详情里(如数据探索的编辑器)就会觉得「点了没反应」。
 *    这里对「同 path」发一次复位信号,让页面回到列表。
 */
function onMenuSelect(index: string): void {
  if (index === route.path) bumpNavReset()
}

/** 点 logo 回首页 */
function goHome() {
  void router.push('/home')
}
// 初始折叠态沿用上次会话的选择,避免每次刷新都弹回展开
const collapsed = ref(localStorage.getItem('el2ck.sidebar') === '1')
/** 侧栏分组默认全部折叠(用户自行展开) */
const defaultOpeneds: string[] = []

// 迁移后固定使用原 Java 版亮色；清除旧版保留的暗色偏好。
document.documentElement.classList.remove('dark')
localStorage.removeItem('el2ck.theme')

/** 菜单高亮跟随当前路由 */
const activeMenu = computed(() => route.path)

function toggleSide() {
  collapsed.value = !collapsed.value
  localStorage.setItem('el2ck.sidebar', collapsed.value ? '1' : '0')
}

async function logout() {
  try {
    await auth.logout()
    await router.replace('/login')
  } catch { /* request interceptor displays the server error */ }
}
</script>

<style>
.app {
  height: 100%;
}
.app-aside {
  background: var(--bg-sidebar);
  border-right: 0;
  display: flex;
  flex-direction: column;
  position: relative;
}
/* 一条规则(以前写了两遍,后者的 transition 把前者的 opacity 过渡吃掉了 —— 合并回来) */
.brand {
  cursor: pointer;
  transition: opacity 0.15s ease;
  display: flex;
  align-items: center;
  padding: 20px 16px 14px;
}
.brand:hover {
  opacity: 0.85;
}
/* 「数仓平台」标题:字号偏大 + 近白,并在深色侧栏上补一点冷色光晕提升"发亮"感 */
.brand-title {
  font-size: 17px;
  font-weight: 800;
  letter-spacing: 0.14em;
  color: #f6f8fc;
  text-shadow: 0 0 12px rgba(90, 150, 255, 0.45);
}
.side-menu {
  flex: 1;
  padding-top: 6px;
}
.side-menu.el-menu--collapse {
  border-right: none;
  width: 64px;
}
.side-tools {
  flex: none;
  display: flex;
  border-top: 1px solid rgba(255, 255, 255, 0.08);
}
.side-toggle {
  flex: 1;
  height: 44px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--sidebar-text);
  cursor: pointer;
  transition: color 0.15s ease, background 0.15s ease;
}
.side-toggle:hover {
  color: #ffffff;
  background: rgba(255, 255, 255, 0.08);
}
.side-menu .el-menu-item,
.side-menu .el-sub-menu__title {
  color: var(--sidebar-text);
}
.side-menu .el-menu-item:hover,
.side-menu .el-sub-menu__title:hover {
  color: #ffffff;
  background: rgba(255, 255, 255, 0.08);
}
.side-menu .el-menu-item.is-active {
  color: var(--sidebar-text-active);
  background: #0052d9;
}
.side-menu .el-menu-item.is-active .el-icon {
  color: #ffffff;
}
.side-menu .el-sub-menu.is-opened > .el-sub-menu__title {
  color: #ffffff;
}
.side-menu .el-menu--inline {
  background: var(--bg-sidebar);
}
.app-main {
  padding: 0;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  background:
    radial-gradient(1000px 480px at 82% -12%, rgba(0, 82, 217, 0.05), transparent 62%),
    var(--bg-page);
  animation: main-fade 0.4s ease;
}
.app-topbar {
  flex: none;
  min-height: 58px;
  padding: 0 24px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  border-bottom: 1px solid #e6ebf3;
  background: #fff;
}
.topbar-title { color: #17243b; font-size: 16px; font-weight: 700; }
.topbar-account { display: flex; align-items: center; gap: 9px; min-width: 0; }
.account-avatar {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  border-radius: 50%;
  background: #e8f0ff;
  color: #0c56ce;
  font-size: 14px;
  font-weight: 700;
}
.account-name { max-width: 150px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: #17243b; font-size: 13px; font-weight: 600; }
.account-role { color: #75839b; font-size: 12px; }
.account-logout { margin-left: 10px; padding: 5px 0 5px 12px; border: 0; border-left: 1px solid #e6ebf3; background: transparent; color: #456385; cursor: pointer; font: inherit; font-size: 12px; }
.account-logout:hover { color: #0c56ce; }
.app-content { flex: 1; min-height: 0; overflow-x: hidden; overflow-y: auto; }
@media (max-width: 520px) {
  .app-topbar { padding: 0 12px; }
  .account-role { display: none; }
  .account-name { max-width: 72px; }
}
@keyframes main-fade {
  from {
    opacity: 0;
    transform: translateY(4px);
  }
  to {
    opacity: 1;
    transform: none;
  }
}
</style>
