package com.qingruan.common.config;

import com.qingruan.exception.ServiceConfigConflictException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/** Maps duplicate monitoring-service configurations to an explicit conflict response. */
@RestControllerAdvice
public class ServiceConfigExceptionHandler {

    @ExceptionHandler(ServiceConfigConflictException.class)
    public ResponseEntity<Map<String, Object>> handleConflict(ServiceConfigConflictException exception) {
        return ResponseEntity.status(409)
                .body(Map.of("code", 409, "message", exception.getMessage()));
    }
}
