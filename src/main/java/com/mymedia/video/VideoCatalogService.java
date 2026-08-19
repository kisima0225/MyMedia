package com.mymedia.video;

import com.mymedia.shared.NotFoundException;
import org.springframework.data.domain.Pageable;
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

    VideoCatalogService(VideoItemRepository itemRepository,
                        VideoGroupRepository groupRepository,
                        VideoFileRepository fileRepository) {
        this.itemRepository = itemRepository;
        this.groupRepository = groupRepository;
        this.fileRepository = fileRepository;
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
}
