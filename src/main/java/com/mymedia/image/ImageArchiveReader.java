package com.mymedia.image;

import com.mymedia.shared.NaturalSortKey;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.FileNotFoundException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 压缩包（CBZ / ZIP）的随机访问读取。
 *
 * <p><b>绝不解压到磁盘。</b>ZIP 的中央目录区记录了每个条目的偏移量，
 * 可以直接定位并解压单个条目。
 *
 * <p>用 Commons Compress 而非 JDK 自带的 {@code java.util.zip.ZipFile}，
 * 理由是条目名编码：JDK 版本要求整个归档用同一种编码，遇到解不开的字节会抛异常；
 * Commons Compress 逐条目检查 ZIP 通用位标记第 11 位——打了标记的按 UTF-8 解，
 * 没打的才用构造时传入的<b>回退</b>编码。中文归档里两种条目混在一起是常态。
 */
@Component
class ImageArchiveReader {

    private static final Set<String> IMAGE_EXTENSIONS = Set.of(
            "jpg", "jpeg", "png", "gif", "webp", "avif", "bmp", "tiff", "tif");

    private final Charset fallbackCharset;

    ImageArchiveReader(@Value("${mymedia.image.archive-charset:GBK}") Charset fallbackCharset) {
        this.fallbackCharset = fallbackCharset;
    }

    /** 归档内的图片条目，已过滤、已按自然序排序。 */
    List<ArchivePage> listPages(Path archive) throws IOException {
        List<ArchivePage> pages = new ArrayList<>();
        try (ZipFile zip = open(archive)) {
            Enumeration<ZipArchiveEntry> entries = zip.getEntries();
            while (entries.hasMoreElements()) {
                ZipArchiveEntry entry = entries.nextElement();
                if (isPage(entry)) {
                    pages.add(new ArchivePage(entry.getName(), entry.getSize()));
                }
            }
        }
        // 归档内条目的物理顺序是打包顺序，不保证有意义；页序必须自己定，
        // 且必须是自然序 —— 字典序会把 1, 2, 10 排成 1, 10, 2。
        pages.sort(Comparator.comparing(page -> NaturalSortKey.of(page.entryName())));
        return pages;
    }

    /**
     * 打开单个条目。
     *
     * <p><b>返回的流关闭时会一并关闭压缩包</b>——否则文件句柄泄漏，
     * 在 Windows 上还会导致该压缩包无法被删除或改名。
     */
    InputStream openEntry(Path archive, String entryName) throws IOException {
        ZipFile zip = open(archive);
        try {
            ZipArchiveEntry entry = zip.getEntry(entryName);
            if (entry == null) {
                throw new FileNotFoundException("压缩包内没有条目: " + entryName + " @ " + archive);
            }
            InputStream delegate = zip.getInputStream(entry);
            return new FilterInputStream(delegate) {
                @Override
                public void close() throws IOException {
                    try {
                        super.close();
                    } finally {
                        zip.close();
                    }
                }
            };
        } catch (IOException | RuntimeException e) {
            // 还没把 zip 的所有权交给调用方就出错了，这里必须自己收尾
            zip.close();
            throw e;
        }
    }

    private ZipFile open(Path archive) throws IOException {
        return ZipFile.builder()
                .setPath(archive)
                .setCharset(fallbackCharset)
                .get();
    }

    private static boolean isPage(ZipArchiveEntry entry) {
        if (entry.isDirectory()) {
            return false;
        }
        String name = entry.getName();
        int lastSlash = name.lastIndexOf('/');
        String fileName = lastSlash < 0 ? name : name.substring(lastSlash + 1);

        // macOS 打包会塞进 __MACOSX/._xxx 资源分叉，Windows 会塞 Thumbs.db，
        // 前者甚至能通过扩展名检查，却不是页。
        if (name.startsWith("__MACOSX/") || fileName.startsWith(".")
                || fileName.equalsIgnoreCase("Thumbs.db")) {
            return false;
        }
        int dot = fileName.lastIndexOf('.');
        if (dot <= 0 || dot == fileName.length() - 1) {
            return false;
        }
        return IMAGE_EXTENSIONS.contains(fileName.substring(dot + 1).toLowerCase(Locale.ROOT));
    }
}