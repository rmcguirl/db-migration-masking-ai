package com.migration.masking;

import com.migration.domain.exception.PlanValidationException;
import com.migration.plan.ColumnPlan;
import com.migration.plan.TablePlan;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Rejects a {@link TablePlan} that assigns a non-injective technique to any column the
 * caller identifies as a key column (design doc §4): bucketing/substitution aren't
 * injective, so applying them to a primary key, foreign key, or natural key column could
 * map two distinct source entities to the same destination key, silently merging rows on
 * upsert. Deliberately takes {@code keyColumns} as a parameter rather than deriving it
 * from schema/graph metadata itself — that derivation (natural key ∪ introspected
 * PK/FK columns) is {@code PlanGenerationService}'s job; this class only enforces the
 * rule once the full key-column set is known.
 */
@Component
public class KeyColumnMaskingValidator {

    private final Map<MaskingTechniqueId, MaskingTechnique> techniquesById;

    public KeyColumnMaskingValidator(List<MaskingTechnique> techniques) {
        Map<MaskingTechniqueId, MaskingTechnique> index = new EnumMap<>(MaskingTechniqueId.class);
        for (MaskingTechnique technique : techniques) {
            index.put(technique.id(), technique);
        }
        this.techniquesById = Map.copyOf(index);
    }

    /** Throws {@link PlanValidationException} on the first violation found; a no-op if the plan is valid. */
    public void validate(TablePlan plan, Set<String> keyColumns) {
        for (String keyColumn : keyColumns) {
            ColumnPlan columnPlan = findColumn(plan, keyColumn);
            if (columnPlan == null || !columnPlan.sensitive()) {
                continue;
            }
            String techniqueId = columnPlan.masking().technique();
            MaskingTechnique technique = techniquesById.get(MaskingTechniqueId.valueOf(techniqueId));
            if (technique == null || !technique.isInjective()) {
                throw new PlanValidationException("column '" + keyColumn + "' in table '" + plan.sourceTable()
                        + "' is a primary/foreign/natural-key column but is assigned non-injective technique '"
                        + techniqueId + "'; key columns must use HASH_HMAC or be left unmasked");
            }
        }
    }

    private ColumnPlan findColumn(TablePlan plan, String columnName) {
        return plan.columns().stream()
                .filter(c -> c.source().equalsIgnoreCase(columnName) || c.destination().equalsIgnoreCase(columnName))
                .findFirst()
                .orElse(null);
    }
}
