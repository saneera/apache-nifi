package com.babcock.circuitmanager.service;

import com.babcock.circuitmanager.dto.CircuitConflicts;
import com.babcock.circuitmanager.dto.ConflictReason;
import com.babcock.circuitmanager.dto.ElementConflict;
import com.babcock.circuitmanager.dto.FragmentConflict;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Enabler stub: returns pre-canned conflict data so the UI can be built
 * before real conflict analysis exists. Replace the bodies once the
 * ACSS frequency data / conflict analysis is wired in.
 */
@Service
public class CircuitConflictService {

    // Fixed IDs so the UI gets stable data across calls and restarts.
    private static final UUID CIRCUIT_A = UUID.fromString("3f2b6c1e-8a4d-4c2b-9f1e-1a2b3c4d5e01");
    private static final UUID CIRCUIT_B = UUID.fromString("3f2b6c1e-8a4d-4c2b-9f1e-1a2b3c4d5e02");
    private static final UUID CIRCUIT_C = UUID.fromString("3f2b6c1e-8a4d-4c2b-9f1e-1a2b3c4d5e03");
    private static final UUID CIRCUIT_D = UUID.fromString("3f2b6c1e-8a4d-4c2b-9f1e-1a2b3c4d5e04");

    // TODO: swap these for the real ConflictReason enum constants from circuit-conflict.yaml.
    private static final ConflictReason REASON_FREQUENCY = ConflictReason.values()[0];
    private static final ConflictReason REASON_OTHER =
            ConflictReason.values()[Math.min(1, ConflictReason.values().length - 1)];

    /**
     * Conflicts for one circuit. Any circuit id returns sample data,
     * echoed back with the requested id, so every circuit detail view has something to show.
     */
    public CircuitConflicts getConflictsForCircuit(UUID circuitId) {
        return new CircuitConflicts()
                .circuitId(circuitId)
                .addFragmentsItem(new FragmentConflict()
                        .id("FRAG-HF-TX-01")
                        .addElementsItem(new ElementConflict()
                                .id("ELEM-TX-FREQ-01")
                                .reason(REASON_FREQUENCY)
                                .conflictingCircuits(List.of(CIRCUIT_B, CIRCUIT_C)))
                        .addElementsItem(new ElementConflict()
                                .id("ELEM-TX-ANT-02")
                                .reason(REASON_OTHER)
                                .conflictingCircuits(List.of(CIRCUIT_D))))
                .addFragmentsItem(new FragmentConflict()
                        .id("FRAG-HF-RX-01")
                        .addElementsItem(new ElementConflict()
                                .id("ELEM-RX-FREQ-01")
                                .reason(REASON_FREQUENCY)
                                .conflictingCircuits(List.of(CIRCUIT_B))))
                // resolution is a free-form extension point in the spec
                .putResolutionItem("status", "UNRESOLVED")
                .putResolutionItem("suggestedAction", "Reallocate frequency for ELEM-TX-FREQ-01");
    }

    /** Conflicts across all circuits. */
    public List<CircuitConflicts> getAllConflicts() {
        CircuitConflicts circuitB = new CircuitConflicts()
                .circuitId(CIRCUIT_B)
                .addFragmentsItem(new FragmentConflict()
                        .id("FRAG-HF-TX-01")
                        .addElementsItem(new ElementConflict()
                                .id("ELEM-TX-FREQ-01")
                                .reason(REASON_FREQUENCY)
                                .conflictingCircuits(List.of(CIRCUIT_A))));

        return List.of(getConflictsForCircuit(CIRCUIT_A), circuitB);
    }
}
