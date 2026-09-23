package com.example.linkedinagent;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Teste mínimo de bootstrap: garante que o contexto Spring sobe com a
 * configuração de teste, sem depender de nenhuma credencial real do LinkedIn.
 */
@SpringBootTest
@ActiveProfiles("test")
class LinkedinAgentApplicationTests {

    @Test
    void contextLoads() {
        // Passa se o ApplicationContext carregar sem exceções.
    }
}
