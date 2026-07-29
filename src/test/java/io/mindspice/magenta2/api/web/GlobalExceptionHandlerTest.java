package io.mindspice.magenta2.api.web;

import java.util.Map;

import io.mindspice.magenta2.ai.orchestration.context.AgentContextService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.server.ResponseStatusException;

import jakarta.validation.ConstraintViolationException;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
    }

    @Test
    void methodArgumentNotValidReturns400WithFieldErrors() throws Exception {
        java.lang.reflect.Method method = String.class.getMethod("valueOf", Object.class);
        MethodParameter methodParameter = new MethodParameter(method, 0);
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "target");
        bindingResult.addError(new FieldError("target", "message", "must not be blank"));
        MethodArgumentNotValidException exception =
            new MethodArgumentNotValidException(methodParameter, bindingResult);

        @SuppressWarnings("unchecked")
        ResponseEntity<Map<String, Object>> response =
                handler.handleValidationExceptions(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("error")).isEqualTo("validation failed");
        assertThat(response.getBody().get("fieldErrors")).isInstanceOf(Iterable.class);
    }

    @Test
    void constraintViolationReturns400WithViolations() {
        ConstraintViolationException exception =
            new ConstraintViolationException("validation failed", java.util.Collections.emptySet());

        @SuppressWarnings("unchecked")
        ResponseEntity<Map<String, Object>> response =
                handler.handleConstraintViolation(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("error")).isEqualTo("validation failed");
    }

    @Test
    void bindExceptionReturns400WithFieldErrors() {
        BindException exception = new BindException(new Object(), "target");
        exception.addError(new FieldError("target", "name", "must not be null"));

        @SuppressWarnings("unchecked")
        ResponseEntity<Map<String, Object>> response =
                handler.handleBindException(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("error")).isEqualTo("validation failed");
    }

    @Test
    void httpMessageNotReadableReturns400WithMessage() {
        HttpMessageNotReadableException exception =
            new HttpMessageNotReadableException(
                "Required request body is missing",
                new RuntimeException("malformed JSON"),
                new org.springframework.mock.http.MockHttpInputMessage(new byte[0])
            );

        @SuppressWarnings("unchecked")
        ResponseEntity<Map<String, Object>> response =
                handler.handleHttpMessageNotReadable(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat((String) response.getBody().get("error")).contains("malformed request body");
    }

    @Test
    void illegalArgumentReturns400() {
        IllegalArgumentException exception = new IllegalArgumentException("invalid argument value");

        @SuppressWarnings("unchecked")
        ResponseEntity<Map<String, Object>> response =
                handler.handleIllegalArgument(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("error")).isEqualTo("invalid argument value");
    }

    @Test
    void operationConflictReturns409() {
        AgentContextService.OperationConflictException exception =
                new AgentContextService.OperationConflictException("version conflict");

        @SuppressWarnings("unchecked")
        ResponseEntity<Map<String, Object>> response =
                handler.handleOperationConflict(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("error")).isEqualTo("version conflict");
    }

    @Test
    void genericExceptionReturns500WithSafeMessage() {
        IllegalStateException exception = new IllegalStateException("some internal detail");

        @SuppressWarnings("unchecked")
        ResponseEntity<Map<String, Object>> response =
                handler.handleGeneric(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("error")).isEqualTo("Internal server error");
    }

    @Test
    void responseStatusExceptionPassesThroughStatusCode() {
        ResponseStatusException exception = new ResponseStatusException(HttpStatus.NOT_FOUND, "not found");

        @SuppressWarnings("unchecked")
        ResponseEntity<Map<String, Object>> response =
                handler.handleResponseStatus(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("error")).isEqualTo("not found");
    }

    @Test
    void nullReasonResponseStatusExceptionReturnsSafeFallback() {
        ResponseStatusException exception = new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);

        @SuppressWarnings("unchecked")
        ResponseEntity<Map<String, Object>> response =
                handler.handleResponseStatus(exception);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("error")).isEqualTo("Unexpected error");
    }
}
