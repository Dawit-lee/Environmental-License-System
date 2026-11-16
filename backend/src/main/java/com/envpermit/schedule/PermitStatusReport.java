package com.envpermit.schedule;

import static com.envpermit.operation.ComplianceReport.safe;

import com.envpermit.core.permit.PermitItem;
import com.envpermit.core.permit.PermitItem.Status;
import com.envpermit.core.permit.PermitItemRepository;
import com.envpermit.core.project.ProjectAccess;
import com.envpermit.core.project.ProjectRepository;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.PrintSetup;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFRichTextString;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * design-schedule/08 인허가 진행 현황 보고 1쪽: 완료 / 계속 진행 groups of the project's permits as an editable
 * A4-portrait XLSX, built from current values on every request (no snapshot). One project = one sheet; the rows
 * are one project's permits, so an in-memory workbook stays small.
 */
@RestController
class PermitStatusReport {

    static final List<String> HEADERS = List.of("구분", "인허가 항목", "완료 여부", "예상완료 시점", "현안사항");
    static final int MAX_ROWS = 22; // above this the 완료 group folds into one row
    private static final int[] WIDTHS = {8, 22, 25, 15, 30};
    private static final Set<Status> MOVING = Set.of(Status.COMPLETED, Status.IN_PROGRESS, Status.NEAR_EXPIRY, Status.EXPIRED);
    private static final Map<Status, String> LABEL = Map.of(Status.NOT_APPLIED, "미신청", Status.IN_PROGRESS, "진행중",
            Status.COMPLETED, "취득완료", Status.NEAR_EXPIRY, "만료임박", Status.EXPIRED, "만료");
    private static final double MARGIN = 15 / 25.4; // 15 mm in inches
    static final float LINE_POINTS = 13.5f; // one 10 pt line

    private final PermitItemRepository permits;
    private final ProjectRepository projects;
    private final ProjectAccess access;
    private final Clock clock;

    PermitStatusReport(PermitItemRepository permits, ProjectRepository projects, ProjectAccess access, Clock clock) {
        this.permits = permits;
        this.projects = projects;
        this.access = access;
        this.clock = clock;
    }

    /** One table row; {@code authority} goes under the name in small type. */
    record Line(String name, String authority, String progress, String expected, String issue) {
    }

