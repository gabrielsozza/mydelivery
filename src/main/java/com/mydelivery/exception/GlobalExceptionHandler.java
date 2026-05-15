package com.mydelivery.exception;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, String>> handleRuntimeException(RuntimeException e) {
        // Loga stack trace pra facilitar debug — antes ficava só "Bad Request" no front
        // sem rastro do que aconteceu no backend.
        log.error("[GlobalException] {}: {}", e.getClass().getSimpleName(), e.getMessage(), e);
        String msg = e.getMessage() != null && !e.getMessage().isBlank()
                ? e.getMessage()
                : e.getClass().getSimpleName();
        return ResponseEntity
                .badRequest()
                .body(Map.of("erro", msg));
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