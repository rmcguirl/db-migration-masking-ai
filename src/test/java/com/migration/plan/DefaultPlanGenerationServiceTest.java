package com.migration.plan;

import com.migration.config.AiConfig;
import com.migration.config.ControlPlaneConfig;
import com.migration.config.DestinationConfig;
import com.migration.config.ConnectionConfig;
import com.migration.config.MaskingConfig;
import com.migration.config.MigrationConfig;
import com.migration.config.OverridesConfig;
import com.migration.config.PoolConfig;
import com.migration.config.ReferentialIntegrityConfig;
import com.migration.config.RunConfig;
import com.migration.config.SourceConfig;
import com.migration.connector.api.ConnectorRegistry;
import com.migration.connector.api.RowStream;
import com.migration.connector.api.SourceConnector;
import com.migration.domain.CanonicalType;
import com.migration.domain.CanonicalTypeKind;
import com.migration.domain.ColumnDescriptor;
import com.migration.domain.ExtractCursor;
import com.migration.domain.Row;
import com.migration.domain.SchemaModel;
import com.migration.domain.TableDescriptor;
import com.migration.domain.TableRef;
import com.migration.masking.KeyColumnMaskingValidator;
import com.migration.referential.DependencyGraph;
import com.migration.referential.ReferentialGraphBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers design doc §6's fallback/safety-net policy: cache hit skips AI+patterns
 * entirely, rules can only push a column toward "sensitive" (never pull one out), and a
 * low-confidence AI classification falls back to a conservative technique rather than
 * being trusted or left unmasked.
 */
class DefaultPlanGenerationServiceTest {

    private PlanCacheRepository planCacheRepository;
    private PatternRuleEngine patternRuleEngine;
    private AiProviderRegistry aiProviderRegistry;
    private AiPlanAnalyzer aiPlanAnalyzer;
    private ConnectorRegistry connectorRegistry;
    private SourceConnector sourceConnector;
    private ReferentialGraphBuilder referentialGraphBuilder;
    private KeyColumnMaskingValidator keyColumnMaskingValidator;
    private DefaultPlanGenerationService service;
    private MigrationConfig config;

    private static final TableRef CUSTOMERS = TableRef.of("customers");

