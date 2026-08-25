package com.mymedia.metadata;

import com.mymedia.library.LibraryDomain;
import com.mymedia.shared.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 标签的增删查。
 *
 * <p>标签归 {@code metadata} 模块：它就是内容元数据，而本模块已经同时持有
 * {@code video} 与 {@code image} 两条依赖边（计划 05 为刮削建立的），
 * 放这里不需要任何新的模块间依赖。关联表和 {@code scrape_candidate} 一样，
 * 是「本模块自己的表，只是外键指向领域表」。
 */
@Service
public class TagService {

    private final TagRepository repository;
    private final TagLinkStore linkStore;

    TagService(TagRepository repository, TagLinkStore linkStore) {
        this.repository = repository;
        this.linkStore = linkStore;
    }

    /**
     * 按 (域, slug) 找或建。
     *
     * <p>用 find-or-create 而不是 create：标签是打的时候顺手建的，
     * 让调用方先查一次再建只会到处重复这段逻辑。
     */
    @Transactional
    public Tag findOrCreate(LibraryDomain domain, String name) {
        String slug = TagSlug.of(name);
        return repository.findByDomainAndSlug(domain, slug)
                .orElseGet(() -> repository.saveAndFlush(new Tag(domain, name.trim(), slug)));
    }

    @Transactional(readOnly = true)
    public List<Tag> findByDomain(LibraryDomain domain) {
        return repository.findByDomainOrderByName(domain);
    }

    @Transactional(readOnly = true)
    public Tag getById(Long tagId) {
        return repository.findById(tagId)
                .orElseThrow(() -> new NotFoundException("找不到标签 id=" + tagId));
    }

    /** 关联表上的外键是 ON DELETE CASCADE，删标签会一并解掉所有关联。 */
    @Transactional
    public void delete(Long tagId) {
        repository.delete(getById(tagId));
    }

    @Transactional(readOnly = true)
    public List<Tag> tagsOf(LibraryDomain domain, Long targetId) {
        List<Long> tagIds = linkStore.tagIdsOf(domain, targetId);
        return tagIds.isEmpty() ? List.of() : repository.findAllById(tagIds);
    }

    /**
     * 整体替换某个目标的标签组。
     *
     * <p><b>替换而不是增删</b>：前端的标签编辑器是一个多选框，用户勾完点保存，
     * 提交的就是「最终应该有的那一组」。做成 add/remove 两个端点会逼前端自己算差集，
     * 还要处理两次请求之间失败的中间态。一次覆盖，语义与界面一致，天然幂等。
     *
     * @throws IllegalArgumentException 有标签不存在，或有标签不属于该域。数据库那道
     *         复合外键是最后一道防线，但它给出的错误是 FK 违例，调用方读不懂；
     *         这里先给一个能读懂的。
     */
    @Transactional
    public List<Tag> setTags(LibraryDomain domain, Long targetId, List<Long> tagIds) {
        List<Long> distinct = tagIds.stream().distinct().toList();
        List<Tag> tags = distinct.isEmpty() ? List.of() : repository.findAllById(distinct);

        if (tags.size() != distinct.size()) {
            throw new IllegalArgumentException("有标签不存在: " + distinct);
        }
        tags.stream()
                .filter(tag -> tag.getDomain() != domain)
                .findFirst()
                .ifPresent(tag -> {
                    throw new IllegalArgumentException(
                            "标签 " + tag.getName() + " 属于 " + tag.getDomain() + " 域，不能贴到 " + domain);
                });

        linkStore.replace(domain, targetId, distinct);
        return tags;
    }

    /** 标签自己带 domain，所以调用方不需要再传一次。 */
    @Transactional(readOnly = true)
    public List<Long> targetIdsWithTag(Long tagId, int limit) {
        Tag tag = getById(tagId);
        return linkStore.targetIdsWithTag(tag.getDomain(), tagId, limit);
    }
}
