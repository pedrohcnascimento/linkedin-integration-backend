package com.example.linkedinagent.application.auth;

public interface UserRegistrationPort {

    boolean existsByEmail(String email);

    RegisteredUser save(String email, String displayName, String passwordHash);
}
