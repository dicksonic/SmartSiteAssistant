// PostCSS 处理链：Tailwind CSS 生成实用类 + autoprefixer 补全浏览器前缀
export default {
  plugins: {
    // 扫描项目文件，按需生成 Tailwind 工具类
    tailwindcss: {},
    // 自动添加 CSS 浏览器前缀（如 -webkit-/-moz-），兼容旧浏览器
    autoprefixer: {},
  },
}
