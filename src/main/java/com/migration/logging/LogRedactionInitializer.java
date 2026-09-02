package com.migration.logging;

import com.migration.config.MigrationConfig;
import org.springframework.stereotype.Component;

/**
 * Bridges resolved secret values (masking key, source/destination DB passwords) from
 * {@link MigrationConfig} into {@link SecretRedactingMessageConverter}'s static registry
 * at startup, since Logback constructs converters via no-arg reflection and can't
 * receive them through normal Spring injection.
 */
@Component
public class LogRedactionInitializer {

    private static final String ENV_SCHEME_PREFIX = "env:";

    public LogRedactionInitializer(MigrationConfig config) {
        String keySource = config.masking().keySource();
        if (keySource != null && keySource.startsWith(ENV_SCHEME_PREFIX)) {
            SecretRedactingMessageConverter.registerSecretValue(
                    System.getenv(keySource.substring(ENV_SCHEME_PREFIX.length())));
        }
        SecretRedactingMessageConverter.registerSecretValue(config.source().connection().password());
        SecretRedactingMessageConverter.registerSecretValue(config.destination().connection().password());
    }
}
