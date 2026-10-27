private CircuitComposeOperation createComposeOperationRequest(
        String operationId,
        CircuitOperation circuitOperation,
        CircuitEntity circuitEntity,
        Map<String, Map<String, Map<String, Object>>> requestOverrides) {   // NEW: FragmentRequest.request (may be null)

    Map<String, FragmentOperationInfo> fragmentOperations =
            circuitOperation.getFragmentOperations().entrySet().stream()
                    .map(fragmentOperation -> {
                        Fragment fragment = getFragmentByTemplateId(circuitEntity, fragmentOperation.getKey());

                        FragmentOperationInfo operationInfo = new FragmentOperationInfo();
                        operationInfo.fragmentId(fragment.getId());

                        Map<String, List<Statement>> populatedFragmentOperation;
                        if (circuitEntity.getOperationsInfo() != null) {
                            CircuitOperationEntity operationEntity =
                                    circuitEntity.getOperationsInfo().get(operationId);
                            populatedFragmentOperation = populateFragmentOperation(
                                    fragment.getElements(), fragmentOperation, operationEntity);
                        } else {
                            log.warn("createComposeOperationRequest: operationsInfo is null");
                            populatedFragmentOperation = fragmentOperation.getValue();
                        }

                        // NEW: values sent by the UI win over template / stored values
                        populatedFragmentOperation = applyRequestOverrides(
                                populatedFragmentOperation,
                                requestOverrides == null ? null : requestOverrides.get(fragmentOperation.getKey()));

                        operationInfo.fragmentOperation(populatedFragmentOperation.entrySet().stream()
                                .collect(toMap(Entry::getKey, e -> e.getValue().stream()
                                        .map(this::convertStatement)
                                        .toList())));

                        return new ImmutablePair<>(fragment.getNodeId(), operationInfo);
                    })
                    .collect(toMap(Pair::getKey, Pair::getValue));

    return new CircuitComposeOperation().fragmentOperations(fragmentOperations);
}


/**
 * Overrides statement values with the ones sent in FragmentRequest.request.
 * overrides = elementId -> { paramName -> value }, e.g. headset -> { conferenceId, targets }.
 * Builds new statements so the template's objects are never mutated.
 */
private Map<String, List<Statement>> applyRequestOverrides(
        Map<String, List<Statement>> statementsByElement,
        Map<String, Map<String, Object>> overrides) {

    if (overrides == null || overrides.isEmpty()) {
        return statementsByElement;
    }

    Map<String, List<Statement>> result = new LinkedHashMap<>();
    statementsByElement.forEach((elementId, statements) -> {
        Map<String, Object> values = overrides.get(elementId);
        if (values == null) {
            result.put(elementId, statements);
            return;
        }
        result.put(elementId, statements.stream().map(statement -> {
            if (statement instanceof CallCommandOperation command) {
                Map<String, Object> params = new LinkedHashMap<>(
                        command.getParameters() == null ? Map.of() : command.getParameters());
                params.putAll(values);   // e.g. targets: ["1115","1114"]
                return (Statement) new CallCommandOperation()
                        .port(command.getPort())
                        .command(command.getCommand())
                        .parameters(params);
            }
            // TODO: property statements (e.g. set position) — same idea with their value field
            return statement;
        }).toList());
    });
    return result;
}
