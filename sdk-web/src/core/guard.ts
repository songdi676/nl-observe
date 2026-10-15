/**
 * 单例守卫（详设 §5.1 / poc-notes §2 POC 发现 4）：
 * OTel JS 2.x 全局注册一次性，重复 init 会互相覆盖/告警，故 SDK 以
 * 全局 `__NL_OBSERVE__`（{version, instance}）守卫：重复 createClient 返回既有实例并 warn。
 *
 * 用 globalThis 而非 window：node（构建产物测试）与 jsdom（双宿主测试）环境均可加载。
 */
import type { NlObserveClient } from './client';

export interface NlObserveGuardEntry {
  version: string;
  instance: NlObserveClient;
}

const GUARD_KEY = '__NL_OBSERVE__';

function globalScope(): Record<string, unknown> {
  return globalThis as unknown as Record<string, unknown>;
}

/** 读取既有守卫条目（无则 undefined）。 */
export function getGuardedClient(): NlObserveGuardEntry | undefined {
  return globalScope()[GUARD_KEY] as NlObserveGuardEntry | undefined;
}

/** 写入守卫条目（仅 createClient 首次创建时调用）。 */
export function setGuardedClient(entry: NlObserveGuardEntry): void {
  globalScope()[GUARD_KEY] = entry;
}