    @BeforeEach
    void setUp() {
        planCacheRepository = mock(PlanCacheRepository.class);
        patternRuleEngine = mock(PatternRuleEngine.class);
        aiProviderRegistry = mock(AiProviderRegistry.class);
        aiPlanAnalyzer = mock(AiPlanAnalyzer.class);
        connectorRegistry = mock(ConnectorRegistry.class);
        sourceConnector = mock(SourceConnector.class);
        referentialGraphBuilder = mock(ReferentialGraphBuilder.class);
        keyColumnMaskingValidator = mock(KeyColumnMaskingValidator.class);

        service = new DefaultPlanGenerationService(planCacheRepository, patternRuleEngine, aiProviderRegistry,
                connectorRegistry, referentialGraphBuilder, keyColumnMaskingValidator);

        config = migrationConfig();
        when(connectorRegistry.resolveSource("postgres")).thenReturn(sourceConnector);
        when(sourceConnector.extract(any(), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenAnswer(invocation -> emptyRowStream());
        when(patternRuleEngine.scan(any(), any())).thenReturn(List.of());

        DependencyGraph graph = mock(DependencyGraph.class);
        when(graph.topologicalLoadOrder()).thenReturn(List.of(CUSTOMERS));
        when(referentialGraphBuilder.build(any(), any())).thenReturn(graph);

        when(aiProviderRegistry.resolve(anyString())).thenReturn(aiPlanAnalyzer);
    }

    @Test
    void a_cache_hit_returns_the_cached_plan_and_never_touches_ai_or_patterns() {
        MigrationPlan cached = new MigrationPlan("sha256:abc", java.time.Instant.now(), List.of());
        when(planCacheRepository.findByFingerprint("sha256:abc")).thenReturn(Optional.of(cached));

        MigrationPlan result = service.getOrGeneratePlan("sha256:abc", schemaWith(), config);

        assertThat(result).isSameAs(cached);
        org.mockito.Mockito.verifyNoInteractions(aiPlanAnalyzer);
        org.mockito.Mockito.verify(patternRuleEngine, org.mockito.Mockito.never()).scan(any(), any());
    }

    @Test
    void a_pattern_match_alone_is_enough_to_mark_a_column_sensitive_even_without_ai() {
        when(planCacheRepository.findByFingerprint(anyString())).thenReturn(Optional.empty());
        when(patternRuleEngine.scan(any(), any()))
                .thenReturn(List.of(new PatternSignal(CUSTOMERS, "", 0.9, "US_SSN")));
        when(aiPlanAnalyzer.analyze(any(), any(), any())).thenReturn(new AiAnalysisResult(Map.of()));

        MigrationConfig aiDisabled = withAiEnabled(false);
        MigrationPlan plan = service.getOrGeneratePlan("sha256:xyz", schemaWith(), aiDisabled);

        ColumnPlan ssnColumn = plan.tables().get(0).column("ssn");
        assertThat(ssnColumn.sensitive()).isTrue();
        assertThat(ssnColumn.decisionSource()).isEqualTo(DecisionSource.RULE);
    }

    @Test
    void ai_saying_not_sensitive_cannot_override_a_pattern_match_toward_unmasked() {
        when(planCacheRepository.findByFingerprint(anyString())).thenReturn(Optional.empty());
        when(patternRuleEngine.scan(any(), any()))
                .thenReturn(List.of(new PatternSignal(CUSTOMERS, "", 0.9, "US_SSN")));
        AiColumnAnalysis aiSaysNotSensitive = new AiColumnAnalysis("ssn", false, 0.99, List.of(), null);
        when(aiPlanAnalyzer.analyze(any(), any(), any())).thenReturn(new AiAnalysisResult(
                Map.of(CUSTOMERS, new AiTableAnalysis(List.of(aiSaysNotSensitive), List.of(), Map.of()))));

        MigrationPlan plan = service.getOrGeneratePlan("sha256:xyz", schemaWith(), config);

        assertThat(plan.tables().get(0).column("ssn").sensitive()).isTrue();
    }

    @Test
    void low_confidence_ai_classification_falls_back_to_a_conservative_technique_rather_than_trusting_it() {
        when(planCacheRepository.findByFingerprint(anyString())).thenReturn(Optional.empty());
        when(patternRuleEngine.scan(any(), any())).thenReturn(List.of());
        AiColumnAnalysis lowConfidence = new AiColumnAnalysis("ssn", true, 0.40, List.of("PCI_DSS"), "REDACT_PARTIAL");
        when(aiPlanAnalyzer.analyze(any(), any(), any())).thenReturn(new AiAnalysisResult(
                Map.of(CUSTOMERS, new AiTableAnalysis(List.of(lowConfidence), List.of(), Map.of()))));

        MigrationPlan plan = service.getOrGeneratePlan("sha256:xyz", schemaWith(), config);

        ColumnPlan ssnColumn = plan.tables().get(0).column("ssn");
        assertThat(ssnColumn.sensitive()).isTrue();
        assertThat(ssnColumn.decisionSource()).isEqualTo(DecisionSource.LOW_CONFIDENCE_FALLBACK);
        assertThat(ssnColumn.masking().technique()).isEqualTo("REDACT_FULL");
    }

    @Test
    void a_key_column_is_always_forced_to_hash_hmac_regardless_of_ai_suggestion() {
        when(planCacheRepository.findByFingerprint(anyString())).thenReturn(Optional.empty());
        when(patternRuleEngine.scan(any(), any())).thenReturn(List.of());
        AiColumnAnalysis aiSuggestsBucketing = new AiColumnAnalysis("id", true, 0.99, List.of(), "GENERALIZE_BUCKET");
        when(aiPlanAnalyzer.analyze(any(), any(), any())).thenReturn(new AiAnalysisResult(
                Map.of(CUSTOMERS, new AiTableAnalysis(List.of(aiSuggestsBucketing), List.of(), Map.of()))));

        MigrationPlan plan = service.getOrGeneratePlan("sha256:xyz", schemaWith(), config);

        ColumnPlan idColumn = plan.tables().get(0).column("id");
        assertThat(idColumn.masking().technique()).isEqualTo("HASH_HMAC");
        assertThat(idColumn.decisionSource()).isEqualTo(DecisionSource.OVERRIDE_KEY_COLUMN);
    }

    private RowStream emptyRowStream() {
        return new RowStream() {
            @Override
            public Iterator<Row> iterator() {
                return Collections.emptyIterator();
            }

            @Override
            public void close() {
            }
        };
    }

    private SchemaModel schemaWith() {
        List<ColumnDescriptor> columns = List.of(
                new ColumnDescriptor("id", CanonicalType.of(CanonicalTypeKind.LONG), null, false, true, null),
                new ColumnDescriptor("ssn", CanonicalType.of(CanonicalTypeKind.STRING), null, true, false, null));
        TableDescriptor table = new TableDescriptor(CUSTOMERS, columns, List.of("id"), null);
        return SchemaModel.of(List.of(table));
    }

    private MigrationConfig withAiEnabled(boolean enabled) {
        AiConfig ai = new AiConfig(enabled, "test-provider", 0.85, false, 20);
        return new MigrationConfig(config.source(), config.destination(), config.run(), config.overrides(), ai,
                config.masking(), config.referentialIntegrity(), config.controlPlane());
    }

    private MigrationConfig migrationConfig() {
        SourceConfig source = new SourceConfig("postgres", new ConnectionConfig("jdbc:postgresql://x/y", "u", "p"),
                new PoolConfig(10));
        DestinationConfig destination = new DestinationConfig("postgres",
                new ConnectionConfig("jdbc:postgresql://x/z", "u", "p"), new PoolConfig(10));
        RunConfig run = new RunConfig(5000, 4);
        OverridesConfig overrides = OverridesConfig.empty();
        AiConfig ai = new AiConfig(true, "test-provider", 0.85, false, 20);
        MaskingConfig masking = new MaskingConfig("env:TEST_KEY", 1);
        ReferentialIntegrityConfig referentialIntegrity = ReferentialIntegrityConfig.empty();
        ControlPlaneConfig controlPlane = new ControlPlaneConfig("jdbc:h2:mem:test");
        return new MigrationConfig(source, destination, run, overrides, ai, masking, referentialIntegrity, controlPlane);
    }
}
