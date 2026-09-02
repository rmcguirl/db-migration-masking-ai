package com.migration.referential;

import com.migration.domain.ForeignKeyRef;
import com.migration.domain.SchemaModel;
import com.migration.domain.TableDescriptor;
import com.migration.domain.TableRef;
import com.migration.plan.VirtualForeignKey;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Merges introspected FKs ({@code SchemaModel.foreignKeys()}) with config-declared
 * virtual FKs into one edge set, builds every table as a graph node (so a table with no
 * FKs at all still gets its own layer, design doc §5), and excludes self-loops from the
 * ordering-relevant edge set (design doc §5: self-referencing FKs need no special-casing
 * because masking correctness doesn't depend on load order at all — see
 * {@code DefaultDependencyGraph}).
 */
@Component
public class DefaultReferentialGraphBuilder implements ReferentialGraphBuilder {

    @Override
    public DependencyGraph build(SchemaModel model, List<VirtualForeignKey> overrides) {
        Set<TableRef> nodes = new LinkedHashSet<>();
        for (TableDescriptor table : model.tables()) {
            nodes.add(table.ref());
        }

        List<ForeignKeyRef> allEdges = new ArrayList<>(model.foreignKeys());
        for (VirtualForeignKey virtual : overrides) {
            allEdges.add(virtual.toForeignKeyRef());
        }

        Map<TableRef, Set<TableRef>> childrenOf = new HashMap<>();
        Map<TableRef, Set<TableRef>> parentsOf = new HashMap<>();
        for (ForeignKeyRef edge : allEdges) {
            TableRef child = edge.childTable();
            TableRef parent = edge.parentTable();
            nodes.add(child);
            nodes.add(parent);
            if (child.equals(parent)) {
                continue; // self-loop: not an ordering constraint between two different tables
            }
            childrenOf.computeIfAbsent(parent, p -> new HashSet<>()).add(child);
            parentsOf.computeIfAbsent(child, c -> new HashSet<>()).add(parent);
        }

        return new DefaultDependencyGraph(nodes, childrenOf, parentsOf);
    }
}
