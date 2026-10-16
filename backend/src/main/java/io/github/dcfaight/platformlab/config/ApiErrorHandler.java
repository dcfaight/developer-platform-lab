package io.github.dcfaight.platformlab.config;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class ApiErrorHandler extends ResponseEntityExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ApiErrorHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Object> handleApi(ApiException exception, WebRequest request) {
        return ResponseEntity.status(exception.status()).body(error(exception.code(), exception.getMessage(),
                List.of(), request));
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        var fields = exception.getBindingResult().getFieldErrors().stream()
                .map(field -> new FieldError(field.getField(), field.getDefaultMessage()))
                .sorted(java.util.Comparator.comparing(FieldError::field).thenComparing(FieldError::message))
                .toList();
        return ResponseEntity.badRequest().body(error("VALIDATION_ERROR", "Request validation failed.",
                fields, request));
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception exception, Object body,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String code = status.value() == 404 ? "NOT_FOUND" : "INVALID_REQUEST";
        String message = status.value() == 404 ? "Resource not found." : "Invalid request format or parameters.";
        return new ResponseEntity<>(error(code, message, List.of(), request), headers, status);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> handleUnexpected(Exception exception, WebRequest request) {
        LOG.error("requestId={} code=INTERNAL_ERROR exceptionType={}", requestId(request),
                exception.getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error("INTERNAL_ERROR",
                "The request could not be completed.", List.of(), request));
    }

    private ApiError error(String code, String message, List<FieldError> fields, WebRequest request) {
        return new ApiError(code, message, requestId(request), fields);
    }

    private String requestId(WebRequest request) {
        return (String) request.getAttribute(RequestCorrelationFilter.REQUEST_ID, RequestAttributes.SCOPE_REQUEST);
    }

    public record ApiError(String code, String message, String requestId, List<FieldError> fieldErrors) {
    }

    public record FieldError(String field, String message) {
    }
}
