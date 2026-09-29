package com.example.linkedinagent.application.ports.out;

import com.example.linkedinagent.application.auth.RegisteredUser;

public interface UserRegistrationPort {

    boolean existsByEmail(String email);

    RegisteredUser save(String email, String displayName, String passwordHash);
}
