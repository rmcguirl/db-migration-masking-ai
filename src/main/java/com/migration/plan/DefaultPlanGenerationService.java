package com.migration.plan;

import com.migration.config.AiConfig;
import com.migration.config.ForceSensitiveOverride;
import com.migration.config.MaskingOverride;
import com.migration.config.MigrationConfig;
import com.migration.config.NaturalKeyOverride;
import com.migration.connector.api.ConnectorRegistry;
import com.migration.connector.api.RowStream;
import com.migration.connector.api.SourceConnector;
import com.migration.domain.ColumnDescriptor;
import com.migration.domain.ExtractCursor;
import com.migration.domain.ForeignKeyRef;
import com.migration.domain.Row;
import com.migration.domain.SchemaModel;
import com.migration.domain.TableDescriptor;
import com.migration.domain.TableRef;
import com.migration.masking.KeyColumnMaskingValidator;
import com.migration.referential.DependencyGraph;
import com.migration.referential.ReferentialGraphBuilder;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Orchestrates plan generation exactly as described in design doc §2 and §6: cache hit
 * returns the cached plan unchanged; a miss scans local sample data with
 * {@link PatternRuleEngine}, calls {@link AiPlanAnalyzer} (if {@code ai.enabled}), merges
 * both with {@code migration.overrides}, applies the confidence-fallback policy, validates
 * key-column technique constraints, and persists the result.
 *
 * <p>Samples rows itself (via a {@link ConnectorRegistry}-resolved {@link SourceConnector})
 * rather than receiving them as a parameter — the canonical
 * {@code getOrGeneratePlan(String, SchemaModel, MigrationConfig)} signature (§11) has no
 * channel for sample data, and since this app processes exactly one fixed config per
 * process invocation (a CLI run, not a multi-tenant server), resolving "the" source
 * connector from the config parameter at call time is safe.
 */
@Component
public class DefaultPlanGenerationService implements PlanGenerationService {

    private static final double PATTERN_MATCH_SENSITIVE_THRESHOLD = 0.5;

    private final PlanCacheRepository planCacheRepository;
    private final PatternRuleEngine patternRuleEngine;
    private final AiProviderRegistry aiProviderRegistry;
    private final ConnectorRegistry connectorRegistry;
    private final ReferentialGraphBuilder referentialGraphBuilder;
    private final KeyColumnMaskingValidator keyColumnMaskingValidator;

    public DefaultPlanGenerationService(PlanCacheRepository planCacheRepository, PatternRuleEngine patternRuleEngine,
                                         AiProviderRegistry aiProviderRegistry, ConnectorRegistry connectorRegistry,
                                         ReferentialGraphBuilder referentialGraphBuilder,
                                         KeyColumnMaskingValidator keyColumnMaskingValidator) {
        this.planCacheRepository = planCacheRepository;
        this.patternRuleEngine = patternRuleEngine;
        this.aiProviderRegistry = aiProviderRegistry;
        this.connectorRegistry = connectorRegistry;
        this.referentialGraphBuilder = referentialGraphBuilder;
        this.keyColumnMaskingValidator = keyColumnMaskingValidator;
    }

    @Override
    public MigrationPlan getOrGeneratePlan(String schemaFingerprint, SchemaModel sourceSchema, MigrationConfig config) {
        return planCacheRepository.findByFingerprint(schemaFingerprint)
                .orElseGet(() -> generateAndPersist(schemaFingerprint, sourceSchema, config));
    }

    private MigrationPlan generateAndPersist(String schemaFingerprint, SchemaModel sourceSchema, MigrationConfig config) {
        Map<TableRef, List<PatternSignal>> patternSignals = scanPatterns(sourceSchema, config);
        AiAnalysisResult aiResult = config.ai().enabled()
                ? runAi(sourceSchema, patternSignals, config.ai())
                : new AiAnalysisResult(Map.of());

        DependencyGraph graph = referentialGraphBuilder.build(sourceSchema, List.of());
        Map<TableRef, Integer> loadOrderByTable = new LinkedHashMap<>();
        List<TableRef> topoOrder = graph.topologicalLoadOrder();
        for (int i = 0; i < topoOrder.size(); i++) {
            loadOrderByTable.put(topoOrder.get(i), i + 1);
        }

        List<TablePlan> tablePlans = new ArrayList<>();
        for (TableDescriptor table : sourceSchema.tables()) {
            tablePlans.add(buildTablePlan(table, sourceSchema, patternSignals.getOrDefault(table.ref(), List.of()),
                    aiResult.tables().get(table.ref()), config, loadOrderByTable.getOrDefault(table.ref(), 0)));
        }

        MigrationPlan plan = new MigrationPlan(schemaFingerprint, Instant.now(), tablePlans);
        planCacheRepository.save(plan);
        return plan;
    }

