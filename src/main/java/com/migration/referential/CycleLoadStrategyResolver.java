package com.migration.referential;

import com.migration.config.CycleConfig;
import com.migration.config.CycleStrategy;
import com.migration.config.ReferentialIntegrityConfig;
import com.migration.domain.TableRef;
import com.migration.domain.exception.ConfigurationException;
import com.migration.domain.exception.PlanValidationException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Validates each detected cycle against {@code referentialIntegrity.cycles[]} and
 * resolves it to a concrete strategy (design doc §5): {@code DEFERRED_TRANSACTION} (gated
 * by every involved connector reporting {@code supportsDeferredConstraints()}),
 * {@code DECLARED_ORDER} (a human-declared linear order), or the default
 * {@code UNSUPPORTED} when no config entry matches — run-blocking for the cycle's tables,
 * never silently handled.
 */
@Component
public class CycleLoadStrategyResolver {

    public List<CycleResolution> resolve(List<List<TableRef>> cycles, ReferentialIntegrityConfig config,
                                          Predicate<TableRef> supportsDeferredConstraints) {
        List<CycleResolution> resolutions = new ArrayList<>();
        for (List<TableRef> cycle : cycles) {
            resolutions.add(resolveOne(cycle, config, supportsDeferredConstraints));
        }
        return resolutions;
    }

    private CycleResolution resolveOne(List<TableRef> cycle, ReferentialIntegrityConfig config,
                                        Predicate<TableRef> supportsDeferredConstraints) {
        Set<String> cycleTableNames = lowerCaseNames(cycle);
        Optional<CycleConfig> match = config.cycles().stream()
                .filter(c -> lowerCaseNamesFromStrings(c.tables()).equals(cycleTableNames))
                .findFirst();

        if (match.isEmpty()) {
            return new CycleResolution(cycle, CycleStrategyOutcome.UNSUPPORTED, List.of());
        }

        CycleConfig cycleConfig = match.get();
        if (cycleConfig.strategy() == CycleStrategy.DEFERRED_TRANSACTION) {
            boolean allSupported = cycle.stream().allMatch(supportsDeferredConstraints);
            if (!allSupported) {
                throw new PlanValidationException("cycle " + cycleTableNames
                        + " declares DEFERRED_TRANSACTION, but not every table's connector reports"
                        + " supportsDeferredConstraints()");
            }
            return new CycleResolution(cycle, CycleStrategyOutcome.DEFERRED_TRANSACTION, cycle);
        }

        // DECLARED_ORDER
        if (cycleConfig.loadOrder().isEmpty()) {
            throw new ConfigurationException(
                    "cycle " + cycleTableNames + " declares strategy DECLARED_ORDER but is missing loadOrder");
        }
        List<TableRef> resolvedOrder = cycleConfig.loadOrder().stream()
                .map(name -> resolveTableInCycle(name, cycle, cycleTableNames))
                .toList();
        return new CycleResolution(cycle, CycleStrategyOutcome.DECLARED_ORDER, resolvedOrder);
    }

    private TableRef resolveTableInCycle(String name, List<TableRef> cycle, Set<String> cycleTableNames) {
        return cycle.stream()
                .filter(t -> t.table().equalsIgnoreCase(name))
                .findFirst()
                .orElseThrow(() -> new ConfigurationException(
                        "loadOrder entry '" + name + "' is not one of this cycle's tables " + cycleTableNames));
    }

    private Set<String> lowerCaseNames(List<TableRef> tables) {
        return tables.stream().map(t -> t.table().toLowerCase()).collect(Collectors.toSet());
    }

    private Set<String> lowerCaseNamesFromStrings(List<String> names) {
        return names.stream().map(String::toLowerCase).collect(Collectors.toSet());
    }
}
