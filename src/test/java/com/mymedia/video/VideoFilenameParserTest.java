package com.mymedia.video;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VideoFilenameParserTest {

    @Test
    void parsesStandardSeasonEpisodePattern() {
        ParsedVideoName parsed = VideoFilenameParser.parse("进击的巨人/S01/S01E05.mkv");

        assertThat(parsed.season()).isEqualTo(1);
        assertThat(parsed.episode()).isEqualTo(5);
    }

    @Test
    void parsesLowercaseSeasonEpisode() {
        ParsedVideoName parsed = VideoFilenameParser.parse("show/s2e13.mp4");

        assertThat(parsed.season()).isEqualTo(2);
        assertThat(parsed.episode()).isEqualTo(13);
    }

    @Test
    void parsesSeasonFromDirectoryAndEpisodeFromFile() {
        ParsedVideoName parsed = VideoFilenameParser.parse("进击的巨人/Season 3/E07.mkv");

        assertThat(parsed.season()).isEqualTo(3);
        assertThat(parsed.episode()).isEqualTo(7);
    }

    @Test
    void parsesChineseSeasonDirectory() {
        ParsedVideoName parsed = VideoFilenameParser.parse("某番剧/第2季/第11话.mkv");

        assertThat(parsed.season()).isEqualTo(2);
        assertThat(parsed.episode()).isEqualTo(11);
    }

    @Test
    void parsesBracketedEpisodeNumber() {
        // 字幕组常用格式
        ParsedVideoName parsed = VideoFilenameParser.parse("[字幕组] 某番 [08][1080p].mkv");

        assertThat(parsed.episode()).isEqualTo(8);
    }

    @Test
    void parsesMovieYear() {
        ParsedVideoName parsed = VideoFilenameParser.parse("电影/黑客帝国 (1999).mkv");

        assertThat(parsed.title()).isEqualTo("黑客帝国");
        assertThat(parsed.year()).isEqualTo(1999);
        assertThat(parsed.season()).isNull();
        assertThat(parsed.episode()).isNull();
    }

    @Test
    void parsesDotSeparatedMovieName() {
        ParsedVideoName parsed = VideoFilenameParser.parse("The.Matrix.1999.1080p.BluRay.mkv");

        assertThat(parsed.title()).isEqualTo("The Matrix");
        assertThat(parsed.year()).isEqualTo(1999);
        assertThat(parsed.quality()).isEqualTo("1080p");
    }

    @Test
    void stripsReleaseGroupTags() {
        ParsedVideoName parsed = VideoFilenameParser.parse("[SubGroup] 作品名 [1080p][BDRip].mkv");

        assertThat(parsed.title()).isEqualTo("作品名");
    }

    @Test
    void titleFallsBackToFilenameWhenNothingMatches() {
        // 自制内容、录屏等，没有任何可识别模式。绝不能因此报错或返回空标题。
        ParsedVideoName parsed = VideoFilenameParser.parse("随手录的一段.mkv");

        assertThat(parsed.title()).isEqualTo("随手录的一段");
        assertThat(parsed.season()).isNull();
        assertThat(parsed.episode()).isNull();
        assertThat(parsed.year()).isNull();
    }

    @Test
    void doesNotMistakeResolutionForYear() {
        // 1080 与 2160 不是年份
        ParsedVideoName parsed = VideoFilenameParser.parse("片子.2160p.mkv");

        assertThat(parsed.year()).isNull();
        assertThat(parsed.quality()).isEqualTo("2160p");
    }

    @Test
    void yearMustBePlausible() {
        // 1234 在合法年份范围外，不应被当成年份
        ParsedVideoName parsed = VideoFilenameParser.parse("编号1234的片子.mkv");

        assertThat(parsed.year()).isNull();
    }

    @Test
    void handlesPathWithoutDirectory() {
        ParsedVideoName parsed = VideoFilenameParser.parse("单独一个文件.mp4");

        assertThat(parsed.title()).isEqualTo("单独一个文件");
    }

    @Test
    void episodeZeroIsValid() {
        // 第 0 话（前导集）是真实存在的
        ParsedVideoName parsed = VideoFilenameParser.parse("番/S01E00.mkv");

        assertThat(parsed.episode()).isZero();
    }
}
