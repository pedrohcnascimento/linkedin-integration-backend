package com.example.linkedinagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Ponto de entrada da aplicação.
 *
 * <p>Esta classe deve permanecer mínima: nenhuma lógica de negócio, de integração
 * com o LinkedIn ou de persistência deve residir aqui. Ver {@code docs/planejamento-tecnico.md},
 * seção 4, para os limites de cada camada da arquitetura hexagonal.</p>
 */
@SpringBootApplication
public class LinkedinAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(LinkedinAgentApplication.class, args);
    }
}
