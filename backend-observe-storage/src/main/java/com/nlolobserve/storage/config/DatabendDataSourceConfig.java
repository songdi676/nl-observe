package com.nlolobserve.storage.config;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Databend 写数据源（详设 §1.3 repo/，T08）。
 * 连接串来自 observe.databend.*（DATABEND_URL 环境变量占位），与查询端只读池（T09）分离；
 * 自定义 DataSource bean 使 {@link DataSourceAutoConfiguration} 的 Hikari 默认装配退让，
 * JdbcTemplate/NamedParameterJdbcTemplate 由 JdbcTemplateAutoConfiguration 基于本池装配。
 * readOnly=false：本进程是三表唯一写入方（REPLACE INTO batch）。
 */
@Configuration(proxyBeanMethods = false)
public class DatabendDataSourceConfig {

    static final int DEFAULT_MAX_POOL_SIZE = 10;

    @Bean
    public DataSource observeStorageDataSource(DatabendProperties properties) {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(properties.getUrl());
        dataSource.setUsername(properties.getUsername());
        dataSource.setPassword(properties.getPassword());
        dataSource.setPoolName("observe-storage");
        dataSource.setMaximumPoolSize(DEFAULT_MAX_POOL_SIZE);
        return dataSource;
    }
}
