package com.migration.plan;

import com.migration.domain.CanonicalType;
import com.migration.domain.NativeTypeDescriptor;
import com.migration.domain.TableRef;

/**
 * A destination column that already exists but is not canonical-type-compatible with
 * the desired schema — never auto-altered, recorded here instead so it surfaces as a
 * run-blocking warning in the audit log and (for {@code --plan-only}) the plan report
 * (design doc §3).
 */
public record DdlConflict(
        TableRef table,
        String column,
        CanonicalType expectedType,
        NativeTypeDescriptor actualType,
        String reason) {
}
