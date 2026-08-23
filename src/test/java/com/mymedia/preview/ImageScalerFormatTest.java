package com.mymedia.preview;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;

import static org.assertj.core.api.Assertions.assertThat;

/** JDK ImageIO 的预览解码边界。 */
class ImageScalerFormatTest {

    @Test
    void currentJdkImageIoBoundaryDoesNotClaimWebpOrAvifPreviewSupport() {
        assertThat(ImageIO.getImageReadersByFormatName("png").hasNext()).isTrue();
        assertThat(ImageIO.getImageReadersByFormatName("jpeg").hasNext()).isTrue();
        assertThat(ImageIO.getImageReadersByFormatName("webp").hasNext()).isFalse();
        assertThat(ImageIO.getImageReadersByFormatName("avif").hasNext()).isFalse();
    }
}
