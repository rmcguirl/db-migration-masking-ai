package com.migration.referential;

import com.migration.config.CycleConfig;
import com.migration.config.CycleStrategy;
import com.migration.config.ReferentialIntegrityConfig;
import com.migration.domain.TableRef;
import com.migration.domain.exception.ConfigurationException;
import com.migration.domain.exception.PlanValidationException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Covers design doc §5's three cycle-resolution outcomes. */
class CycleLoadStrategyResolverTest {

    private final CycleLoadStrategyResolver resolver = new CycleLoadStrategyResolver();
    private final List<TableRef> cycle = List.of(TableRef.of("employees"), TableRef.of("departments"));

    @Test
    void a_cycle_with_no_matching_config_entry_defaults_to_unsupported() {
        List<CycleResolution> resolutions = resolver.resolve(List.of(cycle), ReferentialIntegrityConfig.empty(), t -> true);

        assertThat(resolutions).hasSize(1);
        assertThat(resolutions.get(0).strategy()).isEqualTo(CycleStrategyOutcome.UNSUPPORTED);
        assertThat(resolutions.get(0).loadOrder()).isEmpty();
    }

    @Test
    void deferred_transaction_succeeds_when_every_table_supports_it() {
        ReferentialIntegrityConfig config = configWith(new CycleConfig(
                List.of("employees", "departments"), CycleStrategy.DEFERRED_TRANSACTION, List.of()));

        List<CycleResolution> resolutions = resolver.resolve(List.of(cycle), config, t -> true);

        assertThat(resolutions.get(0).strategy()).isEqualTo(CycleStrategyOutcome.DEFERRED_TRANSACTION);
        assertThat(resolutions.get(0).loadOrder()).containsExactlyInAnyOrderElementsOf(cycle);
    }

    @Test
    void deferred_transaction_fails_fast_when_a_connector_does_not_support_it() {
        ReferentialIntegrityConfig config = configWith(new CycleConfig(
                List.of("employees", "departments"), CycleStrategy.DEFERRED_TRANSACTION, List.of()));

        assertThatThrownBy(() -> resolver.resolve(List.of(cycle), config,
                t -> t.table().equals("employees") /* departments doesn't support it */))
                .isInstanceOf(PlanValidationException.class);
    }

    @Test
    void declared_order_uses_the_human_declared_linear_order() {
        ReferentialIntegrityConfig config = configWith(new CycleConfig(
                List.of("employees", "departments"), CycleStrategy.DECLARED_ORDER, List.of("departments", "employees")));

        List<CycleResolution> resolutions = resolver.resolve(List.of(cycle), config, t -> false);

        assertThat(resolutions.get(0).strategy()).isEqualTo(CycleStrategyOutcome.DECLARED_ORDER);
        assertThat(resolutions.get(0).loadOrder()).containsExactly(TableRef.of("departments"), TableRef.of("employees"));
    }

    @Test
    void declared_order_without_a_loadOrder_fails_fast() {
        ReferentialIntegrityConfig config = configWith(new CycleConfig(
                List.of("employees", "departments"), CycleStrategy.DECLARED_ORDER, List.of()));

        assertThatThrownBy(() -> resolver.resolve(List.of(cycle), config, t -> false))
                .isInstanceOf(ConfigurationException.class);
    }

    private ReferentialIntegrityConfig configWith(CycleConfig cycleConfig) {
        return new ReferentialIntegrityConfig(List.of(cycleConfig));
    }
}
