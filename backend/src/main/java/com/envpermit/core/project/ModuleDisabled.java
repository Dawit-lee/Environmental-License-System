package com.envpermit.core.project;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** The project has the module switched off: 409 with a code the UI can tell apart from other conflicts. */
public class ModuleDisabled extends RuntimeException {

    public ModuleDisabled() {
        super("운영단계 관리 모듈이 꺼진 프로젝트입니다. 프로젝트 설정에서 켜세요.");
    }

    @RestControllerAdvice
    static class Handler {
        @ExceptionHandler(ModuleDisabled.class)
        ResponseEntity<Map<String, String>> handle(ModuleDisabled e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("code", "MODULE_DISABLED", "message", e.getMessage()));
        }
    }
}
