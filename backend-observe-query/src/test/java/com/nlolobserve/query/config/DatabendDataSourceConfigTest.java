package com.nlolobserve.query.config;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T09 验收 #1：只读账号数据源装配——连接串来自 observe.databend.*（*_RO 环境变量），
 * 连接级 readOnly=true（纵深防御；账号级只读由只读账号本身承担）。
 */
class DatabendDataSourceConfigTest {

    @Test
    void dataSource_bindsReadOnlyProperties() {
        DatabendProperties props = new DatabendProperties();
        props.setUrl("jdbc:databend://10.1.8.33:8000/observe?presigned_url_disabled=true");
        props.setUsername("nl_observe_ro");
        props.setPassword("secret-placeholder");

        DataSource dataSource = new DatabendDataSourceConfig().observeQueryDataSource(props);

        assertThat(dataSource).isInstanceOf(HikariDataSource.class);
        HikariDataSource hikari = (HikariDataSource) dataSource;
        assertThat(hikari.getJdbcUrl()).isEqualTo(props.getUrl());
        assertThat(hikari.getUsername()).isEqualTo("nl_observe_ro");
        assertThat(hikari.getPoolName()).isEqualTo("observe-query-ro");
        assertThat(hikari.isReadOnly()).isTrue();
        assertThat(hikari.getMaximumPoolSize()).isEqualTo(DatabendDataSourceConfig.DEFAULT_MAX_POOL_SIZE);
    }
}
