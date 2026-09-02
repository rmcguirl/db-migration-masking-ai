package com.migration.orchestrator;

import com.migration.connector.api.DestinationConnector;
import com.migration.connector.api.SourceConnector;
import com.migration.plan.MigrationPlan;
import com.migration.referential.DependencyGraph;

/**
 * Runs one Spring Batch {@code Job} with one {@code Step} per table, wired in dependency
 * order (design doc §7 step 8–9).
 */
public interface MigrationJobService {

    JobExecutionResult loadAll(MigrationPlan plan, DependencyGraph graph, SourceConnector source,
                               DestinationConnector destination);
}
