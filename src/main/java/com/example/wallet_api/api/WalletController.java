package com.example.wallet_api.api;

import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.wallet_api.api.dto.CreateWalletRequest;
import com.example.wallet_api.api.dto.PageResponse;
import com.example.wallet_api.api.dto.RegisterTransactionRequest;
import com.example.wallet_api.api.dto.TransactionResponse;
import com.example.wallet_api.api.dto.WalletResponse;
import com.example.wallet_api.application.WalletCommandService;
import com.example.wallet_api.application.WalletQueryService;
import com.example.wallet_api.domain.PageRequest;
import com.example.wallet_api.domain.Period;
import com.example.wallet_api.domain.RegisteredTransaction;
import com.example.wallet_api.domain.Wallet;

@RestController
@RequestMapping("/wallets")
public class WalletController {

    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    public static final String REPLAYED_HEADER = "Idempotent-Replayed";

    private final WalletQueryService walletQueryService;
    private final WalletCommandService walletCommandService;

    public WalletController(WalletQueryService walletQueryService, WalletCommandService walletCommandService) {
        this.walletQueryService = walletQueryService;
        this.walletCommandService = walletCommandService;
    }

    @PostMapping
    public ResponseEntity<WalletResponse> createWallet(@RequestBody(required = false) @Validated CreateWalletRequest request) {
        Wallet wallet = walletCommandService.createWallet(request == null ? null : request.holderName());
        return ResponseEntity.created(URI.create("/wallets/" + wallet.id())).body(WalletResponse.from(wallet));
    }

    @GetMapping("/{walletId}")
    public WalletResponse getWallet(@PathVariable long walletId) {
        return WalletResponse.from(walletQueryService.getWallet(walletId));
    }

    /**
     * 201 quando a operacao e aplicada; 200 com {@code Idempotent-Replayed: true} quando a mesma chave chega de novo
     * com o mesmo tipo e valor (o corpo e o do lancamento original). A chave ausente e recusada pelo banco (400),
     * que valida nesta ordem: tipo, valor, chave.
     */
    @PostMapping("/{walletId}/transactions")
    public ResponseEntity<TransactionResponse> registerTransaction(
            @PathVariable long walletId,
            @RequestHeader(name = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
            @RequestBody @Validated RegisterTransactionRequest request) {
        RegisteredTransaction result = walletCommandService.registerTransaction(
                walletId, request.type(), request.amount(), idempotencyKey);
        TransactionResponse body = TransactionResponse.from(result.transaction());
        if (result.replayed()) {
            return ResponseEntity.ok().header(REPLAYED_HEADER, "true").body(body);
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
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
