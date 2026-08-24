package com.mymedia.metadata;

import com.mymedia.library.LibraryDomain;
import com.mymedia.shared.MetadataPatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 读同目录的 {@code .nfo}（Kodi / Jellyfin 标准）或 {@code metadata.json}。
 *
 * <p><b>它同时解决了演示数据的难题</b>：seed 数据全部靠本地文件提供元数据，
 * {@code docker compose up} 之后不需要任何 API key 就能看到完整的库
 * （spec 7.2 规则 6）。
 *
 * <p>本地文件是用户自己写的，没有"可能不对"这回事，因此 {@code score} 恒为 1.0，
 * 命中即被链自动应用。
 */
@Component
class LocalNfoProvider implements MetadataProvider {

    static final String NAME = "LocalNfo";

    private static final Logger log = LoggerFactory.getLogger(LocalNfoProvider.class);

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean supports(LibraryDomain domain) {
        return true;
    }

    @Override
    public List<MetadataCandidate> search(ScrapeSubject subject) {
        return locate(subject)
                .map(file -> List.of(new MetadataCandidate(
                        NAME, file.getFileName().toString(), subject.title(),
                        subject.year(), 1.0, file.toString())))
                .orElseGet(List::of);
    }

    @Override
    public Optional<MetadataPatch> fetch(ScrapeSubject subject, MetadataCandidate candidate) {
        Optional<Path> file = locate(subject);
        if (file.isEmpty()) {
            return Optional.empty();
        }
        String content;
        try {
            content = Files.readString(file.get(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("本地元数据文件读取失败 {}", file.get(), e);
            return Optional.empty();
        }

        ParsedNfo parsed;
        try {
            parsed = file.get().getFileName().toString().endsWith(".json")
                    ? NfoParser.parseJson(content)
                    : NfoParser.parseXml(content);
        } catch (IllegalArgumentException e) {
            // 一个写坏的 .nfo 不该让整条链失败，安静跳过让后面的提供者接手
            log.warn("本地元数据文件格式有误，已跳过 {}：{}", file.get(), e.getMessage());
            return Optional.empty();
        }

        if (parsed.fields().isEmpty() && parsed.extras().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new MetadataPatch(NAME, file.get().getFileName().toString(),
                parsed.fields(), parsed.extras(), content));
    }

    /**
     * 按固定顺序找本地文件。
     *
     * <p>目标是文件时（视频）：先找与它同名的 {@code .nfo}，再找目录级的
     * {@code movie.nfo} / {@code tvshow.nfo} / {@code metadata.json}。
     * 目标是目录或压缩包时（图片）：在目录里找同一批名字。
     */
    private Optional<Path> locate(ScrapeSubject subject) {
        Path primary = subject.primaryPath();
        if (primary == null) {
            return Optional.empty();
        }

        boolean isDirectory = Files.isDirectory(primary);
        Path directory = isDirectory ? primary : primary.getParent();
        if (directory == null) {
            return Optional.empty();
        }

        List<Path> candidates = new ArrayList<>();
        String baseName = stripExtension(primary.getFileName().toString());
        candidates.add(directory.resolve(baseName + ".nfo"));
        candidates.add(directory.resolve("movie.nfo"));
        candidates.add(directory.resolve("tvshow.nfo"));
        candidates.add(directory.resolve("metadata.json"));

        return candidates.stream().filter(Files::isReadable).findFirst();
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot <= 0 ? fileName : fileName.substring(0, dot);
    }
}
