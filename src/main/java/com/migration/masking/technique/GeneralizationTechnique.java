package com.migration.masking.technique;

import com.migration.domain.exception.MigrationException;
import com.migration.masking.MaskedValue;
import com.migration.masking.MaskingContext;
import com.migration.masking.MaskingTechnique;
import com.migration.masking.MaskingTechniqueId;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Date;

/**
 * Reduces precision rather than replacing the value outright: DOB → birth year, ZIP →
 * first 3 digits, exact age → a 5-year band or HIPAA's ≥90 aggregation. Directly
 * implements HIPAA Safe Harbor's specific de-identification rules (dates limited to
 * year; geographic subdivision to first 3 ZIP digits; ages ≥90 aggregated); also a GDPR
 * anonymization technique when the resulting equivalence class is large enough to
 * prevent singling-out — bucket size is a per-column tuning decision, not fully
 * automatable (design doc §4).
 */
@Component
public class GeneralizationTechnique implements MaskingTechnique {

    @Override
    public MaskingTechniqueId id() {
        return MaskingTechniqueId.GENERALIZE_BUCKET;
    }

    @Override
    public boolean isInjective() {
        return false;
    }

    @Override
    public MaskedValue apply(Object rawValue, MaskingContext ctx) {
        if (rawValue == null) {
            return MaskedValue.of(null);
        }
        String granularity = ctx.rule() != null ? ctx.rule().granularity() : null;
        if (granularity == null) {
            throw new MigrationException("GENERALIZE_BUCKET requires a granularity for column " + ctx.column());
        }
        return switch (granularity.toUpperCase()) {
            case "YEAR" -> MaskedValue.of(toLocalDate(rawValue).getYear());
            case "ZIP3" -> MaskedValue.of(zip3(rawValue));
            case "AGE_5YEAR_BAND" -> MaskedValue.of(ageBand(rawValue));
            case "AGE_90_PLUS" -> MaskedValue.of(age90Plus(rawValue));
            default -> throw new MigrationException("unknown GENERALIZE_BUCKET granularity: " + granularity);
        };
    }

    private String zip3(Object rawValue) {
        String zip = String.valueOf(rawValue);
        return zip.length() >= 3 ? zip.substring(0, 3) : zip;
    }

    private String ageBand(Object rawValue) {
        int age = toInt(rawValue);
        int bandStart = (age / 5) * 5;
        return bandStart + "-" + (bandStart + 4);
    }

    private String age90Plus(Object rawValue) {
        int age = toInt(rawValue);
        return age >= 90 ? "90+" : String.valueOf(age);
    }

    private int toInt(Object rawValue) {
        if (rawValue instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(rawValue).trim());
        } catch (NumberFormatException e) {
            throw new MigrationException("cannot interpret '" + rawValue + "' as an age", e);
        }
    }

    private LocalDate toLocalDate(Object rawValue) {
        if (rawValue instanceof LocalDate localDate) {
            return localDate;
        }
        if (rawValue instanceof java.time.LocalDateTime localDateTime) {
            return localDateTime.toLocalDate();
        }
        if (rawValue instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime().toLocalDate();
        }
        if (rawValue instanceof java.sql.Date sqlDate) {
            return sqlDate.toLocalDate();
        }
        if (rawValue instanceof Instant instant) {
            return instant.atZone(ZoneOffset.UTC).toLocalDate();
        }
        if (rawValue instanceof Date date) {
            return date.toInstant().atZone(ZoneOffset.UTC).toLocalDate();
        }
        try {
            return LocalDate.parse(String.valueOf(rawValue).substring(0, 10));
        } catch (DateTimeParseException | IndexOutOfBoundsException e) {
            throw new MigrationException("cannot interpret '" + rawValue + "' as a date", e);
        }
    }
}
