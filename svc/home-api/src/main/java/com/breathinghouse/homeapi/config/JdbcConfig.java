package com.breathinghouse.homeapi.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class JdbcConfig {

    private final JdbcTemplate jdbcTemplate;
    private final int queryTimeoutSeconds;

    public JdbcConfig(
            JdbcTemplate jdbcTemplate,
            @Value("${home-api.database.query-timeout-seconds}") int queryTimeoutSeconds) {
        this.jdbcTemplate = jdbcTemplate;
        this.queryTimeoutSeconds = queryTimeoutSeconds;
    }

    @PostConstruct
    void configureQueryTimeout() {
        jdbcTemplate.setQueryTimeout(queryTimeoutSeconds);
    }
}
