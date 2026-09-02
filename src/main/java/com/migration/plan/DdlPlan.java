package com.migration.plan;

import java.util.List;

/**
 * The result of diffing the desired schema against the destination's actually
 * introspected schema (design doc §3): the additive actions to take, and any
 * present-but-different conflicts that are flagged rather than resolved.
 */
public record DdlPlan(List<DdlAction> actions, List<DdlConflict> conflicts) {

    public DdlPlan {
        actions = List.copyOf(actions == null ? List.of() : actions);
        conflicts = List.copyOf(conflicts == null ? List.of() : conflicts);
    }

    public static DdlPlan empty() {
        return new DdlPlan(List.of(), List.of());
    }

    public boolean hasConflicts() {
        return !conflicts.isEmpty();
    }
}
