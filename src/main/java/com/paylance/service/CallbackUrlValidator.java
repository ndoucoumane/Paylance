package com.paylance.service;

import com.paylance.domain.enums.EnvironmentType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

/**
 * Protège les callbacks sortants contre le SSRF : l'URL est fournie par un client, Paylance
 * l'appelle depuis son réseau. Refuse les adresses internes (loopback, privées, link-local,
 * métadonnées cloud...) après résolution DNS, et exige HTTPS en LIVE.
 *
 * <p>Vérifié à l'enregistrement de l'URL ET juste avant chaque envoi (un nom DNS peut changer de
 * cible entre les deux). {@code paylance.callbacks.allow-private-networks=true} n'est destiné
 * qu'au développement local.
 */
@Component
public class CallbackUrlValidator {

    private final boolean allowPrivateNetworks;

    public CallbackUrlValidator(@Value("${paylance.callbacks.allow-private-networks:false}") boolean allowPrivateNetworks) {
        this.allowPrivateNetworks = allowPrivateNetworks;
    }

    public void validate(String url, EnvironmentType environment) {
        URI uri = requireHttpUrl(url, "callbackUrl");
        if (uri == null) {
            return;
        }
        if (environment == EnvironmentType.LIVE && !"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("callbackUrl doit être en HTTPS pour une application LIVE");
        }
        if (allowPrivateNetworks) {
            return;
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(uri.getHost());
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("callbackUrl : hôte introuvable (" + uri.getHost() + ")");
        }
        for (InetAddress address : addresses) {
            if (isInternal(address)) {
                throw new IllegalArgumentException("callbackUrl ne peut pas viser une adresse interne");
            }
        }
    }

    /** @return l'URI analysée, ou {@code null} si la valeur est vide. */
    public static URI requireHttpUrl(String url, String field) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(url.trim());
            if (uri.getHost() == null || !("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))) {
                throw new IllegalArgumentException(field + " doit être une URL http(s) absolue");
            }
            if (uri.getUserInfo() != null) {
                throw new IllegalArgumentException(field + " ne doit pas contenir d'identifiants");
            }
            return uri;
        } catch (IllegalArgumentException e) {
            if (e.getMessage() != null && e.getMessage().startsWith(field)) {
                throw e;
            }
            throw new IllegalArgumentException(field + " invalide");
        }
    }

    static boolean isInternal(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        byte[] b = address.getAddress();
        if (b.length == 4) {
            int first = b[0] & 0xFF;
            int second = b[1] & 0xFF;
            return first == 0                                  // 0.0.0.0/8
                    || (first == 100 && second >= 64 && second <= 127) // CGNAT 100.64.0.0/10
                    || (first == 198 && (second == 18 || second == 19)); // bancs de test 198.18.0.0/15
        }
        // IPv6 unique local fc00::/7
        return (b[0] & 0xFE) == 0xFC;
    }
}
