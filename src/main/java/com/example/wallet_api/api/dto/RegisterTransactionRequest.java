package com.example.wallet_api.api.dto;

import java.math.BigDecimal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Corpo de POST /wallets/{id}/transactions.
 *
 * <p>Valor do tipo, faixa e escala do valor sao regras do PKG_WALLET, que responde com o codigo do catalogo (422).
 * As anotacoes de validacao daqui so exigem presenca e barram um numero como {@code 1e999999999}, que cabe em poucos
 * bytes mas tem expoente gigante: {@code @Digits} e checado com precision/scale, sem expandir o numero. O limite e
 * folgado de proposito: {@code 10.005} passa daqui e e recusado pelo banco. As anotacoes {@code @Schema} documentam
 * a regra real. Texto enorme e barrado pelo limite de corpo e pela guarda do adaptador do banco.
 */
public record RegisterTransactionRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = { "CREDIT", "DEBIT" }, example = "CREDIT")
        @NotBlank String type,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, type = "number", example = "10.50",
                description = "Maior que zero, no maximo 2 casas decimais e no maximo 99999999999999999.99",
                exclusiveMinimumValue = 0, maximum = "99999999999999999.99", multipleOf = 0.01)
        @NotNull @Digits(integer = 20, fraction = 10) BigDecimal amount) {
}
