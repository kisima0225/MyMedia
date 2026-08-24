package com.mymedia.image;

import com.mymedia.library.LibraryService;
import com.mymedia.scan.ScannedFileQueryService;
import com.mymedia.shared.FieldMergePolicy;
import com.mymedia.shared.MetadataPatch;
import com.mymedia.shared.MetadataSnapshot;
import com.mymedia.shared.NotFoundException;
import com.mymedia.shared.ScrapeStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code image} 模块对外暴露的节点与页查询能力。
 */
@Service
public class ImageCatalogService {

    private final ImageNodeRepository nodeRepository;
    private final ImageFileRepository fileRepository;
    private final ImageArchiveReader archiveReader;
    private final ScannedFileQueryService scannedFiles;
    private final LibraryService libraryService;
    private final ImageMetadataStore metadataStore;
    private final JdbcTemplate jdbc;

    ImageCatalogService(ImageNodeRepository nodeRepository,
                        ImageFileRepository fileRepository,
                        ImageArchiveReader archiveReader,
                        ScannedFileQueryService scannedFiles,
                        LibraryService libraryService,
                        ImageMetadataStore metadataStore,
                        JdbcTemplate jdbc) {
        this.nodeRepository = nodeRepository;
        this.fileRepository = fileRepository;
        this.archiveReader = archiveReader;
        this.scannedFiles = scannedFiles;
        this.libraryService = libraryService;
        this.metadataStore = metadataStore;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public ImageNode getNode(Long nodeId) {
        return nodeRepository.findById(nodeId)
                .orElseThrow(() -> new NotFoundException("找不到图片节点 id=" + nodeId));
    }

    @Transactional(readOnly = true)
    public List<ImageNode> findRoots(Long libraryId) {
        return nodeRepository.findByLibraryIdAndParentIdIsNullOrderBySortKey(libraryId);
    }

    /**
     * 节点的页。
     *
     * <p>{@code FORCE_BOOK} 下返回<b>整棵子树</b>的页，按（顺序路径, 页序）展开，
     * 也就是「章节顺序 + 页顺序」。这正是 {@code sort_path} 列存在的理由：
     * 结构路径由 id 组成，它的顺序是节点创建顺序，拿它排序会得到扫描时的偶然次序。
     */
    @Transactional(readOnly = true)
    public List<ImageFile> pagesOf(Long nodeId) {
        ImageNode node = getNode(nodeId);
        if (node.getReadingMode() == ImageReadingMode.FORCE_BOOK) {
            return fileRepository.findSubtreePages(
                    node.getLibraryId(), node.getMaterializedPath() + "%");
        }
        return fileRepository.findByNodeIdOrderByPageIndex(nodeId);
    }

    @Transactional(readOnly = true)
    public ImageFile getFile(Long fileId) {
        return fileRepository.findById(fileId)
                .orElseThrow(() -> new NotFoundException("找不到图片文件 id=" + fileId));
    }

    /**
     * 打开一页的字节流，<b>不做任何权限校验</b>。
     *
     * <p><b>仅供后台任务使用</b>（封面生成、将来的尺寸探测）。这些任务没有
     * 调用者身份可言——它们由扫描触发、在 worker 线程上运行。面向用户的入口
     * 是 {@code ImagePageService.locate(userId, fileId)}，那条路径会校验访问权
     * 并对无权用户返回 404。
     *
     * <p><b>不要把本方法接到任何 controller 上。</b>
     */
    @Transactional(readOnly = true)
    public InputStream openPageForProcessing(Long imageFileId) throws IOException {
        ImageFile file = getFile(imageFileId);
        ImageNode node = getNode(file.getNodeId());
        Path path = Path.of(libraryService.getById(node.getLibraryId()).getRootPath())
                .resolve(scannedFiles.getById(file.getScannedFileId()).getRelativePath());
        return file.getArchiveEntryName() == null
                ? Files.newInputStream(path)
                : archiveReader.openEntry(path, file.getArchiveEntryName());
    }

    /** 归档索引已经完成，包括“索引完成但没有图片页”的情况。 */
    @Transactional(readOnly = true)
    public boolean isArchiveIndexReady(Long nodeId) {
        ImageNode node = getNode(nodeId);
        if (node.getSourceKind() != ImageSourceKind.ARCHIVE) {
            return true;
        }
        Long archiveScannedFileId = node.getArchiveScannedFileId();
        if (archiveScannedFileId == null) {
            return false;
        }
        String status = jdbc.queryForObject("""
                SELECT COALESCE((
                    SELECT status
                      FROM job
                     WHERE type = 'ARCHIVE_INDEX'
                       AND payload->>'scannedFileId' = ?
                     ORDER BY id DESC
                     LIMIT 1
                ), 'MISSING')
                """, String.class, String.valueOf(archiveScannedFileId));
        return "SUCCEEDED".equals(status);
    }

    /**
     * 节点还没有封面时才写入，返回是否真的写了。
     *
     * <p>与视频域同款：判断与写入必须是同一条 UPDATE，否则并发的补齐任务会互相覆盖。
     */
    @Transactional
    public boolean assignCoverIfAbsent(Long nodeId, Long assetId) {
        return jdbc.update(
                "UPDATE image_node SET cover_asset_id = ? WHERE id = ? AND cover_asset_id IS NULL",
                assetId, nodeId) > 0;
    }

    @Transactional
    public void applyMetadata(Long nodeId, MetadataPatch patch, ScrapeStatus status) {
        Set<String> locked = metadataStore.lockedFields(nodeId);
        metadataStore.applyFields(nodeId,
                FieldMergePolicy.apply(patch.fields(), locked),
                FieldMergePolicy.apply(patch.extras(), locked),
                patch.source(), patch.sourceId(), patch.rawResponse(), status);
    }

    @Transactional
    public void applyUserEdit(Long nodeId, Map<String, String> fields) {
        metadataStore.lockedFields(nodeId);
        metadataStore.applyFields(nodeId, fields, Map.of(), "USER", null, null,
                metadataStore.snapshot(nodeId).scrapeStatus());
        metadataStore.lock(nodeId, fields.keySet());
    }

    @Transactional
    public void updateScrapeStatus(Long nodeId, ScrapeStatus status) {
        metadataStore.updateStatus(nodeId, status);
    }

    @Transactional(readOnly = true)
    public MetadataSnapshot metadataOf(Long nodeId) {
        return metadataStore.snapshot(nodeId);
    }

    /** 扫描完成后的封面补齐用：列出该库中有直属页却还没有封面的节点。 */
    @Transactional(readOnly = true)
    public List<Long> nodesWithoutCover(Long libraryId, int limit) {
        return jdbc.queryForList("""
                SELECT id FROM image_node
                 WHERE library_id = ? AND cover_asset_id IS NULL
                   AND direct_page_count > 0 AND status = 'ACTIVE'
                 ORDER BY id LIMIT ?
                """, Long.class, libraryId, limit);
    }

    /** 用户推翻自动判定。 */
    @Transactional
    public ImageNode setReadingMode(Long nodeId, ImageReadingMode mode) {
        ImageNode node = getNode(nodeId);
        node.overrideReadingMode(mode);
        return nodeRepository.save(node);
    }
}
