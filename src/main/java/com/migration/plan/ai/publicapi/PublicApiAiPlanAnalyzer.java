package com.migration.plan.ai.publicapi;

import com.migration.domain.exception.ConfigurationException;
import com.migration.domain.exception.MigrationException;
import com.migration.plan.AiAnalysisConfig;
import com.migration.plan.ai.AbstractAiPlanAnalyzer;
import com.migration.plan.ai.AiProviderFor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * A public, hosted-API AI provider client — for organizations comfortable sending schema
 * metadata and pattern-signal summaries (never raw values, per
 * {@link AbstractAiPlanAnalyzer}) to a third-party hosted endpoint (design doc §1's
 * "public-API deployment" option, §6). Endpoint and credentials come from environment
 * variables, never from the config file, consistent with how every other secret in this
 * app is handled: {@code AI_PUBLIC_API_URL} (request URL) and {@code AI_PUBLIC_API_KEY}
 * (bearer token).
 */
@Component
@AiProviderFor("public-api")
public class PublicApiAiPlanAnalyzer extends AbstractAiPlanAnalyzer {

    private final RestClient restClient = RestClient.create();

    @Override
    protected String callProvider(String requestJson, AiAnalysisConfig config) {
        String url = requireEnv("AI_PUBLIC_API_URL");
        String apiKey = requireEnv("AI_PUBLIC_API_KEY");
        try {
            return restClient.post()
                    .uri(url)
                    .header("Authorization", "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestJson)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientException e) {
            throw new MigrationException("public-api AI provider call failed", e);
        }
    }

    private String requireEnv(String variableName) {
        String value = System.getenv(variableName);
        if (value == null || value.isBlank()) {
            throw new ConfigurationException(
                    "environment variable '" + variableName + "' is required when ai.provider is 'public-api'");
        }
        return value;
    }
}
