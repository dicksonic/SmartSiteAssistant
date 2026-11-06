import path from "path"
// __dirname 在 ESM 中默认不可用，这里从 import.meta 中显式取出
const __dirname = import.meta.dirname
// React 官方 Vite 插件，提供 JSX 转换、HMR 热更新、React 错误边界等功能
import react from "@vitejs/plugin-react"
import { defineConfig } from "vite"
// Kimi 可视化检查插件（仅在开发时启用），用于辅助调试组件属性
import { inspectAttr } from 'kimi-plugin-inspect-react'

// 纯前端工程：后端为本地 Java (Spring Boot, 8080)。
// 开发时 /api 自动代理到 Java 后端；生产部署时由 Nginx/网关做同样的反向代理。
export default defineConfig({
  // Vite 插件列表：inspectAttr 仅开发辅助；react 提供 JSX 编译与 HMR
  plugins: [inspectAttr(), react()],
  // 开发服务器配置
  server: {
    // 前端开发服务监听端口，访问 http://localhost:3000
    port: 3001,
    // 代理规则：所有以 /api 开头的请求转发到 Java 后端，避免跨域
    proxy: {
      "/api": { target: "http://localhost:8087", changeOrigin: true },
    },
  },
  // 模块解析别名：使代码中可通过 @/xxx 和 @contracts/xxx 引用源码和契约文件
  resolve: {
    alias: {
      // @ 指向 src 目录，例如 @/pages/Home -> src/pages/Home
      "@": path.resolve(__dirname, "./src"),
      // @contracts 指向 contracts 目录，前后端共享类型定义
      "@contracts": path.resolve(__dirname, "./contracts"),
    },
  },
})
