package com.nlolobserve.storage.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 启动自检（详设 §1.3，T08 验收 #3）：
 * 1) DatabendProperties.validate()——url 必须含 presigned_url_disabled=true（NFR-04），否则 fail-fast；
 * 2) 执行 {@code SELECT 1} 验证写账号可连（快速暴露 presigned_url/网络/权限问题），失败即启动失败。
 * observe.databend.check-on-startup=false 时跳过连接自检（集成测试口径），presigned 校验不豁免。
 */
@Component
public class DatabendStartupCheck implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(DatabendStartupCheck.class);

    private final DatabendProperties properties;
    private final JdbcTemplate jdbcTemplate;

    public DatabendStartupCheck(DatabendProperties properties, JdbcTemplate jdbcTemplate) {
        this.properties = properties;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        properties.validate();
        if (!properties.isCheckOnStartup()) {
            LOG.info("Databend 启动连接自检跳过（observe.databend.check-on-startup=false）");
            return;
        }
        try {
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            LOG.info("Databend 写连接自检通过（SELECT 1）");
        } catch (RuntimeException e) {
            throw new IllegalStateException("Databend 写连接自检失败（SELECT 1）: " + e.getMessage(), e);
        }
    }
}
