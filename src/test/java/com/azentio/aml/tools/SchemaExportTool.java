package com.azentio.aml.tools;

import org.junit.jupiter.api.Test;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * Development helper: exports the PostgreSQL DDL implied by the entity model to
 * {@code target/schema-postgres.sql} so the Flyway migration can be kept in step. Not part of the
 * regular build - run explicitly with {@code -Dtest=SchemaExportTool}.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@TestPropertySource(
        properties = {
            "spring.jpa.hibernate.ddl-auto=none",
            "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
            "spring.jpa.properties.jakarta.persistence.schema-generation.database.action=none",
            "spring.jpa.properties.jakarta.persistence.schema-generation.scripts.action=create",
            "spring.jpa.properties.jakarta.persistence.schema-generation.scripts.create-target=target/schema-postgres.sql"
        })
class SchemaExportTool {

    @Test
    void exportSchema() {
        // Script generation happens during EntityManagerFactory bootstrap.
    }
}