    private Map<TableRef, List<PatternSignal>> scanPatterns(SchemaModel sourceSchema, MigrationConfig config) {
        SourceConnector source = connectorRegistry.resolveSource(config.source().type());
        int sampleSize = config.ai().sampleSize();
        Map<TableRef, List<PatternSignal>> result = new LinkedHashMap<>();

        for (TableDescriptor table : sourceSchema.tables()) {
            Map<String, List<Object>> samplesByColumn = new LinkedHashMap<>();
            for (ColumnDescriptor column : table.columns()) {
                samplesByColumn.put(column.name(), new ArrayList<>());
            }
            try (RowStream rows = source.extract(table.ref(), ExtractCursor.START, sampleSize)) {
                for (Row row : rows) {
                    for (String column : samplesByColumn.keySet()) {
                        samplesByColumn.get(column).add(row.get(column));
                    }
                }
            }

            List<PatternSignal> tableSignals = new ArrayList<>();
            for (Map.Entry<String, List<Object>> entry : samplesByColumn.entrySet()) {
                for (PatternSignal signal : patternRuleEngine.scan(table.ref(), entry.getValue())) {
                    tableSignals.add(new PatternSignal(table.ref(), entry.getKey(), signal.matchRate(), signal.matchedPattern()));
                }
            }
            result.put(table.ref(), tableSignals);
        }
        return result;
    }

    private AiAnalysisResult runAi(SchemaModel sourceSchema, Map<TableRef, List<PatternSignal>> patternSignals,
                                    AiConfig aiConfig) {
        AiPlanAnalyzer analyzer = aiProviderRegistry.resolve(aiConfig.provider());
        AiAnalysisConfig analysisConfig = new AiAnalysisConfig(aiConfig.confidenceThreshold(), aiConfig.allowDataSampling());
        return analyzer.analyze(sourceSchema, patternSignals, analysisConfig);
    }

    private TablePlan buildTablePlan(TableDescriptor table, SchemaModel sourceSchema, List<PatternSignal> tableSignals,
                                      AiTableAnalysis aiTableAnalysis, MigrationConfig config, int loadOrder) {
        Map<String, Double> patternMatchRateByColumn = new LinkedHashMap<>();
        for (PatternSignal signal : tableSignals) {
            patternMatchRateByColumn.merge(signal.column(), signal.matchRate(), Math::max);
        }

        List<String> naturalKey = resolveNaturalKey(table, aiTableAnalysis, config);
        Set<String> foreignKeyChildColumns = sourceSchema.foreignKeys().stream()
                .filter(fk -> fk.childTable().equals(table.ref()))
                .flatMap(fk -> fk.childColumns().stream())
                .collect(java.util.stream.Collectors.toSet());
        Set<String> keyColumns = new LinkedHashSet<>();
        keyColumns.addAll(table.primaryKeyColumns());
        keyColumns.addAll(foreignKeyChildColumns);
        keyColumns.addAll(naturalKey);

        List<ColumnPlan> columnPlans = new ArrayList<>();
        for (ColumnDescriptor column : table.columns()) {
            columnPlans.add(buildColumnPlan(table, column, patternMatchRateByColumn.getOrDefault(column.name(), 0.0),
                    aiTableAnalysis, config, keyColumns.contains(column.name())));
        }

        List<String> dependsOn = sourceSchema.foreignKeys().stream()
                .filter(fk -> fk.childTable().equals(table.ref()) && !fk.parentTable().equals(table.ref()))
                .map(ForeignKeyRef::parentTable)
                .map(TableRef::table)
                .distinct()
                .toList();

        TablePlan tablePlan = new TablePlan(table.ref().table(), table.ref().table(), naturalKey, loadOrder,
                dependsOn, columnPlans, List.of());
        keyColumnMaskingValidator.validate(tablePlan, keyColumns);
        return tablePlan;
    }

