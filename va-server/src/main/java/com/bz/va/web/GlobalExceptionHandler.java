package com.bz.va.web;

import com.bz.va.client.DownstreamException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

/** 统一错误体：{"error": {code, message, detail?}} */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> apiException(ApiException exc) {
        return ResponseEntity.status(exc.getStatus())
                .body(envelope(exc.getCode(), exc.getMessage(), null, null));
    }

    @ExceptionHandler(DownstreamException.class)
    public ResponseEntity<Map<String, Object>> downstream(DownstreamException exc) {
        return ResponseEntity.status(exc.getStatus()).body(envelope(exc.getCode(), exc.getMessage(),
                exc.getProvider(), exc.getDetail()));
    }

    @ExceptionHandler({MissingServletRequestParameterException.class, MissingServletRequestPartException.class,
            MethodArgumentTypeMismatchException.class, IllegalArgumentException.class})
    public ResponseEntity<Map<String, Object>> badRequest(Exception exc) {
        return ResponseEntity.badRequest().body(envelope("INVALID_ARGUMENT", exc.getMessage(), null, null));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> tooLarge(MaxUploadSizeExceededException exc) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(envelope("PAYLOAD_TOO_LARGE", "上传文件超过大小限制", null, null));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> unhandled(Exception exc) {
        log.error("unhandled error", exc);
        return ResponseEntity.internalServerError()
                .body(envelope("INTERNAL", "内部错误: " + exc.getMessage(), null, null));
    }

    private Map<String, Object> envelope(String code, String message, String provider, Object detail) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", message);
        if (provider != null) {
            error.put("provider", provider);
        }
        if (detail != null) {
            error.put("detail", detail);
        }
        return Map.of("error", error);
    }
}
