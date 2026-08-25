package com.mymedia.metadata;

import com.mymedia.AbstractIntegrationTest;
import com.mymedia.library.LibraryDomain;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TagServiceTest extends AbstractIntegrationTest {

    @Autowired
    TagService tagService;

    @Autowired
    JdbcTemplate jdbc;

    private String uniqueName() {
        return "标签" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    void createsATagWithAGeneratedSlug() {
        Tag tag = tagService.findOrCreate(LibraryDomain.VIDEO, "科  幻");

        assertThat(tag.getId()).isNotNull();
        assertThat(tag.getName()).isEqualTo("科  幻");
        assertThat(tag.getSlug()).isEqualTo("科-幻");
        assertThat(tag.getDomain()).isEqualTo(LibraryDomain.VIDEO);
    }

    @Test
    void namesThatDifferOnlyByPunctuationOrCaseAreTheSameTag() {
        Tag first = tagService.findOrCreate(LibraryDomain.VIDEO, "Sci-Fi");
        Tag second = tagService.findOrCreate(LibraryDomain.VIDEO, "sci fi!");

        assertThat(second.getId()).isEqualTo(first.getId());
    }

    @Test
    void theSameSlugInTheOtherDomainIsADifferentTag() {
        // 视频标签与图片标签互不混用（spec §6.2）
        String name = uniqueName();
        Tag videoTag = tagService.findOrCreate(LibraryDomain.VIDEO, name);
        Tag imageTag = tagService.findOrCreate(LibraryDomain.IMAGE, name);

        assertThat(imageTag.getId()).isNotEqualTo(videoTag.getId());
    }

    @Test
    void listsOnlyTheRequestedDomain() {
        String name = uniqueName();
        tagService.findOrCreate(LibraryDomain.VIDEO, name);

        assertThat(tagService.findByDomain(LibraryDomain.VIDEO))
                .extracting(Tag::getName).contains(name);
        assertThat(tagService.findByDomain(LibraryDomain.IMAGE))
                .extracting(Tag::getName).doesNotContain(name);
    }

    @Test
    void deletesATag() {
        Tag tag = tagService.findOrCreate(LibraryDomain.VIDEO, uniqueName());

        tagService.delete(tag.getId());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM tag WHERE id = ?",
                Integer.class, tag.getId())).isZero();
    }

    @Test
    void theDatabaseRefusesAnImageTagOnAVideoItem() {
        // ADR-001 的复合外键手法在标签上的第三次应用
        Tag imageTag = tagService.findOrCreate(LibraryDomain.IMAGE, uniqueName());
        Long libraryId = jdbc.queryForObject("""
                INSERT INTO libraries (name, domain, root_path)
                VALUES ('库' || gen_random_uuid(), 'VIDEO', '/tmp/' || gen_random_uuid())
                RETURNING id
                """, Long.class);
        Long itemId = jdbc.queryForObject("""
                INSERT INTO video_item (library_id, item_type, title, sort_title)
                VALUES (?, 'MOVIE', '某片', '某片') RETURNING id
                """, Long.class, libraryId);

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO video_item_tag (video_item_id, tag_id) VALUES (?, ?)",
                itemId, imageTag.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void unknownTagIdIsNotFound() {
        assertThatThrownBy(() -> tagService.getById(999_999_999L))
                .isInstanceOf(com.mymedia.shared.NotFoundException.class);
    }
}
