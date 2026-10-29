package com.babcock.circuitmanager.controller;

import com.babcock.circuitmanager.api.ConflictsApi;
import com.babcock.circuitmanager.dto.CircuitConflicts;
import com.babcock.circuitmanager.service.CircuitConflictService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController            // without this Spring never registers the generated mappings
@RequiredArgsConstructor
public class ConflictsController implements ConflictsApi {

    private final CircuitConflictService circuitConflictService;

    @Override
    public List<CircuitConflicts> getConflictsList() throws Exception {
        return circuitConflictService.getAllConflicts();
    }
}
