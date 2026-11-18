package com.paylance.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paylance.domain.enums.MemberRole;
import com.paylance.domain.enums.OrganizationStatus;
import com.paylance.repository.OrganizationRepository;
import com.paylance.service.OrganizationService;
import com.paylance.tenant.TenantContext;
import com.paylance.web.dto.ErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Dashboard ({@code /api/v1/dashboard/**}) : l'utilisateur JWT choisit son organisation via
 * {@code X-Organization-Id}. On vérifie qu'il en est membre (ou super admin), on ajoute son rôle
 * aux autorités ({@code ROLE_ORG_OWNER}...), puis l'organisation devient le tenant de la requête.
 */
@Slf4j
@RequiredArgsConstructor
public class OrganizationContextFilter extends OncePerRequestFilter {

    private final OrganizationService organizationService;
    private final OrganizationRepository organizationRepository;
    private final ObjectMapper objectMapper;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return "OPTIONS".equalsIgnoreCase(request.getMethod())
                || !request.getRequestURI().startsWith("/api/v1/dashboard");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (!(SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken jwt)) {
            filterChain.doFilter(request, response); // l'autorisation renverra 401
            return;
        }
        String organizationId = request.getHeader(OrganizationAccess.HEADER);
        if (organizationId == null || organizationId.isBlank()) {
            write(request, response, HttpStatus.BAD_REQUEST, "En-tête " + OrganizationAccess.HEADER + " requis");
            return;
        }
        boolean organizationActive = organizationRepository.findById(organizationId)
                .map(o -> o.getStatus() == OrganizationStatus.ACTIVE)
                .orElse(false);
        boolean superAdmin = jwt.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_" + OrganizationAccess.SUPER_ADMIN));
        Optional<MemberRole> role = organizationActive
                ? organizationService.roleOf(organizationId, jwt.getToken().getSubject())
                : Optional.empty();
        if (role.isEmpty() && superAdmin && organizationRepository.existsById(organizationId)) {
            role = Optional.of(MemberRole.OWNER); // support : accès complet, tracé
            log.warn("[SUPER_ADMIN_ACCESS] {} accède à l'organisation {}", jwt.getToken().getSubject(), organizationId);
        }
        if (role.isEmpty()) {
            write(request, response, HttpStatus.FORBIDDEN, "Accès refusé à cette organisation");
            return;
        }

        List<GrantedAuthority> authorities = new ArrayList<>(jwt.getAuthorities());
        authorities.add(new SimpleGrantedAuthority(role.get().authority()));
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt.getToken(), authorities, jwt.getName()));
        TenantContext.set(organizationId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }

    private void write(HttpServletRequest request, HttpServletResponse response, HttpStatus status, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), new ErrorResponse(LocalDateTime.now(), status.value(),
                status.getReasonPhrase(), message, request.getRequestURI(), null, null));
    }
}
