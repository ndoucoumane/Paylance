package com.paylance.web.exception;

/** Ressource absente du tenant courant (404) — y compris quand elle existe chez un autre tenant. */
public class ResourceNotFoundException extends RuntimeException {
    public ResourceNotFoundException(String resource, String id) {
        super(resource + " introuvable : " + id);
    }
}
