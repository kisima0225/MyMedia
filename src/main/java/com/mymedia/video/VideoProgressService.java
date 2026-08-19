package com.mymedia.video;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
public class VideoProgressService {

    private final VideoProgressRepository repository;
    private final VideoCatalogService catalogService;

    VideoProgressService(VideoProgressRepository repository, VideoCatalogService catalogService) {
        this.repository = repository;
        this.catalogService = catalogService;
    }

    @Transactional
    public void record(Long userId, Long fileId, int positionSeconds, Integer durationSeconds) {
        catalogService.getFile(fileId);

        VideoProgress progress = repository.findByUserIdAndVideoFileId(userId, fileId)
                .orElseGet(() -> new VideoProgress(userId, fileId));
        progress.update(positionSeconds, durationSeconds);
        repository.save(progress);
    }

    @Transactional(readOnly = true)
    public Optional<VideoProgress> find(Long userId, Long fileId) {
        return repository.findByUserIdAndVideoFileId(userId, fileId);
    }

    @Transactional(readOnly = true)
    public List<VideoProgress> continueWatching(Long userId, int limit) {
        return repository.findContinueWatching(userId, PageRequest.of(0, limit));
    }
}
