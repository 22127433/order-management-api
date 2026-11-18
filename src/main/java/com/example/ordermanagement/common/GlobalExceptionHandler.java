package com.example.ordermanagement.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<Object> handleAuthentication(AuthenticationException exception, HttpServletRequest request) {
        if (exception instanceof AuthenticationServiceException) {
            LOG.error("Authentication backend failure for {}", request.getRequestURI(), exception);
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred", request.getRequestURI(), List.of());
        }
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).header("WWW-Authenticate", "Bearer")
                .header("Cache-Control", "no-store").body(body(HttpStatus.UNAUTHORIZED,
                        exception instanceof BadCredentialsException ? "Invalid email or password" : "Authentication is required",
                        request.getRequestURI(), List.of()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<Object> handleAccessDenied(AccessDeniedException exception, HttpServletRequest request) {
        return error(HttpStatus.FORBIDDEN, "Access denied", request.getRequestURI(), List.of());
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    ResponseEntity<Object> handleNotFound(ResourceNotFoundException exception, HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, exception.getMessage(), request.getRequestURI(), List.of());
    }

    @ExceptionHandler(ConflictException.class)
    ResponseEntity<Object> handleConflict(ConflictException exception, HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, exception.getMessage(), request.getRequestURI(), List.of());
    }

    @ExceptionHandler(BadRequestException.class)
    ResponseEntity<Object> handleBadRequest(BadRequestException exception, HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, exception.getMessage(), request.getRequestURI(), List.of());
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<Object> handleConstraintViolation(ConstraintViolationException exception,
                                                      HttpServletRequest request) {
        List<ApiError.FieldError> fields = exception.getConstraintViolations().stream()
                .map(v -> new ApiError.FieldError(v.getPropertyPath().toString(), v.getMessage()))
                .toList();
        return error(HttpStatus.BAD_REQUEST, "Request validation failed", request.getRequestURI(), fields);
    }

    @ExceptionHandler({PessimisticLockingFailureException.class, OptimisticLockingFailureException.class})
    ResponseEntity<Object> handleLockConflict(Exception exception, HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, "Resource is busy; retry the request",
                request.getRequestURI(), List.of());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<Object> handleIntegrityConflict(DataIntegrityViolationException exception,
                                                   HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, "Operation conflicts with existing data",
                request.getRequestURI(), List.of());
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<ApiError.FieldError> fields = exception.getBindingResult().getFieldErrors().stream()
                .map(f -> new ApiError.FieldError(f.getField(), f.getDefaultMessage())).toList();
        return createResponseEntity(body(status, "Request validation failed", path(request), fields),
                headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception exception, Object body,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String message = status.is5xxServerError() ? "An unexpected error occurred"
                : body instanceof ProblemDetail problem && problem.getDetail() != null
                ? problem.getDetail() : "Invalid HTTP request";
        return createResponseEntity(body(status, message, path(request), List.of()), headers, status, request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> handleUnexpected(Exception exception, HttpServletRequest request) {
        LOG.error("Unexpected failure for {} {}", request.getMethod(), request.getRequestURI(), exception);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred",
                request.getRequestURI(), List.of());
    }

    private ResponseEntity<Object> error(HttpStatus status, String message, String path,
                                          List<ApiError.FieldError> fields) {
        return ResponseEntity.status(status).body(body(status, message, path, fields));
    }

    private ApiError body(HttpStatusCode status, String message, String path,
                           List<ApiError.FieldError> fields) {
        HttpStatus resolved = HttpStatus.resolve(status.value());
        return new ApiError(Instant.now(), status.value(),
                resolved == null ? "HTTP error" : resolved.getReasonPhrase(), message, path, fields);
    }

    private String path(WebRequest request) {
        return ((ServletWebRequest) request).getRequest().getRequestURI();
    }
}
