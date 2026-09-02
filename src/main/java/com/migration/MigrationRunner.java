package com.migration;

import com.migration.orchestrator.MigrationOrchestrator;
import com.migration.orchestrator.RunMode;
import com.migration.orchestrator.RunResult;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * CLI entry point: reads {@code --plan-only}, invokes {@link MigrationOrchestrator}, and
 * exposes the result as a process exit code (design doc §8) — {@code 0} success,
 * {@code 1} partial failure, {@code 2} hard failure — so it's script/CI friendly.
 */
@Component
public class MigrationRunner implements CommandLineRunner, ExitCodeGenerator {

    private final MigrationOrchestrator orchestrator;
    private volatile RunResult lastResult;

    public MigrationRunner(MigrationOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @Override
    public void run(String... args) {
        RunMode mode = Arrays.asList(args).contains("--plan-only") ? RunMode.PLAN_ONLY : RunMode.FULL;
        try {
            lastResult = orchestrator.run(mode);
            System.out.println(lastResult.outcome() + ": " + lastResult.message());
        } catch (RuntimeException e) {
            lastResult = RunResult.hardFailure(e.getMessage());
            System.err.println("HARD_FAILURE: " + e.getMessage());
        }
    }

    @Override
    public int getExitCode() {
        if (lastResult == null) {
            return 2;
        }
        return switch (lastResult.outcome()) {
            case SUCCESS -> 0;
            case PARTIAL_FAILURE -> 1;
            case HARD_FAILURE -> 2;
        };
    }
}
