package com.mymedia.metadata;

import com.mymedia.library.LibraryDomain;
import com.mymedia.shared.MetadataFields;
import com.mymedia.shared.MetadataPatch;
import com.mymedia.shared.ScrapeStatus;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class MetadataResolverTest {

    /**
     * 手写替身，不用 Mockito：计划 01 把 Boot 4 的 test starter 拆开引入，
     * Mockito 是否在 classpath 上没有验证过。
     */
    private static final class FakeProvider implements MetadataProvider {

        private final String name;
        private final double score;
        private final boolean available;
        private final RuntimeException failure;
        final List<String> searchCalls = new ArrayList<>();

        FakeProvider(String name, double score) {
            this(name, score, true, null);
        }

        FakeProvider(String name, double score, boolean available, RuntimeException failure) {
            this.name = name;
            this.score = score;
            this.available = available;
            this.failure = failure;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public boolean supports(LibraryDomain domain) {
            return true;
        }

        @Override
        public boolean available() {
            return available;
        }

        @Override
        public List<MetadataCandidate> search(ScrapeSubject subject) {
            searchCalls.add(subject.title());
            if (failure != null) {
                throw failure;
            }
            if (score <= 0) {
                return List.of();
            }
            return List.of(new MetadataCandidate(name, "id-" + name, name + " 的标题",
                    2019, score, "{}"));
        }

        @Override
        public Optional<MetadataPatch> fetch(ScrapeSubject subject, MetadataCandidate candidate) {
            return Optional.of(new MetadataPatch(name, candidate.externalId(),
                    Map.of(MetadataFields.TITLE, candidate.title()), Map.of(), "{}"));
        }
    }

    private static final MetadataProperties PROPERTIES = new MetadataProperties(
            null, Duration.ZERO, 0.8, 0.4, null, null);

    private static final ScrapeSubject SUBJECT = new ScrapeSubject(
            LibraryDomain.VIDEO, 1L, 1L, "沙漠风暴", 2019, null);

    private static MetadataResolver resolverWith(MetadataProvider... providers) {
        List<MetadataProvider> all = new ArrayList<>(List.of(providers));
        all.add(new FakeProvider(FilenameProvider.NAME, 1.0));
        return new MetadataResolver(all, PROPERTIES);
    }

    @Test
    void highScoreIsAppliedAutomatically() {
        ResolutionResult result = resolverWith(new FakeProvider("TMDB", 0.95))
                .resolve(SUBJECT, List.of("TMDB"));

        assertThat(result.status()).isEqualTo(ScrapeStatus.MATCHED);
        assertThat(result.patch().source()).isEqualTo("TMDB");
        assertThat(result.candidates()).isEmpty();
    }

    @Test
    void localNfoRunsFirstEvenWhenItIsNotInTheConfiguredList() {
        FakeProvider local = new FakeProvider(LocalNfoProvider.NAME, 1.0);
        FakeProvider tmdb = new FakeProvider("TMDB", 0.95);

        ResolutionResult result = resolverWith(local, tmdb).resolve(SUBJECT, List.of("TMDB"));

        // 本地文件优先于任何刮削（spec 7.2 规则 2），命中即停
        assertThat(result.patch().source()).isEqualTo(LocalNfoProvider.NAME);
        assertThat(tmdb.searchCalls).isEmpty();
    }

    @Test
    void configuredOrderDecidesWhichScraperGoesFirst() {
        FakeProvider tmdb = new FakeProvider("TMDB", 0.9);
        FakeProvider bangumi = new FakeProvider("Bangumi", 0.9);

        ResolutionResult result = resolverWith(tmdb, bangumi)
                .resolve(SUBJECT, List.of("Bangumi", "TMDB"));

        assertThat(result.patch().source()).isEqualTo("Bangumi");
        assertThat(tmdb.searchCalls).isEmpty();
    }

    @Test
    void mediumScoreGoesToTheReviewQueueAndWritesNothing() {
        ResolutionResult result = resolverWith(new FakeProvider("TMDB", 0.6))
                .resolve(SUBJECT, List.of("TMDB"));

        // 绝不在低置信度下强行写入（spec 7.2 规则 4）
        assertThat(result.status()).isEqualTo(ScrapeStatus.NEEDS_REVIEW);
        assertThat(result.patch()).isNull();
        assertThat(result.candidates()).hasSize(1);
    }

    @Test
    void lowScoreIsDiscardedRatherThanQueuedForReview() {
        ResolutionResult result = resolverWith(new FakeProvider("TMDB", 0.2))
                .resolve(SUBJECT, List.of("TMDB"));

        assertThat(result.status()).isEqualTo(ScrapeStatus.NO_MATCH);
        assertThat(result.candidates()).isEmpty();
    }

    @Test
    void noCandidatesFallsBackToFilenameQuietly() {
        ResolutionResult result = resolverWith(new FakeProvider("TMDB", 0.0))
                .resolve(SUBJECT, List.of("TMDB"));

        // 找不到是正常状态，不是错误
        assertThat(result.status()).isEqualTo(ScrapeStatus.NO_MATCH);
        assertThat(result.patch().source()).isEqualTo(FilenameProvider.NAME);
    }

    @Test
    void unavailableProviderIsSkippedWithoutBeingAnError() {
        FakeProvider tmdb = new FakeProvider("TMDB", 0.95, false, null);

        ResolutionResult result = resolverWith(tmdb).resolve(SUBJECT, List.of("TMDB"));

        assertThat(tmdb.searchCalls).isEmpty();
        assertThat(result.status()).isEqualTo(ScrapeStatus.NO_MATCH);
    }

    @Test
    void networkFailureWithNoOtherResultBecomesErrorSoTheJobRetries() {
        ResolutionResult result = resolverWith(new FakeProvider(
                "TMDB", 0.95, true, new ProviderUnavailableException("连接超时")))
                .resolve(SUBJECT, List.of("TMDB"));

        assertThat(result.status()).isEqualTo(ScrapeStatus.ERROR);
    }

    @Test
    void oneFlakyProviderDoesNotBlockAWorkingOne() {
        FakeProvider flaky = new FakeProvider(
                "TMDB", 0.95, true, new ProviderUnavailableException("429"));
        FakeProvider working = new FakeProvider("Bangumi", 0.9);

        ResolutionResult result = resolverWith(flaky, working)
                .resolve(SUBJECT, List.of("TMDB", "Bangumi"));

        assertThat(result.status()).isEqualTo(ScrapeStatus.MATCHED);
        assertThat(result.patch().source()).isEqualTo("Bangumi");
    }

    @Test
    void unknownProviderNameInConfigIsIgnored() {
        ResolutionResult result = resolverWith(new FakeProvider("TMDB", 0.95))
                .resolve(SUBJECT, List.of("拼错了的名字"));

        assertThat(result.status()).isEqualTo(ScrapeStatus.NO_MATCH);
    }
}
