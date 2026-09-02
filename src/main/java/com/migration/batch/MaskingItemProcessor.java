package com.migration.batch;

import com.migration.domain.Row;
import com.migration.masking.MaskingEngine;
import com.migration.plan.TablePlan;
import org.springframework.batch.item.ItemProcessor;

/** Delegates each row to {@link MaskingEngine#maskRow}, per {@code tablePlan}'s per-column assignments. */
public class MaskingItemProcessor implements ItemProcessor<Row, Row> {

    private final MaskingEngine maskingEngine;
    private final TablePlan tablePlan;

    public MaskingItemProcessor(MaskingEngine maskingEngine, TablePlan tablePlan) {
        this.maskingEngine = maskingEngine;
        this.tablePlan = tablePlan;
    }

    @Override
    public Row process(Row item) {
        return maskingEngine.maskRow(item, tablePlan);
    }
}
