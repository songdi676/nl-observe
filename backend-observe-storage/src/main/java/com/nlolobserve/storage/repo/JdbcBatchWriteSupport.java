package com.nlolobserve.storage.repo;

import java.util.Arrays;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

/**
 * 三表 JDBC batch 写入公共逻辑（详设 §1.3 repo/，T08）：
 * 行参数按 {@value #BATCH_SIZE} 分块调用 NamedParameterJdbcTemplate.batchUpdate，
 * SQL 一律为静态 REPLACE INTO 模板（列名来自 common ddl 常量），值全部经命名参数绑定，
 * 不做任何字符串拼接（安全 A03）。去重身份 = 各表聚簇键（DdlScripts 注释，详设 §3）。
 */
final class JdbcBatchWriteSupport {

    /** JDBC batch 分块上限（详设 §1.3：500/批，与消费端 max-poll-records=500 对齐） */
    static final int BATCH_SIZE = 500;

    private JdbcBatchWriteSupport() {
    }

    /** 按 {@value #BATCH_SIZE} 分块批量执行（空数组不执行任何 SQL）。 */
    static void chunkedBatchUpdate(NamedParameterJdbcTemplate namedJdbc, String sql, SqlParameterSource[] sources) {
        for (int from = 0; from < sources.length; from += BATCH_SIZE) {
            int to = Math.min(from + BATCH_SIZE, sources.length);
            namedJdbc.batchUpdate(sql, Arrays.copyOfRange(sources, from, to));
        }
    }
}
