import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      // 开发期将 /api 代理到 backend-observe-query（本地联调用，生产由业务网关统一接入）
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
  },
  test: {
    environment: 'jsdom',
    include: ['tests/**/*.spec.ts'],
    coverage: {
      provider: 'v8',
      reporter: ['text', 'json-summary'],
      reportsDirectory: 'coverage',
      include: ['src/**'],
      // main.ts 为启动装配；env.d.ts/types.ts 为纯类型声明（无运行时代码，type-only import 被擦除）
      exclude: ['src/main.ts', 'src/env.d.ts', 'src/api/types.ts']
    }
  }
})
