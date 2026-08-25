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

    TagService(TagRepository repository) {
        this.repository = repository;
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
}
