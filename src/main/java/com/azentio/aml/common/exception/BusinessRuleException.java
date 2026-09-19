package com.azentio.aml.common.exception;

/**
 * Raised when a request is well-formed but violates a compliance workflow rule, such as closing an
 * alert without a disposition. Surfaces as HTTP 409.
 */
public class BusinessRuleException extends RuntimeException {

    public BusinessRuleException(String message) {
        super(message);
    }
}
