// fileURLToPath/URL 用于把 import.meta.url 转成文件路径,供下面 @ 别名使用
import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 开发模式:监听全部网卡，局域网设备可通过本机 IP 访问；`/api` 代理到 Java 后端 17878。
export default defineConfig({
  // Vue 单文件组件编译插件(必需的官方插件)
  plugins: [vue()],
  resolve: {
    alias: {
      // 路径别名:@ → src,使 src 之外的文件也能用 '@/xxx' 统一风格导入(与 tsconfig.json 的 paths 保持一致)
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    // 开发服务器监听 0.0.0.0,便于手机/其他机器联调(而非仅 localhost)
    host: '0.0.0.0',
    port: 17373,
    proxy: {
      // 开发期把 /api 前缀请求转发到后端,免去本地跨域与 CORS 配置;
      // 生产环境由后端同源托管 dist,不存在代理
      '/api': {
        target: 'http://localhost:17878',
        // 改写 Host 头为目标地址,避免后端基于 Host 的校验失败
        changeOrigin: true,
      },
    },
  },
  preview: {
    // 预览(构建产物)服务同样监听全网卡,端口与 dev 区分
    host: '0.0.0.0',
    port: 17373,
  },
  build: {
    // 产物输出到 dist,对应后端配置 app.web-dir=./web/dist
    outDir: 'dist',
    // 调高单 chunk 体积告警阈值:Element Plus + CodeMirror 打包后偏大,默认 500kB 会一直告警
    chunkSizeWarningLimit: 1500,
  },
})
