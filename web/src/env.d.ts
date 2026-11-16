/**
 * env.d.ts
 *
 * 职责：TypeScript 编译环境声明：引入 Vite 客户端类型并让编译器识别 `.vue` 单文件组件。
 * 边界：只提供构建期类型，不声明运行时配置或凭据；`.vue` 使用宽泛组件类型以兼顾项目当前类型检查策略。
 */

// Vite 客户端类型引用:提供 import.meta.env、?raw / ?url 等导入形式以及静态资源模块的声明。
/// <reference types="vite/client" />

// 让 TS 识别 *.vue 单文件组件:统一视作 Vue 组件类型,否则 import Foo from './Foo.vue' 会报「找不到模块」。
// 这里是简化声明(不解析 props/emits 的具体类型),类型严格度让位于开发便利。
declare module '*.vue' {
  import type { DefineComponent } from 'vue'
  const component: DefineComponent<{}, {}, any>
  export default component
}
