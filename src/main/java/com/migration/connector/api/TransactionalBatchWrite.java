package com.migration.connector.api;

import java.util.function.Supplier;

/**
 * Optional capability: transactional write support, including whether the engine can
 * defer FK constraint checking to commit time. {@code supportsDeferredConstraints()}
 * drives the {@code DEFERRED_TRANSACTION} FK-cycle handling strategy (design doc §5).
 */
public interface TransactionalBatchWrite {

    <T> T inTransaction(Supplier<T> unitOfWork);

    boolean supportsDeferredConstraints();
}
