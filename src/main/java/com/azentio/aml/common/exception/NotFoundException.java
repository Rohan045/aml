package com.azentio.aml.common.exception;

/** Raised when a referenced business record does not exist; surfaces as HTTP 404. */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }

    public static NotFoundException of(String entity, Object id) {
        return new NotFoundException(entity + " '" + id + "' was not found");
    }
}
