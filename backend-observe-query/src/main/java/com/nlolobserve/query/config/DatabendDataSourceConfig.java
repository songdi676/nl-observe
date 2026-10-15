package com.nlolobserve.query.config;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Databend 只读数据源（详设 §1.4/§5）。
 * 连接串来自 observe.databend.*（DATABEND_URL_RO 环境变量占位），与存储端写账号分离；
 * 自定义 DataSource bean 使 {@link DataSourceAutoConfiguration} 的 Hikari 默认装配退让。
 * readOnly=true 为连接级只读提示（纵深防御）；账号级只读边界由只读账号本身承担。
 */
@Configuration(proxyBeanMethods = false)
public class DatabendDataSourceConfig {

    static final int DEFAULT_MAX_POOL_SIZE = 10;

    @Bean
    public DataSource observeQueryDataSource(DatabendProperties properties) {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(properties.getUrl());
        dataSource.setUsername(properties.getUsername());
        dataSource.setPassword(properties.getPassword());
        dataSource.setPoolName("observe-query-ro");
        dataSource.setMaximumPoolSize(DEFAULT_MAX_POOL_SIZE);
        dataSource.setReadOnly(true);
        return dataSource;
    }
}
