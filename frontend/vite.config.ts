import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],

  build: {
    // 代码分割配置
    rollupOptions: {
      output: {
        manualChunks: {
          // React 核心
          'vendor-react': ['react', 'react-dom', 'react-router-dom'],

          // 图表库
          'vendor-charts': ['recharts'],

          // UI 工具
          'vendor-ui': ['lucide-react', 'framer-motion', 'clsx', 'tailwind-merge'],
        },
      },
    },

    // chunk 大小警告阈值
    chunkSizeWarningLimit: 500,

    // 生成 sourcemap (生产环境可关闭)
    sourcemap: false,

    // 压缩配置
    minify: 'esbuild',

    // 目标浏览器
    target: 'es2020',
  },

  // 优化依赖预构建
  optimizeDeps: {
    include: ['react', 'react-dom', 'react-router-dom', 'recharts', 'lucide-react'],
  },

  // 开发服务器配置
  server: {
    port: 5173,
    host: true,
  },

  test: {
    environment: 'jsdom',
    setupFiles: './src/test/setup.ts',
    css: true,
    clearMocks: true,
    restoreMocks: true,
    coverage: {
      provider: 'istanbul',
      reporter: ['text-summary', 'html', 'json-summary'],
      reportsDirectory: './coverage',
      exclude: ['src/**/*.test.{ts,tsx}', 'src/test/**'],
    },
    include: ['src/**/*.test.{ts,tsx}'],
  },
})
