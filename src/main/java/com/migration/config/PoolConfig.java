package com.migration.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Connection-pool sizing for one connector. Source, destination, and control-plane each
 * get their own pool (design doc §8) so control-plane bookkeeping never contends with
 * source/destination throughput.
 */
public record PoolConfig(
        @Min(1) @DefaultValue("10") int maxSize) {
}
