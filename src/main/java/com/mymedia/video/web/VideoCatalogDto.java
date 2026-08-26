package com.mymedia.video.web;

import com.mymedia.video.VideoFile;
import com.mymedia.video.VideoGroup;
import com.mymedia.video.VideoItem;

import java.util.List;

public final class VideoCatalogDto {

    private VideoCatalogDto() {
    }

    /**
     * 条目摘要。
     *
     * <p>{@code libraryId} 供前端首页按媒体库分段展示——没有这个分量，
     * 前端就得为每个条目再发一次请求去问它属于哪个库。
     */
    public record ItemSummary(Long id, String title, String itemType,
                              String structure, Long coverAssetId, Long libraryId) {

        static ItemSummary from(VideoItem item) {
            return new ItemSummary(item.getId(), item.getTitle(),
                    item.getItemType().name(), item.getStructure().name(),
                    item.getCoverAssetId(), item.getLibraryId());
        }
    }

    public record GroupSummary(Long id, int groupIndex, String name) {

        static GroupSummary from(VideoGroup group) {
            return new GroupSummary(group.getId(), group.getGroupIndex(), group.getName());
        }
    }

    public record FileSummary(Long id, String role, Integer episodeIndex,
                              Integer durationSeconds, Integer width, Integer height) {

        static FileSummary from(VideoFile file) {
            return new FileSummary(file.getId(), file.getRole().name(), file.getEpisodeIndex(),
                    file.getDurationSeconds(), file.getWidth(), file.getHeight());
        }
    }

    public record ItemDetail(ItemSummary item, List<GroupSummary> groups, List<FileSummary> files) {
    }
}
