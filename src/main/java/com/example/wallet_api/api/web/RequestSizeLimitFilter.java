package com.example.wallet_api.api.web;

import java.io.IOException;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

/**
 * Recusa corpos maiores que 16 KB com 413 antes de qualquer leitura: os corpos validos desta API tem poucos bytes.
 * Corpo sem Content-Length (chunked) nao e coberto aqui; esse limite fica para o proxy na frente da aplicacao.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class RequestSizeLimitFilter extends OncePerRequestFilter {

    static final long MAX_BODY_BYTES = 16 * 1024;

    private final JsonMapper jsonMapper;

    public RequestSizeLimitFilter(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getContentLengthLong() > MAX_BODY_BYTES) {
            ProblemResponses.write(request, response, jsonMapper, HttpStatus.CONTENT_TOO_LARGE,
                    "Corpo muito grande", "O corpo da requisicao deve ter no maximo 16 KB", "PAYLOAD_TOO_LARGE");
            return;
        }
        chain.doFilter(request, response);
    }
}
