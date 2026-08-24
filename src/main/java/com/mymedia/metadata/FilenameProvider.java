package com.mymedia.metadata;

import com.mymedia.library.LibraryDomain;
import com.mymedia.shared.MetadataFields;
import com.mymedia.shared.MetadataPatch;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 兜底提供者：永远成功，把已有的标题稍作清理后交出去。
 *
 * <p>它的存在让 spec 7.2 规则 1「无刮削亦完全可用」成立——链的末端总有结果，
 * 条目不会停在"什么都没有"的状态。
 *
 * <p><b>它的结果不算命中</b>：链在应用它的同时把状态置为 {@code NO_MATCH}，
 * 界面安静回落，不显示为错误。
 */
@Component
class FilenameProvider implements MetadataProvider {

    static final String NAME = "Filename";

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
        return List.of(new MetadataCandidate(NAME, null, clean(subject.title()),
                subject.year(), 1.0, "{}"));
    }

    @Override
    public Optional<MetadataPatch> fetch(ScrapeSubject subject, MetadataCandidate candidate) {
        Map<String, String> fields = new LinkedHashMap<>();
        String title = clean(subject.title());
        if (title != null && !title.isBlank()) {
            fields.put(MetadataFields.TITLE, title);
        }
        if (subject.year() != null) {
            fields.put(MetadataFields.RELEASE_DATE, subject.year() + "-01-01");
        }
        return fields.isEmpty() ? Optional.empty()
                : Optional.of(new MetadataPatch(NAME, null, fields, Map.of(), null));
    }

    /**
     * 去掉发布组方括号、把点和下划线还原成空格、收敛连续空白。
     *
     * <p>比计划 03 的 {@code VideoFilenameParser} 轻得多——那个解析器要认季集号，
     * 这里只需要一个能看的标题。两者不共用代码是有意的：解析器是 {@code video}
     * 模块的内部实现，把它开放出来只为了做字符串清理，代价大于收益。
     */
    private static String clean(String raw) {
        if (raw == null) {
            return null;
        }
        return raw.replaceAll("\\[[^\\]]*\\]", " ")
                  .replaceAll("[._]+", " ")
                  .replaceAll("\\s+", " ")
                  .trim();
    }
}
