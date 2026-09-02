package com.migration.orchestrator;

import com.migration.plan.DdlPlan;
import com.migration.plan.MigrationPlan;

/** For {@code --plan-only}, renders the plan plus DDL diff to a human-readable and machine-readable report (design doc §7). */
public interface PlanReportWriter {

    void write(MigrationPlan plan, DdlPlan ddlDiff, ReportSink sink);
}
