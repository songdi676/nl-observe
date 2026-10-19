/**
 * 演示宿主页构建配置：SDK 走 alias 直引 sdk-web/src 源码（与 tests/e2e/host 同口径，
 * 免先 build SDK）。/v1（OTLP）与 /mock-api（mock 后端）由部署侧
 * deploy/docker/nginx/demo.conf 同源代理提供，构建产物为纯静态 dist。
 */
import { fileURLToPath } from 'node:url';
import { defineConfig } from 'vite';

export default defineConfig({
  resolve: {
    alias: {
      '@nl-observe/sdk-web': fileURLToPath(new URL('../../../sdk-web/src/index.ts', import.meta.url)),
    },
  },
});
