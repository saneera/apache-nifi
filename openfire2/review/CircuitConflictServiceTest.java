package com.babcock.circuitmanager.service;

import com.babcock.circuitmanager.dto.CircuitConflicts;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CircuitConflictServiceTest {

    private final CircuitConflictService service = new CircuitConflictService();

    @Test
    void returnsSampleConflictsForRequestedCircuit() {
        UUID id = UUID.randomUUID();

        CircuitConflicts result = service.getConflictsForCircuit(id);

        assertThat(result.getCircuitId()).isEqualTo(id);
        assertThat(result.getFragments()).isNotEmpty();            // spec: minItems 1
        assertThat(result.getFragments())
                .allSatisfy(f -> assertThat(f.getElements()).isNotEmpty())
                .flatMap(f -> f.getElements())
                .allSatisfy(e -> {
                    assertThat(e.getReason()).isNotNull();
                    assertThat(e.getConflictingCircuits()).isNotEmpty();
                });
    }

    @Test
    void returnsConflictsForAllCircuits() {
        List<CircuitConflicts> all = service.getAllConflicts();

        assertThat(all).hasSize(2);
        assertThat(all).extracting(CircuitConflicts::getCircuitId).doesNotHaveDuplicates();
    }
}
