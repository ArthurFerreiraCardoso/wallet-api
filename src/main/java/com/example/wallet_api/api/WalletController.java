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
import com.example.wallet_api.api.error.ApiProblem;
import com.example.wallet_api.application.WalletCommandService;
import com.example.wallet_api.application.WalletQueryService;
import com.example.wallet_api.domain.PageRequest;
import com.example.wallet_api.domain.Period;
import com.example.wallet_api.domain.RegisteredTransaction;
import com.example.wallet_api.domain.Wallet;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/wallets")
@Tag(name = "Carteiras")
@ApiResponse(responseCode = "401", description = "UNAUTHORIZED: API key ausente ou invalida",
        headers = @Header(name = "WWW-Authenticate", description = "ApiKey header=\"X-API-Key\""), content = @Content(
        mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
@ApiResponse(responseCode = "413", description = "PAYLOAD_TOO_LARGE: corpo acima de 16 KB", content = @Content(
        mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
@ApiResponse(responseCode = "500", description = "INTERNAL_ERROR: detalhe so no log, com o traceId", content = @Content(
        mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
@ApiResponse(responseCode = "503", description = "SERVICE_BUSY (sem conexao livre) ou WALLET_LOCKED: repita depois do Retry-After",
        headers = @Header(name = "Retry-After", description = "segundos ate tentar de novo",
                schema = @Schema(type = "integer", example = "1")),
        content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
public class WalletController {

    public static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    public static final String REPLAYED_HEADER = "Idempotent-Replayed";

    private final WalletQueryService walletQueryService;
    private final WalletCommandService walletCommandService;

    public WalletController(WalletQueryService walletQueryService, WalletCommandService walletCommandService) {
        this.walletQueryService = walletQueryService;
        this.walletCommandService = walletCommandService;
    }

    @Operation(summary = "Cria uma carteira com saldo 0.00", description = "Titular opcional, ate 120 caracteres.")
    @ApiResponse(responseCode = "201", description = "Carteira criada",
            headers = @Header(name = "Location", description = "Caminho da carteira criada", schema = @Schema(example = "/wallets/42")))
    @ApiResponse(responseCode = "400", description = "MALFORMED_REQUEST: JSON invalido", content = @Content(
            mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
    @ApiResponse(responseCode = "415", description = "UNSUPPORTED_MEDIA_TYPE: corpo que nao e JSON", content = @Content(
            mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
    @ApiResponse(responseCode = "422", description = "INVALID_HOLDER", content = @Content(
            mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
    @PostMapping
    public ResponseEntity<WalletResponse> createWallet(@RequestBody(required = false) @Validated CreateWalletRequest request) {
        Wallet wallet = walletCommandService.createWallet(request == null ? null : request.holderName());
        return ResponseEntity.created(URI.create("/wallets/" + wallet.id())).body(WalletResponse.from(wallet));
    }

    @Operation(summary = "Consulta a carteira e o saldo atual")
    @ApiResponse(responseCode = "200", description = "Carteira encontrada")
    @ApiResponse(responseCode = "400", description = "MALFORMED_REQUEST: id nao numerico", content = @Content(
            mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
    @ApiResponse(responseCode = "404", description = "WALLET_NOT_FOUND", content = @Content(
            mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
    @GetMapping("/{walletId}")
    public WalletResponse getWallet(@Parameter(example = "42") @PathVariable long walletId) {
        return WalletResponse.from(walletQueryService.getWallet(walletId));
    }

    /**
     * 201 quando a operacao e aplicada; 200 com {@code Idempotent-Replayed: true} quando a mesma chave chega de novo
     * com o mesmo tipo e valor (o corpo e o do lancamento original). A chave ausente e recusada pelo banco (400),
     * que valida nesta ordem: tipo, valor, chave.
     */
    @Operation(summary = "Registra um credito ou debito", description = """
            Idempotente pela chave, por carteira: repetir a mesma chave com o mesmo tipo e valor devolve o lancamento \
            original sem aplicar de novo. Valor maior que zero, com no maximo 2 casas. O banco valida nesta ordem: \
            tipo, valor, chave (chave ausente com valor invalido responde 422 INVALID_AMOUNT).""")
    @ApiResponse(responseCode = "201", description = "Operacao aplicada")
    @ApiResponse(responseCode = "200", description = "Replay: mesmo corpo da primeira resposta", headers = @Header(
            name = REPLAYED_HEADER, description = "Presente so no replay", schema = @Schema(allowableValues = "true")))
    @ApiResponse(responseCode = "400", description = "INVALID_IDEMPOTENCY_KEY, VALIDATION_ERROR (com errors) ou MALFORMED_REQUEST",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
    @ApiResponse(responseCode = "415", description = "UNSUPPORTED_MEDIA_TYPE: corpo que nao e JSON", content = @Content(
            mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
    @ApiResponse(responseCode = "404", description = "WALLET_NOT_FOUND", content = @Content(
            mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
    @ApiResponse(responseCode = "409", description = "IDEMPOTENCY_KEY_CONFLICT: chave ja usada para outra operacao",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
    @ApiResponse(responseCode = "422", description = "INSUFFICIENT_FUNDS, INVALID_AMOUNT, INVALID_TRANSACTION_TYPE ou "
            + "BALANCE_LIMIT_EXCEEDED", content = @Content(
            mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
    @PostMapping("/{walletId}/transactions")
    public ResponseEntity<TransactionResponse> registerTransaction(
            @Parameter(example = "42") @PathVariable long walletId,
            // required=false no Spring de proposito (o banco responde INVALID_IDEMPOTENCY_KEY); obrigatorio no contrato.
            @Parameter(required = true, description = "1 a 64 caracteres entre letras, numeros, _ e -; diferencia maiusculas",
                    example = "pedido-123", schema = @Schema(type = "string", pattern = "^[A-Za-z0-9_-]{1,64}$"))
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

    @Operation(summary = "Extrato paginado", description = "Do lancamento mais recente para o mais antigo. Periodo semiaberto [from, to).")
    @ApiResponse(responseCode = "200", description = "Pagina do extrato")
    @ApiResponse(responseCode = "400", description = "VALIDATION_ERROR, INVALID_PERIOD ou MALFORMED_REQUEST", content = @Content(
            mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
    @ApiResponse(responseCode = "404", description = "WALLET_NOT_FOUND", content = @Content(
            mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
    @GetMapping("/{walletId}/transactions")
    public PageResponse<TransactionResponse> listTransactions(
            @Parameter(example = "42") @PathVariable long walletId,
            @Parameter(description = "Pagina, a partir de 0", schema = @Schema(minimum = "0", defaultValue = "0"))
            @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Itens por pagina", schema = @Schema(minimum = "1", maximum = "100", defaultValue = "20"))
            @RequestParam(defaultValue = "20") int size,
            @Parameter(description = "Inicio inclusivo, ISO-8601 com fuso (na URL, + vira %2B)", example = "2026-11-01T00:00:00-03:00")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @Parameter(description = "Fim exclusivo, ISO-8601 com fuso", example = "2026-12-01T00:00:00-03:00")
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
