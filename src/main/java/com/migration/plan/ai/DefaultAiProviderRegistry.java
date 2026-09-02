package com.migration.plan.ai;

import com.migration.domain.exception.ConfigurationException;
import com.migration.plan.AiPlanAnalyzer;
import com.migration.plan.AiProviderRegistry;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Indexes every {@code AiPlanAnalyzer} bean by its {@link AiProviderFor} value (design doc §6). */
@Component
public class DefaultAiProviderRegistry implements AiProviderRegistry {

    private final Map<String, AiPlanAnalyzer> analyzersByProviderId;

    public DefaultAiProviderRegistry(List<AiPlanAnalyzer> candidates) {
        Map<String, AiPlanAnalyzer> index = new HashMap<>();
        for (AiPlanAnalyzer candidate : candidates) {
            AiProviderFor annotation = AnnotationUtils.findAnnotation(candidate.getClass(), AiProviderFor.class);
            if (annotation == null) {
                throw new ConfigurationException(
                        "AiPlanAnalyzer bean " + candidate.getClass().getName() + " is missing an @AiProviderFor annotation");
            }
            String key = annotation.value().trim().toLowerCase();
            AiPlanAnalyzer existing = index.putIfAbsent(key, candidate);
            if (existing != null) {
                throw new ConfigurationException("duplicate @AiProviderFor(\"" + key + "\") on "
                        + existing.getClass().getName() + " and " + candidate.getClass().getName());
            }
        }
        this.analyzersByProviderId = Map.copyOf(index);
    }

    @Override
    public AiPlanAnalyzer resolve(String providerId) {
        if (providerId == null || providerId.isBlank()) {
            throw new ConfigurationException("ai.provider must not be blank");
        }
        AiPlanAnalyzer analyzer = analyzersByProviderId.get(providerId.trim().toLowerCase());
        if (analyzer == null) {
            throw new ConfigurationException("no AiPlanAnalyzer registered for ai.provider '" + providerId
                    + "'; known providers: " + analyzersByProviderId.keySet());
        }
        return analyzer;
    }
}
