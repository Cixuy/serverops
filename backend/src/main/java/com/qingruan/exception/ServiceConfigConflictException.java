package com.qingruan.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/** Raised when a server would receive a duplicate service configuration. */
@ResponseStatus(HttpStatus.CONFLICT)
public class ServiceConfigConflictException extends RuntimeException {
    public ServiceConfigConflictException(String message) {
        super(message);
    }
}
