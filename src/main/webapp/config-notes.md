# 前端工程配置说明

> 本文件用于记录 `H:\codes\SmartSiteAI\src\main\webapp` 目录下各配置文件的作用，避免在 JSON 文件中写注释导致 IDE 报错。

---

## 1. package.json

项目 npm 依赖清单与脚本入口。

### 字段说明

| 字段 | 说明 |
|---|---|
| `name` | 项目名，当前为 `my-app`（建议改成与项目一致，如 `reg-assistant-web`） |
| `private` | `true` 表示不会误发布到 npm 仓库 |
| `version` | 版本号，当前 `0.0.0` |
| `type` | `"module"` 表示使用 ESM 模块规范，配置文件可写 `import/export` |

### 脚本说明

| 脚本 | 作用 |
|---|---|
| `npm run dev` | 启动 Vite 开发服务器，默认 `http://localhost:3000` |
| `npm run build` | 生产构建，产物输出到 `dist/` |
| `npm run preview` | 本地预览生产构建产物 |
| `npm run lint` | 运行 ESLint 检查所有文件 |
| `npm run check` | 运行 `tsc -b` 类型检查 |
| `npm run format` | 使用 Prettier 格式化全部文件 |
| `npm run test` | 运行 Vitest 测试 |

### 主要依赖分类

#### UI 与交互
- `react` / `react-dom`：React 19 运行时
- `@radix-ui/react-*`：shadcn/ui 底层无头组件，提供对话框、下拉菜单、标签页等交互能力
- `lucide-react`：图标库
- `sonner`：Toast 通知
- `vaul`：Drawer 抽屉组件
- `embla-carousel-react`：轮播组件
- `react-resizable-panels`：可拖拽调整大小的面板
- `input-otp`：验证码输入组件
- `cmdk`：命令面板 / 快速搜索
- `react-day-picker`：日期选择器

#### 表单与校验
- `react-hook-form`：表单状态管理
- `@hookform/resolvers`：与 Zod 等校验库桥接
- `zod`：Schema 校验与 TypeScript 类型推导

#### 样式与主题
- `tailwindcss`：原子化 CSS 框架
- `tailwind-merge` / `clsx` / `class-variance-authority`：组合 Tailwind 类名
- `next-themes`：深色/浅色主题切换
- `tw-animate-css` / `tailwindcss-animate`：动画扩展

#### 数据请求与路由
- `react-router`：前端路由
- `@tanstack/react-query`：服务端状态管理、缓存、请求去重
- `@trpc/client` / `@trpc/server` / `@trpc/react-query`：端到端类型安全 API
- `hono`：轻量后端框架（如需要前后端合体模式时使用）

#### 其他工具
- `date-fns`：日期处理
- `dotenv`：环境变量读取
- `drizzle-orm` / `mysql2`：ORM 与数据库（当前项目后端为 Java，这里更像是预留依赖）

### 开发依赖

| 依赖 | 说明 |
|---|---|
| `vite` | 构建工具与开发服务器 |
| `@vitejs/plugin-react` | React 官方 Vite 插件 |
| `@hono/vite-dev-server` | Hono 开发服务器插件 |
| `typescript` | TypeScript 编译器 |
| `typescript-eslint` | TypeScript ESLint 规则 |
| `@eslint/js` / `eslint` / `eslint-plugin-react-*` | ESLint 与 React 相关规则 |
| `prettier` | 代码格式化 |
| `vitest` | 单元测试框架 |
| `globals` | ESLint 浏览器全局变量 |
| `kimi-plugin-inspect-react` | 开发调试 React 组件属性的 Kimi 插件 |
| `autoprefixer` / `postcss` | CSS 后处理 |
| `@types/*` | TypeScript 类型声明 |

---

## 2. package-lock.json

npm 依赖版本锁定文件。记录每个依赖的精确版本、下载地址和依赖树，确保团队成员安装相同版本。不要手动修改，由 `npm install` 自动维护。

---

## 3. vite.config.ts

Vite 构建工具核心配置。

### 配置项说明

| 配置项 | 说明 |
|---|---|
| `plugins` | 插件列表：`inspectAttr()` 用于开发调试；`react()` 提供 JSX 编译、热更新、错误边界 |
| `server.port` | 开发服务器端口，默认 `3000` |
| `server.proxy` | 开发代理：所有 `/api` 开头的请求转发到 `http://localhost:8080`（Java 后端），避免跨域 |
| `resolve.alias` | 路径别名：`@` 指向 `src/`，`@contracts` 指向 `contracts/`，用于简化 import 路径 |

