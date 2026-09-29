package com.example.linkedinagent.application.auth;

public class PasswordTooLongException extends RuntimeException {
    public PasswordTooLongException() {
        super("Password exceeds the supported UTF-8 byte length.");
    }
}
