package com.example.wallet_api.api.error;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import com.example.wallet_api.api.web.TraceIdFilter;
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

    @ExceptionHandler(WalletException.class)
    ResponseEntity<ProblemDetail> handleWalletException(WalletException ex) {
        ErrorMapping mapping = ErrorMapping.of(ex.code());
        if (!mapping.exposeDetail()) {
            log.error("Erro de negocio que nao deveria chegar pela API: {}", ex.code(), ex);
        }
        ProblemDetail body = ProblemDetail.forStatusAndDetail(mapping.status(),
                mapping.exposeDetail() ? ex.getMessage() : INTERNAL_DETAIL);
        body.setTitle(mapping.title());
        return ResponseEntity.status(mapping.status()).body(enrich(body, ex.code().name()));
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
