package com.example.wallet_api.api.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Corpo de POST /wallets/{id}/transactions.
 *
 * <p>Valor do tipo, faixa e escala do valor sao regras do PKG_WALLET, que responde com o codigo do catalogo (422).
 * As anotacoes daqui so exigem presenca e barram entrada absurda antes do driver: um texto enorme, ou um numero
 * como {@code 1e999999999}, que cabe em poucos bytes mas tem expoente gigante. {@code @Digits} e checado com
 * precision/scale, sem expandir o numero. Os limites sao folgados de proposito: {@code 10.005} passa daqui e e
 * recusado pelo banco.
 */
public record RegisterTransactionRequest(
        @NotBlank @Size(max = 20) String type,
        @NotNull @Digits(integer = 20, fraction = 10) BigDecimal amount) {
}
