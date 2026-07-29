package io.mindspice.magenta2.api.web;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.mindspice.magenta2.ai.chat.service.AuditService;
import io.mindspice.magenta2.ai.orchestration.context.AgentContextService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

import jakarta.validation.ConstraintViolationException;

@ControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final Pattern CONVERSATION_ID_PATTERN = Pattern.compile(
        "/chat/(stream|send|plan)/([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})"
    );

    private final AuditService auditService;

    public GlobalExceptionHandler() {
        this(null);
    }

    @Autowired
    public GlobalExceptionHandler(@Autowired(required = false) AuditService auditService) {
        this.auditService = auditService;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidationExceptions(MethodArgumentNotValidException exception) {
        List<Map<String, String>> fieldErrors = exception.getBindingResult().getFieldErrors().stream()
            .map(this::fieldErrorEntry)
            .toList();
        log.warn("HTTP validation error");
        recordIfConversation("http_validation", "validation failed");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(Map.of("error", "validation failed", "fieldErrors", fieldErrors));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Map<String, Object>> handleConstraintViolation(ConstraintViolationException exception) {
        List<Map<String, String>> violations = exception.getConstraintViolations().stream()
            .map(violation -> Map.of(
                "field", violation.getPropertyPath().toString(),
                "message", "invalid value"
            ))
            .toList();
        log.warn("HTTP constraint violation");
        recordIfConversation("http_validation", "validation failed");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(Map.of("error", "validation failed", "fieldErrors", violations));
    }

    @ExceptionHandler(BindException.class)
    public ResponseEntity<Map<String, Object>> handleBindException(BindException exception) {
        List<Map<String, String>> fieldErrors = exception.getBindingResult().getFieldErrors().stream()
            .map(this::fieldErrorEntry)
            .toList();
        log.warn("HTTP bind error");
        recordIfConversation("http_validation", "validation failed");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(Map.of("error", "validation failed", "fieldErrors", fieldErrors));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleHttpMessageNotReadable(HttpMessageNotReadableException exception) {
        log.warn("Malformed HTTP request body");
        recordIfConversation("http_validation", "malformed request body");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(Map.of("error", "malformed request body"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException exception) {
        log.warn("Invalid HTTP request");
        recordIfConversation("http_validation", "invalid request");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(Map.of("error", "invalid request"));
    }

    @ExceptionHandler(AgentContextService.OperationConflictException.class)
    public ResponseEntity<Map<String, Object>> handleOperationConflict(
            AgentContextService.OperationConflictException exception) {
        log.warn("HTTP operation conflict");
        recordIfConversation("http_conflict", "operation conflict");
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(Map.of("error", "operation conflicts with current state"));
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Map<String, Object>> handleDataAccess(DataAccessException exception) {
        log.warn("HTTP infrastructure failure type={}", exception.getClass().getSimpleName());
        recordIfConversation("http_unavailable", "service temporarily unavailable");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(Map.of("error", "service temporarily unavailable"));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleResponseStatus(ResponseStatusException exception) {
        log.warn("HTTP response status={}", exception.getStatusCode());
        String message = safeStatusMessage(exception.getStatusCode());
        recordIfConversation("http_response_status", message);
        return ResponseEntity.status(exception.getStatusCode())
            .body(Map.of("error", message));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneric(Exception exception) {
        log.error("Unhandled HTTP exception type={}", exception.getClass().getSimpleName());
        recordIfConversation("http_internal_error", "internal server error");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(Map.of("error", "Internal server error"));
    }

    private void recordIfConversation(String errorType, String errorMessage) {
        if (auditService == null) return;
        String conversationId = conversationIdFromRequest();
        if (conversationId != null) {
            auditService.recordError(conversationId, errorType, errorMessage, null, null);
        }
    }

    private static String safeStatusMessage(HttpStatusCode status) {
        if (status.isSameCodeAs(HttpStatus.BAD_REQUEST)) return "invalid request";
        if (status.isSameCodeAs(HttpStatus.NOT_FOUND)) return "resource not found";
        if (status.isSameCodeAs(HttpStatus.CONFLICT)) return "request conflicts with current state";
        if (status.isSameCodeAs(HttpStatus.SERVICE_UNAVAILABLE)) return "service temporarily unavailable";
        return "request could not be completed";
    }

    private String conversationIdFromRequest() {
        try {
            var attrs = RequestContextHolder.getRequestAttributes();
            if (attrs instanceof ServletRequestAttributes servletAttrs) {
                String path = servletAttrs.getRequest().getRequestURI();
                Matcher matcher = CONVERSATION_ID_PATTERN.matcher(path);
                if (matcher.find()) {
                    String candidate = matcher.group(2);
                    UUID.fromString(candidate);
                    return candidate;
                }
            }
        } catch (Exception ignored) {
            log.debug("Failed to extract conversation ID from request");
        }
        return null;
    }

    private Map<String, String> fieldErrorEntry(FieldError fieldError) {
        return Map.of(
            "field", fieldError.getField(),
            "message", "invalid value"
        );
    }
}
