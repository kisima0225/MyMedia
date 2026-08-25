package com.mymedia.upload;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SafeFileNameTest {

    @Test
    void keepsAnOrdinaryNameIncludingChineseAndSpaces() {
        assertThat(SafeFileName.of("进击的巨人 第01话.mkv")).isEqualTo("进击的巨人 第01话.mkv");
    }

    @Test
    void stripsAnyDirectoryPartOnBothSeparators() {
        assertThat(SafeFileName.of("../../etc/passwd")).isEqualTo("passwd");
        assertThat(SafeFileName.of("C:\\Windows\\System32\\evil.exe")).isEqualTo("evil.exe");
        assertThat(SafeFileName.of("a/b/c/movie.mp4")).isEqualTo("movie.mp4");
    }

    @Test
    void rejectsNamesThatAreNothingButDots() {
        assertThatThrownBy(() -> SafeFileName.of(".."))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SafeFileName.of("."))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void removesControlCharacters() {
        assertThat(SafeFileName.of("mo\u0000vie\u0007.mp4")).isEqualTo("movie.mp4");
    }

    @Test
    void removesCharactersWindowsRefuses() {
        assertThat(SafeFileName.of("a<b>c:d\"e|f?g*h.mp4")).isEqualTo("abcdefgh.mp4");
    }

    @Test
    void trimsTrailingDotsAndSpacesWhichWindowsSilentlyEats() {
        // 不处理的话「写进去的名字」和「读出来的名字」对不上，扫描会当成两个文件
        assertThat(SafeFileName.of("movie.mp4. . ")).isEqualTo("movie.mp4");
        assertThat(SafeFileName.of("  movie.mp4")).isEqualTo("movie.mp4");
    }

    @Test
    void truncatesLongNamesButKeepsTheExtension() {
        String name = "长".repeat(400) + ".mkv";

        String safe = SafeFileName.of(name);

        assertThat(safe).hasSize(200).endsWith(".mkv");
    }

    @Test
    void rejectsInputThatSanitisesDownToNothing() {
        assertThatThrownBy(() -> SafeFileName.of("<<<>>>"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SafeFileName.of("   "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SafeFileName.of(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aNameWithoutAnExtensionSurvives() {
        assertThat(SafeFileName.of("README")).isEqualTo("README");
    }
}
