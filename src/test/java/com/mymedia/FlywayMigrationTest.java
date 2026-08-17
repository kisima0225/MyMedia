package com.mymedia;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class FlywayMigrationTest extends AbstractIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void pgTrgmExtensionIsInstalled() {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM pg_extension WHERE extname = 'pg_trgm'", Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void chineseSubstringSimilarityIsUsable() {
        Double similarity = jdbc.queryForObject(
                "SELECT similarity('进击的巨人', '巨人')", Double.class);
        assertThat(similarity).isGreaterThan(0.0);
    }
}
