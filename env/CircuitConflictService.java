package com.babcock.circuitmanager.service;

import com.babcock.circuitmanager.dto.CircuitConflicts;

import java.util.List;
import java.util.UUID;

/**
 * Source of circuit conflicts. The implementation is chosen by the
 * {@code circuitmanager.features.conflicts.pre-canned} feature flag:
 * <ul>
 *   <li>false / missing (default): {@link PropsCircuitConflictService}, which reads from the props service</li>
 *   <li>true: {@link PreCannedCircuitConflictService}, with fixed sample data for UI development and testing</li>
 * </ul>
 */
public interface CircuitConflictService {

    String PRE_CANNED_FLAG = "circuitmanager.features.conflicts.pre-canned";

    CircuitConflicts getConflictsForCircuit(UUID circuitId);

    List<CircuitConflicts> getAllConflicts();
}
