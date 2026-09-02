package com.migration.connector.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a {@code @Component}-annotated {@link Connector} implementation as the connector
 * for one database engine, indexed by {@code value()} (e.g. {@code "postgres"}). A new
 * engine is added by writing a class with both annotations and, where applicable, a
 * {@link TypeMapper} resource — no change to {@link ConnectorRegistry} or any
 * orchestration code (design doc §3).
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ConnectorFor {

    String value();
}
