package com.example.linkedinagent.application.ports.out;

public interface TokenCipherPort {

    void validateConfiguration();

    String encrypt(String plaintext);

    String decrypt(String encryptedValue);
}
