package com.paylance.web.exception;

/** Aucun compte prestataire utilisable (inexistant, désactivé ou d'un autre environnement). */
public class ProviderAccountNotConfiguredException extends RuntimeException {
    public ProviderAccountNotConfiguredException(String message) {
        super(message);
    }
}
