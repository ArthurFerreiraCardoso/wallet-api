package com.example.wallet_api.api.security;

import java.io.IOException;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

import com.example.wallet_api.api.web.ProblemResponses;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

/**
 * 401 e 403 no mesmo formato dos demais erros (ProblemDetail com code e traceId), escritos direto na resposta:
 * esses erros nascem na cadeia de seguranca, antes do @RestControllerAdvice. A mensagem nao diz se a chave
 * estava ausente ou errada.
 */
public class ProblemAuthenticationEntryPoint implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final JsonMapper jsonMapper;

    public ProblemAuthenticationEntryPoint(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException authException)
            throws IOException {
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "ApiKey header=\"" + ApiKeyAuthenticationFilter.HEADER + "\"");
        ProblemResponses.write(request, response, jsonMapper, HttpStatus.UNAUTHORIZED, "Nao autenticado",
                "Envie uma API key valida no header " + ApiKeyAuthenticationFilter.HEADER, "UNAUTHORIZED");
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException accessDeniedException)
            throws IOException {
        ProblemResponses.write(request, response, jsonMapper, HttpStatus.FORBIDDEN, "Acesso negado",
                "Acesso negado", "FORBIDDEN");
    }
}
