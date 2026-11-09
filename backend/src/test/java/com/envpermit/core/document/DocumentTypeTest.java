package com.envpermit.core.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class DocumentTypeTest {

    @ParameterizedTest
    @CsvSource({
            "a.pdf,    25504446,  PDF",
            "A.PDF,    25504446,  PDF",
            "a.jpg,    FFD8FFE0,  JPG",
            "a.jpeg,   FFD8FFE1,  JPG",
            "a.png,    89504E47,  PNG",
            "a.xlsx,   504B0304,  XLSX",
            "a.pdf,    89504E47,  ",     // png bytes with pdf name
            "a.png,    25504446,  ",
            "a.exe,    4D5A9000,  ",
            "a.xls,    D0CF11E0,  ",     // old Excel not allowed
            "noext,    25504446,  ",
            "a.pdf,    2550,      ",     // too short
    })
    void detects_by_extension_and_magic_bytes(String name, String hex, DocumentType expected) {
        byte[] head = java.util.HexFormat.of().parseHex(hex);
        assertThat(DocumentType.detect(name, Arrays.copyOf(head, head.length))).isEqualTo(expected);
    }
}
