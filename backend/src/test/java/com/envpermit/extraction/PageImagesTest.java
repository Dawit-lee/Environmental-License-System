package com.envpermit.extraction;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;

/** M5: normal pages render at 110 dpi; a huge page gets a lower dpi so one image stays bounded. */
class PageImagesTest {

    @Test
    void a4_keeps_the_normal_dpi() {
        assertThat(PageImages.dpi(PDRectangle.A4)).isEqualTo(PageImages.DPI);
    }

    @Test
    void a_huge_page_is_scaled_down_to_the_pixel_cap() {
        PDRectangle huge = new PDRectangle(14_400, 14_400); // 200 x 200 inches
        float dpi = PageImages.dpi(huge);
        double pixels = Math.pow(200 * dpi, 2);
        assertThat(dpi).isLessThan(PageImages.DPI);
        assertThat(pixels).isLessThanOrEqualTo(PageImages.MAX_PIXELS * 1.001);
    }
}
