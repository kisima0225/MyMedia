package com.mymedia.video;

import com.mymedia.library.LibraryAccessService;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class VideoProgressService {

    private final VideoProgressRepository repository;
    private final VideoCatalogService catalogService;
    private final LibraryAccessService accessService;

    VideoProgressService(VideoProgressRepository repository, VideoCatalogService catalogService,
                         LibraryAccessService accessService) {
        this.repository = repository;
        this.catalogService = catalogService;
        this.accessService = accessService;
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

    /**
     * 继续观看列表。
     *
     * <p>返回的是<b>可以直接渲染成卡片</b>的视图而不是裸的进度行：前端拿到
     * 20 条进度却要再发 20 次请求补标题与封面，是一个必然会出现在
     * 性能剖面上的 N+1。
     *
     * <p>同时按当前用户的库访问权过滤——媒体库的访问权被撤销之后，
     * 继续观看列表不应该还在展示那个库里条目的标题与封面（总览 §5 G24）。
     */
    @Transactional(readOnly = true)
    public List<ContinueWatchingEntry> continueWatching(Long userId, int limit) {
        List<VideoProgress> progresses =
                repository.findContinueWatching(userId, PageRequest.of(0, limit));
        if (progresses.isEmpty()) {
            return List.of();
        }

        Map<Long, VideoFile> filesById = catalogService
                .findFilesByIds(progresses.stream().map(VideoProgress::getVideoFileId).toList())
                .stream()
                .collect(Collectors.toMap(VideoFile::getId, Function.identity()));

        Map<Long, VideoItem> itemsById = catalogService
                .findByIds(filesById.values().stream().map(VideoFile::getItemId).distinct().toList())
                .stream()
                .filter(item -> accessService.canAccess(userId, item.getLibraryId()))
                .collect(Collectors.toMap(VideoItem::getId, Function.identity()));

        return progresses.stream()
                .map(progress -> {
                    VideoFile file = filesById.get(progress.getVideoFileId());
                    if (file == null) {
                        return null;
                    }
                    VideoItem item = itemsById.get(file.getItemId());
                    if (item == null) {
                        return null;   // 没有访问权，或条目已消失
                    }
                    return new ContinueWatchingEntry(
                            file.getId(), item.getId(), item.getTitle(), item.getCoverAssetId(),
                            file.getEpisodeIndex(), progress.getPositionSeconds(),
                            progress.getDurationSeconds(), progress.isCompleted());
                })
                .filter(Objects::nonNull)
                .toList();
    }

    /** 一条继续观看记录，字段已经够渲染一张卡片。 */
    public record ContinueWatchingEntry(
            Long fileId, Long itemId, String itemTitle, Long coverAssetId,
            Integer episodeIndex, int positionSeconds, Integer durationSeconds, boolean completed) {
    }
}
