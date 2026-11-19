package com.example.wallet_api.api.web;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

/**
 * Escreve um erro no mesmo formato do GlobalExceptionHandler (ProblemDetail + code, traceId, timestamp)
 * a partir de um filtro, onde o @RestControllerAdvice ainda nao atua.
 */
public final class ProblemResponses {

    private ProblemResponses() {
    }

    public static void write(HttpServletRequest request, HttpServletResponse response, JsonMapper jsonMapper,
                             HttpStatus status, String title, String detail, String code) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "about:blank");
        body.put("title", title);
        body.put("status", status.value());
        body.put("detail", detail);
        body.put("instance", request.getRequestURI());
        body.put("code", code);
        body.put("traceId", TraceIdFilter.currentTraceId());
        body.put("timestamp", Instant.now().toString());

        response.setStatus(status.value());
        // Mesmo Content-Type do GlobalExceptionHandler; o JsonMapper sempre escreve UTF-8.
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        jsonMapper.writeValue(response.getOutputStream(), body);
    }
}