---

## 4. vitest.config.ts

Vitest 测试配置。

| 配置项 | 说明 |
|---|---|
| `root` | 测试根目录，与配置文件目录一致 |
| `resolve.alias` | 与 `vite.config.ts` 保持一致，测试代码也能用 `@/` 和 `@contracts/` |
| `test.environment` | 测试环境为 `node`；如需测试 DOM 组件，需安装 `jsdom` 并改为 `jsdom` |
| `test.include` | 匹配 `src/**/*.test.ts` / `.spec.ts` / `.test.tsx` / `.spec.tsx` 作为测试文件 |

---

## 5. tsconfig.json

TypeScript 工程引用（Project References）根配置，不直接编译文件。

| 配置项 | 说明 |
|---|---|
| `files` | 空数组，根配置只负责引用子项目 |
| `references` | 引用两个子项目：`tsconfig.app.json`（浏览器应用）、`tsconfig.node.json`（Node/Vite 构建脚本） |
| `compilerOptions.baseUrl` | 基准目录为当前配置文件所在目录 |
| `compilerOptions.paths` | 路径别名：`@/*` → `src/*`，`@contracts/*` → `contracts/*` |

---

## 6. tsconfig.app.json

浏览器应用源码的 TypeScript 配置，仅包含 `src/` 目录。

| 配置项 | 说明 |
|---|---|
| `tsBuildInfoFile` | 增量编译缓存位置，加快 `tsc -b` |
| `target` | 编译目标 ES2022 |
| `useDefineForClassFields` | 使用 TC39 标准 class fields 语义 |
| `lib` | 包含 ES2022、DOM、DOM.Iterable 类型声明 |
| `module` | ESNext，与 Vite ESM 一致 |
| `types` | 引入 `vite/client`（import.meta.env）和 `node` 类型 |
| `skipLibCheck` | 跳过第三方声明文件类型检查，减少噪音 |
| `moduleResolution` | `bundler` 模式，兼容 Vite 的 ESM 行为 |
| `allowImportingTsExtensions` | 允许 import `.ts/.tsx` 扩展名，Vite 打包时自动处理 |
| `verbatimModuleSyntax` | 强制使用 `import type` / `export type` 区分值与类型导入 |
| `moduleDetection` | 强制所有文件视为模块，避免 CJS/ESM 混用问题 |
| `noEmit` | 只类型检查，不输出 JS，由 Vite 负责构建 |
| `jsx` | `react-jsx` 转换，无需每个文件顶部 import React |
| `strict` | 启用全部严格类型检查 |
| `noUnusedLocals` | 禁止未使用局部变量 |
| `noUnusedParameters` | 禁止未使用函数参数 |
| `erasableSyntaxOnly` | 只允许可在运行时擦除的语法（如 enum/namespace 被禁用） |
| `noFallthroughCasesInSwitch` | switch 禁止 case 穿透 |
| `noUncheckedSideEffectImports` | 检查副作用 import 是否声明 |
| `include` | 仅包含 `src` 目录 |

---

## 7. tsconfig.node.json

Node 环境配置文件（如 `vite.config.ts`）的 TypeScript 检查配置。

| 配置项 | 说明 |
|---|---|
| `target` | ES2023，Node 可直接运行 |
| `lib` | 仅 ES2023，不引入 DOM 类型 |
| `types` | 仅 `node`，用于 `process`、`path` 等 API |
| `moduleResolution` / `allowImportingTsExtensions` / `verbatimModuleSyntax` / `moduleDetection` / `noEmit` | 与 app 配置保持一致，适配 Vite 风格 ESM |
| `strict` 相关规则 | 严格类型检查 |
| `include` | 仅包含 `vite.config.ts` |

---

## 8. tailwind.config.js

Tailwind CSS 主题与扫描范围配置。

| 配置项 | 说明 |
|---|---|
| `darkMode` | `"class"` 模式，通过 HTML 标签上的 `dark`/`light` 类切换主题（由 `next-themes` 控制） |
| `content` | 扫描 `index.html` 和 `src/**/*.{js,ts,jsx,tsx}`，只生成实际用到的 CSS 类 |
| `theme.extend.colors` | 扩展 shadcn/ui 颜色系统，使用 HSL CSS 变量，如 `background`、`foreground`、`primary`、`destructive` 等 |
| `theme.extend.borderRadius` | 基于 `--radius` 变量生成 `xs/sm/md/lg/xl` 五级圆角 |
| `theme.extend.boxShadow` | 添加 `xs` 超小阴影 |
| `theme.extend.keyframes` / `animation` | 手风琴展开/收起、光标闪烁动画，供 Radix UI 组件使用 |
| `plugins` | 加载 `tailwindcss-animate` 插件，扩展动画工具类 |

