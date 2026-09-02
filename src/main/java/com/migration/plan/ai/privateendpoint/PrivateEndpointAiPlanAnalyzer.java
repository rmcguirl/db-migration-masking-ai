package com.migration.plan.ai.privateendpoint;

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
 * A VPC/private-endpoint AI provider client — for organizations running their own
 * enterprise AI deployment under their own data agreement (design doc §1's resolution:
 * "the design supports both public-API and VPC-hosted/private deployments as pluggable
 * {@code AiPlanAnalyzer} implementations"). This is what makes
 * {@code ai.allowDataSampling: true} a defensible opt-in in practice — org-controlled
 * infrastructure, not a third party. Endpoint comes from
 * {@code AI_PRIVATE_ENDPOINT_URL}; the API key is optional
 * ({@code AI_PRIVATE_ENDPOINT_API_KEY}), since many private/VPC deployments rely on
 * network-level trust instead of a bearer token.
 */
@Component
@AiProviderFor("private-endpoint")
public class PrivateEndpointAiPlanAnalyzer extends AbstractAiPlanAnalyzer {

    private final RestClient restClient = RestClient.create();

    @Override
    protected String callProvider(String requestJson, AiAnalysisConfig config) {
        String url = System.getenv("AI_PRIVATE_ENDPOINT_URL");
        if (url == null || url.isBlank()) {
            throw new ConfigurationException(
                    "environment variable 'AI_PRIVATE_ENDPOINT_URL' is required when ai.provider is 'private-endpoint'");
        }
        String apiKey = System.getenv("AI_PRIVATE_ENDPOINT_API_KEY");
        try {
            RestClient.RequestBodySpec request = restClient.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON);
            if (apiKey != null && !apiKey.isBlank()) {
                request = request.header("Authorization", "Bearer " + apiKey);
            }
            return request.body(requestJson).retrieve().body(String.class);
        } catch (RestClientException e) {
            throw new MigrationException("private-endpoint AI provider call failed", e);
        }
    }
}
