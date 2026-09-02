package com.migration.referential;

import com.migration.domain.SchemaModel;
import com.migration.plan.VirtualForeignKey;

import java.util.List;

/**
 * Builds the {@link DependencyGraph} from a schema's introspected FK edges
 * ({@code SchemaModel.foreignKeys()}) merged with config-declared virtual FKs, for load
 * ordering (design doc §5).
 */
public interface ReferentialGraphBuilder {

    DependencyGraph build(SchemaModel model, List<VirtualForeignKey> overrides);
}
