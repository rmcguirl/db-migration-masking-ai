package com.migration.report;

import com.migration.orchestrator.ReportSink;

/** Writes the {@code --plan-only} report to stdout — the human-readable form only, to keep console output scannable. */
public class ConsoleReportSink implements ReportSink {

    @Override
    public void writeHumanReadable(String text) {
        System.out.println(text);
    }

    @Override
    public void writeJson(String json) {
        // intentionally not printed to stdout; use FileReportSink for the machine-readable form
    }
}
