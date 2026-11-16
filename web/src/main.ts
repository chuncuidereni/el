/**
 * main.ts
 *
 * 职责：前端启动入口：创建 Vue 应用，安装 Pinia、路由、Element Plus 中文包和全局主题。
 * 边界：Pinia 必须先于 store 使用安装，路由必须先于首次渲染挂载；表格悬停标题由全局 mouseover 监听统一补充。
 */

import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'
import '@/assets/theme.css'

import App from './App.vue'
import router from './router'

/*
  构建标记:把入口 chunk 的文件名(带 Vite 内容哈希)打到控制台。
  用途:报"改了没效果"时先 F12 看这一行 —— 文件名与最新构建不一致就是浏览器在跑旧包
  (缓存 / 没硬刷新),不用再改代码。
*/
console.info('[前端构建]', import.meta.url.split('/').pop() || '(未知)')

const app = createApp(App)
// pinia 必须在任何 store 被使用前装好;router 在挂载前装,避免首次渲染时路由还没就绪
app.use(createPinia())
app.use(router)
// 全局中文:Element Plus 内置文案(分页、空表格、确认框按钮)默认是英文
app.use(ElementPlus, { locale: zhCn })
app.mount('#app')

// Resized table columns stay on one line; show truncated plain text on hover.
document.addEventListener('mouseover', (event) => {
  const target = event.target
  if (!(target instanceof Element)) return
  const cell = target.closest<HTMLElement>('.el-table .cell')
  if (!cell || cell.querySelector('button, input, textarea, select')) return
  if (cell.scrollWidth > cell.clientWidth) {
    if (!cell.title || cell.dataset.autoTitle === '1') {
      cell.title = cell.innerText.trim()
      cell.dataset.autoTitle = '1'
    }
  } else if (cell.dataset.autoTitle === '1') {
    cell.removeAttribute('title')
    delete cell.dataset.autoTitle
  }
}, { passive: true })
