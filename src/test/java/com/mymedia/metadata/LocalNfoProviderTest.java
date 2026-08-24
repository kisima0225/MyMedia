package com.mymedia.metadata;

import com.mymedia.library.LibraryDomain;
import com.mymedia.shared.MetadataFields;
import com.mymedia.shared.MetadataPatch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class LocalNfoProviderTest {

    private final LocalNfoProvider provider = new LocalNfoProvider();

    @TempDir
    Path dir;

    private static final String NFO = """
            <movie><title>大雄兔</title><plot>一只巨兔。</plot><premiered>2008-05-20</premiered></movie>
            """;

    private ScrapeSubject subjectFor(Path primaryPath) {
        return new ScrapeSubject(LibraryDomain.VIDEO, 1L, 1L, "文件名标题", null, primaryPath);
    }

    private Path writeVideo(String name) throws IOException {
        Path video = dir.resolve(name);
        Files.write(video, new byte[16]);
        return video;
    }

    @Test
    void findsSiblingNfoNamedAfterTheVideoFile() throws IOException {
        Path video = writeVideo("大雄兔.mp4");
        Files.writeString(dir.resolve("大雄兔.nfo"), NFO, StandardCharsets.UTF_8);

        List<MetadataCandidate> candidates = provider.search(subjectFor(video));

        assertThat(candidates).hasSize(1);
        // 本地文件是用户自己写的，没有"可能不对"这回事
        assertThat(candidates.get(0).score()).isEqualTo(1.0);
        assertThat(candidates.get(0).provider()).isEqualTo(LocalNfoProvider.NAME);
    }

    @Test
    void fallsBackToMovieNfoInTheSameDirectory() throws IOException {
        Path video = writeVideo("VIDEO_TS.mp4");
        Files.writeString(dir.resolve("movie.nfo"), NFO, StandardCharsets.UTF_8);

        assertThat(provider.search(subjectFor(video))).hasSize(1);
    }

    @Test
    void fetchesFieldsAndKeepsTheRawFile() throws IOException {
        Path video = writeVideo("大雄兔.mp4");
        Files.writeString(dir.resolve("大雄兔.nfo"), NFO, StandardCharsets.UTF_8);
        ScrapeSubject subject = subjectFor(video);

        Optional<MetadataPatch> patch = provider.fetch(subject, provider.search(subject).get(0));

        assertThat(patch).isPresent();
        assertThat(patch.get().source()).isEqualTo(LocalNfoProvider.NAME);
        assertThat(patch.get().fields())
                .containsEntry(MetadataFields.TITLE, "大雄兔")
                .containsEntry(MetadataFields.RELEASE_DATE, "2008-05-20");
        assertThat(patch.get().rawResponse()).contains("<movie>");
    }

    @Test
    void readsMetadataJsonWhenNoNfoIsPresent() throws IOException {
        Path video = writeVideo("家庭录像.mp4");
        Files.writeString(dir.resolve("metadata.json"),
                "{\"title\":\"家庭录像 2024\"}", StandardCharsets.UTF_8);
        ScrapeSubject subject = subjectFor(video);

        Optional<MetadataPatch> patch = provider.fetch(subject, provider.search(subject).get(0));

        assertThat(patch.orElseThrow().fields())
                .containsEntry(MetadataFields.TITLE, "家庭录像 2024");
    }

    @Test
    void looksInsideTheDirectoryWhenTheSubjectIsADirectory() throws IOException {
        Path book = Files.createDirectory(dir.resolve("某画集"));
        Files.writeString(book.resolve("metadata.json"),
                "{\"title\":\"某画集\"}", StandardCharsets.UTF_8);

        ScrapeSubject subject = new ScrapeSubject(
                LibraryDomain.IMAGE, 2L, 1L, "某画集", null, book);

        assertThat(provider.search(subject)).hasSize(1);
    }

    @Test
    void returnsNoCandidateWhenThereIsNoLocalFile() throws IOException {
        assertThat(provider.search(subjectFor(writeVideo("孤零零.mp4")))).isEmpty();
    }

    @Test
    void returnsNoCandidateWhenThePathIsUnknown() {
        assertThat(provider.search(new ScrapeSubject(
                LibraryDomain.VIDEO, 1L, 1L, "标题", null, null))).isEmpty();
    }

    @Test
    void supportsBothDomainsAndIsAlwaysAvailable() {
        assertThat(provider.supports(LibraryDomain.VIDEO)).isTrue();
        assertThat(provider.supports(LibraryDomain.IMAGE)).isTrue();
        assertThat(provider.available()).isTrue();
    }

    @Test
    void aBrokenNfoIsReportedAsNoResultRatherThanCrashingTheChain() throws IOException {
        Path video = writeVideo("坏文件.mp4");
        Files.writeString(dir.resolve("坏文件.nfo"), "<movie><title>没闭合");
        ScrapeSubject subject = subjectFor(video);

        // search 仍然报告"这里有个文件"，fetch 时才发现读不了——
        // 此时返回空而不是抛异常：一个坏 NFO 不该让整条链失败
        assertThat(provider.fetch(subject, provider.search(subject).get(0))).isEmpty();
    }
}
