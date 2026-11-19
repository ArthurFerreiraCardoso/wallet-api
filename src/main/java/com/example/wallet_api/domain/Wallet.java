package com.example.wallet_api.domain;

import java.math.BigDecimal;
import java.time.Instant;

public record Wallet(long id, String holderName, BigDecimal balance, Instant createdAt, Instant updatedAt) {
}
