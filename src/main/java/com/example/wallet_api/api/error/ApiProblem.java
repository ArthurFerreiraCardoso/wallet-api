package com.example.wallet_api.api.error;

import java.time.Instant;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * So para o documento OpenAPI: o formato real dos erros (ProblemDetail com os campos extras no nivel de cima).
 * As respostas sao montadas pelo GlobalExceptionHandler e pelo ProblemResponses.
 */
@Schema(name = "ApiProblem", description = "Erro no formato RFC 9457 (application/problem+json) com code, traceId e timestamp",
        example = """
                {"title":"Saldo insuficiente","status":422,"detail":"Saldo insuficiente para o debito",
                 "instance":"/wallets/7/transactions","code":"INSUFFICIENT_FUNDS",
                 "traceId":"6f1c2a9e-0b7d-4c55-9a51-2f0c1e8d4b3a","timestamp":"2026-11-19T13:00:00.123456Z"}""")
public record ApiProblem(
        @Schema(description = "Resumo legivel do erro") String title,
        @Schema(description = "Status HTTP", example = "422") int status,
        @Schema(description = "Explicacao para o cliente; nunca traz detalhe interno") String detail,
        @Schema(description = "Caminho da requisicao", example = "/wallets/7/transactions") String instance,
        @Schema(description = "Codigo estavel para o cliente tratar (catalogo em docs/ERROR-CODES.md)", example = "INSUFFICIENT_FUNDS")
        String code,
        @Schema(description = "Mesmo valor do header X-Trace-Id e dos logs") String traceId,
        @Schema(description = "Momento do erro, ISO-8601 UTC com fracao de segundo") Instant timestamp,
        @Schema(description = "So em VALIDATION_ERROR de corpo: campos ausentes", nullable = true) List<FieldProblem> errors) {

    @Schema(name = "FieldProblem")
    public record FieldProblem(
            @Schema(example = "amount") String field,
            @Schema(example = "obrigatorio") String message) {
    }
}
