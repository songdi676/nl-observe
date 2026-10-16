package com.nlolobserve.storage.config;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T08 验收：存储端写数据源装配——连接串来自 observe.databend.*（DATABEND_URL 环境变量），
 * 与查询端只读池（T09）分离；写账号可写（readOnly=false）。
 */
class DatabendDataSourceConfigTest {

    @Test
    void dataSource_bindsWritePoolProperties() {
        DatabendProperties props = new DatabendProperties();
        props.setUrl("jdbc:databend://10.1.8.33:8000/observe?presigned_url_disabled=true");
        props.setUsername("writer");
        props.setPassword("secret-placeholder");

        DataSource dataSource = new DatabendDataSourceConfig().observeStorageDataSource(props);

        assertThat(dataSource).isInstanceOf(HikariDataSource.class);
        HikariDataSource hikari = (HikariDataSource) dataSource;
        assertThat(hikari.getJdbcUrl()).isEqualTo(props.getUrl());
        assertThat(hikari.getUsername()).isEqualTo("writer");
        assertThat(hikari.getPoolName()).isEqualTo("observe-storage");
        assertThat(hikari.isReadOnly()).isFalse();
        assertThat(hikari.getMaximumPoolSize()).isEqualTo(DatabendDataSourceConfig.DEFAULT_MAX_POOL_SIZE);
    }
}
