package com.example.wallet_api.api;

import java.time.Instant;
import java.time.OffsetDateTime;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.wallet_api.api.dto.PageResponse;
import com.example.wallet_api.api.dto.TransactionResponse;
import com.example.wallet_api.api.dto.WalletResponse;
import com.example.wallet_api.application.WalletQueryService;
import com.example.wallet_api.domain.PageRequest;
import com.example.wallet_api.domain.Period;

@RestController
@RequestMapping("/wallets")
public class WalletController {

    private final WalletQueryService walletQueryService;

    public WalletController(WalletQueryService walletQueryService) {
        this.walletQueryService = walletQueryService;
    }

    @GetMapping("/{walletId}")
    public WalletResponse getWallet(@PathVariable long walletId) {
        return WalletResponse.from(walletQueryService.getWallet(walletId));
    }

    /** Periodo opcional e semiaberto: [from, to). */
    @GetMapping("/{walletId}/transactions")
    public PageResponse<TransactionResponse> listTransactions(
            @PathVariable long walletId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to) {
        Period period = new Period(toInstant(from), toInstant(to));
        return PageResponse.from(
                walletQueryService.listTransactions(walletId, period, new PageRequest(page, size)),
                TransactionResponse::from);
    }

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
