package com.migration.domain.exception;

/**
 * Thrown at startup for cross-field configuration validation that Bean Validation
 * annotations can't express on their own — e.g. an {@code ai.provider} value with no
 * matching {@code @AiProviderFor} bean, or a {@code referentialIntegrity.cycles[]}
 * entry with {@code strategy: DECLARED_ORDER} missing {@code loadOrder}. Thrown before
 * any connector is touched, not mid-run.
 */
public class ConfigurationException extends MigrationException {

    public ConfigurationException(String message) {
        super(message);
    }

    public ConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}
