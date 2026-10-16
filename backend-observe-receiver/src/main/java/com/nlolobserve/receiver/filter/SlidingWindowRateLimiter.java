package com.nlolobserve.receiver.filter;

import java.util.ArrayDeque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * 按 key（客户端 IP）的滑动窗口限流器（T06，详设 §1.2 filter/RateLimitFilter）。
 * 语义：过去 60 秒内已放行的请求数达到 limit 后，后续请求在窗口滑动前一律拒绝（429）。
 * - 只计数放行请求：被拒绝的请求不占额度（拒绝后窗口内仍可恢复，不自我锁定）。
 * - key 空间受前置 CIDR 白名单约束（过滤器链上白名单判定在限流之前），
 *   白名单外来源不会创建窗口，避免任意源 IP 撑爆窗口表（DoS 面收敛）。
 * - 时钟可注入（LongSupplier），测试以确定性时钟断言窗口滑动。
 */
public final class SlidingWindowRateLimiter {

    /** 窗口长度（毫秒）：与 rate-limit-per-min 的"分钟"口径一致 */
    private static final long WINDOW_MILLIS = 60_000L;

    private final long limit;
    private final LongSupplier clockMillis;
    private final ConcurrentHashMap<String, ArrayDeque<Long>> windows = new ConcurrentHashMap<>();

    /**
     * @param limitPerMin 每分钟（滑动窗口）最多放行的请求数，必须 > 0
     * @param clockMillis 时钟（毫秒），测试注入
     */
    public SlidingWindowRateLimiter(long limitPerMin, LongSupplier clockMillis) {
        if (limitPerMin <= 0) {
            throw new IllegalArgumentException("rate-limit-per-min 必须 > 0");
        }
        this.limit = limitPerMin;
        this.clockMillis = clockMillis;
    }

    /**
     * 尝试为一个 key 放行一次请求。
     *
     * @param key 客户端标识（IP 字面量）
     * @return true=放行；false=窗口内已达上限（调用方应返回 429）
     */
    public boolean tryAcquire(String key) {
        ArrayDeque<Long> window = windows.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (window) {
            long now = clockMillis.getAsLong();
            while (!window.isEmpty() && window.peekFirst() <= now - WINDOW_MILLIS) {
                window.removeFirst();
            }
            if (window.size() >= limit) {
                return false;
            }
            window.addLast(now);
            return true;
        }
    }

    /**
     * 低频清扫：移除窗口已全过期的 key（评审 F2/Minor：窗口表否则只增不减——
     * 同 key 惰性清理无法回收长期不再出现的源 IP，进程长期运行内存随"曾出现过的
     * 白名单内源 IP 数"单调增长，NAT/LB 轮换源 IP 场景尤甚）。
     * 由 EmbeddedCollectorConfig 的 Vert.x 周期定时器低频调用，成本 O(keyCount)。
     */
    public void evictIdle() {
        windows.entrySet().removeIf(entry -> {
            ArrayDeque<Long> window = entry.getValue();
            synchronized (window) {
                long now = clockMillis.getAsLong();
                while (!window.isEmpty() && window.peekFirst() <= now - WINDOW_MILLIS) {
                    window.removeFirst();
                }
            }
            return window.isEmpty();
        });
    }

    /**
     * 当前持有的 key 数（诊断/测试用）。
     */
    public int keyCount() {
        return windows.size();
    }
}
