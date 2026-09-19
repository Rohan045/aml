package com.azentio.aml.web.error;

import com.azentio.aml.common.exception.BusinessRuleException;
import com.azentio.aml.common.exception.IngestionException;
import com.azentio.aml.common.exception.NotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Translates every exception escaping a controller into the single JSON error shape documented in
 * the OpenAPI spec, so clients never have to parse a container error page.
 *
 * <p>Server-side faults are logged with their stack trace but the response body deliberately omits
 * internal detail; client faults are logged at debug level only, since they are expected traffic.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(NotFoundException ex, HttpServletRequest req) {
        return build(HttpStatus.NOT_FOUND, "Resource not found", ex.getMessage(), req, null);
    }

    @ExceptionHandler(BusinessRuleException.class)
    public ResponseEntity<ApiError> handleBusinessRule(
            BusinessRuleException ex, HttpServletRequest req) {
        return build(HttpStatus.CONFLICT, "Business rule violation", ex.getMessage(), req, null);
    }

    @ExceptionHandler(IngestionException.class)
    public ResponseEntity<ApiError> handleIngestion(IngestionException ex, HttpServletRequest req) {
        return build(
                HttpStatus.UNPROCESSABLE_ENTITY, "Ingestion failed", ex.getMessage(), req, null);
    }

    /** Field-level bean validation failures on a request body. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleInvalidBody(
            MethodArgumentNotValidException ex, HttpServletRequest req) {
        Map<String, String> fields = new TreeMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            fields.merge(
                    error.getField(),
                    String.valueOf(error.getDefaultMessage()),
                    (a, b) -> a + "; " + b);
        }
        ex.getBindingResult()
                .getGlobalErrors()
                .forEach(error -> fields.put(error.getObjectName(), error.getDefaultMessage()));
        return build(
                HttpStatus.BAD_REQUEST,
                "Validation failed",
                "One or more fields are invalid",
                req,
                fields);
    }

    /** Bean validation failures on path variables, request params and service arguments. */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> handleConstraintViolation(
            ConstraintViolationException ex, HttpServletRequest req) {
        Map<String, String> fields = new TreeMap<>();
        for (ConstraintViolation<?> violation : ex.getConstraintViolations()) {
            fields.put(String.valueOf(violation.getPropertyPath()), violation.getMessage());
        }
        return build(
                HttpStatus.BAD_REQUEST,
                "Validation failed",
                "One or more parameters are invalid",
                req,
                fields);
    }

    @ExceptionHandler({
        MethodArgumentTypeMismatchException.class,
        MissingServletRequestParameterException.class,
        IllegalArgumentException.class
    })
    public ResponseEntity<ApiError> handleBadRequest(Exception ex, HttpServletRequest req) {
        return build(HttpStatus.BAD_REQUEST, "Bad request", ex.getMessage(), req, null);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleIntegrity(
            DataIntegrityViolationException ex, HttpServletRequest req) {
        log.warn("Data integrity violation on {}: {}", req.getRequestURI(), ex.getMessage());
        return build(
                HttpStatus.CONFLICT,
                "Conflicting record",
                "The record conflicts with data already held by the platform",
                req,
                null);
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ApiError> handleOptimisticLock(
            OptimisticLockingFailureException ex, HttpServletRequest req) {
        return build(
                HttpStatus.CONFLICT,
                "Concurrent modification",
                "Another user changed this record while you were editing it; reload and retry",
                req,
                null);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(
            AccessDeniedException ex, HttpServletRequest req) {
        return build(
                HttpStatus.FORBIDDEN,
                "Access denied",
                "Your role is not permitted to perform this operation",
                req,
                null);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiError> handleAuthentication(
            AuthenticationException ex, HttpServletRequest req) {
        return build(
                HttpStatus.UNAUTHORIZED,
                "Authentication required",
                "Valid credentials are required to access this resource",
                req,
                null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest req) {
        log.error("Unhandled exception on {} {}", req.getMethod(), req.getRequestURI(), ex);
        return build(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Internal server error",
                "The request could not be completed; the incident has been logged",
                req,
                null);
    }

    private ResponseEntity<ApiError> build(
            HttpStatus status,
            String error,
            String detail,
            HttpServletRequest req,
            Map<String, String> fieldErrors) {
        ApiError body =
                new ApiError(
                        Instant.now(),
                        status.value(),
                        error,
                        detail,
                        req.getRequestURI(),
                        fieldErrors == null || fieldErrors.isEmpty()
                                ? null
                                : new LinkedHashMap<>(fieldErrors));
        return ResponseEntity.status(status).body(body);
    }
}
