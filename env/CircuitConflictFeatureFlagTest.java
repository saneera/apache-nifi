package com.babcock.circuitmanager.service;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Checks the feature flag picks the right implementation.
 * ApplicationContextRunner starts a tiny context with just these beans, so it's fast.
 */
class CircuitConflictFeatureFlagTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(PropsServiceClient.class, () -> mock(PropsServiceClient.class))
            .withUserConfiguration(PropsCircuitConflictService.class, PreCannedCircuitConflictService.class);

    @Test
    void flagMissing_usesPropsService() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(CircuitConflictService.class);
            assertThat(ctx.getBean(CircuitConflictService.class)).isInstanceOf(PropsCircuitConflictService.class);
        });
    }

    @Test
    void flagFalse_usesPropsService() {
        runner.withPropertyValues(CircuitConflictService.PRE_CANNED_FLAG + "=false")
                .run(ctx -> assertThat(ctx.getBean(CircuitConflictService.class))
                        .isInstanceOf(PropsCircuitConflictService.class));
    }

    @Test
    void flagTrue_usesPreCannedData() {
        runner.withPropertyValues(CircuitConflictService.PRE_CANNED_FLAG + "=true")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(CircuitConflictService.class);
                    assertThat(ctx.getBean(CircuitConflictService.class))
                            .isInstanceOf(PreCannedCircuitConflictService.class);
                });
    }
}
