package com.migration.masking.technique;

import com.migration.domain.exception.MigrationException;
import com.migration.masking.MaskedValue;
import com.migration.masking.MaskingContext;
import com.migration.masking.MaskingTechnique;
import com.migration.masking.MaskingTechniqueId;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Deterministically seeds a synthetic-value generator from
 * {@code HASH_HMAC(value, key)} so the same input always yields the same synthetic
 * output, with no path back to the original (design doc §4) — a GDPR anonymization
 * technique when the corpus has no linkage back to source values, useful where
 * downstream systems need realistic-looking but non-reversible test data. The bundled
 * corpora are generic placeholder name/email combinations (not drawn from any real
 * individual or real-value dictionary that could be inverted by frequency-matching);
 * best used for display-only fields, not identifiers used for joins (identifiers use
 * {@code HASH_HMAC} directly instead — see {@code KeyColumnMaskingValidator}).
 */
@Component
public class SyntheticSubstitutionTechnique implements MaskingTechnique {

    private static final String DEFAULT_CORPUS = "name";

    private final HmacHashTechnique hmac;
    private final Map<String, List<String>> corpora;

    public SyntheticSubstitutionTechnique(HmacHashTechnique hmac) {
        this.hmac = hmac;
        this.corpora = Map.of(
                "name", loadCorpus("/masking/corpus/name-corpus.txt"),
                "email", loadCorpus("/masking/corpus/email-corpus.txt"));
    }

    @Override
    public MaskingTechniqueId id() {
        return MaskingTechniqueId.SYNTHETIC_SUBSTITUTION;
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
        String corpusName = ctx.rule() != null && ctx.rule().corpus() != null ? ctx.rule().corpus() : DEFAULT_CORPUS;
        List<String> corpus = corpora.get(corpusName);
        if (corpus == null) {
            throw new MigrationException("unknown SYNTHETIC_SUBSTITUTION corpus: " + corpusName);
        }
        byte[] digest = hmac.digest(rawValue, ctx.key());
        long index = ByteBuffer.wrap(digest, 0, Long.BYTES).getLong();
        String candidate = corpus.get((int) Math.floorMod(index, corpus.size()));
        return MaskedValue.of(candidate);
    }

    private List<String> loadCorpus(String classpathLocation) {
        List<String> lines = new ArrayList<>();
        try (InputStream in = SyntheticSubstitutionTechnique.class.getResourceAsStream(classpathLocation)) {
            if (in == null) {
                throw new MigrationException("corpus resource not found: " + classpathLocation);
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.isBlank()) {
                        lines.add(line.trim());
                    }
                }
            }
        } catch (IOException e) {
            throw new MigrationException("failed to load corpus resource: " + classpathLocation, e);
        }
        if (lines.isEmpty()) {
            throw new MigrationException("corpus resource is empty: " + classpathLocation);
        }
        return List.copyOf(lines);
    }
}
