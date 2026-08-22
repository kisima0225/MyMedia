package com.mymedia.image;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
public class ImageProgressService {

    private final ImageProgressRepository repository;
    private final ImageCatalogService catalogService;

    ImageProgressService(ImageProgressRepository repository, ImageCatalogService catalogService) {
        this.repository = repository;
        this.catalogService = catalogService;
    }

    @Transactional
    public void record(Long userId, Long nodeId, int pageIndex) {
        catalogService.getNode(nodeId);      // 不存在则抛 NotFoundException

        ImageProgress progress = repository.findByUserIdAndImageNodeId(userId, nodeId)
                .orElseGet(() -> new ImageProgress(userId, nodeId));
        progress.update(pageIndex);
        repository.save(progress);
    }

    @Transactional(readOnly = true)
    public Optional<ImageProgress> find(Long userId, Long nodeId) {
        return repository.findByUserIdAndImageNodeId(userId, nodeId);
    }

    @Transactional(readOnly = true)
    public List<ImageProgress> continueReading(Long userId, int limit) {
        return repository.findContinueReading(userId, PageRequest.of(0, limit));
    }
}