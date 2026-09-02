package com.migration.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code migration.source} — identifies which {@code SourceConnector} bean to resolve
 * (via {@code type}, matched against a {@code @ConnectorFor} value) and how to connect.
 */
public record SourceConfig(
        @NotBlank String type,
        @Valid @NotNull ConnectionConfig connection,
        @Valid @DefaultValue PoolConfig pool) {
}
