package com.example.linkedinagent.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("local")
public class VerifyEnvRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(VerifyEnvRunner.class);
    private final AppProperties appProperties;

    public VerifyEnvRunner(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    @Override
    public void run(String... args) throws Exception {
        String clientId = appProperties.getLinkedin().getClientId();
        
        log.info("=========================================================");
        log.info("Verificação de Configuração Local:");
        if (clientId != null && !clientId.isEmpty() && !clientId.equals("${LINKEDIN_CLIENT_ID:}")) {
            String masked = clientId.length() > 4 
                ? clientId.substring(0, 4) + "****" 
                : "****";
            log.info("✅ O .env foi carregado com sucesso!");
            log.info("✅ LINKEDIN_CLIENT_ID mascarado: {}", masked);
        } else {
            log.warn("❌ Variável LINKEDIN_CLIENT_ID não foi carregada do .env.");
            log.warn("Certifique-se de carregar o .env no seu IDE ou via plugin.");
        }
        log.info("=========================================================");
    }
}
