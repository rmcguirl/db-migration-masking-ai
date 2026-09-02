package com.migration.plan;

import com.migration.domain.SchemaModel;
import com.migration.domain.TableRef;

import java.util.List;
import java.util.Map;

/**
 * Classifies column sensitivity, picks masking techniques, and suggests natural
 * keys/field mappings by calling an AI provider with schema metadata and pattern-signal
 * summaries (never raw values unless {@code config.allowDataSampling()}). One
 * implementation per provider/deployment, each a {@code @AiProviderFor}-annotated
 * {@code @Component}, resolved via {@link AiProviderRegistry} (design doc §6).
 */
public interface AiPlanAnalyzer {

    AiAnalysisResult analyze(SchemaModel schema,
                              Map<TableRef, List<PatternSignal>> patternSignals,
                              AiAnalysisConfig config);
}
