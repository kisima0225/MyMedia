package com.mymedia.image;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 图片收藏。纯用户态，与阅读进度同类：独立成表，绝不塞进 {@code image_node}。
 *
 * <p>增删都做成<b>幂等</b>的：收藏按钮会被反复点，前端也可能重发，
 * 「已经收藏了」和「本来就没收藏」都不该是错误。
 *
 * <p><b>可以收藏任意节点，包括纯目录</b>（spec §6.5 明写）：
 * 「某画师」这样的中间目录同样值得被收藏，它不需要自己可读。
 * 界面靠 {@code ImageNode.isReadable()} / {@code isBrowsable()} 决定点进去看什么。
 */
@Service
public class ImageFavoriteService {

    private final ImageFavoriteRepository repository;
    private final ImageCatalogService catalogService;

    ImageFavoriteService(ImageFavoriteRepository repository, ImageCatalogService catalogService) {
        this.repository = repository;
        this.catalogService = catalogService;
    }

    @Transactional
    public void add(Long userId, Long nodeId) {
        if (!repository.existsById(new ImageFavorite.Key(userId, nodeId))) {
            repository.save(new ImageFavorite(userId, nodeId));
        }
    }

    /**
     * 取消收藏。
     *
     * <p>{@code deleteById} 在实体不存在时<b>静默返回</b>，正是这里想要的幂等语义。
     */
    @Transactional
    public void remove(Long userId, Long nodeId) {
        repository.deleteById(new ImageFavorite.Key(userId, nodeId));
    }

    @Transactional(readOnly = true)
    public boolean isFavorite(Long userId, Long nodeId) {
        return repository.existsById(new ImageFavorite.Key(userId, nodeId));
    }

    /** 收藏的节点，最近加入的在前。 */
    @Transactional(readOnly = true)
    public List<ImageNode> listNodes(Long userId, int limit) {
        List<Long> nodeIds = repository.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, limit)).stream()
                .map(ImageFavorite::getImageNodeId)
                .toList();
        return catalogService.findByIds(nodeIds);
    }
}
