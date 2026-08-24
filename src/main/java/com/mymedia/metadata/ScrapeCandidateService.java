package com.mymedia.metadata;

import com.mymedia.image.ImageCatalogService;
import com.mymedia.library.LibraryDomain;
import com.mymedia.shared.MetadataPatch;
import com.mymedia.shared.MetadataSnapshot;
import com.mymedia.shared.NotFoundException;
import com.mymedia.shared.ScrapeStatus;
import com.mymedia.video.VideoCatalogService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 待确认队列：列出候选、一键确认、一键忽略。
 *
 * <p>确认时才去取详情——搜索结果本来就不含简介（Bangumi 实测如此），
 * 而中等置信度的候选大多数会被丢弃，提前取详情等于白发一次请求。
 */
@Service
public class ScrapeCandidateService {

    private static final String COLLECTION_KEY = "collection";

    private final ScrapeCandidateStore store;
    private final SubjectFactory subjectFactory;
    private final VideoCatalogService videoCatalog;
    private final ImageCatalogService imageCatalog;
    private final Map<String, MetadataProvider> providersByName = new LinkedHashMap<>();

    ScrapeCandidateService(ScrapeCandidateStore store,
                           SubjectFactory subjectFactory,
                           VideoCatalogService videoCatalog,
                           ImageCatalogService imageCatalog,
                           List<MetadataProvider> providers) {
        this.store = store;
        this.subjectFactory = subjectFactory;
        this.videoCatalog = videoCatalog;
        this.imageCatalog = imageCatalog;
        providers.forEach(provider -> providersByName.put(provider.name(), provider));
    }

    @Transactional(readOnly = true)
    public List<ScrapeCandidateRecord> candidatesFor(LibraryDomain domain, Long targetId) {
        return store.findByTarget(domain, targetId);
    }

    /** 端点要先按 id 拿到候选，才知道该校验哪个媒体库的访问权。 */
    @Transactional(readOnly = true)
    public ScrapeCandidateRecord candidateById(Long candidateId) {
        return store.getById(candidateId);
    }

    /** 用户选中了某个候选：取详情、应用、清空队列。 */
    @Transactional
    public MetadataSnapshot confirm(Long candidateId) {
        ScrapeCandidateRecord candidate = store.getById(candidateId);
        MetadataProvider provider = providersByName.get(candidate.provider());
        if (provider == null) {
            throw new NotFoundException("候选来自一个已经不存在的提供者: " + candidate.provider());
        }

        ScrapeSubject subject = subjectFactory.create(candidate.domain(), candidate.targetId());
        Optional<MetadataPatch> patch = provider.fetch(subject, new MetadataCandidate(
                candidate.provider(), candidate.externalId(), candidate.title(),
                candidate.year(), candidate.score(), candidate.payload()));
        if (patch.isEmpty()) {
            throw new NotFoundException("该候选在提供者侧已不存在，请重新刮削");
        }

        applyMetadata(candidate.domain(), candidate.targetId(), patch.get(), ScrapeStatus.MATCHED);
        attachCollection(candidate.domain(), candidate.targetId(), patch.get());
        store.deleteAll(candidate.domain(), candidate.targetId());
        return snapshot(candidate.domain(), candidate.targetId());
    }

    /** 用户认为都不对：清空队列并置 {@code NO_MATCH}，界面从此安静。 */
    @Transactional
    public void ignore(LibraryDomain domain, Long targetId) {
        store.deleteAll(domain, targetId);
        updateStatus(domain, targetId, ScrapeStatus.NO_MATCH);
    }

    private void attachCollection(LibraryDomain domain, Long targetId, MetadataPatch patch) {
        if (domain != LibraryDomain.VIDEO || patch == null) {
            return;
        }
        String collection = patch.extras().get(COLLECTION_KEY);
        if (collection != null && !collection.isBlank()) {
            videoCatalog.attachToCollection(targetId, collection.trim());
        }
    }

    private void applyMetadata(LibraryDomain domain, Long targetId,
                               MetadataPatch patch, ScrapeStatus status) {
        switch (domain) {
            case VIDEO -> videoCatalog.applyMetadata(targetId, patch, status);
            case IMAGE -> imageCatalog.applyMetadata(targetId, patch, status);
        }
    }

    private void updateStatus(LibraryDomain domain, Long targetId, ScrapeStatus status) {
        switch (domain) {
            case VIDEO -> videoCatalog.updateScrapeStatus(targetId, status);
            case IMAGE -> imageCatalog.updateScrapeStatus(targetId, status);
        }
    }

    private MetadataSnapshot snapshot(LibraryDomain domain, Long targetId) {
        return domain == LibraryDomain.VIDEO
                ? videoCatalog.metadataOf(targetId)
                : imageCatalog.metadataOf(targetId);
    }
}
