package com.mymedia.metadata;

import com.mymedia.shared.MetadataFields;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NfoParserTest {

    private static final String KODI_MOVIE_NFO = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <movie>
              <title>大雄兔</title>
              <originaltitle>Big Buck Bunny</originaltitle>
              <plot>一只巨兔与三个坏蛋的故事。</plot>
              <premiered>2008-05-20</premiered>
              <rating>7.9</rating>
              <director>Sacha Goedegebure</director>
              <studio>Blender Foundation</studio>
              <genre>动画</genre>
              <genre>喜剧</genre>
            </movie>
            """;

    @Test
    void readsStandardFieldsFromAKodiMovieNfo() {
        ParsedNfo parsed = NfoParser.parseXml(KODI_MOVIE_NFO);

        assertThat(parsed.fields())
                .containsEntry(MetadataFields.TITLE, "大雄兔")
                .containsEntry(MetadataFields.ORIGINAL_TITLE, "Big Buck Bunny")
                .containsEntry(MetadataFields.SUMMARY, "一只巨兔与三个坏蛋的故事。")
                .containsEntry(MetadataFields.RELEASE_DATE, "2008-05-20")
                .containsEntry(MetadataFields.RATING, "7.9");
    }

    @Test
    void putsTypeSpecificTagsIntoExtras() {
        ParsedNfo parsed = NfoParser.parseXml(KODI_MOVIE_NFO);

        assertThat(parsed.extras())
                .containsEntry("director", "Sacha Goedegebure")
                .containsEntry("studio", "Blender Foundation")
                .containsEntry("genres", "动画, 喜剧");
    }

    @Test
    void acceptsTvshowRootAsWellAsMovie() {
        ParsedNfo parsed = NfoParser.parseXml("""
                <tvshow><title>某番剧</title><plot>简介</plot></tvshow>
                """);

        assertThat(parsed.fields()).containsEntry(MetadataFields.TITLE, "某番剧");
    }

    @Test
    void fallsBackFromPremieredToYear() {
        ParsedNfo parsed = NfoParser.parseXml("""
                <movie><title>老片</title><year>1998</year></movie>
                """);

        // 只有年份时补成当年 1 月 1 日，让 release_date 列有个能排序的值
        assertThat(parsed.fields()).containsEntry(MetadataFields.RELEASE_DATE, "1998-01-01");
    }

    @Test
    void ignoresEmptyTagsInsteadOfWritingBlanks() {
        ParsedNfo parsed = NfoParser.parseXml("""
                <movie><title>有标题</title><plot></plot><rating>   </rating></movie>
                """);

        assertThat(parsed.fields()).containsOnlyKeys(MetadataFields.TITLE);
    }

    @Test
    void rejectsDoctypeDeclarationsToBlockXxe() {
        // .nfo 是用户放在媒体目录里的文件，内容不可信
        String malicious = """
                <?xml version="1.0"?>
                <!DOCTYPE foo [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <movie><title>&xxe;</title></movie>
                """;

        assertThatThrownBy(() -> NfoParser.parseXml(malicious))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unparseableXmlRaisesRatherThanReturningEmpty() {
        assertThatThrownBy(() -> NfoParser.parseXml("<movie><title>没闭合"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void readsTheProjectsOwnMetadataJsonSchema() {
        ParsedNfo parsed = NfoParser.parseJson("""
                {
                  "title": "自制视频",
                  "summary": "2024 年家庭录像",
                  "releaseDate": "2024-08-01",
                  "rating": "9.9",
                  "extras": {"photographer": "我自己"}
                }
                """);

        assertThat(parsed.fields())
                .containsEntry(MetadataFields.TITLE, "自制视频")
                .containsEntry(MetadataFields.RELEASE_DATE, "2024-08-01");
        assertThat(parsed.extras()).containsEntry("photographer", "我自己");
    }

    @Test
    void metadataJsonWithoutExtrasIsFine() {
        ParsedNfo parsed = NfoParser.parseJson("{\"title\":\"只有标题\"}");

        assertThat(parsed.fields()).containsOnlyKeys(MetadataFields.TITLE);
        assertThat(parsed.extras()).isEmpty();
    }
}
