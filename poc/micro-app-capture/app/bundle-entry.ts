/**
 * T21 POC 子应用 bundle 入口（esbuild IIFE 打包，micro-app 沙箱内执行）。
 * 仅做「取挂载点 + 调 runSubApp」——逻辑全在 src/sub-app.ts（control 场景直接 import，
 * 保证该文件有测试进程覆盖；本入口是构建期胶水，不计入覆盖率聚合，见根 vitest.config.ts
 * coverage.exclude）。
 *
 * __POC_MODE__/__POC_BACKEND_ORIGIN__ 由 esbuild define 烘焙（harness.buildSubAppBundle）。
 */
import { runSubApp } from '../src/sub-app';

declare const __POC_MODE__: string;
declare const __POC_BACKEND_ORIGIN__: string;

const container = document.getElementById('sub-app');
if (container) {
  runSubApp({
    mode: __POC_MODE__ === 'host' ? 'host' : 'sub',
    backendOrigin: __POC_BACKEND_ORIGIN__,
    container,
  });
}
