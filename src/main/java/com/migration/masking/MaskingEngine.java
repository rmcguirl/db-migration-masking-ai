package com.migration.masking;

import com.migration.domain.Row;
import com.migration.plan.TablePlan;

/** Applies the configured one-way technique per column to one extracted row (design doc §4). */
public interface MaskingEngine {

    Row maskRow(Row sourceRow, TablePlan plan);
}
