package com.migration.plan.ai;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a {@code @Component}-annotated {@code AiPlanAnalyzer} implementation as the
 * analyzer for one provider/deployment, indexed by {@code value()} and resolved from
 * {@code ai.provider} config — the same extensibility pattern as
 * {@code @ConnectorFor} (design doc §6). Adding a new provider is a new
 * {@code @Component}, no core-code change.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface AiProviderFor {

    String value();
}
