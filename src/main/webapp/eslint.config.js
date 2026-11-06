// ESLint 官方 JavaScript 推荐规则
import js from '@eslint/js'
// 浏览器全局变量定义（window、document 等）
import globals from 'globals'
// React Hooks 规则（检查依赖数组、Hook 调用规则等）
import reactHooks from 'eslint-plugin-react-hooks'
// React Fast Refresh 规则（禁止在模块顶层导出组件导致 HMR 失效）
import reactRefresh from 'eslint-plugin-react-refresh'
// TypeScript ESLint 插件
import tseslint from 'typescript-eslint'
// ESLint 新版 flat config 工具
import { defineConfig, globalIgnores } from 'eslint/config'

export default defineConfig([
  // 忽略构建产物目录，不对 dist 中的文件做 lint
  globalIgnores(['dist']),
  {
    // 仅对 TypeScript/TSX 文件应用这些规则
    files: ['**/*.{ts,tsx}'],
    // 继承的推荐规则集合
    extends: [
      // JS 基础推荐规则
      js.configs.recommended,
      // TypeScript 推荐规则
      tseslint.configs.recommended,
      // React Hooks 推荐规则
      reactHooks.configs.flat.recommended,
      // React Refresh 的 Vite 适配规则
      reactRefresh.configs.vite,
    ],
    languageOptions: {
      // ECMAScript 版本 2020
      ecmaVersion: 2020,
      // 声明浏览器全局变量，避免 window/document 等被报错为未定义
      globals: globals.browser,
    },
  },
])
