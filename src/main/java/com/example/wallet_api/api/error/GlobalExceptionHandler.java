package com.example.wallet_api.api.error;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import com.example.wallet_api.api.web.TraceIdFilter;
import com.example.wallet_api.domain.ErrorCode;
import com.example.wallet_api.domain.WalletException;

/**
 * Toda resposta de erro sai como ProblemDetail (RFC 9457) com tres campos extras: code, traceId e timestamp.
 * O detalhe nunca carrega stack trace nem mensagem do banco; erros nao previstos viram 500 generico e o
 * detalhe tecnico fica so no log, junto com o traceId.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static final String INTERNAL_DETAIL = "Erro interno. Informe o traceId ao suporte.";

    /** Contencao passageira: o cliente pode repetir a mesma requisicao (com a mesma chave) depois deste intervalo. */
    private static final String RETRY_AFTER_SECONDS = "1";

    @ExceptionHandler(WalletException.class)
    ResponseEntity<ProblemDetail> handleWalletException(WalletException ex) {
        return walletError(ex.code(), ex.getMessage(), ex);
    }

    /**
     * Pool de conexoes esgotado (por exemplo, varias requisicoes esperando o lock de uma carteira disputada):
     * e contencao, nao falha interna.
     */
    @ExceptionHandler({ CannotCreateTransactionException.class, CannotGetJdbcConnectionException.class })
    ResponseEntity<ProblemDetail> handleNoConnection(Exception ex) {
        log.warn("Sem conexao disponivel no pool: {}", ex.getMessage());
        ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "Servico ocupado; tente novamente em instantes");
        body.setTitle("Servico ocupado");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS)
                .body(enrich(body, ErrorMapping.SERVICE_BUSY));
    }

    private ResponseEntity<ProblemDetail> walletError(ErrorCode code, String message, Exception cause) {
        ErrorMapping mapping = ErrorMapping.of(code);
        if (!mapping.exposeDetail()) {
            log.error("Erro de negocio que nao deveria chegar pela API: {}", code, cause);
        } else if (code == ErrorCode.WALLET_LOCKED) {
            log.info("Carteira ocupada; cliente orientado a repetir");
        }
        ProblemDetail body = ProblemDetail.forStatusAndDetail(mapping.status(), mapping.exposeDetail() ? message : INTERNAL_DETAIL);
        body.setTitle(mapping.title());
        ResponseEntity.BodyBuilder response = ResponseEntity.status(mapping.status());
        if (mapping.status() == HttpStatus.SERVICE_UNAVAILABLE) {
            response.header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS);
        }
        return response.body(enrich(body, code.name()));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
        log.error("Erro nao tratado", ex);
        ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, INTERNAL_DETAIL);
        body.setTitle("Erro interno");
        return ResponseEntity.internalServerError().body(enrich(body, ErrorMapping.INTERNAL_ERROR));
    }

    /**
     * Erros do proprio Spring MVC (parametro com tipo errado, rota inexistente, metodo nao suportado...).
     * O texto padrao do Spring pode citar tipos Java ou ecoar o path; o cliente recebe um texto neutro,
     * no maximo com o nome do parametro problematico.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
                                                             HttpStatusCode statusCode, WebRequest request) {
        ProblemDetail problem = body instanceof ProblemDetail pd ? pd : ProblemDetail.forStatus(statusCode);
        problem.setDetail(frameworkDetail(ex, statusCode));
        return super.handleExceptionInternal(ex, enrich(problem, frameworkCode(statusCode)), headers, statusCode, request);
    }

    /**
     * Campo obrigatorio ausente: 400 VALIDATION_ERROR com a lista de campos.
     * O {@code @Digits} de amount e so uma barreira contra numero absurdo; quando ele dispara, a resposta e a mesma
     * que o banco daria (ex.: amount com 11 casas -> 422 INVALID_AMOUNT, igual a 10.005).
     */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex, HttpHeaders headers,
                                                                  HttpStatusCode status, WebRequest request) {
        List<FieldError> fieldErrors = ex.getBindingResult().getFieldErrors();
        boolean missingField = fieldErrors.stream().anyMatch(GlobalExceptionHandler::isPresenceError);
        if (!missingField && !fieldErrors.isEmpty() && "amount".equals(fieldErrors.getFirst().getField())) {
            ResponseEntity<ProblemDetail> response = walletError(ErrorCode.INVALID_AMOUNT, ErrorCode.INVALID_AMOUNT.defaultMessage(), ex);
            return ResponseEntity.status(response.getStatusCode()).headers(response.getHeaders()).body(response.getBody());
        }
        List<Map<String, String>> errors = fieldErrors.stream()
                .map(error -> Map.of("field", error.getField(), "message", isPresenceError(error) ? "obrigatorio" : "invalido"))
                .toList();
        ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Campos obrigatorios ausentes ou invalidos");
        body.setTitle(ErrorMapping.of(ErrorCode.VALIDATION_ERROR).title());
        body.setProperty("errors", errors);
        return ResponseEntity.badRequest().body(enrich(body, ErrorCode.VALIDATION_ERROR.name()));
    }

    /** Mensagem fixa na lista de erros: a padrao do Bean Validation varia com o idioma da JVM. */
    private static boolean isPresenceError(FieldError error) {
        return "NotNull".equals(error.getCode()) || "NotBlank".equals(error.getCode());
    }


    private static ProblemDetail enrich(ProblemDetail problem, String code) {
        problem.setProperty("code", code);
        problem.setProperty("traceId", TraceIdFilter.currentTraceId());
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }

    private static String frameworkDetail(Exception ex, HttpStatusCode status) {
        return switch (status.value()) {
            case 400 -> switch (ex) {
                case MethodArgumentTypeMismatchException e -> "Valor invalido para o parametro '" + e.getName() + "'";
                case MissingServletRequestParameterException e -> "Parametro obrigatorio ausente: '" + e.getParameterName() + "'";
                case MissingRequestHeaderException e -> "Header obrigatorio ausente: '" + e.getHeaderName() + "'";
                default -> "Parametro, corpo ou header invalido";
            };
            case 404 -> "Recurso nao encontrado";
            case 405 -> "Metodo HTTP nao suportado nesta rota";
            case 406 -> "Formato de resposta nao suportado";
            case 415 -> "Content-Type nao suportado";
            default -> status.is5xxServerError() ? INTERNAL_DETAIL : "Requisicao invalida";
        };
    }

    private static String frameworkCode(HttpStatusCode status) {
        return switch (status.value()) {
            case 400 -> ErrorMapping.MALFORMED_REQUEST;
            case 404 -> ErrorMapping.NOT_FOUND;
            case 405 -> ErrorMapping.METHOD_NOT_ALLOWED;
            case 406 -> ErrorMapping.NOT_ACCEPTABLE;
            case 415 -> ErrorMapping.UNSUPPORTED_MEDIA_TYPE;
            default -> status.is5xxServerError() ? ErrorMapping.INTERNAL_ERROR : ErrorMapping.REQUEST_ERROR;
        };
    }
}
