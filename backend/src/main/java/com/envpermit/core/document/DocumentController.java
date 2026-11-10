package com.envpermit.core.document;

import static com.envpermit.core.project.ProjectAccess.userId;

import com.envpermit.core.permit.PermitItem;
import com.envpermit.core.permit.PermitItemRepository;
import com.envpermit.core.project.ProjectAccess;
import com.envpermit.core.user.User;
import com.envpermit.core.user.UserRepository;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.core.io.InputStreamResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.format.annotation.DateTimeFormat.ISO;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class DocumentController {

    static final long MAX_BYTES = 10 * 1024 * 1024;
    static final String TOO_LARGE = "파일은 10MB 이하만 올릴 수 있습니다.";
    static final String ALREADY_SUPERSEDED = "이미 다른 문서로 대체된 문서입니다. 최신 문서를 선택하세요.";
    static final String BAD_TYPE = "PDF, JPG, PNG, XLSX 파일만 올릴 수 있습니다.";

    private final DocumentRepository documents;
    private final DocumentStorage storage;
    private final PermitItemRepository permits;
    private final UserRepository users;
    private final ProjectAccess access;
    private final Clock clock;

    public DocumentController(DocumentRepository documents, DocumentStorage storage, PermitItemRepository permits,
                              UserRepository users, ProjectAccess access, Clock clock) {
        this.documents = documents;
        this.storage = storage;
        this.permits = permits;
        this.users = users;
        this.access = access;
        this.clock = clock;
    }

    record DocumentView(long id, String fileName, String contentType, long size, String sha256,
                        String uploadedBy, Instant uploadedAt, Long supersedesDocumentId, LocalDate effectiveDate,
                        long permitItemId) {
    }

    @GetMapping("/api/projects/{projectId}/permits/{permitId}/documents")
    List<DocumentView> list(@AuthenticationPrincipal Jwt jwt, @PathVariable long projectId,
                            @PathVariable long permitId) {
        checkPermit(jwt, projectId, permitId);
        return views(documents.findByPermitItemIdOrderByUploadedAtDescIdDesc(permitId));
    }

    /** Every document of the project (프로젝트 › 문서 tab), newest first. */
    @GetMapping("/api/projects/{projectId}/documents")
    List<DocumentView> projectList(@AuthenticationPrincipal Jwt jwt, @PathVariable long projectId) {
        access.member(projectId, jwt);
        return views(documents.findByPermitItemIdInOrderByUploadedAtDescIdDesc(
                permits.findByProjectIdOrderById(projectId).stream().map(PermitItem::getId).toList()));
    }

    private List<DocumentView> views(List<Document> rows) {
        Map<Long, String> names = users.findAllById(rows.stream().map(Document::getUploadedBy).toList())
                .stream().collect(Collectors.toMap(User::getId, User::getName));
        return rows.stream().map(d -> view(d, names.get(d.getUploadedBy()))).toList();
    }

    @PostMapping("/api/projects/{projectId}/permits/{permitId}/documents")
    ResponseEntity<?> upload(@AuthenticationPrincipal Jwt jwt, @PathVariable long projectId,
                             @PathVariable long permitId, @RequestParam("file") MultipartFile file,
                             @RequestParam(required = false) Long supersedesDocumentId,
                             @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate effectiveDate)
            throws IOException {
        checkPermit(jwt, projectId, permitId);
        if (supersedesDocumentId != null) {
            String why = cannotSupersede(permitId, supersedesDocumentId);
            if (why != null) {
                return reject(HttpStatus.UNPROCESSABLE_ENTITY, why);
            }
        }
        if (file.getSize() > MAX_BYTES) {
            return reject(HttpStatus.PAYLOAD_TOO_LARGE, TOO_LARGE);
        }
        String name = baseName(file.getOriginalFilename());
        byte[] bytes = file.getBytes();
        DocumentType type = DocumentType.detect(name, bytes);
        if (type == null || bytes.length == 0) {
            return reject(HttpStatus.BAD_REQUEST, BAD_TYPE);
        }
        if (name.length() > 255) {
            return reject(HttpStatus.BAD_REQUEST, "파일 이름은 255자 이하여야 합니다.");
        }
        String key = "permits/" + permitId + "/" + UUID.randomUUID();
        storage.put(key, new ByteArrayInputStream(bytes), bytes.length, type.contentType);
        // shortcut: if this save fails the stored object is orphaned; add a sweep if that ever matters
        Document d;
        try {
            d = documents.save(new Document(permitId, name, type.contentType, bytes.length, sha256(bytes),
                    key, userId(jwt), clock.instant(), supersedesDocumentId, effectiveDate));
        } catch (DataIntegrityViolationException e) { // a concurrent upload superseded the same document first
            storage.delete(key);
            return reject(HttpStatus.UNPROCESSABLE_ENTITY, ALREADY_SUPERSEDED);
        }
        String uploader = users.findById(userId(jwt)).map(User::getName).orElse(null);
        return ResponseEntity.status(HttpStatus.CREATED).body(view(d, uploader));
    }

    @GetMapping("/api/projects/{projectId}/permits/{permitId}/documents/{id}")
    ResponseEntity<InputStreamResource> download(@AuthenticationPrincipal Jwt jwt, @PathVariable long projectId,
                                                 @PathVariable long permitId, @PathVariable long id) {
        Document d = find(jwt, projectId, permitId, id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(d.getContentType()))
                .contentLength(d.getSizeBytes())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(d.getFileName(), StandardCharsets.UTF_8).build().toString())
                .body(new InputStreamResource(storage.open(d.getStorageKey())));
    }

    // no @Transactional: the row is gone (committed) before the bytes are, so a failure never leaves a dangling row
    @DeleteMapping("/api/projects/{projectId}/permits/{permitId}/documents/{id}")
    ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable long projectId,
                                @PathVariable long permitId, @PathVariable long id) {
        Document d = find(jwt, projectId, permitId, id);
        documents.delete(d);
        storage.delete(d.getStorageKey());
        return ResponseEntity.noContent().build();
    }

    /** ④ "이전 버전 대체" (design §3.2): same permit item, no successor yet, nothing left to review on its line. */
    private String cannotSupersede(long permitId, long previousId) {
        if (documents.findByIdAndPermitItemId(previousId, permitId).isEmpty()) {
            return "같은 인허가 항목의 문서만 대체할 수 있습니다.";
        }
        if (documents.existsBySupersedesDocumentId(previousId)) {
            return ALREADY_SUPERSEDED;
        }
        try {
            documents.requireNoOpenReview(previousId);
            return null;
        } catch (ResponseStatusException e) {
            return e.getReason();
        }
    }

    private void checkPermit(Jwt jwt, long projectId, long permitId) {
        access.member(projectId, jwt);
        permits.findByIdAndProjectId(permitId, projectId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private Document find(Jwt jwt, long projectId, long permitId, long id) {
        checkPermit(jwt, projectId, permitId);
        return documents.findByIdAndPermitItemId(id, permitId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    static ResponseEntity<Map<String, String>> reject(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("message", message));
    }

    private static String baseName(String name) {
        if (name == null) {
            return "";
        }
        return name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1).trim();
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static DocumentView view(Document d, String uploader) {
        return new DocumentView(d.getId(), d.getFileName(), d.getContentType(), d.getSizeBytes(), d.getSha256(),
                uploader, d.getUploadedAt(), d.getSupersedesDocumentId(), d.getEffectiveDate(), d.getPermitItemId());
    }
}
