package com.mymedia.image.web;

import com.mymedia.image.ImageFile;
import com.mymedia.image.ImageNode;

import java.util.List;

public final class ImageNodeDto {

    private ImageNodeDto() {
    }

    /**
     * 节点摘要。
     *
     * <p>{@code readable} 与 {@code browsable} 是<b>两个独立的布尔值</b>，
     * 不是一个 type 字段——一个目录既有散图又有子目录时两者同时为真，
     * 前端据此同时渲染「阅读」与「进入」两个入口。
     */
    public record NodeSummary(
            Long id,
            String name,
            String displayName,
            int depth,
            String sourceKind,
            String readingMode,
            int directPageCount,
            int childNodeCount,
            int totalPageCount,
            boolean readable,
            boolean browsable) {

        public static NodeSummary from(ImageNode node) {
            return new NodeSummary(
                    node.getId(), node.getName(), node.getDisplayName(), node.getDepth(),
                    node.getSourceKind().name(), node.getReadingMode().name(),
                    node.getDirectPageCount(), node.getChildNodeCount(), node.getTotalPageCount(),
                    node.isReadable(), node.isBrowsable());
        }
    }

    public record PageSummary(Long id, int pageIndex, Integer width, Integer height) {

        public static PageSummary from(ImageFile file) {
            return new PageSummary(file.getId(), file.getPageIndex(),
                    file.getWidth(), file.getHeight());
        }
    }

    public record BrowseResponse(List<NodeSummary> breadcrumb, List<NodeSummary> nodes) {
    }

    public record ReadingModeRequest(String mode) {
    }
}
