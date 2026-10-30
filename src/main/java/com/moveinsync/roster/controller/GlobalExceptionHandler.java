package com.moveinsync.roster.controller;

import com.moveinsync.roster.domain.dto.ErrorResponseDto;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponseDto> handleDataIntegrityViolation(org.springframework.dao.DataIntegrityViolationException ex, HttpServletRequest request) {
        log.warn("Data integrity violation: URI={}", request.getRequestURI());

        ErrorResponseDto error = ErrorResponseDto.builder()
                .type("https://moveinsync.com/errors/conflict")
                .title("Conflict")
                .status(HttpStatus.CONFLICT.value())
                .detail("Data integrity or unique constraint violation")
                .instance(request.getRequestURI())
                .timestamp(Instant.now())
                .build();

        return ResponseEntity.status(HttpStatus.CONFLICT).body(error);
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ErrorResponseDto> handleDatabaseUnavailable(DataAccessException ex, HttpServletRequest request) {
        log.error("Database unavailable during request: URI={}", request.getRequestURI());

        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.RETRY_AFTER, "30");

        ErrorResponseDto error = ErrorResponseDto.builder()
                .type("https://moveinsync.com/errors/service-unavailable")
                .title("Service Unavailable")
                .status(HttpStatus.SERVICE_UNAVAILABLE.value())
                .detail("Database is temporarily unreachable. Please retry.")
                .instance(request.getRequestURI())
                .timestamp(Instant.now())
                .build();

        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .headers(headers)
                .body(error);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ErrorResponseDto> handleResponseStatusException(ResponseStatusException ex, HttpServletRequest request) {
        log.warn("Response status exception: status={}, reason={}", ex.getStatusCode(), ex.getReason());

        ErrorResponseDto error = ErrorResponseDto.builder()
                .type("https://moveinsync.com/errors/" + ex.getStatusCode().value())
                .title(ex.getStatusCode().toString())
                .status(ex.getStatusCode().value())
                .detail(ex.getReason())
                .instance(request.getRequestURI())
                .timestamp(Instant.now())
                .build();

        return ResponseEntity.status(ex.getStatusCode()).body(error);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponseDto> handleValidationExceptions(MethodArgumentNotValidException ex, HttpServletRequest request) {
        List<String> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .collect(Collectors.toList());

        log.warn("Validation failed: count={}", fieldErrors.size());

        ErrorResponseDto error = ErrorResponseDto.builder()
                .type("https://moveinsync.com/errors/validation-failed")
                .title("Bad Request")
                .status(HttpStatus.BAD_REQUEST.value())
                .detail("Input validation failed")
                .instance(request.getRequestURI())
                .timestamp(Instant.now())
                .errors(fieldErrors)
                .build();

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponseDto> handleMessageNotReadable(HttpMessageNotReadableException ex, HttpServletRequest request) {
        log.warn("Malformed JSON payload: URI={}", request.getRequestURI());

        ErrorResponseDto error = ErrorResponseDto.builder()
                .type("https://moveinsync.com/errors/malformed-json")
                .title("Bad Request")
                .status(HttpStatus.BAD_REQUEST.value())
                .detail("Malformed JSON request body")
                .instance(request.getRequestURI())
                .timestamp(Instant.now())
                .build();

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponseDto> handleIllegalArgument(IllegalArgumentException ex, HttpServletRequest request) {
        log.warn("Illegal argument: {}", ex.getMessage());

        ErrorResponseDto error = ErrorResponseDto.builder()
                .type("https://moveinsync.com/errors/bad-request")
                .title("Bad Request")
                .status(HttpStatus.BAD_REQUEST.value())
                .detail(ex.getMessage())
                .instance(request.getRequestURI())
                .timestamp(Instant.now())
                .build();

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponseDto> handleGenericException(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception processing request: URI={}", request.getRequestURI(), ex);

        ErrorResponseDto error = ErrorResponseDto.builder()
                .type("https://moveinsync.com/errors/internal-server-error")
                .title("Internal Server Error")
                .status(HttpStatus.INTERNAL_SERVER_ERROR.value())
                .detail("An unexpected error occurred. Please contact support.")
                .instance(request.getRequestURI())
                .timestamp(Instant.now())
                .build();

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
    }
}

