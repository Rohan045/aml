package com.azentio.aml.common.exception;

/** Raised when an upload cannot be processed at all (unreadable file, missing header, oversized). */
public class IngestionException extends RuntimeException {

    public IngestionException(String message) {
        super(message);
    }

    public IngestionException(String message, Throwable cause) {
        super(message, cause);
    }
}
