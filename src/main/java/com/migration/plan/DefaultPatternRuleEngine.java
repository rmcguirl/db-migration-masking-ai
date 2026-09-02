package com.migration.plan;

import com.migration.domain.TableRef;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Local, deterministic, non-AI PII/PCI/PHI pattern detection over a sample of a column's
 * actual values (design doc §6). Runs regardless of AI availability or opinion; sample
 * values never leave the process here — only the resulting counts/labels do.
 *
 * <p>{@link #scan} matches the canonical {@code (TableRef, List<Object>)} signature
 * (§11), which has no column-name parameter: it's called once per column, over that
 * column's samples, and returns each matched rule as a {@link PatternSignal} with
 * {@code column} left blank — the caller (which already knows which column it's
 * scanning) re-associates the column name onto each returned signal.
 */
@Component
public class DefaultPatternRuleEngine implements PatternRuleEngine {

    private static final List<PatternRule> RULES = List.of(
            regex("US_SSN", "\\d{3}-\\d{2}-\\d{4}"),
            regex("EMAIL", "[^@\\s]+@[^@\\s]+\\.[^@\\s]+"),
            regex("PHONE_US", "\\+?1?[\\s.-]?\\(?\\d{3}\\)?[\\s.-]?\\d{3}[\\s.-]?\\d{4}"),
            regex("US_ZIP", "\\d{5}(-\\d{4})?"),
            regex("PASSPORT_GENERIC", "[A-Z]\\d{8}"),
            new LuhnCreditCardRule());

    @Override
    public List<PatternSignal> scan(TableRef table, List<Object> sampleValues) {
        List<String> values = sampleValues.stream()
                .filter(Objects::nonNull)
                .map(String::valueOf)
                .map(String::trim)
                .filter(v -> !v.isEmpty())
                .toList();
        if (values.isEmpty()) {
            return List.of();
        }

        List<PatternSignal> signals = new ArrayList<>();
        for (PatternRule rule : RULES) {
            long matchCount = values.stream().filter(rule::matches).count();
            if (matchCount == 0) {
                continue;
            }
            double matchRate = (double) matchCount / values.size();
            signals.add(new PatternSignal(table, "", matchRate, rule.name()));
        }
        return signals;
    }

    private static PatternRule regex(String name, String pattern) {
        return new RegexPatternRule(name, Pattern.compile(pattern));
    }

    private interface PatternRule {
        String name();

        boolean matches(String value);
    }

    private record RegexPatternRule(String name, Pattern pattern) implements PatternRule {
        @Override
        public boolean matches(String value) {
            return pattern.matcher(value).matches();
        }
    }

    /** Credit-card-shaped digit runs (13–19 digits after stripping separators) that pass the Luhn checksum. */
    private static final class LuhnCreditCardRule implements PatternRule {
        private static final Pattern DIGIT_RUN = Pattern.compile("\\d{13,19}");

        @Override
        public String name() {
            return "CREDIT_CARD";
        }

        @Override
        public boolean matches(String value) {
            String digits = value.replaceAll("[\\s-]", "");
            return DIGIT_RUN.matcher(digits).matches() && luhnValid(digits);
        }

        private boolean luhnValid(String digits) {
            int sum = 0;
            boolean alternate = false;
            for (int i = digits.length() - 1; i >= 0; i--) {
                int n = digits.charAt(i) - '0';
                if (alternate) {
                    n *= 2;
                    if (n > 9) {
                        n -= 9;
                    }
                }
                sum += n;
                alternate = !alternate;
            }
            return sum % 10 == 0;
        }
    }
}
