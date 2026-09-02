package com.migration.logging;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * A Logback message converter (design doc §8's "defense-in-depth" log-redaction rule)
 * that replaces known secret values — and any {@code key=value}-shaped credential
 * pattern — in the formatted log message before it's written, guarding against the
 * masking key or a DB password leaking into a stack trace or debug log line even though
 * neither is ever logged deliberately.
 *
 * <p>Instantiated by Logback via no-arg reflection (see {@code logback-spring.xml}'s
 * {@code conversionRule}), so it can't be constructor-injected with Spring beans;
 * {@link #registerSecretValue} is how the application bridges resolved secret values in
 * from {@code LogRedactionInitializer} at startup.
 *
 * <p><b>Known gap:</b> this only redacts the formatted message (Logback's {@code %msg}/
 * {@code %maskedMsg}), not exception stack traces (Logback's separate {@code %ex}
 * conversion) — a secret embedded in an exception's own message would still surface
 * there. Redacting stack traces too would need a second, similar converter for the
 * exception-throwable proxy; left out here given scope.
 */
public final class SecretRedactingMessageConverter extends ClassicConverter {

    private static final Set<String> SECRET_VALUES = ConcurrentHashMap.newKeySet();
    private static final List<Pattern> SECRET_PATTERNS = List.of(
            Pattern.compile("(?i)(password|secret|api[_-]?key|token)\\s*[=:]\\s*\\S+"));
    private static final String REDACTED = "***REDACTED***";

    public static void registerSecretValue(String value) {
        if (value != null && !value.isBlank()) {
            SECRET_VALUES.add(value);
        }
    }

    @Override
    public String convert(ILoggingEvent event) {
        String message = event.getFormattedMessage();
        if (message == null) {
            return "";
        }
        for (String secret : SECRET_VALUES) {
            message = message.replace(secret, REDACTED);
        }
        for (Pattern pattern : SECRET_PATTERNS) {
            message = pattern.matcher(message).replaceAll(m -> m.group(1) + "=" + REDACTED);
        }
        return message;
    }
}
