package com.migration.masking;

import com.migration.config.MigrationConfig;
import com.migration.domain.Row;
import com.migration.domain.exception.MigrationException;
import com.migration.plan.ColumnPlan;
import com.migration.plan.TablePlan;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Applies each column's assigned technique (or passes non-sensitive columns through
 * unchanged) and re-keys the row from source to destination column names in the same
 * pass, so a field-mapping suggestion from AI analysis (design doc §6) is realized here
 * rather than needing a separate rename step. Only columns present in {@code plan}'s
 * column list appear in the output row.
 */
@Component
public class DefaultMaskingEngine implements MaskingEngine {

    private final Map<MaskingTechniqueId, MaskingTechnique> techniquesById;
    private final MaskingKeyProvider keyProvider;
    private final int keyVersion;

    public DefaultMaskingEngine(List<MaskingTechnique> techniques, MaskingKeyProvider keyProvider,
                                 MigrationConfig migrationConfig) {
        Map<MaskingTechniqueId, MaskingTechnique> index = new EnumMap<>(MaskingTechniqueId.class);
        for (MaskingTechnique technique : techniques) {
            index.put(technique.id(), technique);
        }
        this.techniquesById = Map.copyOf(index);
        this.keyProvider = keyProvider;
        this.keyVersion = migrationConfig.masking().keyVersion();
    }

    @Override
    public Row maskRow(Row sourceRow, TablePlan plan) {
        byte[] key = keyProvider.currentKey(keyVersion);
        try {
            Map<String, Object> destinationValues = new LinkedHashMap<>();
            for (ColumnPlan columnPlan : plan.columns()) {
                Object rawValue = sourceRow.get(columnPlan.source());
                Object destinationValue = columnPlan.sensitive()
                        ? maskOne(rawValue, sourceRow, columnPlan, key).value()
                        : rawValue;
                destinationValues.put(columnPlan.destination(), destinationValue);
            }
            return new Row(sourceRow.table(), destinationValues);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    private MaskedValue maskOne(Object rawValue, Row sourceRow, ColumnPlan columnPlan, byte[] key) {
        String techniqueId = columnPlan.masking().technique();
        MaskingTechnique technique = techniquesById.get(MaskingTechniqueId.valueOf(techniqueId));
        if (technique == null) {
            throw new MigrationException("no MaskingTechnique registered for '" + techniqueId + "'");
        }
        MaskingContext ctx = new MaskingContext(sourceRow.table(), columnPlan.source(), key, columnPlan.masking());
        return technique.apply(rawValue, ctx);
    }
}
