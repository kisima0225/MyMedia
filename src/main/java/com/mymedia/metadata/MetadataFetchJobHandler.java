package com.mymedia.metadata;

import com.mymedia.image.ImageCatalogService;
import com.mymedia.jobs.Job;
import com.mymedia.jobs.JobHandler;
import com.mymedia.library.LibraryDomain;
import com.mymedia.library.LibraryService;
import com.mymedia.shared.MetadataPatch;
import com.mymedia.shared.ScrapeStatus;
import com.mymedia.video.VideoCatalogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * {@code METADATA_FETCH}：跑一遍提供者链并把结论写回条目。
 *
 * <p>错误状态使用独立的领域事务先提交，再抛出可重试异常；成功分支则把
 * 写回、候选队列和合集操作交给各自的事务边界，避免外层异常把 ERROR 回滚掉。
 */
@Component
class MetadataFetchJobHandler implements JobHandler {

    static final String JOB_TYPE = "METADATA_FETCH";

    /** {@code MetadataPatch.extras} 里表示所属合集的键。 */
    static final String COLLECTION_KEY = "collection";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Logger log = LoggerFactory.getLogger(MetadataFetchJobHandler.class);

    private final SubjectFactory subjectFactory;
    private final MetadataResolver resolver;
    private final LibraryService libraryService;
    private final ScrapeCandidateStore candidateStore;
    private final VideoCatalogService videoCatalog;
    private final ImageCatalogService imageCatalog;

    MetadataFetchJobHandler(SubjectFactory subjectFactory,
                            MetadataResolver resolver,
                            LibraryService libraryService,
                            ScrapeCandidateStore candidateStore,
                            VideoCatalogService videoCatalog,
                            ImageCatalogService imageCatalog) {
        this.subjectFactory = subjectFactory;
        this.resolver = resolver;
        this.libraryService = libraryService;
        this.candidateStore = candidateStore;
        this.videoCatalog = videoCatalog;
        this.imageCatalog = imageCatalog;
    }

    @Override
    public String jobType() {
        return JOB_TYPE;
    }

    @Override
    public void handle(Job job) throws Exception {
        JsonNode payload = MAPPER.readTree(job.getPayload());
        LibraryDomain domain = LibraryDomain.valueOf(payload.path("domain").asString());
        Long targetId = payload.path("targetId").asLong();

        ScrapeSubject subject = subjectFactory.create(domain, targetId);
        List<String> providers = libraryService.metadataProvidersOf(subject.libraryId());
        if (providers.isEmpty()) {
            // 防守：本该由事件监听器拦下，走到这里说明配置刚被清空。
            updateStatus(domain, targetId, ScrapeStatus.NOT_APPLICABLE);
            return;
        }

        ResolutionResult result = resolver.resolve(subject, providers);
        switch (result.status()) {
            case MATCHED -> {
                applyMetadata(domain, targetId, result.patch(), ScrapeStatus.MATCHED);
                candidateStore.deleteAll(domain, targetId);
                attachCollection(domain, targetId, result.patch());
            }
            case NEEDS_REVIEW -> {
                // 绝不在低置信度下强行写入：只存候选，等用户拍板。
                candidateStore.replaceAll(domain, targetId, result.candidates());
                updateStatus(domain, targetId, ScrapeStatus.NEEDS_REVIEW);
            }
            case NO_MATCH -> {
                if (result.patch() != null) {
                    applyMetadata(domain, targetId, result.patch(), ScrapeStatus.NO_MATCH);
                } else {
                    updateStatus(domain, targetId, ScrapeStatus.NO_MATCH);
                }
                candidateStore.deleteAll(domain, targetId);
            }
            case ERROR -> {
                // 该调用没有包在 handle 的外层事务中，返回后状态已经提交。
                updateStatus(domain, targetId, ScrapeStatus.ERROR);
                throw new ProviderUnavailableException(
                        "刮削失败，等待重试：" + domain + " id=" + targetId);
            }
            default -> log.warn("未预期的刮削结论 {}", result.status());
        }
    }

    /** 只有视频域有合集；图片域的树本身已经表达层级聚合。 */
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
}
