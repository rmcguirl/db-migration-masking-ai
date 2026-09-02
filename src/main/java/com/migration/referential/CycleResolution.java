package com.migration.referential;

import com.migration.domain.TableRef;

import java.util.List;

/**
 * How one detected cycle resolves: which strategy applies, and (for
 * {@code DECLARED_ORDER}) the linear load order to use in place of topological sort,
 * which can't linearize a true cycle (design doc §5).
 *
 * @param cycleTables the tables in this strongly-connected component
 * @param strategy    the resolved outcome
 * @param loadOrder   the load order to use for these tables; empty for
 *                    {@code UNSUPPORTED} (run-blocking, no order is safe to assume),
 *                    {@code cycleTables} itself (order irrelevant) for
 *                    {@code DEFERRED_TRANSACTION}, and the config-declared order for
 *                    {@code DECLARED_ORDER}
 */
public record CycleResolution(List<TableRef> cycleTables, CycleStrategyOutcome strategy, List<TableRef> loadOrder) {

    public CycleResolution {
        cycleTables = List.copyOf(cycleTables == null ? List.of() : cycleTables);
        loadOrder = List.copyOf(loadOrder == null ? List.of() : loadOrder);
    }
}
