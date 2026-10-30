package com.example.linkedinagent.security;

public interface TokenCipher {

    void validateConfiguration();

    String encrypt(String plaintext);

    String decrypt(String encryptedValue);
}
