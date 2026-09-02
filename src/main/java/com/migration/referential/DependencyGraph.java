package com.migration.referential;

import com.migration.domain.TableRef;

import java.util.List;
import java.util.Set;

/**
 * A parent-precedes-child table dependency graph built from FK edges (design doc §5).
 *
 * @see ReferentialGraphBuilder
 */
public interface DependencyGraph {

    /** Every table, ordered so a parent always precedes its children; tables stuck in a cycle are appended last. */
    List<TableRef> topologicalLoadOrder();

    /** Tables grouped into parallelizable layers — no dependency between tables in the same layer. */
    List<List<TableRef>> loadLayers();

    /** Strongly-connected components of size &gt; 1 (true multi-table cycles; self-referencing FKs are not cycles). */
    List<List<TableRef>> detectCycles();

    /** Every table reachable by following child edges from {@code table} (not including {@code table} itself). */
    Set<TableRef> transitiveDependents(TableRef table);
}
