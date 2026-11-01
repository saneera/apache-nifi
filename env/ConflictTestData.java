package com.babcock.circuitmanager.testdata;

import com.babcock.circuitmanager.dto.CircuitConflicts;
import com.babcock.circuitmanager.dto.ConflictReason;
import com.babcock.circuitmanager.dto.ElementConflict;
import com.babcock.circuitmanager.dto.FragmentConflict;

import java.util.List;
import java.util.UUID;

/** Mock conflict data shared by the controller tests. */
public final class ConflictTestData {

    public static final UUID CIRCUIT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    public static final UUID OTHER_CIRCUIT_1 = UUID.fromString("22222222-2222-2222-2222-222222222222");
    public static final UUID OTHER_CIRCUIT_2 = UUID.fromString("33333333-3333-3333-3333-333333333333");

    public static final String FRAGMENT_ID = "FRAG-TEST-01";
    public static final String ELEMENT_ID = "ELEM-TEST-01";

    // TODO: replace with a real constant once ConflictReason is confirmed, e.g. ConflictReason.FREQUENCY
    public static final ConflictReason REASON = ConflictReason.values()[0];

    private ConflictTestData() { }

    public static CircuitConflicts circuitConflicts(UUID circuitId) {
        return new CircuitConflicts()
                .circuitId(circuitId)
                .addFragmentsItem(new FragmentConflict()
                        .id(FRAGMENT_ID)
                        .addElementsItem(new ElementConflict()
                                .id(ELEMENT_ID)
                                .reason(REASON)
                                .conflictingCircuits(List.of(OTHER_CIRCUIT_1, OTHER_CIRCUIT_2))))
                .putResolutionItem("status", "UNRESOLVED");
    }

    public static List<CircuitConflicts> conflictsList() {
        return List.of(circuitConflicts(CIRCUIT_ID), circuitConflicts(OTHER_CIRCUIT_1));
    }
}
