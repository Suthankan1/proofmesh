package com.proofmesh.controlplane;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class DatabaseMigrationTest {

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void flywayCreatesIdentityTables() {
        assertThat(tableExists("organizations")).isTrue();
        assertThat(tableExists("operator_users")).isTrue();
        assertThat(tableExists("organization_memberships")).isTrue();
    }

    private Boolean tableExists(String tableName) {
        return jdbcTemplate.queryForObject(
                """
                SELECT EXISTS (
                    SELECT 1
                    FROM information_schema.tables
                    WHERE table_schema = 'proofmesh'
                    AND table_name = ?
                )
                """,
                Boolean.class,
                tableName
        );
    }
}