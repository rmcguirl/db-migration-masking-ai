package com.migration.plan;

import com.migration.domain.SchemaModel;

/** Computes a stable hash over a {@link SchemaModel} to key the plan cache (design doc §2). */
public interface SchemaFingerprinter {

    String fingerprint(SchemaModel model);
}
