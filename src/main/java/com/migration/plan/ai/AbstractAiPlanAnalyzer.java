package com.migration.plan.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.migration.domain.SchemaModel;
import com.migration.domain.TableDescriptor;
import com.migration.domain.TableRef;
import com.migration.domain.exception.MigrationException;
import com.migration.plan.AiAnalysisConfig;
import com.migration.plan.AiAnalysisResult;
import com.migration.plan.AiPlanAnalyzer;
import com.migration.plan.AiTableAnalysis;
import com.migration.plan.PatternSignal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared prompt construction and response parsing for every {@code AiPlanAnalyzer}
 * provider. Builds one request from schema metadata plus pattern-signal summaries —
 * counts and pattern names, never raw values (design doc §6) — and parses the provider's
 * JSON response back into an {@link AiAnalysisResult}.
 *
 * <p><b>{@code allowDataSampling} scope note:</b> {@link AiAnalysisConfig#allowDataSampling()}
 * is threaded through per config, but this class's inputs — {@code SchemaModel} and
 * pattern signals — never carry raw sample values in the first place (the canonical
 * {@code AiPlanAnalyzer.analyze(SchemaModel, Map, AiAnalysisConfig)} signature, §11, has
 * no raw-sample parameter). So in this reference implementation the flag has no effect on
 * what's sent — every request is metadata + pattern signals only, regardless of its
 * value. It's preserved in config for a future provider variant that's given raw samples
 * through a different, opt-in-only channel.
 */
public abstract class AbstractAiPlanAnalyzer implements AiPlanAnalyzer {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public AiAnalysisResult analyze(SchemaModel schema, Map<TableRef, List<PatternSignal>> patternSignals,
                                     AiAnalysisConfig config) {
        AiRequestPayload payload = buildPayload(schema, patternSignals);
        String requestJson = toJson(payload);
        String responseJson = callProvider(requestJson, config);
        return parseResponse(responseJson, schema);
    }

    /** Sends {@code requestJson} to the provider and returns its raw JSON response body. */
    protected abstract String callProvider(String requestJson, AiAnalysisConfig config);

    private AiRequestPayload buildPayload(SchemaModel schema, Map<TableRef, List<PatternSignal>> patternSignals) {
        List<AiTableRequest> tables = new ArrayList<>();
        for (TableDescriptor table : schema.tables()) {
            List<AiColumnMetadata> columns = table.columns().stream()
                    .map(c -> new AiColumnMetadata(c.name(), c.canonicalType().kind().name(), c.nullable(),
                            c.primaryKey(), c.comment()))
                    .toList();
            List<PatternSignal> signals = patternSignals.getOrDefault(table.ref(), List.of());
            tables.add(new AiTableRequest(table.ref().qualifiedName(), columns, signals));
        }
        return new AiRequestPayload(tables);
    }

    private String toJson(AiRequestPayload payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new MigrationException("failed to serialize AI request payload", e);
        }
    }

    private AiAnalysisResult parseResponse(String responseJson, SchemaModel schema) {
        AiResponseWireFormat wireFormat;
        try {
            wireFormat = objectMapper.readValue(responseJson, AiResponseWireFormat.class);
        } catch (JsonProcessingException e) {
            throw new MigrationException("failed to parse AI response payload", e);
        }
        Map<TableRef, AiTableAnalysis> byTableRef = new LinkedHashMap<>();
        for (Map.Entry<String, AiTableAnalysis> entry : wireFormat.tables().entrySet()) {
            TableRef ref = schema.tables().stream()
                    .map(TableDescriptor::ref)
                    .filter(r -> r.qualifiedName().equals(entry.getKey()))
                    .findFirst()
                    .orElseThrow(() -> new MigrationException(
                            "AI response references a table not in the request schema: " + entry.getKey()));
            byTableRef.put(ref, entry.getValue());
        }
        return new AiAnalysisResult(byTableRef);
    }

    /** The response wire shape: table-qualified-name to analysis, since JSON object keys must be strings. */
    private record AiResponseWireFormat(Map<String, AiTableAnalysis> tables) {
    }
}
