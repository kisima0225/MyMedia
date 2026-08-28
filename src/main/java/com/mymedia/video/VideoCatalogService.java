package com.mymedia.video;

import com.mymedia.shared.FieldMergePolicy;
import com.mymedia.shared.MetadataPatch;
import com.mymedia.shared.MetadataSnapshot;
import com.mymedia.shared.NaturalSortKey;
import com.mymedia.shared.NotFoundException;
import com.mymedia.shared.ScrapeStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

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

    /**
     * 按 id 批量取条目，<b>保持入参顺序</b>。
     *
     * <p>顺序很重要：调用方（按标签浏览、收藏列表）的 id 是有序取出来的，
     * 而 {@code WHERE id IN (…)} 的返回顺序由数据库决定。不重排就会让同一个列表
     * 每次刷新都换个样子。
     *
     * <p>查不到的 id 被静默丢弃而不是留一个 {@code null}：条目可能在调用方
     * 取到 id 之后被删掉，让列表短一项比让它带一个洞好。
     */
    @Transactional(readOnly = true)
    public List<VideoItem> findByIds(Collection<Long> itemIds) {
        if (itemIds.isEmpty()) {
            return List.of();
        }
        Map<Long, VideoItem> byId = itemRepository.findAllById(itemIds).stream()
                .collect(Collectors.toMap(VideoItem::getId, item -> item));
        return itemIds.stream().map(byId::get).filter(Objects::nonNull).toList();
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

    /**
     * 按 id 批量取文件，<b>不保证顺序</b>——调用方（继续观看）是按进度顺序驱动的，
     * 文件只是用来查表，不需要像 {@link #findByIds} 那样重排。
     */
    @Transactional(readOnly = true)
    public List<VideoFile> findFilesByIds(Collection<Long> fileIds) {
        if (fileIds.isEmpty()) {
            return List.of();
        }
        return fileRepository.findAllById(fileIds);
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
        // 一次 SELECT ... FOR UPDATE 取到锁定集合即可：同一个事务里再读一遍拿到的是同样的值，
        // 只是多一次行锁往返。与图片域保持同一种写法。
        Set<String> locked = metadataStore.lockedFields(itemId);
        Map<String, String> fields = FieldMergePolicy.apply(patch.fields(), locked);
        Map<String, String> extras = FieldMergePolicy.apply(patch.extras(), locked);
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

    /** 刮削器被清空时，收敛该库已有的待刮削条目。 */
    @Transactional
    public int markPendingScrapeNotApplicable(Long libraryId) {
        return jdbc.update(
                "UPDATE video_item SET scrape_status = ?"
                        + " WHERE library_id = ? AND scrape_status = ?",
                ScrapeStatus.NOT_APPLICABLE.name(), libraryId, ScrapeStatus.PENDING.name());
    }

    @Transactional(readOnly = true)
    public MetadataSnapshot metadataOf(Long itemId) {
        return metadataStore.snapshot(itemId);
    }

    /**
     * 把条目挂进一个合集，合集按 (库, 名字) find-or-create。
     *
     * <p>先插入再查询使用两个语句：冲突的插入语句等待并发事务结束，
     * 后续查询在新的 READ COMMITTED 快照中稳定看到已经存在的 id。
     */
    @Transactional
    public void attachToCollection(Long itemId, String collectionName) {
        Long libraryId = getItem(itemId).getLibraryId();
        jdbc.update("""
                INSERT INTO collection (library_id, name, sort_key)
                VALUES (?, ?, ?)
                ON CONFLICT (library_id, name) DO NOTHING
                """, libraryId, collectionName, NaturalSortKey.of(collectionName));

        Long collectionId = jdbc.queryForObject(
                "SELECT id FROM collection WHERE library_id = ? AND name = ?",
                Long.class, libraryId, collectionName);

        jdbc.update("INSERT INTO collection_item (collection_id, video_item_id)"
                + " VALUES (?, ?) ON CONFLICT DO NOTHING", collectionId, itemId);
    }

    /** 扫描完成后的刮削补齐用。只挑 PENDING。 */
    @Transactional(readOnly = true)
    public List<Long> itemsPendingScrape(Long libraryId, int limit) {
        return jdbc.queryForList(
                "SELECT id FROM video_item WHERE library_id = ? AND scrape_status = 'PENDING'"
                        + " ORDER BY id LIMIT ?", Long.class, libraryId, limit);
    }
}
