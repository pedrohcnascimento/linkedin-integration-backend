package com.example.linkedinagent.application.publishing;

import org.springframework.http.HttpStatus;

public class DraftWorkflowException extends RuntimeException {
    private final String code;
    private final HttpStatus status;

    public DraftWorkflowException(String code, String message, HttpStatus status) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public String getCode() {
        return code;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
