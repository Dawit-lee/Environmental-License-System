package com.envpermit.core.document;

import java.util.Arrays;
import java.util.Locale;

/** Allowed attachment types. The extension and the file's first bytes must both match. */
enum DocumentType {
    PDF("application/pdf", new byte[] {'%', 'P', 'D', 'F'}, "pdf"),
    JPG("image/jpeg", new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}, "jpg", "jpeg"),
    PNG("image/png", new byte[] {(byte) 0x89, 'P', 'N', 'G'}, "png"),
    // xlsx is a zip container
    XLSX("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", new byte[] {'P', 'K', 3, 4}, "xlsx");

    final String contentType;
    private final byte[] magic;
    private final String[] extensions;

    DocumentType(String contentType, byte[] magic, String... extensions) {
        this.contentType = contentType;
        this.magic = magic;
        this.extensions = extensions;
    }

    /** null when the file is not an allowed type (or its content does not match its extension). */
    static DocumentType detect(String fileName, byte[] head) {
        if (fileName == null || fileName.lastIndexOf('.') < 0) {
            return null;
        }
        String ext = fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        for (DocumentType t : values()) {
            if (Arrays.asList(t.extensions).contains(ext)) {
                return head.length >= t.magic.length
                        && Arrays.equals(head, 0, t.magic.length, t.magic, 0, t.magic.length) ? t : null;
            }
        }
        return null;
    }
}
