package com.envpermit.core.document;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/** Multipart limits trip before any controller runs, so the Korean message needs a global handler. */
@RestControllerAdvice
class UploadLimitHandler {

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<?> tooLarge() {
        return DocumentController.reject(HttpStatus.PAYLOAD_TOO_LARGE, DocumentController.TOO_LARGE);
    }
}
