package com.babcock.circuitmanager.service;

import com.babcock.circuitmanager.dto.CircuitConflicts;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static com.babcock.circuitmanager.testdata.ConflictTestData.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CircuitConflictServiceTest {

    @Mock
    private PropsServiceClient propsServiceClient;

    @Nested
    class FlagOff_usesPropsService {

        private CircuitConflictService service() {
            return new CircuitConflictService(propsServiceClient, false);
        }

        @Test
        void getConflictsForCircuit_readsFromPropsService() {
            CircuitConflicts mockData = circuitConflicts(CIRCUIT_ID);
            when(propsServiceClient.getCircuitConflicts(CIRCUIT_ID)).thenReturn(mockData);

            assertThat(service().getConflictsForCircuit(CIRCUIT_ID)).isSameAs(mockData);
            verify(propsServiceClient).getCircuitConflicts(CIRCUIT_ID);
        }

        @Test
        void getAllConflicts_readsFromPropsService() {
            List<CircuitConflicts> mockData = conflictsList();
            when(propsServiceClient.getAllCircuitConflicts()).thenReturn(mockData);

            assertThat(service().getAllConflicts()).isSameAs(mockData);
            verify(propsServiceClient).getAllCircuitConflicts();
        }

        @Test
        void propsServiceFailure_propagates() {
            when(propsServiceClient.getCircuitConflicts(CIRCUIT_ID))
                    .thenThrow(new RuntimeException("props service down"));

            assertThatThrownBy(() -> service().getConflictsForCircuit(CIRCUIT_ID))
                    .hasMessageContaining("props service down");
        }
    }

    @Nested
    class FlagOn_usesPreCannedData {

        private CircuitConflictService service() {
            return new CircuitConflictService(propsServiceClient, true);
        }

        @Test
        void getConflictsForCircuit_returnsSampleData_andNeverCallsPropsService() {
            UUID id = UUID.randomUUID();

            CircuitConflicts result = service().getConflictsForCircuit(id);

            assertThat(result.getCircuitId()).isEqualTo(id);
            assertThat(result.getFragments()).isNotEmpty();                // spec: minItems 1
            assertThat(result.getFragments())
                    .allSatisfy(f -> assertThat(f.getElements()).isNotEmpty())
                    .flatMap(f -> f.getElements())
                    .allSatisfy(e -> {
                        assertThat(e.getReason()).isNotNull();
                        assertThat(e.getConflictingCircuits()).isNotEmpty();
                    });
            verifyNoInteractions(propsServiceClient);
        }

        @Test
        void getAllConflicts_returnsSampleData_andNeverCallsPropsService() {
            List<CircuitConflicts> result = service().getAllConflicts();

            assertThat(result).hasSize(2);
            assertThat(result).extracting(CircuitConflicts::getCircuitId).doesNotHaveDuplicates();
            verifyNoInteractions(propsServiceClient);
        }
    }
}
