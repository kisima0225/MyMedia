package com.mymedia.video;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 视频收藏。纯用户态，与播放进度同类：独立成表，绝不塞进 {@code video_item}。
 *
 * <p>增删都做成<b>幂等</b>的：收藏按钮会被反复点，前端也可能重发，
 * 「已经收藏了」和「本来就没收藏」都不该是错误。
 */
@Service
public class VideoFavoriteService {

    private final VideoFavoriteRepository repository;
    private final VideoCatalogService catalogService;

    VideoFavoriteService(VideoFavoriteRepository repository, VideoCatalogService catalogService) {
        this.repository = repository;
        this.catalogService = catalogService;
    }

    @Transactional
    public void add(Long userId, Long itemId) {
        if (!repository.existsById(new VideoFavorite.Key(userId, itemId))) {
            repository.save(new VideoFavorite(userId, itemId));
        }
    }

    /**
     * 取消收藏。
     *
     * <p>{@code deleteById} 在实体不存在时<b>静默返回</b>，正是这里想要的幂等语义。
     */
    @Transactional
    public void remove(Long userId, Long itemId) {
        repository.deleteById(new VideoFavorite.Key(userId, itemId));
    }

    @Transactional(readOnly = true)
    public boolean isFavorite(Long userId, Long itemId) {
        return repository.existsById(new VideoFavorite.Key(userId, itemId));
    }

    /** 收藏的条目，最近加入的在前。 */
    @Transactional(readOnly = true)
    public List<VideoItem> listItems(Long userId, int limit) {
        List<Long> itemIds = repository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(VideoFavorite::getVideoItemId)
                .limit(limit)
                .toList();
        return catalogService.findByIds(itemIds);
    }
}
