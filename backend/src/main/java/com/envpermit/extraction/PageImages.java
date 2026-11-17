package com.envpermit.extraction;

import com.envpermit.core.document.DocumentStorage;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * PNG of one PDF page for the review screen (M5). Rendered pages are kept in a small LRU (stored documents never
 * change under a key), the PDF's streams are buffered in a temp file, and a huge page is rendered at a lower DPI
 * so one image stays under {@link #MAX_PIXELS}.
 */
@Component
class PageImages {

    static final int DPI = 110;
    static final long MAX_PIXELS = 6_000_000; // ~A2 at 110 dpi; 4 bytes each while rendering
    private static final long CACHE_BYTES = 32L * 1024 * 1024;

    private final DocumentStorage storage;
    private final Map<String, byte[]> cache = new LinkedHashMap<>(16, 0.75f, true);
    private long cachedBytes;

    PageImages(DocumentStorage storage) {
        this.storage = storage;
    }

    byte[] render(String storageKey, int pageNo) {
        String key = storageKey + "#" + pageNo;
        synchronized (this) {
            byte[] hit = cache.get(key);
            if (hit != null) return hit;
        }
        byte[] png = draw(storageKey, pageNo);
        put(key, png);
        return png;
    }

    private byte[] draw(String storageKey, int pageNo) {
        try (InputStream in = storage.open(storageKey);
             PDDocument pdf = Loader.loadPDF(new RandomAccessReadBuffer(in), null, null, null,
                     IOUtils.createTempFileOnlyStreamCache());
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (pageNo < 1 || pageNo > pdf.getNumberOfPages()) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "페이지가 없습니다.");
            }
            BufferedImage image = new PDFRenderer(pdf).renderImageWithDPI(pageNo - 1, dpi(pdf.getPage(pageNo - 1).getCropBox()));
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "PDF를 읽을 수 없습니다.");
        }
    }

    /** {@link #DPI}, lowered so width × height stays under {@link #MAX_PIXELS}. */
    static float dpi(PDRectangle box) {
        double pixels = (box.getWidth() / 72.0 * DPI) * (box.getHeight() / 72.0 * DPI);
        return pixels <= MAX_PIXELS ? DPI : (float) (DPI * Math.sqrt(MAX_PIXELS / pixels));
    }

    private synchronized void put(String key, byte[] png) {
        if (png.length > CACHE_BYTES / 4 || cache.containsKey(key)) return;
        cache.put(key, png);
        cachedBytes += png.length;
        for (Iterator<byte[]> it = cache.values().iterator(); cachedBytes > CACHE_BYTES && it.hasNext(); ) {
            cachedBytes -= it.next().length;
            it.remove();
        }
    }
}
