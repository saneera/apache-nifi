package com.babcock.circuitmanager.controller;

import com.babcock.circuitmanager.dto.CircuitConflicts;
import com.babcock.circuitmanager.service.CircuitConflictService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.UUID;

import static com.babcock.circuitmanager.testdata.ConflictTestData.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Tests only getCircuitConflicts. CircuitsController's other dependencies are left
 * as null by @InjectMocks; add @Mock fields for them if the constructor rejects nulls.
 */
@ExtendWith(MockitoExtension.class)
class CircuitsControllerConflictsTest {

    @Mock
    private CircuitConflictService circuitConflictService;

    // @Mock SolutionManagerClient solutionManagerClient;  // add other dependencies as needed

    @InjectMocks
    private CircuitsController controller;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void getCircuitConflicts_passesCircuitIdToService() throws Exception {
        CircuitConflicts mockData = circuitConflicts(CIRCUIT_ID);
        when(circuitConflictService.getConflictsForCircuit(CIRCUIT_ID)).thenReturn(mockData);

        CircuitConflicts result = controller.getCircuitConflicts(CIRCUIT_ID);

        assertThat(result).isSameAs(mockData);
        verify(circuitConflictService).getConflictsForCircuit(CIRCUIT_ID);
        verifyNoMoreInteractions(circuitConflictService);
    }

    @Test
    void getCircuitConflicts_overHttp_returnsConflictDetails() throws Exception {
        when(circuitConflictService.getConflictsForCircuit(CIRCUIT_ID))
                .thenReturn(circuitConflicts(CIRCUIT_ID));

        mockMvc.perform(get("/circuits/{circuit}/conflicts", CIRCUIT_ID)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.circuitId").value(CIRCUIT_ID.toString()))
                .andExpect(jsonPath("$.fragments", hasSize(1)))
                .andExpect(jsonPath("$.fragments[0].id").value(FRAGMENT_ID))
                .andExpect(jsonPath("$.fragments[0].elements[0].id").value(ELEMENT_ID))
                .andExpect(jsonPath("$.fragments[0].elements[0].reason").exists())
                .andExpect(jsonPath("$.fragments[0].elements[0].conflictingCircuits",
                        containsInAnyOrder(OTHER_CIRCUIT_1.toString(), OTHER_CIRCUIT_2.toString())))
                .andExpect(jsonPath("$.resolution.status").value("UNRESOLVED"));
    }

    @Test
    void getCircuitConflicts_invalidUuid_returns400AndDoesNotCallService() throws Exception {
        mockMvc.perform(get("/circuits/{circuit}/conflicts", "not-a-uuid"))
                .andExpect(status().isBadRequest());

        verify(circuitConflictService, never()).getConflictsForCircuit(any(UUID.class));
    }

    @Test
    void getCircuitConflicts_serviceFails_exceptionPropagates() {
        when(circuitConflictService.getConflictsForCircuit(CIRCUIT_ID))
                .thenThrow(new IllegalStateException("conflict analysis unavailable"));

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> controller.getCircuitConflicts(CIRCUIT_ID));
    }
}
