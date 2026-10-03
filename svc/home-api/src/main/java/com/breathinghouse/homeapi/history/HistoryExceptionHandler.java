package com.breathinghouse.homeapi.history;

import com.breathinghouse.homeapi.alerts.AlertNotFoundException;
import com.breathinghouse.homeapi.rooms.RoomNotFoundException;
import com.breathinghouse.homeapi.sensors.SensorAssignmentConflictException;
import com.breathinghouse.homeapi.sensors.SensorNotFoundException;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class HistoryExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(HistoryExceptionHandler.class);

    @ExceptionHandler({
            InvalidHistoryRequestException.class,
            ConstraintViolationException.class,
            MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class,
            IllegalArgumentException.class,
            MethodArgumentNotValidException.class,
            HttpMessageNotReadableException.class
    })
    public ResponseEntity<ApiError> badRequest(Exception ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError("bad_request", safeMessage(ex)));
    }

    @ExceptionHandler({RoomNotFoundException.class, SensorNotFoundException.class, AlertNotFoundException.class})
    public ResponseEntity<ApiError> notFound(RuntimeException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiError("not_found", ex.getMessage()));
    }

    @ExceptionHandler(SensorAssignmentConflictException.class)
    public ResponseEntity<ApiError> conflict(SensorAssignmentConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError("conflict", ex.getMessage()));
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ApiError> databaseFailure(DataAccessException ex) {
        log.error("Database failure while serving history API", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiError("database_error", "Database query failed"));
    }

    private static String safeMessage(Exception ex) {
        if (ex instanceof MethodArgumentNotValidException manve) {
            return manve.getBindingResult().getFieldErrors().stream()
                    .findFirst()
                    .map(error -> error.getField() + " " + error.getDefaultMessage())
                    .orElse("Invalid request");
        }
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            return "Invalid request";
        }
        return message;
    }
}
