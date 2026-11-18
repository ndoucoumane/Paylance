package com.paylance.web.exception;

/**
 * 409 : l'action est incompatible avec l'état actuel de la ressource (statut, opération déjà en
 * cours...). Le message est affiché tel quel par le front : il doit être en français et ne
 * contenir aucun détail interne.
 */
public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}
