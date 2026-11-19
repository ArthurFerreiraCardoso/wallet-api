package com.example.wallet_api.api.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

class ApiKeyPropertiesTest {

    private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
    private final Validator validator = FACTORY.getValidator();

    @AfterAll
    static void close() {
        FACTORY.close();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = { "   ", "curta-demais", "change-me-gere-uma-chave-aleatoria", "CHANGE-ME-0123456789abcdef" })
    void missingOrShortKeyIsRejectedSoTheApplicationDoesNotStart(String key) {
        assertThat(validator.validate(new ApiKeyProperties(key))).isNotEmpty();
    }

    @Test
    void keyWithAtLeast16CharactersIsAccepted() {
        assertThat(validator.validate(new ApiKeyProperties("0123456789abcdef"))).isEmpty();
    }

    @Test
    void toStringDoesNotRevealTheKey() {
        assertThat(new ApiKeyProperties("0123456789abcdef")).hasToString("ApiKeyProperties[apiKey=***]");
    }
}
