/**
 * T21 POC：子应用 bundle 构建器（子进程运行）。
 *
 * 为何子进程：esbuild JS API 在模块加载时断言
 * `new TextEncoder().encode("") instanceof Uint8Array`——vitest jsdom 环境把 TextEncoder/
 * Uint8Array 换成 jsdom 版本，该断言在 jsdom worker 内必然失败（环境假象）。测试进程 spawn
 * `node build-bundle.mjs <mode> <origin>`：子进程是干净 Node 环境，构建产物经 stdout 回传。
 *
 * 用法：node build-bundle.mjs <sub|host> <backendOrigin>  → stdout = IIFE bundle 文本
 */
/* global process, console, URL */
import { fileURLToPath } from 'node:url';
import { build } from 'esbuild';

const [mode, origin] = process.argv.slice(2);
if (mode !== 'sub' && mode !== 'host' || !origin) {
  console.error(`usage: node build-bundle.mjs <sub|host> <backendOrigin>`);
  process.exit(2);
}

const here = fileURLToPath(new URL('..', import.meta.url));
const sdkSrcEntry = fileURLToPath(new URL('../../../sdk-web/src/index.ts', import.meta.url));

const result = await build({
  absWorkingDir: here,
  entryPoints: ['app/bundle-entry.ts'],
  bundle: true,
  format: 'iife',
  platform: 'browser',
  target: 'es2020',
  write: false,
  define: {
    __POC_MODE__: JSON.stringify(mode),
    __POC_BACKEND_ORIGIN__: JSON.stringify(origin),
  },
  // SDK 走源码打进 bundle（与测试进程 vitest alias 同口径：dist 未构建）
  alias: {
    '@nl-observe/sdk-web': sdkSrcEntry,
  },
  logLevel: 'silent',
});

process.stdout.write(result.outputFiles[0].text);
