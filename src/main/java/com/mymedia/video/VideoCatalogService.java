package com.mymedia.video;

import com.mymedia.shared.NotFoundException;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * {@code video} 模块对外暴露的条目查询能力。
 */
@Service
public class VideoCatalogService {

    private final VideoItemRepository itemRepository;
    private final VideoGroupRepository groupRepository;
    private final VideoFileRepository fileRepository;
    private final VideoProbeStore probeStore;
    private final JdbcTemplate jdbc;

    VideoCatalogService(VideoItemRepository itemRepository,
                        VideoGroupRepository groupRepository,
                        VideoFileRepository fileRepository,
                        VideoProbeStore probeStore,
                        JdbcTemplate jdbc) {
        this.itemRepository = itemRepository;
        this.groupRepository = groupRepository;
        this.fileRepository = fileRepository;
        this.probeStore = probeStore;
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

    /** 扫描完成后的封面补齐用：列出该库中还没有封面的条目。 */
    @Transactional(readOnly = true)
    public List<Long> itemsWithoutCover(Long libraryId, int limit) {
        return jdbc.queryForList(
                "SELECT id FROM video_item WHERE library_id = ? AND cover_asset_id IS NULL"
                        + " ORDER BY id LIMIT ?",
                Long.class, libraryId, limit);
    }
}
