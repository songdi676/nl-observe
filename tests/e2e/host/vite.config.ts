/**
 * T23 E2E 宿主页 vite dev 配置（详设 §6.4「vue3-host 起 vite dev」）：
 * - SDK 走 alias 直接引 sdk-web/src 源码（宿主即被测链路一部分，无需先 build dist）
 * - mock 后端：/mock-api/* 中间件挂在同一 vite 进程——宿主页的 fetch 是真实
 *   网络请求（跨进程 HTTP），SDK fetch 包装据此产生 REST span
 * - 默认 127.0.0.1:4173，E2E_HOST_PORT 可覆盖（须与 playwright.config.ts /
 *   e2e-env.ts 的读取口径一致，否则 webServer 等待端口与 vite 实际端口错位）；
 *   strictPort 下端口被占即启动失败。多 worktree 并行跑 E2E 时务必给每个
 *   worktree 设不同 E2E_HOST_PORT，避免静默测到别的 worktree 的宿主页
 *   （评审 arch M-1 串扰）
 */
import { fileURLToPath } from 'node:url';
import { defineConfig, type Plugin, type ViteDevServer } from 'vite';
import vue from '@vitejs/plugin-vue';

function mockApiPlugin(): Plugin {
  return {
    name: 'nl-observe-e2e-mock-api',
    configureServer(server: ViteDevServer) {
      server.middlewares.use('/mock-api', (req, res) => {
        const url = req.url ?? '';
        if (url.startsWith('/orders') || url.startsWith('/payments')) {
          res.setHeader('content-type', 'application/json');
          res.end(JSON.stringify({ code: 0, message: 'ok', data: { id: 'mock-1' } }));
        } else {
          res.statusCode = 404;
          res.setHeader('content-type', 'application/json');
          res.end(JSON.stringify({ code: 40401, message: 'not found', data: null }));
        }
      });
    },
  };
}

export default defineConfig({
  plugins: [vue(), mockApiPlugin()],
  resolve: {
    alias: {
      '@nl-observe/sdk-web': fileURLToPath(new URL('../../../sdk-web/src/index.ts', import.meta.url)),
    },
  },
  server: { host: '127.0.0.1', port: Number(process.env.E2E_HOST_PORT ?? 4173), strictPort: true },
});
