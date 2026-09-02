package com.migration.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code migration.masking} — where the HMAC secret comes from and which version of it
 * is active. {@code keySource} is a scheme-prefixed reference (e.g. {@code env:VAR_NAME})
 * resolved by {@code MaskingKeyProvider}; the key itself is never present in this config
 * object. {@code keyVersion} is reserved for future key rotation (design doc §1
 * assumption 3) — a single version is used throughout v1.
 */
public record MaskingConfig(
        @NotBlank String keySource,
        @Min(1) @DefaultValue("1") int keyVersion) {
}
