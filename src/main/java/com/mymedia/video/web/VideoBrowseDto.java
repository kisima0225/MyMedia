package com.mymedia.video.web;

import com.mymedia.video.VideoFolder;
import com.mymedia.video.VideoItem;

import java.util.List;

public final class VideoBrowseDto {

    private VideoBrowseDto() {
    }

    public record FolderNode(Long id, String name, int depth, int totalItemCount) {

        static FolderNode from(VideoFolder folder) {
            return new FolderNode(folder.getId(), folder.getName(),
                    folder.getDepth(), folder.getTotalItemCount());
        }
    }

    public record ItemNode(Long id, String title, String itemType, String structure) {

        static ItemNode from(VideoItem item) {
            return new ItemNode(item.getId(), item.getTitle(),
                    item.getItemType().name(), item.getStructure().name());
        }
    }

    public record BrowseResponse(
            List<FolderNode> breadcrumb,
            List<FolderNode> folders,
            List<ItemNode> items) {
    }
}
