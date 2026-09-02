package com.migration.masking;

/**
 * Resolves the HMAC secret from its configured source. Never logs or persists the key;
 * the returned array should be zeroed by the caller after use rather than retained on a
 * long-lived object (design doc §8).
 */
public interface MaskingKeyProvider {

    byte[] currentKey(int keyVersion);
}
