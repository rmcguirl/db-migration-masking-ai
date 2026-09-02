package com.migration.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/** A human-declared natural key for one table, taking precedence over an AI suggestion. */
public record NaturalKeyOverride(
        @NotBlank String table,
        @NotEmpty List<String> columns) {
}
