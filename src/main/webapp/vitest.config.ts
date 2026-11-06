import { defineConfig } from "vitest/config";
import path from "path";

// Vitest 测试配置文件的根目录
const templateRoot = path.resolve(import.meta.dirname);

export default defineConfig({
  // 测试根目录与配置文件所在目录一致
  root: templateRoot,
  resolve: {
    // 与 vite.config.ts 保持一致的别名，测试代码中也能用 @/xxx 和 @contracts/xxx
    alias: {
      "@": path.resolve(templateRoot, "src"),
      "@contracts": path.resolve(templateRoot, "contracts"),
    },
  },
  test: {
    // 测试在 Node 环境运行（前端组件测试可改为 jsdom，但当前项目未安装）
    environment: "node",
    // 测试文件匹配规则：src 下任意层级的 .test.ts / .spec.ts / .test.tsx / .spec.tsx
    include: ["src/**/*.test.ts", "src/**/*.spec.ts", "src/**/*.test.tsx", "src/**/*.spec.tsx"],
  },
});