---

## 9. postcss.config.js

PostCSS 处理链配置。

| 配置项 | 说明 |
|---|---|
| `tailwindcss` | 扫描项目文件并生成 Tailwind 工具类 |
| `autoprefixer` | 自动添加浏览器前缀（如 `-webkit-`），兼容旧浏览器 |

---

## 10. eslint.config.js

ESLint 扁平配置（Flat Config）。

| 配置项 | 说明 |
|---|---|
| `globalIgnores(['dist'])` | 忽略 `dist/` 构建产物，不对其 lint |
| `files` | 只对 `**/*.{ts,tsx}` 应用规则 |
| `extends` | 继承 JS 推荐、TS 推荐、React Hooks 推荐、React Refresh 推荐规则 |
| `languageOptions.ecmaVersion` | ECMAScript 2020 |
| `languageOptions.globals` | 声明浏览器全局变量，避免 `window`/`document` 报未定义 |

---

## 11. components.json

shadcn/ui 项目初始化配置，控制 CLI 安装组件时的目录和风格。

| 配置项 | 说明 |
|---|---|
| `$schema` | shadcn 配置 JSON Schema 地址 |
| `style` | 组件风格为 `new-york`（偏现代紧凑） |
| `rsc` | `false` 表示不使用 React Server Components |
| `tsx` | `true` 表示使用 TypeScript/TSX 组件 |
| `tailwind.config` | Tailwind 配置文件路径 |
| `tailwind.css` | 全局 CSS 文件路径，存放 CSS 变量 |
| `tailwind.baseColor` | 基础色板为 `slate` |
| `tailwind.cssVariables` | `true` 表示使用 CSS 变量定义主题色 |
| `tailwind.prefix` | 空，表示 Tailwind 类不加前缀 |
| `iconLibrary` | 图标库使用 `lucide` |
| `aliases.components` | 组件别名 `@/components` → `src/components` |
| `aliases.utils` | 工具函数别名 `@/lib/utils` → `src/lib/utils` |
| `aliases.ui` | UI 组件别名 `@/components/ui` → `src/components/ui` |
| `aliases.lib` | lib 目录别名 `@/lib` → `src/lib` |
| `aliases.hooks` | hooks 目录别名 `@/hooks` → `src/hooks` |
| `registries` | 自定义组件仓库，当前为空 |

---

## 12. index.html

Vite 应用的 HTML 入口文件。

| 元素 | 说明 |
|---|---|
| `<meta charset="UTF-8" />` | 字符编码 |
| `<meta name="viewport" />` | 移动端视口适配 |
| `<title>监管AI助手</title>` | 页面标题 |
| `<div id="root"></div>` | React 应用挂载点 |
| `<script src="/src/main.tsx">` | 加载 React 入口文件 |

---

## 13. 其他文件说明

| 文件 | 说明 |
|---|---|
| `src/index.css` | Tailwind CSS 入口 + shadcn/ui 主题变量 |
| `src/main.tsx` | React 应用启动入口，挂载 `<App />` 到 `#root` |
| `src/App.tsx` | 根路由组件，定义 `/`、`/platform`、`/assistant`、`/dashboard` 路由 |
| `contracts/types.ts` | 前后端共享领域类型，定义 4 大场景类型和枚举 |
| `contracts/errors.ts` | 前后端共享错误形状 `{ tag: "app_error"; status; message }` |

---

## 14. 开发常用命令速查

```bash
# 安装依赖
npm install

# 启动前端开发服务（自动代理 /api 到 localhost:8080）
npm run dev

# 类型检查
npm run check

# 代码检查
npm run lint

# 格式化
npm run format

# 测试
npm run test

# 生产构建
npm run build

# 预览构建产物
npm run preview
```

---

## 15. 注意事项

- 当前 `src/pages/` 目录不存在，`App.tsx` 中引用的 `Home`、`PlatformPage`、`AssistantPage`、`DashboardPage` 组件缺失，项目无法直接运行。需要补充页面实现。
- `model.mode` 在后端 `application.yml` 中配置为 `mock` 或 `http`，前端无需关心。
- 生产部署时，`/api` 反向代理应统一在 Nginx/网关层配置，而不是前端配置中。
