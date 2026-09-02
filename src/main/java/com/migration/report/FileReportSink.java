package com.migration.report;

import com.migration.domain.exception.MigrationException;
import com.migration.orchestrator.ReportSink;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Writes the {@code --plan-only} report to {@code <basePath>.txt} and {@code <basePath>.json}. */
public class FileReportSink implements ReportSink {

    private final Path basePath;

    public FileReportSink(Path basePath) {
        this.basePath = basePath;
    }

    @Override
    public void writeHumanReadable(String text) {
        write(basePath.resolveSibling(basePath.getFileName() + ".txt"), text);
    }

    @Override
    public void writeJson(String json) {
        write(basePath.resolveSibling(basePath.getFileName() + ".json"), json);
    }

    private void write(Path path, String content) {
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            Files.writeString(path, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new MigrationException("failed to write report to " + path, e);
        }
    }
}
