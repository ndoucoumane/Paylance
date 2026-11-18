package com.paylance.service;

import com.paylance.common.LuhnUtil;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Génère une référence de transaction lisible au format {@code PL-YYMMDD-SSSSSS-C}
 * (C = chiffre de contrôle Luhn), à partir de la séquence Postgres {@code paylance_txn_seq}
 * — atomique nativement (pas de verrou applicatif, pas de race condition entre créations
 * concurrentes). La séquence est continue (jamais remise à zéro) pour éviter tout risque
 * de collision autour d'un changement de jour.
 */
@Service
public class PaymentNumberGenerator {

    private static final DateTimeFormatter DATE_PATTERN = DateTimeFormatter.ofPattern("yyMMdd");

    @PersistenceContext
    private EntityManager entityManager;

    // REQUIRES_NEW : la valeur de séquence consommée ne doit pas dépendre du sort de la
    // transaction appelante — un nextval() n'est de toute façon jamais rendu au rollback.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String generate() {
        Number sequenceValue = (Number) entityManager
                .createNativeQuery("SELECT nextval('paylance_txn_seq')")
                .getSingleResult();

        String datePart = LocalDate.now().format(DATE_PATTERN);
        String sequencePart = String.format("%06d", sequenceValue.longValue());
        int checkDigit = LuhnUtil.computeCheckDigit(datePart + sequencePart);

        return "PL-" + datePart + "-" + sequencePart + "-" + checkDigit;
    }
}
