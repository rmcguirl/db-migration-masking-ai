package com.migration.config;

import jakarta.validation.constraints.NotBlank;

/**
 * JDBC-style connection details for a relational connector, or the equivalent for a
 * document-store connector (e.g. a Mongo connection string in {@code url}).
 */
public record ConnectionConfig(
        @NotBlank String url,
        String username,
        String password) {
}