    @GetMapping("/api/projects/{projectId}/permit-status-report.xlsx")
    ResponseEntity<byte[]> download(@AuthenticationPrincipal Jwt jwt, @PathVariable long projectId) {
        access.viewer(projectId, jwt);
        String project = projects.findById(projectId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)).getName();
        LocalDate today = LocalDate.now(clock);
        String fileName = today.format(DateTimeFormatter.ofPattern("yyMMdd")) + " 인허가 진행 현황.xlsx";
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(fileName, StandardCharsets.UTF_8).build().toString())
                .body(xlsx(project, today, permits.findByProjectIdOrderById(projectId)));
    }

    static byte[] xlsx(String project, LocalDate today, List<PermitItem> all) {
        List<PermitItem> done = all.stream().filter(p -> p.getStatus() == Status.COMPLETED)
                .sorted(by(PermitItem::getAcquisitionDate)).toList();
        List<PermitItem> going = all.stream()
                .filter(p -> p.getStatus() != Status.COMPLETED
                        && (MOVING.contains(p.getStatus()) || !blank(p.getProgressNote())))
                .sorted(by(PermitItem::getApplicationDate)).toList();
        List<Line> doneLines = done.stream().map(p -> new Line(p.getName(), p.getAuthority(), "완료", "-", cut(p.getIssueNote(), 90))).toList();
        if (done.size() + going.size() > MAX_ROWS && !done.isEmpty()) {
            String names = String.join(", ", done.stream().map(PermitItem::getName).toList());
            doneLines = List.of(new Line(cut("완료 " + done.size() + "건: " + names, 90), null, "완료", "-", ""));
        }
        List<Line> goingLines = going.stream().map(p -> new Line(p.getName(), p.getAuthority(), progress(p),
                cut(p.getExpectedCompletion(), 20), cut(p.getIssueNote(), 90))).toList();

        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("인허가 진행 현황");
            Font body = font(wb, 10, false);
            Font small = font(wb, 8, false);
            CellStyle cell = style(wb, body, HorizontalAlignment.LEFT, true);
            CellStyle center = style(wb, body, HorizontalAlignment.CENTER, true);
            CellStyle head = style(wb, font(wb, 10, true), HorizontalAlignment.CENTER, true);
            CellStyle title = style(wb, font(wb, 14, true), HorizontalAlignment.CENTER, false);
            CellStyle month = style(wb, body, HorizontalAlignment.RIGHT, false);

            for (int c = 0; c < WIDTHS.length; c++) {
                sheet.setColumnWidth(c, WIDTHS[c] * 256); // fixed: autosizing needs fonts the server image does not have
            }
            text(sheet.createRow(0), 0, safe(project), title);
            text(sheet.createRow(1), 0, "인허가 진행 현황", title);
            sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 4));
            sheet.addMergedRegion(new CellRangeAddress(1, 1, 0, 4));
            text(sheet.createRow(2), 4, today.format(DateTimeFormatter.ofPattern("yyyy. MM.")), month);
            Row header = sheet.createRow(3);
            for (int c = 0; c < HEADERS.size(); c++) {
                text(header, c, HEADERS.get(c), head);
            }
            int r = 4;
            for (var group : List.of(Map.entry("완료", doneLines), Map.entry("계속 진행", goingLines))) {
                int first = r;
                for (Line l : group.getValue()) {
                    Row row = sheet.createRow(r++);
                    text(row, 0, group.getKey(), center);
                    String name = safe(l.name());
                    XSSFRichTextString rich = new XSSFRichTextString(blank(l.authority()) ? name : name + "\n" + safe(l.authority()));
                    rich.applyFont(0, name.length(), body);
                    if (!blank(l.authority())) {
                        rich.applyFont(name.length() + 1, rich.length(), small);
                    }
                    var c1 = row.createCell(1);
                    c1.setCellValue(rich);
                    c1.setCellStyle(cell);
                    text(row, 2, l.progress(), cell);
                    text(row, 3, l.expected(), center);
                    text(row, 4, l.issue(), cell);
                    // Excel does not always re-measure wrapped rows on open: set the height from the wrapped line count
                    int lines = Math.max(Math.max(lines(c1.getStringCellValue(), WIDTHS[1]), lines(l.progress(), WIDTHS[2])),
                            Math.max(lines(l.expected(), WIDTHS[3]), lines(l.issue(), WIDTHS[4])));
                    row.setHeightInPoints(lines * LINE_POINTS + 3);
                }
                if (r - first > 1) {
                    sheet.addMergedRegion(new CellRangeAddress(first, r - 1, 0, 0));
                }
            }

            PrintSetup print = sheet.getPrintSetup();
            print.setPaperSize(PrintSetup.A4_PAPERSIZE);
            print.setLandscape(false);
            print.setFitWidth((short) 1);
            print.setFitHeight((short) 1);
            sheet.setFitToPage(true);
            sheet.setHorizontallyCenter(true);
            for (var m : List.of(Sheet.LeftMargin, Sheet.RightMargin, Sheet.TopMargin, Sheet.BottomMargin)) {
                sheet.setMargin(m, MARGIN);
            }
            sheet.getFooter().setCenter("- &P -");
            wb.setPrintArea(0, 0, 4, 0, Math.max(r - 1, 3));
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 완료 여부 of a moving permit: the note, else the status name plus "(접수 MM/DD)". */
    private static String progress(PermitItem p) {
        if (!blank(p.getProgressNote())) {
            return cut(p.getProgressNote(), 60);
        }
        return LABEL.get(p.getStatus()) + (p.getApplicationDate() == null ? ""
                : " (접수 " + p.getApplicationDate().format(DateTimeFormatter.ofPattern("MM/dd")) + ")");
    }

    /** Date ascending, no date last, then id. */
    private static Comparator<PermitItem> by(java.util.function.Function<PermitItem, LocalDate> date) {
        return Comparator.comparing(date, Comparator.nullsLast(Comparator.naturalOrder())).thenComparing(PermitItem::getId);
    }

    /** User text for a cell: formula-escaped, cut to {@code max} characters with "…". */
    static String cut(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.strip();
        return safe(t.codePointCount(0, t.length()) > max ? t.substring(0, t.offsetByCodePoints(0, max)) + "…" : t);
    }

    /** Wrapped lines of {@code s} in a column {@code width} characters wide; Hangul/CJK count as two. */
    static int lines(String s, int width) {
        int n = 0;
        for (String part : s.split("\n", -1)) {
            int w = part.codePoints().map(c -> c >= 0x1100 ? 2 : 1).sum();
            n += Math.max(1, (w + width - 2) / (width - 1)); // a cell's padding eats about one character
        }
        return n;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static void text(Row row, int c, String value, CellStyle style) {
        var cell = row.createCell(c);
        cell.setCellValue(value);
        cell.setCellStyle(style);
    }

    private static Font font(XSSFWorkbook wb, int points, boolean bold) {
        Font f = wb.createFont();
        f.setFontHeightInPoints((short) points);
        f.setBold(bold);
        return f;
    }

    private static CellStyle style(XSSFWorkbook wb, Font font, HorizontalAlignment align, boolean boxed) {
        CellStyle s = wb.createCellStyle();
        s.setFont(font);
        s.setAlignment(align);
        s.setVerticalAlignment(VerticalAlignment.CENTER);
        if (boxed) {
            s.setWrapText(true);
            s.setBorderTop(BorderStyle.THIN);
            s.setBorderBottom(BorderStyle.THIN);
            s.setBorderLeft(BorderStyle.THIN);
            s.setBorderRight(BorderStyle.THIN);
        }
        return s;
    }
}
