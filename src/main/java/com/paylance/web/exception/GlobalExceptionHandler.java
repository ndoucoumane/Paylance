package com.paylance.web.exception;

import com.paylance.common.CorrelationContext;
import com.paylance.tenant.TenantAccessViolationException;
import com.paylance.web.dto.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.List;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(PaymentNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(PaymentNotFoundException e, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, e.getMessage(), request, null);
    }

    @ExceptionHandler({ResourceNotFoundException.class, TenantAccessViolationException.class})
    public ResponseEntity<ErrorResponse> handleResourceNotFound(RuntimeException e, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, e instanceof TenantAccessViolationException ? "Ressource introuvable" : e.getMessage(), request, null);
    }

    @ExceptionHandler(ScheduledPaymentNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleScheduledPaymentNotFound(ScheduledPaymentNotFoundException e, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, e.getMessage(), request, null);
    }

    @ExceptionHandler(ProviderAccountNotConfiguredException.class)
    public ResponseEntity<ErrorResponse> handleProviderAccountNotConfigured(ProviderAccountNotConfiguredException e, HttpServletRequest request) {
        return build(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage(), request, null);
    }

    @ExceptionHandler(DuplicatePaymentRequestException.class)
    public ResponseEntity<ErrorResponse> handleDuplicate(DuplicatePaymentRequestException e, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, e.getMessage(), request, null);
    }

    @ExceptionHandler(InvalidWebhookSignatureException.class)
    public ResponseEntity<ErrorResponse> handleInvalidSignature(InvalidWebhookSignatureException e, HttpServletRequest request) {
        return build(HttpStatus.UNAUTHORIZED, e.getMessage(), request, null);
    }

    @ExceptionHandler(ProviderCommunicationException.class)
    public ResponseEntity<ErrorResponse> handleProviderError(ProviderCommunicationException e, HttpServletRequest request) {
        // Pas de stack trace ici : le client HTTP provider (WaveClient/OrangeMoneyClient) a déjà
        // loggé le détail utile (statut HTTP, corps de réponse) au moment de l'erreur.
        log.error("Erreur provider : {}", e.getMessage());
        return build(HttpStatus.BAD_GATEWAY, ErrorMessages.PROVIDER_UNAVAILABLE, request, null);
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ErrorResponse> handleOptimisticLock(ObjectOptimisticLockingFailureException e, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, "Le paiement a été modifié concurremment, veuillez réessayer", request, null);
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ErrorResponse> handleConflict(ConflictException e, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, e.getMessage(), request, null);
    }

    @ExceptionHandler(BusinessRuleException.class)
    public ResponseEntity<ErrorResponse> handleBusinessRule(BusinessRuleException e, HttpServletRequest request) {
        return build(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage(), request, null);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponse> handleIllegalState(IllegalStateException e, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, e.getMessage(), request, null);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException e, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, e.getMessage(), request, null);
    }

    @ExceptionHandler(org.springframework.web.bind.MissingRequestHeaderException.class)
    public ResponseEntity<ErrorResponse> handleMissingHeader(org.springframework.web.bind.MissingRequestHeaderException e, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "En-tête " + e.getHeaderName() + " requis.", request, null);
    }

    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(org.springframework.http.converter.HttpMessageNotReadableException e, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, ErrorMessages.MALFORMED_REQUEST, request, null);
    }

    /**
     * Validation des corps de requête : {@code message} explique au commerçant quoi corriger
     * (messages des DTO, ou message générique en français par type de contrainte) ;
     * {@code details} garde le détail par champ.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e, HttpServletRequest request) {
        List<String> messages = e.getBindingResult().getFieldErrors().stream()
                .map(ValidationMessages::of)
                .distinct()
                .toList();
        List<String> details = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + " : " + ValidationMessages.of(fe))
                .toList();
        String message = messages.isEmpty() ? ErrorMessages.MALFORMED_REQUEST : String.join(" ", messages);
        return build(HttpStatus.BAD_REQUEST, message, request, details);
    }

    /** Paramètre d'URL mal typé (date, statut, prestataire inconnus...). */
    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException e, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "Le paramètre « " + e.getName() + " » est invalide.", request, null);
    }

    /** Tri sur un champ non autorisé ou inexistant. */
    @ExceptionHandler(org.springframework.data.mapping.PropertyReferenceException.class)
    public ResponseEntity<ErrorResponse> handleBadSort(
            org.springframework.data.mapping.PropertyReferenceException e, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "Ce critère de tri n'est pas disponible.", request, null);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException e, HttpServletRequest request) {
        return build(HttpStatus.FORBIDDEN, ErrorMessages.FORBIDDEN, request, null);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotSupported(HttpRequestMethodNotSupportedException e, HttpServletRequest request) {
        // Ex: un simple GET de vérification (dashboard provider, uptime check...) sur un
        // endpoint POST-only — pas une erreur serveur, ne mérite ni 500 ni stack trace ERROR.
        return build(HttpStatus.METHOD_NOT_ALLOWED, "Méthode HTTP non prise en charge pour cette route.", request, null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(Exception e, HttpServletRequest request) {
        log.error("Erreur inattendue sur {}", request.getRequestURI(), e);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, ErrorMessages.INTERNAL, request, null);
    }

    private ResponseEntity<ErrorResponse> build(HttpStatus status, String message, HttpServletRequest request, List<String> details) {
        ErrorResponse body = new ErrorResponse(
                Instant.now(), status.value(), status.getReasonPhrase(), message,
                request.getRequestURI(), CorrelationContext.current(), details);
        return ResponseEntity.status(status).body(body);
    }
}
