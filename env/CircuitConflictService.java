package com.babcock.circuitmanager.service;

import com.babcock.circuitmanager.dto.CircuitConflicts;
import com.babcock.circuitmanager.dto.ConflictReason;
import com.babcock.circuitmanager.dto.ElementConflict;
import com.babcock.circuitmanager.dto.FragmentConflict;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Circuit conflicts. Reads from the props service by default. When
 * {@code circuitmanager.features.conflicts.pre-canned=true}, it returns fixed sample data
 * instead, so the UI can be built and tested before real conflict data exists.
 */
@Slf4j
@Service
public class CircuitConflictService {

    // TODO: replace with the real props service client / repository used elsewhere in circuitmanager
    private final PropsServiceClient propsServiceClient;
    private final boolean preCanned;

    public CircuitConflictService(
            PropsServiceClient propsServiceClient,
            @Value("${circuitmanager.features.conflicts.pre-canned:false}") boolean preCanned) {
        this.propsServiceClient = propsServiceClient;
        this.preCanned = preCanned;
        if (preCanned) {
            log.warn("Circuit conflicts feature flag is ON: returning pre-canned sample data, not props service data");
        }
    }

    public CircuitConflicts getConflictsForCircuit(UUID circuitId) {
        if (preCanned) {
            return sampleConflicts(circuitId);
        }
        return propsServiceClient.getCircuitConflicts(circuitId);
    }

    public List<CircuitConflicts> getAllConflicts() {
        if (preCanned) {
            return sampleConflictsList();
        }
        return propsServiceClient.getAllCircuitConflicts();
    }

    // ---------------------------------------------------------------------
    // Pre-canned sample data (only used when the feature flag is on)
    // ---------------------------------------------------------------------

    private static final UUID CIRCUIT_A = UUID.fromString("3f2b6c1e-8a4d-4c2b-9f1e-1a2b3c4d5e01");
    private static final UUID CIRCUIT_B = UUID.fromString("3f2b6c1e-8a4d-4c2b-9f1e-1a2b3c4d5e02");
    private static final UUID CIRCUIT_C = UUID.fromString("3f2b6c1e-8a4d-4c2b-9f1e-1a2b3c4d5e03");
    private static final UUID CIRCUIT_D = UUID.fromString("3f2b6c1e-8a4d-4c2b-9f1e-1a2b3c4d5e04");

    // TODO: swap these for the real ConflictReason enum constants from circuit-conflict.yaml.
    private static final ConflictReason REASON_FREQUENCY = ConflictReason.values()[0];
    private static final ConflictReason REASON_OTHER =
            ConflictReason.values()[Math.min(1, ConflictReason.values().length - 1)];

    private static CircuitConflicts sampleConflicts(UUID circuitId) {
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
                .putResolutionItem("status", "UNRESOLVED")
                .putResolutionItem("suggestedAction", "Reallocate frequency for ELEM-TX-FREQ-01");
    }

    private static List<CircuitConflicts> sampleConflictsList() {
        CircuitConflicts circuitB = new CircuitConflicts()
                .circuitId(CIRCUIT_B)
                .addFragmentsItem(new FragmentConflict()
                        .id("FRAG-HF-TX-01")
                        .addElementsItem(new ElementConflict()
                                .id("ELEM-TX-FREQ-01")
                                .reason(REASON_FREQUENCY)
                                .conflictingCircuits(List.of(CIRCUIT_A))));
        return List.of(sampleConflicts(CIRCUIT_A), circuitB);
    }
}
