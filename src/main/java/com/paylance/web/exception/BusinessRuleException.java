package com.paylance.web.exception;

/**
 * 422 : la requête est bien formée mais enfreint une règle métier (montant hors bornes, règle
 * d'un prestataire...). Le message est affiché tel quel par le front : il doit être en français
 * et ne contenir aucun détail interne.
 */
public class BusinessRuleException extends RuntimeException {
    public BusinessRuleException(String message) {
        super(message);
    }
}
