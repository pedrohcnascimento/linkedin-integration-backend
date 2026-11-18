package com.example.linkedinagent.security;

public interface TokenCipher {

    void validateConfiguration();

    String encrypt(String plaintext);

    default String encrypt(String plaintext, String associatedData) {
        return encrypt(plaintext);
    }

    String decrypt(String encryptedValue);

    default String decrypt(String encryptedValue, String associatedData) {
        return decrypt(encryptedValue);
    }
}
