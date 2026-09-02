package com.migration.masking;

import com.migration.config.MaskingConfig;
import com.migration.config.MigrationConfig;
import com.migration.domain.exception.ConfigurationException;
import org.springframework.stereotype.Component;

import java.util.Base64;

/**
 * Default {@link MaskingKeyProvider}: reads a base64-encoded key from the environment
 * variable named in {@code masking.keySource} (e.g. {@code env:MASKING_HMAC_KEY}). Never
 * writes the key to the config file, the plan cache, or a log line (design doc §8) —
 * designed to be swapped for a Vault/secrets-manager provider without touching call
 * sites, since callers depend only on {@link MaskingKeyProvider}.
 */
@Component
public class EnvVarMaskingKeyProvider implements MaskingKeyProvider {

    private static final String ENV_SCHEME_PREFIX = "env:";

    private final MaskingConfig config;

    public EnvVarMaskingKeyProvider(MigrationConfig migrationConfig) {
        this.config = migrationConfig.masking();
    }

    @Override
    public byte[] currentKey(int keyVersion) {
        if (keyVersion != config.keyVersion()) {
            throw new ConfigurationException("requested masking key version " + keyVersion
                    + " does not match configured version " + config.keyVersion());
        }
        String keySource = config.keySource();
        if (!keySource.startsWith(ENV_SCHEME_PREFIX)) {
            throw new ConfigurationException(
                    "unsupported masking key source scheme: '" + keySource + "' (expected 'env:VAR_NAME')");
        }
        String variableName = keySource.substring(ENV_SCHEME_PREFIX.length());
        String value = System.getenv(variableName);
        if (value == null || value.isBlank()) {
            throw new ConfigurationException(
                    "environment variable '" + variableName + "' referenced by masking.keySource is not set");
        }
        try {
            return Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException e) {
            throw new ConfigurationException(
                    "environment variable '" + variableName + "' is not valid base64", e);
        }
    }
}
