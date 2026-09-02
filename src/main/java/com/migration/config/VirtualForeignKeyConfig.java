package com.migration.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * A human-declared logical foreign key for a source with no enforced FK metadata
 * (MyISAM tables, a document store, a logical-only relationship) — participates in
 * {@code ReferentialGraphBuilder} construction identically to an introspected FK
 * (design doc §5).
 */
public record VirtualForeignKeyConfig(
        @NotBlank String childTable,
        @NotEmpty List<String> childColumns,
        @NotBlank String parentTable,
        @NotEmpty List<String> parentColumns) {
}
