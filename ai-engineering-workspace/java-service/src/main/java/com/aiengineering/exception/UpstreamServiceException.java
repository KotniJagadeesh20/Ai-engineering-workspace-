package com.aiengineering.exception;

/** The Python service failed or timed out. Surfaces as 502. */
public class UpstreamServiceException extends RuntimeException {
    public UpstreamServiceException(String message, Throwable cause) {
        super(message, cause);
    }
}
