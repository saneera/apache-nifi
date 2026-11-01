package com.babcock.circuitmanager.service;

import com.babcock.circuitmanager.dto.CircuitConflicts;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/** Default implementation: circuit conflicts come from the props service. */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = CircuitConflictService.PRE_CANNED_FLAG, havingValue = "false", matchIfMissing = true)
public class PropsCircuitConflictService implements CircuitConflictService {

    // TODO: replace with the real props service client / repository used elsewhere in circuitmanager
    private final PropsServiceClient propsServiceClient;

    @Override
    public CircuitConflicts getConflictsForCircuit(UUID circuitId) {
        log.debug("Fetching conflicts for circuit {} from props service", circuitId);
        return propsServiceClient.getCircuitConflicts(circuitId);
    }

    @Override
    public List<CircuitConflicts> getAllConflicts() {
        log.debug("Fetching all circuit conflicts from props service");
        return propsServiceClient.getAllCircuitConflicts();
    }
}
