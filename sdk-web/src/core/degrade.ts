/**
 * 静默降级诊断通道（T17 降级策略，NFR-03 / 详设 §4.2「SDK init 失败/endpoint 不可达」行）。
 *
 * OTLP endpoint 不可达时，OTel SDK 经 @opentelemetry/api 的 diag 通道记录导出失败/批次丢弃
 * （error/warn 级）。API 包默认无 diag logger → 这些记录被静默丢弃，宿主无从观察「上报已降级」
 * 的事实。本模块把 SDK diag 全级别统一落到 console.debug：
 * - 静默：不占 console.error/warn（不触宿主告警面板、不污染用户 console）；
 * - 可诊断：开启浏览器 debug 级别即可复核降级与丢弃原因；
 * - 幂等：仅挂一次（多客户端/重复 init 不覆盖宿主可能自设的通道——首次安装即锁定）。
 *
 * 注意：diag.setLogger 是 API 包级单例，安装后影响本进程全部 OTel SDK 组件的日志出口
 * （本 SDK 独占该页面时语义干净；与宿主自设 diag 冲突时以先安装者为准）。
 */
import { diag, DiagLogLevel, type DiagLogger } from '@opentelemetry/api';

class ConsoleDebugDiagLogger implements DiagLogger {
  readonly error: DiagLogFn = (message, ...args) => this.emit(message, args);
  readonly warn: DiagLogFn = (message, ...args) => this.emit(message, args);
  readonly info: DiagLogFn = (message, ...args) => this.emit(message, args);
  readonly debug: DiagLogFn = (message, ...args) => this.emit(message, args);
  readonly verbose: DiagLogFn = (message, ...args) => this.emit(message, args);

  private emit(message: string, args: unknown[]): void {
    if (args.length === 0) {
      console.debug(message);
    } else {
      console.debug(message, ...args);
    }
  }
}

type DiagLogFn = (message: string, ...args: unknown[]) => void;

let installed = false;

/** 安装 console.debug 诊断通道（幂等；init 配置 endpoint 后调用）。 */
export function installSilentDiagnostics(): void {
  if (installed) return;
  installed = true;
  diag.setLogger(new ConsoleDebugDiagLogger(), DiagLogLevel.ALL);
}
