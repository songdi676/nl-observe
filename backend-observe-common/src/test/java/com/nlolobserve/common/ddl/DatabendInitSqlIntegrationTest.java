package com.nlolobserve.common.ddl;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.databend.DatabendContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * T03 验收：Testcontainers Databend（镜像 databend-query）执行 scripts/databend/001-init.sql 无错，
 * SHOW TABLES 含 otel_spans/otel_metrics/otel_logs 三表。
 * env-gated（详细设计 §测试策略）：Docker 不可用或镜像不可得时按 assumption 跳过，不判失败。
 */
class DatabendInitSqlIntegrationTest {

    /** 与 testcontainers databend 模块默认镜像一致（all-in-one：databend-meta + databend-query）。
     *  可用环境变量 NL_OBSERVE_DATABEND_IMAGE 覆盖 tag（如内网只缓存了特定版本） */
    private static final String DEFAULT_IMAGE_NAME = "datafuselabs/databend:latest";

    private static DatabendContainer databend;

    @BeforeAll
    static void startDatabend() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker 不可用：跳过 Databend 集成测试");
        String imageName = envOr("NL_OBSERVE_DATABEND_IMAGE", DEFAULT_IMAGE_NAME);
        try {
            databend = new DatabendContainer(DockerImageName.parse(imageName));
            databend.start();
        } catch (Throwable t) {
            if (databend != null) {
                databend.stop();
                databend = null;
            }
            assumeTrue(false, "Databend 镜像 " + imageName + " 不可得：跳过（人工环境验证），原因: " + t);
        }
    }

    @AfterAll
    static void stopDatabend() {
        if (databend != null) {
            databend.stop();
        }
    }

    @Test
    void initScriptExecutesWithoutErrorAndShowsThreeTables() throws IOException, SQLException {
        Path sqlFile = TestRepoPaths.repoRoot().resolve(DdlScriptGenerator.DEFAULT_RELATIVE_PATH);
        String script = Files.readString(sqlFile);

        try (Connection conn = DriverManager.getConnection(
                databend.getJdbcUrl(), databend.getUsername(), databend.getPassword());
                Statement stmt = conn.createStatement()) {

            for (String raw : script.split(";")) {
                String single = stripLeadingComments(raw).trim();
                if (!single.isEmpty()) {
                    stmt.execute(single);
                }
            }

            List<String> tables = new ArrayList<>();
            try (ResultSet rs = stmt.executeQuery("SHOW TABLES")) {
                while (rs.next()) {
                    tables.add(rs.getString(1));
                }
            }
            assertTrue(tables.contains(TableNames.OTEL_SPANS), "SHOW TABLES 缺 otel_spans，实际: " + tables);
            assertTrue(tables.contains(TableNames.OTEL_METRICS), "SHOW TABLES 缺 otel_metrics，实际: " + tables);
            assertTrue(tables.contains(TableNames.OTEL_LOGS), "SHOW TABLES 缺 otel_logs，实际: " + tables);
        }
    }

    private static String envOr(String key, String fallback) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? fallback : v;
    }

    /** 去掉语句前置的 -- 行注释（脚本头部注释随首条 CREATE 一起到达服务端前剥离，保持语句纯净）。 */
    private static String stripLeadingComments(String statement) {
        String[] lines = statement.split("\n");
        int first = 0;
        while (first < lines.length && lines[first].trim().startsWith("--")) {
            first++;
        }
        return String.join("\n", java.util.Arrays.copyOfRange(lines, first, lines.length));
    }
}
