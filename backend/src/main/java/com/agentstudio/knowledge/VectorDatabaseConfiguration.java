package com.agentstudio.knowledge;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
@ConditionalOnProperty(prefix = "agent-studio.vector", name = "enabled", havingValue = "true")
public class VectorDatabaseConfiguration {

    @Bean("vectorDataSource")
    DataSource vectorDataSource(
            @Value("${agent-studio.vector.url}") String url,
            @Value("${agent-studio.vector.username}") String username,
            @Value("${agent-studio.vector.password}") String password) {
        var dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(url);
        dataSource.setUsername(username);
        dataSource.setPassword(password);
        dataSource.setMaximumPoolSize(4);
        dataSource.setConnectionTimeout(5_000);
        dataSource.setPoolName("VectorPool");
        return dataSource;
    }

    @Bean("vectorJdbcTemplate")
    JdbcTemplate vectorJdbcTemplate(@Qualifier("vectorDataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }
}

