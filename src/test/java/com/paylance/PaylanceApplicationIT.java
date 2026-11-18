package com.paylance;

import com.paylance.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Le contexte démarre et les migrations sont cohérentes avec le mapping JPA (ddl-auto=validate). */
class PaylanceApplicationIT extends AbstractIntegrationTest {

    @Test
    void contextLoads_andSchemaIsValid() {
        Integer applied = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class);
        assertThat(applied).isEqualTo(3);
    }
}
