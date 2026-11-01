package com.babcock.circuitmanager.service;

import com.babcock.circuitmanager.dto.CircuitConflicts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static com.babcock.circuitmanager.testdata.ConflictTestData.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PropsCircuitConflictServiceTest {

    @Mock
    private PropsServiceClient propsServiceClient;

    @InjectMocks
    private PropsCircuitConflictService service;

    @Test
    void getConflictsForCircuit_readsFromPropsService() {
        CircuitConflicts mockData = circuitConflicts(CIRCUIT_ID);
        when(propsServiceClient.getCircuitConflicts(CIRCUIT_ID)).thenReturn(mockData);

        assertThat(service.getConflictsForCircuit(CIRCUIT_ID)).isSameAs(mockData);
        verify(propsServiceClient).getCircuitConflicts(CIRCUIT_ID);
    }

    @Test
    void getAllConflicts_readsFromPropsService() {
        List<CircuitConflicts> mockData = conflictsList();
        when(propsServiceClient.getAllCircuitConflicts()).thenReturn(mockData);

        assertThat(service.getAllConflicts()).isSameAs(mockData);
    }

    @Test
    void propsServiceFailure_propagates() {
        when(propsServiceClient.getCircuitConflicts(CIRCUIT_ID))
                .thenThrow(new RuntimeException("props service down"));

        assertThatThrownBy(() -> service.getConflictsForCircuit(CIRCUIT_ID))
                .hasMessageContaining("props service down");
    }
}
