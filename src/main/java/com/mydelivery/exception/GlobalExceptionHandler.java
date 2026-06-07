package com.mydelivery.exception;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.server.ResponseStatusException;

import jakarta.persistence.EntityNotFoundException;
import lombok.extern.slf4j.Slf4j;

/**
 * Handlers tipados por exceção. Antes desse refactor todo RuntimeException
 * virava HTTP 400, mascarando 500/404/409 e dificultando debug em prod.
 * Agora cada categoria devolve o status semanticamente correto + log no
 * nivel apropriado.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** Argumento invalido vindo do usuario (validacao de logica de negocio). */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("[Validacao] {}", e.getMessage());
        return ResponseEntity.badRequest().body(Map.of("erro", safeMsg(e)));
    }

    /** Entidade nao encontrada. Vira 404, nao 400. */
    @ExceptionHandler({ EntityNotFoundException.class, NoSuchElementException.class })
    public ResponseEntity<Map<String, String>> handleNotFound(RuntimeException e) {
        log.warn("[NotFound] {}: {}", e.getClass().getSimpleName(), e.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("erro", safeMsg(e)));
    }

    /** Violacao de constraint do banco (unique, FK, NOT NULL). Vira 409. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, String>> handleDataIntegrity(DataIntegrityViolationException e) {
        // Mensagem do JPA traz SQL cru e nomes de constraints internas — nao expor.
        String root = e.getMostSpecificCause() != null ? e.getMostSpecificCause().getMessage() : e.getMessage();
        log.error("[DataIntegrity] {}", root);
        String msg = "Conflito de dados. Verifique se o registro ja existe ou tem dependencias.";
        if (root != null && root.toLowerCase().contains("duplicate")) {
            msg = "Registro duplicado.";
        }
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("erro", msg));
    }

    /** Autorizacao negada (Spring Security ja seria 403, mas algumas vezes chega aqui). */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, String>> handleAccessDenied(AccessDeniedException e) {
        log.warn("[Forbidden] {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("erro", "Voce nao tem permissao pra essa acao."));
    }

    /** Excecoes onde o controller ja definiu o status (ex: throw new ResponseStatusException). */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handleResponseStatus(ResponseStatusException e) {
        log.warn("[ResponseStatus] {} -> {}", e.getStatusCode(), e.getReason());
        String msg = e.getReason() != null ? e.getReason() : e.getStatusCode().toString();
        return ResponseEntity.status(e.getStatusCode()).body(Map.of("erro", msg));
    }

    /**
     * Fallback pra RuntimeException nao tratada. Mantemos 400 com a mensagem
     * por COMPATIBILIDADE: muito codigo legado lanca
     * {@code throw new RuntimeException("msg amigavel")} esperando o frontend
     * ler a msg e mostrar pro usuario.
     *
     * Bugs reais (NPE, OOM, IllegalStateException) tambem caem aqui — sao
     * detectados pelo {@link NullPointerException} no log. A medio prazo, esse
     * codigo legado deve migrar pra IllegalArgumentException (400) ou
     * ResponseStatusException (status custom).
     */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, String>> handleRuntimeException(RuntimeException e) {
        // NPE / IllegalState / etc. sao bugs reais — log com stack trace.
        // Mensagens amigaveis (throw new RuntimeException("msg")) viram 400.
        boolean bugReal = e instanceof NullPointerException
                || e instanceof IllegalStateException
                || e instanceof ClassCastException;
        if (bugReal) {
            log.error("[ServerError] {}: {}", e.getClass().getSimpleName(), e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("erro", "Erro interno. Tente novamente em instantes."));
        }
        log.warn("[BusinessError] {}: {}", e.getClass().getSimpleName(), e.getMessage());
        return ResponseEntity.badRequest().body(Map.of("erro", safeMsg(e)));
    }

    private static String safeMsg(Throwable e) {
        return e.getMessage() != null && !e.getMessage().isBlank()
                ? e.getMessage()
                : e.getClass().getSimpleName();
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleValidationException(
            MethodArgumentNotValidException e) {
        Map<String, String> erros = new LinkedHashMap<>();
        e.getBindingResult().getAllErrors().forEach(error -> {
            String campo = ((FieldError) error).getField();
            String mensagem = error.getDefaultMessage();
            erros.put(campo, mensagem);
        });
        return ResponseEntity.badRequest().body(erros);
    }

    /**
     * Mensagens amigáveis pra erros de upload de arquivo. Antes essas exceptions
     * caíam num 400 genérico sem corpo, deixando o frontend sem contexto.
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, String>> handleUploadTooBig(MaxUploadSizeExceededException e) {
        log.warn("[Upload] Arquivo excedeu o limite: {}", e.getMessage());
        return ResponseEntity.badRequest().body(Map.of("erro", "Arquivo muito grande. Máximo permitido conforme configuração do servidor."));
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<Map<String, String>> handleMissingPart(MissingServletRequestPartException e) {
        log.warn("[Upload] Parte do multipart ausente: {}", e.getRequestPartName());
        return ResponseEntity.badRequest().body(Map.of("erro", "Campo '" + e.getRequestPartName() + "' ausente no upload."));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, String>> handleMissingParam(MissingServletRequestParameterException e) {
        log.warn("[Upload] Parâmetro ausente: {}", e.getParameterName());
        return ResponseEntity.badRequest().body(Map.of("erro", "Parâmetro '" + e.getParameterName() + "' ausente."));
    }

    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<Map<String, String>> handleMultipart(MultipartException e) {
        log.error("[Upload] Erro de multipart: {}", e.getMessage(), e);
        return ResponseEntity.badRequest().body(Map.of("erro", "Falha ao processar o upload. Verifique o arquivo e tente novamente."));
    }
}