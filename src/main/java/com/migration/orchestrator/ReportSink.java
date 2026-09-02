package com.migration.orchestrator;

/** Where {@code PlanReportWriter} writes the {@code --plan-only} report (design doc §7). */
public interface ReportSink {

    void writeHumanReadable(String text);

    void writeJson(String json);
}
