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

import java.util.List;

import static com.babcock.circuitmanager.testdata.ConflictTestData.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class ConflictsControllerTest {

    // TODO: confirm against the path in the spec for getConflictsList
    private static final String CONFLICTS_PATH = "/conflicts";

    @Mock
    private CircuitConflictService circuitConflictService;

    @InjectMocks
    private ConflictsController controller;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // Standalone setup: no Spring context and no security filters, just the controller.
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void getConflictsList_returnsWhatTheServiceReturns() throws Exception {
        List<CircuitConflicts> mockData = conflictsList();
        when(circuitConflictService.getAllConflicts()).thenReturn(mockData);

        List<CircuitConflicts> result = controller.getConflictsList();

        assertThat(result).isSameAs(mockData);
        verify(circuitConflictService).getAllConflicts();
    }

    @Test
    void getConflictsList_returnsEmptyListWhenNoConflicts() throws Exception {
        when(circuitConflictService.getAllConflicts()).thenReturn(List.of());

        assertThat(controller.getConflictsList()).isEmpty();
    }

    @Test
    void getConflictsList_overHttp_returnsJson() throws Exception {
        when(circuitConflictService.getAllConflicts()).thenReturn(conflictsList());

        mockMvc.perform(get(CONFLICTS_PATH).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].circuitId").value(CIRCUIT_ID.toString()))
                .andExpect(jsonPath("$[1].circuitId").value(OTHER_CIRCUIT_1.toString()))
                .andExpect(jsonPath("$[0].fragments[0].id").value(FRAGMENT_ID));

        verify(circuitConflictService, times(1)).getAllConflicts();
    }
}
