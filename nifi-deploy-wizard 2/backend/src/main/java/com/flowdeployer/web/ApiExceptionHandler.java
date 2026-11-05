package com.flowdeployer.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.ResourceAccessException;

/** Turns failures into RFC 7807 problem responses; the UI shows `detail` in its error banner. */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiController.NotFoundException.class)
    ProblemDetail notFound(ApiController.NotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail badRequest(IllegalArgumentException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail invalid(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b).orElse("Invalid request");
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, msg);
    }

    /** NiFi / Registry answered with an error status. */
    @ExceptionHandler(RestClientResponseException.class)
    ProblemDetail upstream(RestClientResponseException e) {
        log.warn("Upstream error {}: {}", e.getStatusCode(), e.getResponseBodyAsString());
        String body = e.getResponseBodyAsString();
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY,
                "NiFi/Registry returned " + e.getStatusCode().value()
                        + (body.isBlank() ? "" : ": " + (body.length() > 200 ? body.substring(0, 200) + "…" : body)));
    }

    /** Connection refused, TLS failure, timeout… */
    @ExceptionHandler(ResourceAccessException.class)
    ProblemDetail unreachable(ResourceAccessException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, "Cannot reach NiFi/Registry: " + e.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    ProblemDetail state(IllegalStateException e) {
        log.error("Request failed", e);
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
    }

    @ExceptionHandler(com.flowdeployer.deploy.DeploymentManager.BusyException.class)
    ProblemDetail busy(com.flowdeployer.deploy.DeploymentManager.BusyException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }
}