    private List<String> resolveNaturalKey(TableDescriptor table, AiTableAnalysis aiTableAnalysis, MigrationConfig config) {
        return config.overrides().naturalKeys().stream()
                .filter(nk -> nk.table().equalsIgnoreCase(table.ref().table()))
                .findFirst()
                .map(NaturalKeyOverride::columns)
                .or(() -> aiTableAnalysis != null && !aiTableAnalysis.suggestedNaturalKey().isEmpty()
                        ? java.util.Optional.of(aiTableAnalysis.suggestedNaturalKey())
                        : java.util.Optional.empty())
                .orElseGet(table::primaryKeyColumns);
    }

    private ColumnPlan buildColumnPlan(TableDescriptor table, ColumnDescriptor column, double patternMatchRate,
                                        AiTableAnalysis aiTableAnalysis, MigrationConfig config, boolean isKeyColumn) {
        String destinationName = aiTableAnalysis != null
                ? aiTableAnalysis.suggestedFieldMappings().getOrDefault(column.name(), column.name())
                : column.name();

        java.util.Optional<MaskingOverride> maskingOverride = config.overrides().masking().stream()
                .filter(m -> m.table().equalsIgnoreCase(table.ref().table()) && m.column().equalsIgnoreCase(column.name()))
                .findFirst();
        boolean forceSensitive = config.overrides().forceSensitive().stream()
                .anyMatch(f -> f.table().equalsIgnoreCase(table.ref().table()) && f.column().equalsIgnoreCase(column.name()));

        AiColumnAnalysis aiColumn = aiTableAnalysis != null
                ? aiTableAnalysis.columns().stream().filter(c -> c.column().equalsIgnoreCase(column.name())).findFirst().orElse(null)
                : null;

        boolean patternSensitive = patternMatchRate >= PATTERN_MATCH_SENSITIVE_THRESHOLD;
        boolean aiSensitive = aiColumn != null && aiColumn.sensitive();
        // Rules/overrides can only push a column toward "sensitive," never pull one out (design doc §6).
        boolean sensitive = aiSensitive || patternSensitive || forceSensitive || maskingOverride.isPresent();

        List<String> regulatoryBasis = aiColumn != null ? aiColumn.regulatoryBasis() : List.of();

        if (!sensitive) {
            return new ColumnPlan(column.name(), destinationName, column.canonicalType(), false, 1.0,
                    aiColumn != null ? DecisionSource.AI : DecisionSource.RULE, List.of(), null);
        }

        if (isKeyColumn) {
            return new ColumnPlan(column.name(), destinationName, column.canonicalType(), true, 1.0,
                    DecisionSource.OVERRIDE_KEY_COLUMN, regulatoryBasis, MaskingRule.hmac("BASE64URL"));
        }

        if (maskingOverride.isPresent()) {
            return new ColumnPlan(column.name(), destinationName, column.canonicalType(), true, 1.0,
                    DecisionSource.OVERRIDE, regulatoryBasis, MaskingRule.technique(maskingOverride.get().technique()));
        }

        if (aiSensitive && aiColumn.confidence() >= config.ai().confidenceThreshold()) {
            DecisionSource source = patternSensitive ? DecisionSource.AI_AND_RULE : DecisionSource.AI;
            return new ColumnPlan(column.name(), destinationName, column.canonicalType(), true, aiColumn.confidence(),
                    source, regulatoryBasis, MaskingRule.technique(aiColumn.suggestedTechnique()));
        }

        // Low-confidence or rule-only verdict: fall back to the most conservative technique
        // for the column's inferred shape rather than leaving it unmasked (design doc §6).
        DecisionSource fallbackSource = (aiColumn != null && aiSensitive) ? DecisionSource.LOW_CONFIDENCE_FALLBACK : DecisionSource.RULE;
        MaskingRule fallbackRule = MaskingRule.technique("REDACT_FULL");
        double confidence = aiColumn != null ? aiColumn.confidence() : 1.0;
        return new ColumnPlan(column.name(), destinationName, column.canonicalType(), true, confidence,
                fallbackSource, regulatoryBasis, fallbackRule);
    }
}
