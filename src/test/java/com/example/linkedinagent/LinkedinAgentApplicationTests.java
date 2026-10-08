package com.example.linkedinagent;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Teste mínimo de bootstrap: garante que o contexto Spring sobe com a
 * configuração de teste, sem depender de nenhuma credencial real do LinkedIn.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LinkedinAgentApplicationTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void apiDocumentationIsAvailableWithoutAuthenticationInTestProfile() throws Exception {
        mockMvc.perform(get("/api-docs"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/swagger-ui.html"))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk());
    }
}
