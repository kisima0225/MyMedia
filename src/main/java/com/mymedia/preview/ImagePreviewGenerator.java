package com.mymedia.preview;

import com.mymedia.image.ImageCatalogService;
import com.mymedia.image.ImageFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;

/**
 * 图片节点的封面与缩略图。
 *
 * <p>来源是该节点<b>自然序的第一页</b>：散图目录取第一张图片本身的 scanned_file，
 * CBZ 取压缩包本体的 scanned_file。因此 {@code derived_asset} 依旧是
 * 「一个来源文件 + 一种资源」的单一外键模型，不需要多态。
 *
 * <p>压缩包内的页走 {@code ZipFile.getInputStream} 按需读取，
 * <b>绝不解压到磁盘</b>——这条约束在计划 04 已经定死，这里只是继续遵守。
 */
@Component
class ImagePreviewGenerator {

    private static final Logger log = LoggerFactory.getLogger(ImagePreviewGenerator.class);

    private final ImageCatalogService catalog;
    private final DerivedAssetService assets;
    private final PreviewProperties properties;

    ImagePreviewGenerator(ImageCatalogService catalog,
                          DerivedAssetService assets,
                          PreviewProperties properties) {
        this.catalog = catalog;
        this.assets = assets;
        this.properties = properties;
    }

    void generate(Long nodeId) throws IOException {
        List<ImageFile> pages = catalog.pagesOf(nodeId);
        if (pages.isEmpty()) {
            // 纯中间目录没有直属页，它的封面由界面用子节点的封面代偿，这里不是错误
            log.debug("节点没有直属页，跳过封面生成 nodeId={}", nodeId);
            return;
        }

        ImageFile firstPage = pages.get(0);
        BufferedImage source;
        try (InputStream in = catalog.openPageForProcessing(firstPage.getId())) {
            source = ImageScaler.read(in);
        }

        Path coverPath = assets.prepare(DerivedAssetKind.COVER, firstPage.getScannedFileId());
        ImageScaler.Size coverSize = ImageScaler.writeJpeg(
                source, properties.coverWidth(), coverPath);
        DerivedAsset cover = assets.record(DerivedAssetKind.COVER,
                firstPage.getScannedFileId(), coverSize.width(), coverSize.height());

        Path thumbnailPath = assets.prepare(DerivedAssetKind.THUMBNAIL, firstPage.getScannedFileId());
        ImageScaler.Size thumbnailSize = ImageScaler.writeJpeg(
                source, properties.thumbnailWidth(), thumbnailPath);
        assets.record(DerivedAssetKind.THUMBNAIL, firstPage.getScannedFileId(),
                thumbnailSize.width(), thumbnailSize.height());

        boolean assigned = catalog.assignCoverIfAbsent(nodeId, cover.getId());
        log.debug("图片封面生成完毕 nodeId={} assetId={} 设为节点封面={}",
                nodeId, cover.getId(), assigned);
    }
}
