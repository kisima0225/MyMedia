package com.mymedia.scan;

import com.mymedia.scan.spi.MediaKind;
import com.mymedia.scan.spi.MediaTypeResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class MediaExtensionsTest {

    @ParameterizedTest
    @CsvSource({
            "黑客帝国.mkv,     VIDEO",
            "movie.MP4,        VIDEO",
            "ep01.avi,         VIDEO",
            "clip.webm,        VIDEO",
            "page001.jpg,      IMAGE",
            "page001.JPEG,     IMAGE",
            "cover.png,        IMAGE",
            "sticker.gif,      IMAGE",
            "photo.webp,       IMAGE",
            "modern.avif,      IMAGE",
            "vol01.cbz,        ARCHIVE",
            "vol01.zip,        ARCHIVE",
    })
    void classifiesKnownExtensions(String fileName, MediaKind expected) {
        assertThat(MediaExtensions.classify(fileName)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({
            "movie.nfo",
            "movie.srt",
            "movie.ass",
            "readme.txt",
            "desktop.ini",
            ".DS_Store",
            "no-extension",
            "archive.rar",
    })
    void ignoresNonMediaFiles(String fileName) {
        assertThat(MediaExtensions.classify(fileName)).isEqualTo(MediaKind.IGNORED);
    }

    @Test
    void extensionIsLowercasedAndStripped() {
        assertThat(MediaExtensions.extensionOf("MOVIE.MKV")).isEqualTo("mkv");
        assertThat(MediaExtensions.extensionOf("a.b.c.mp4")).isEqualTo("mp4");
        assertThat(MediaExtensions.extensionOf("noext")).isEmpty();
        assertThat(MediaExtensions.extensionOf(".hidden")).isEmpty();
    }

    @Test
    void classificationIsCaseInsensitive() {
        assertThat(MediaExtensions.classify("A.MkV")).isEqualTo(MediaKind.VIDEO);
        assertThat(MediaExtensions.classify("B.JpG")).isEqualTo(MediaKind.IMAGE);
    }

    @Test
    void resolverClassifiesUnknownExtensionAfterBuiltIns() {
        MediaTypeResolver resolver = extension -> extension.equals("flac")
                ? Optional.of(MediaKind.AUDIO)
                : Optional.empty();

        assertThat(MediaExtensions.classify("track.flac", List.of(resolver)))
                .isEqualTo(MediaKind.AUDIO);
        assertThat(MediaExtensions.classify("movie.mkv", List.of(resolver)))
                .isEqualTo(MediaKind.VIDEO);
        assertThat(MediaExtensions.classify("movie.nfo", List.of(resolver)))
                .isEqualTo(MediaKind.IGNORED);
    }
}
