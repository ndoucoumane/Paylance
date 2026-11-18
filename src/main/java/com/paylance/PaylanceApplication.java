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

    static {
        // Fuseau JVM fixé à UTC dès le chargement de la classe, donc aussi pour les tests et tout
        // démarrage qui ne passe pas par main() : les entités persistent des LocalDateTime
        // (LocalDateTime.now(), @CreationTimestamp) qui sont relus comme de l'UTC (filtres
        // from/to, conversion en Instant pour l'API). Sans cela, tout dériverait selon l'hôte.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }

    public static void main(String[] args) {
        SpringApplication.run(PaylanceApplication.class, args);
    }
}
