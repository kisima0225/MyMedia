package com.mymedia.scan;

import com.mymedia.scan.spi.MediaKind;
import com.mymedia.scan.spi.MediaTypeResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 遍历媒体库目录，产出媒体文件清单。
 *
 * <p>跟随符号链接，因此使用每个已访问目录的真实路径做全局环检测。
 */
class DirectoryWalker {

    private static final Logger log = LoggerFactory.getLogger(DirectoryWalker.class);

    private final int maxDepth;
    private final List<MediaTypeResolver> resolvers;

    DirectoryWalker(int maxDepth) {
        this(maxDepth, List.of());
    }

    DirectoryWalker(int maxDepth, List<MediaTypeResolver> resolvers) {
        this.maxDepth = maxDepth;
        this.resolvers = List.copyOf(resolvers);
    }

    List<ScannedEntry> walk(Path root) throws IOException {
        List<ScannedEntry> entries = new ArrayList<>();
        Set<Path> visitedRealPaths = new HashSet<>();
        Path normalizedRoot = root.toRealPath();

        Files.walkFileTree(normalizedRoot, Set.of(FileVisitOption.FOLLOW_LINKS), maxDepth,
                new SimpleFileVisitor<>() {

                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        Path real;
                        try {
                            real = dir.toRealPath();
                        } catch (IOException e) {
                            log.warn("无法解析目录真实路径，跳过: {}", dir, e);
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        if (!visitedRealPaths.add(real)) {
                            log.warn("检测到目录环，剪枝: {} -> {}", dir, real);
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (!attrs.isRegularFile()) {
                            return FileVisitResult.CONTINUE;
                        }
                        String fileName = file.getFileName().toString();
                        MediaKind kind = MediaExtensions.classify(fileName, resolvers);
                        if (kind == MediaKind.IGNORED) {
                            return FileVisitResult.CONTINUE;
                        }
                        String relative = toPortablePath(normalizedRoot.relativize(file));
                        entries.add(new ScannedEntry(
                                relative,
                                attrs.size(),
                                attrs.lastModifiedTime().toInstant(),
                                MediaExtensions.extensionOf(fileName),
                                kind));
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException e) {
                        // 单个文件不可读不应中断整次扫描
                        log.warn("无法访问，跳过: {} ({})", file, e.getMessage());
                        return FileVisitResult.CONTINUE;
                    }
                });

        return entries;
    }

    /** 把平台相关的路径分隔符统一成正斜杠。 */
    private static String toPortablePath(Path relative) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < relative.getNameCount(); i++) {
            if (i > 0) {
                sb.append('/');
            }
            sb.append(relative.getName(i));
        }
        return sb.toString();
    }
}
