package com.mymedia.video;

import com.mymedia.shared.FieldMergePolicy;
import com.mymedia.shared.MetadataPatch;
import com.mymedia.shared.MetadataSnapshot;
import com.mymedia.shared.NotFoundException;
import com.mymedia.shared.ScrapeStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * {@code video} 模块对外暴露的条目查询能力。
 */
@Service
public class VideoCatalogService {

    private final VideoItemRepository itemRepository;
    private final VideoGroupRepository groupRepository;
    private final VideoFileRepository fileRepository;
    private final VideoProbeStore probeStore;
    private final VideoMetadataStore metadataStore;
    private final JdbcTemplate jdbc;

    VideoCatalogService(VideoItemRepository itemRepository,
                        VideoGroupRepository groupRepository,
                        VideoFileRepository fileRepository,
                        VideoProbeStore probeStore,
                        VideoMetadataStore metadataStore,
                        JdbcTemplate jdbc) {
        this.itemRepository = itemRepository;
        this.groupRepository = groupRepository;
        this.fileRepository = fileRepository;
        this.probeStore = probeStore;
        this.metadataStore = metadataStore;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public VideoItem getItem(Long itemId) {
        return itemRepository.findById(itemId)
                .orElseThrow(() -> new NotFoundException("找不到视频条目 id=" + itemId));
    }

    @Transactional(readOnly = true)
    public List<VideoItem> findByLibrary(Long libraryId) {
        return itemRepository.findByLibraryIdIn(List.of(libraryId), Pageable.unpaged()).getContent();
    }

    @Transactional(readOnly = true)
    public List<VideoGroup> groupsOf(Long itemId) {
        return groupRepository.findByItemIdOrderBySortKey(itemId);
    }

    @Transactional(readOnly = true)
    public List<VideoFile> filesOf(Long itemId) {
        return fileRepository.findByItemIdOrderBySortKey(itemId);
    }

    @Transactional(readOnly = true)
    public List<VideoFile> episodesOf(Long groupId) {
        return fileRepository.findByGroupIdOrderByEpisodeIndex(groupId);
    }

    @Transactional(readOnly = true)
    public VideoFile getFile(Long fileId) {
        return fileRepository.findById(fileId)
                .orElseThrow(() -> new NotFoundException("找不到视频文件 id=" + fileId));
    }

    /** 由 {@code preview} 模块在探测完成后调用，把技术参数写回语义层。 */
    @Transactional
    public void applyProbe(Long videoFileId, VideoProbeData probe) {
        probeStore.apply(videoFileId, probe);
    }

    /**
     * 条目还没有封面时才写入，返回是否真的写了。
     *
     * <p>"判断没有"和"写入"必须是同一个原子操作：一个剧集条目下的多个文件会
     * 各自跑一次预览生成，若先查后写，两个并发任务会互相覆盖封面。
     */
    @Transactional
    public boolean assignCoverIfAbsent(Long itemId, Long assetId) {
        return jdbc.update(
                "UPDATE video_item SET cover_asset_id = ? WHERE id = ? AND cover_asset_id IS NULL",
                assetId, itemId) > 0;
    }

    /**
     * 应用一次刮削结果。
     *
     * <p>{@code status} 由调用方（刮削链）决定而不是塞进 {@link MetadataPatch}：
     * 同一份数据高置信度时是 {@code MATCHED}，来自文件名兜底时是 {@code NO_MATCH}，
     * 这是链的判定不是提供者的数据。
     */
    @Transactional
    public void applyMetadata(Long itemId, MetadataPatch patch, ScrapeStatus status) {
        Map<String, String> fields = FieldMergePolicy.apply(
                patch.fields(), metadataStore.lockedFields(itemId));
        Map<String, String> extras = FieldMergePolicy.apply(
                patch.extras(), metadataStore.lockedFields(itemId));
        metadataStore.applyFields(itemId, fields, extras,
                patch.source(), patch.sourceId(), patch.rawResponse(), status);
    }

    /**
     * 用户手工编辑。
     *
     * <p><b>不经过 {@link FieldMergePolicy}</b>——用户就是权威，可以改自己锁过的字段。
     * 写入的同时把这些字段加进 {@code locked_fields}，此后任何刮削都覆盖不了它们。
     */
    @Transactional
    public void applyUserEdit(Long itemId, Map<String, String> fields) {
        metadataStore.lockedFields(itemId);
        metadataStore.applyFields(itemId, fields, Map.of(), "USER", null, null,
                metadataStore.snapshot(itemId).scrapeStatus());
        metadataStore.lock(itemId, fields.keySet());
    }

    @Transactional
    public void updateScrapeStatus(Long itemId, ScrapeStatus status) {
        metadataStore.updateStatus(itemId, status);
    }

    @Transactional(readOnly = true)
    public MetadataSnapshot metadataOf(Long itemId) {
        return metadataStore.snapshot(itemId);
    }

    /** 扫描完成后的封面补齐用：列出该库中还没有封面的条目。 */
    @Transactional(readOnly = true)
    public List<Long> itemsWithoutCover(Long libraryId, int limit) {
        return jdbc.queryForList(
                "SELECT id FROM video_item WHERE library_id = ? AND cover_asset_id IS NULL"
                        + " ORDER BY id LIMIT ?",
                Long.class, libraryId, limit);
    }
}
