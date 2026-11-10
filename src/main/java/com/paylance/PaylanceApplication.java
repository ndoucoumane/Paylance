package com.paylance;

import java.util.TimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

// UserDetailsServiceAutoConfiguration exclu : Paylance n'utilise ni httpBasic() ni formLogin(),
// l'authentification passe par ApiKeyAuthFilter (applications), AdminApiKeyFilter (actuator) et
// le JWT Keycloak (dashboard admin). Sans cette exclusion, Spring Boot génère un utilisateur
// "user" + mot de passe aléatoire au démarrage (loggé en clair) qui n'est câblé nulle part —
// du bruit trompeur, pas un mécanisme réel.
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@ConfigurationPropertiesScan
public class PaylanceApplication {

    public static void main(String[] args) {
        // Fixe le fuseau JVM en UTC, quel que soit l'hôte/conteneur (jamais configuré
        // explicitement en déploiement) : les entités persistent des LocalDateTime
        // (Payment.expiresAt/createdAt...) via LocalDateTime.now()/@CreationTimestamp, et
        // PaymentService les convertit en Instant pour l'API — les deux doivent s'accorder
        // sur le même fuseau, sinon ils dérivent l'un de l'autre selon l'hôte.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        SpringApplication.run(PaylanceApplication.class, args);
    }
}
