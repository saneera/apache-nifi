// ===== Add to the existing CircuitsControllerTest =====
// (and delete CircuitConflictControllerTest.java)

// ---- 1. New field, next to the other @MockBean fields ----

    @MockBean
    private CircuitConflictService circuitConflictService;


// ---- 2. New test methods (use the existing `mvc` and BASE_URL) ----

    @Test
    void get_circuit_conflicts_returns_conflict_details() throws Exception {
        when(circuitConflictService.getConflictsForCircuit(CIRCUIT_ID))
                .thenReturn(circuitConflicts(CIRCUIT_ID));

        mvc.perform(get(BASE_URL + "/{circuit}/conflicts", CIRCUIT_ID)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.circuitId").value(CIRCUIT_ID.toString()))
                .andExpect(jsonPath("$.fragments", hasSize(1)))
                .andExpect(jsonPath("$.fragments[0].id").value(FRAGMENT_ID))
                .andExpect(jsonPath("$.fragments[0].elements[0].id").value(ELEMENT_ID))
                .andExpect(jsonPath("$.fragments[0].elements[0].reason").exists())
                .andExpect(jsonPath("$.fragments[0].elements[0].conflictingCircuits",
                        containsInAnyOrder(OTHER_CIRCUIT_1.toString(), OTHER_CIRCUIT_2.toString())))
                .andExpect(jsonPath("$.resolution.status").value("UNRESOLVED"));

        verify(circuitConflictService).getConflictsForCircuit(CIRCUIT_ID);
    }

    @Test
    void get_circuit_conflicts_with_invalid_uuid_returns_bad_request() throws Exception {
        mvc.perform(get(BASE_URL + "/{circuit}/conflicts", "not-a-uuid"))
                .andExpect(status().isBadRequest());

        verify(circuitConflictService, never()).getConflictsForCircuit(any(UUID.class));
    }

    @Test
    void get_circuit_conflicts_returns_not_implemented_when_props_service_not_ready() throws Exception {
        when(circuitConflictService.getConflictsForCircuit(CIRCUIT_ID))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED));

        mvc.perform(get(BASE_URL + "/{circuit}/conflicts", CIRCUIT_ID))
                .andExpect(status().isNotImplemented());
    }


// ---- 3. Imports to add (IntelliJ will offer most of these) ----
// import com.babcock.circuitmanager.service.CircuitConflictService;
// import org.springframework.http.HttpStatus;
// import org.springframework.web.server.ResponseStatusException;
// import static com.babcock.circuitmanager.testdata.ConflictTestData.*;
// import static org.hamcrest.Matchers.containsInAnyOrder;
// import static org.hamcrest.Matchers.hasSize;
