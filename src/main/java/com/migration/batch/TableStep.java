package com.migration.batch;

import org.springframework.batch.core.Step;

/** A table's built {@link Step} plus its writer, so the caller can read final insert/update totals after execution. */
public record TableStep(Step step, UpsertItemWriter writer) {
}
