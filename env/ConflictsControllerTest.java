package com.babcock.circuitmanager.controller;

import com.babcock.circuitmanager.service.CircuitConflictService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static com.babcock.circuitmanager.testdata.ConflictTestData.*;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ConflictsController.class)
@ActiveProfiles("test")
class ConflictsControllerTest {

    // TODO: confirm against the path in the spec for getConflictsList
    private static final String BASE_URL = "/conflicts";

    @MockBean
    private CircuitConflictService circuitConflictService;

    private final MockMvc mvc;

    @Autowired
    ConflictsControllerTest(MockMvc mvc) {
        this.mvc = mvc;
    }

    @Test
    void get_conflicts_list_returns_all_circuit_conflicts() throws Exception {
        when(circuitConflictService.getAllConflicts()).thenReturn(conflictsList());

        mvc.perform(get(BASE_URL).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].circuitId").value(CIRCUIT_ID.toString()))
                .andExpect(jsonPath("$[1].circuitId").value(OTHER_CIRCUIT_1.toString()))
                .andExpect(jsonPath("$[0].fragments[0].id").value(FRAGMENT_ID));

        verify(circuitConflictService).getAllConflicts();
    }

    @Test
    void get_conflicts_list_returns_empty_list_when_no_conflicts() throws Exception {
        when(circuitConflictService.getAllConflicts()).thenReturn(List.of());

        mvc.perform(get(BASE_URL).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void get_conflicts_list_returns_not_implemented_when_props_service_not_ready() throws Exception {
        when(circuitConflictService.getAllConflicts())
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED));

        mvc.perform(get(BASE_URL))
                .andExpect(status().isNotImplemented());
    }
}
