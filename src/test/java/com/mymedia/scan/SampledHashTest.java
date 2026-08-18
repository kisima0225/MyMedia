package com.mymedia.scan;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class SampledHashTest {

    @TempDir
    Path tempDir;

    private Path writeFile(String name, byte[] content) throws IOException {
        Path file = tempDir.resolve(name);
        Files.write(file, content);
        return file;
    }

    private byte[] randomBytes(int length, long seed) {
        byte[] bytes = new byte[length];
        new Random(seed).nextBytes(bytes);
        return bytes;
    }

    @Test
    void identicalContentProducesIdenticalHash() throws IOException {
        byte[] content = randomBytes(5000, 1L);
        Path a = writeFile("a.bin", content);
        Path b = writeFile("b.bin", content);

        assertThat(SampledHash.of(a, content.length))
                .isEqualTo(SampledHash.of(b, content.length));
    }

    @Test
    void differentContentProducesDifferentHash() throws IOException {
        Path a = writeFile("a.bin", randomBytes(5000, 1L));
        Path b = writeFile("b.bin", randomBytes(5000, 2L));

        assertThat(SampledHash.of(a, 5000))
                .isNotEqualTo(SampledHash.of(b, 5000));
    }

    @Test
    void sameSizeButDifferentMiddleIsStillDistinguishedForSmallFiles() throws IOException {
        // 小于两倍采样窗口的文件应全量哈希，因此中段差异也能分辨
        byte[] a = new byte[1000];
        byte[] b = new byte[1000];
        b[500] = 1;
        Path fa = writeFile("a.bin", a);
        Path fb = writeFile("b.bin", b);

        assertThat(SampledHash.of(fa, 1000)).isNotEqualTo(SampledHash.of(fb, 1000));
    }

    @Test
    void sizeIsPartOfTheHash() throws IOException {
        // 内容前缀相同但长度不同的两个文件必须得到不同哈希
        Path a = writeFile("a.bin", new byte[1000]);
        Path b = writeFile("b.bin", new byte[2000]);

        assertThat(SampledHash.of(a, 1000)).isNotEqualTo(SampledHash.of(b, 2000));
    }

    @Test
    void emptyFileHashesWithoutError() throws IOException {
        Path empty = writeFile("empty.bin", new byte[0]);

        assertThat(SampledHash.of(empty, 0)).hasSize(64);
    }

    @Test
    void largeFileOnlyReadsHeadAndTail() throws IOException {
        // 构造一个超过采样阈值的文件，中段不同但首尾相同 —— 采样哈希会认为它们相同。
        // 这是刻意接受的取舍：换取不必读完 20GB 文件。
        int size = 3 * 1024 * 1024;
        byte[] a = randomBytes(size, 7L);
        byte[] b = a.clone();
        b[size / 2] = (byte) (b[size / 2] ^ 0xFF);

        Path fa = writeFile("big-a.bin", a);
        Path fb = writeFile("big-b.bin", b);

        assertThat(SampledHash.of(fa, size)).isEqualTo(SampledHash.of(fb, size));
    }

    @Test
    void hashIsLowercaseHexOf64Chars() throws IOException {
        Path file = writeFile("x.bin", randomBytes(100, 3L));

        assertThat(SampledHash.of(file, 100)).matches("[0-9a-f]{64}");
    }
}
