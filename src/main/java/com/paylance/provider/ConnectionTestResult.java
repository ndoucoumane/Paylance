package com.paylance.provider;

/** Résultat d'un appel en lecture seule vérifiant que les identifiants d'un compte sont acceptés. */
public record ConnectionTestResult(boolean success, String message) {
}
