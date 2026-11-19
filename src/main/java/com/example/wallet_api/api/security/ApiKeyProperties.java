package com.example.wallet_api.api.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Chave que os sistemas clientes enviam no header X-API-Key. Vem de WALLET_API_KEY; sem ela a aplicacao nao sobe.
 * O placeholder do .env.example ("change-me...") tambem e recusado, para ninguem subir com uma chave publica.
 */
@Validated
@ConfigurationProperties(prefix = "wallet.security")
public record ApiKeyProperties(
        @NotBlank
        @Size(min = 16)
        @Pattern(regexp = "(?i)(?!change-me).*", message = "troque o placeholder do .env.example por uma chave aleatoria")
        String apiKey) {

    @Override
    public String toString() {
        return "ApiKeyProperties[apiKey=***]";
    }
}
