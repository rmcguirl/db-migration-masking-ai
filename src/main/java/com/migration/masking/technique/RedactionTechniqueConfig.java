package com.migration.masking.technique;

import com.migration.masking.MaskingTechniqueId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers the two {@link RedactionTechnique} instances (one per id it serves). */
@Configuration
public class RedactionTechniqueConfig {

    @Bean
    public RedactionTechnique redactFullTechnique() {
        return new RedactionTechnique(MaskingTechniqueId.REDACT_FULL);
    }

    @Bean
    public RedactionTechnique redactPartialTechnique() {
        return new RedactionTechnique(MaskingTechniqueId.REDACT_PARTIAL);
    }
}
