package com.envpermit.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * One error shape for the whole API: {@code {code, message}}. Only our own {@link ResponseStatusException} reasons
 * (user-facing Korean) reach the client; framework and unexpected exception text is logged, never returned.
 * Lowest precedence, so feature handlers ({@code ModuleDisabled}, upload limit) still answer first.
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class ApiErrors extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiErrors.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    public static Map<String, String> body(HttpStatusCode status, String message) {
        HttpStatus s = HttpStatus.resolve(status.value());
        return Map.of("code", s == null ? String.valueOf(status.value()) : s.name(), "message", message);
    }

    /** For filters and Spring Security handlers, which run outside MVC. */
    public static void write(HttpServletResponse res, HttpStatus status, String message) throws IOException {
        res.setStatus(status.value());
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.setCharacterEncoding("UTF-8");
        JSON.writeValue(res.getWriter(), body(status, message));
    }

    static String generic(HttpStatusCode status) {
        return switch (status.value()) {
            case 400 -> "요청 형식이 올바르지 않습니다. 입력값을 확인해 주세요.";
            case 401 -> "로그인이 필요합니다.";
            case 403 -> "권한이 없습니다.";
            case 404 -> "찾을 수 없습니다.";
            case 405 -> "지원하지 않는 요청입니다.";
            case 409 -> "다른 변경과 충돌했습니다. 새로고침 후 다시 시도해 주세요.";
            case 413 -> "파일이 너무 큽니다.";
            case 415 -> "지원하지 않는 형식입니다.";
            case 429 -> "요청이 너무 잦습니다. 잠시 후 다시 시도해 주세요.";
            default -> status.is5xxServerError() ? "서버 오류가 발생했습니다. 잠시 후 다시 시도해 주세요." : "요청을 처리할 수 없습니다.";
        };
    }

    /** Every Spring MVC exception (bad JSON, type mismatch, validation, ResponseStatusException...) ends here. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, @Nullable Object body, HttpHeaders headers,
                                                             HttpStatusCode status, WebRequest request) {
        String message = ex instanceof ResponseStatusException rse && rse.getReason() != null
                ? rse.getReason() : generic(status);
        if (status.is5xxServerError()) {
            log.error("{} {}", status.value(), request.getDescription(false), ex);
        }
        return ResponseEntity.status(status).headers(headers).body(body(status, message));
    }

    /** Anything unexpected (SQL errors, NPEs, SDK failures): 500 with a fixed message, details only in the log. */
    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> unexpected(Exception ex, WebRequest request) {
        return handleExceptionInternal(ex, null, new HttpHeaders(), HttpStatus.INTERNAL_SERVER_ERROR, request);
    }
}
