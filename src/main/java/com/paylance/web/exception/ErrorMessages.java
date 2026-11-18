package com.paylance.web.exception;

/** Messages d'erreur génériques, partagés entre les filtres de sécurité et les contrôleurs. */
public final class ErrorMessages {

    public static final String UNAUTHORIZED = "Votre session a expiré. Reconnectez-vous.";
    public static final String FORBIDDEN = "Vous n'avez pas les droits nécessaires pour cette action.";
    public static final String EMAIL_NOT_VERIFIED = "Vérifiez votre adresse e-mail pour accéder à cet espace.";
    public static final String NOT_A_MEMBER = "Vous n'avez pas accès à cette organisation.";
    public static final String PROVIDER_UNAVAILABLE =
            "Le prestataire de paiement ne répond pas pour le moment. Réessayez dans quelques instants.";
    public static final String MALFORMED_REQUEST = "La requête est mal formée. Vérifiez les valeurs envoyées.";
    public static final String INTERNAL = "Une erreur interne est survenue.";

    private ErrorMessages() {
    }
}
