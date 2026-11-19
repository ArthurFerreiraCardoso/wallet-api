package com.example.wallet_api.api.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Autentica a requisicao pelo header X-API-Key. Chave ausente ou errada nao interrompe aqui: a requisicao segue
 * anonima e a cadeia de seguranca responde 401 nas rotas protegidas (ver {@link ProblemAuthenticationEntryPoint}).
 *
 * <p>A comparacao e feita sobre o SHA-256 das duas chaves com {@link MessageDigest#isEqual}, que leva o mesmo tempo
 * qualquer que seja o conteudo; o hash antes deixa os dois lados do mesmo tamanho. A chave nunca e logada.
 */
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-API-Key";

    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthenticationFilter.class);

    private final byte[] expectedDigest;

    public ApiKeyAuthenticationFilter(String expectedApiKey) {
        this.expectedDigest = sha256(expectedApiKey);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String provided = request.getHeader(HEADER);
        if (provided != null) {
            if (MessageDigest.isEqual(expectedDigest, sha256(provided))) {
                var authentication = new PreAuthenticatedAuthenticationToken(
                        "api-client", null, List.of(new SimpleGrantedAuthority("ROLE_API_CLIENT")));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } else {
                // DEBUG e nao WARN: um cliente anonimo poderia encher o log repetindo chaves erradas.
                log.debug("Requisicao com X-API-Key invalida em {} {}", request.getMethod(), request.getRequestURI());
            }
        }
        chain.doFilter(request, response);
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponivel na JVM", e);
        }
    }
}
