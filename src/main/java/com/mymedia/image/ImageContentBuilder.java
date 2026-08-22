package com.mymedia.image;

import com.mymedia.jobs.JobQueue;
import com.mymedia.library.LibraryDomain;
import com.mymedia.scan.event.ScannedFileChanged;
import com.mymedia.scan.event.ScannedFileDiscovered;
import com.mymedia.scan.event.ScannedFileVanished;
import com.mymedia.scan.spi.LibraryContentBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 把扫描发现的图片文件构建成节点树。
 *
 * <p>两条路径：
 * <ul>
 *   <li>散图 → 挂到所在目录的节点上，成为它的一页</li>
 *   <li>压缩包 → 自成一个 {@code ARCHIVE} 叶子节点，并排一个
 *       {@code ARCHIVE_INDEX} 任务去建页索引（见 Task 4）</li>
 * </ul>
 *
 * <p>整个过程<b>幂等</b>：重复扫描同一批文件不产生重复节点或重复页。
 *
 * <p>三个回调对应物理层的三种结局：发现、变化（含从 {@code MISSING} 恢复）、消失。
 * 只有第一种会建结构，第二种只对压缩包有意义，第三种什么都不做。
 */
@Component
class ImageContentBuilder implements LibraryContentBuilder {

    private static final Logger log = LoggerFactory.getLogger(ImageContentBuilder.class);

    private final ImageNodeIndexer indexer;
    private final ImageNodeRepository nodeRepository;
    private final ImageFileRepository fileRepository;
    private final JobQueue jobQueue;

    ImageContentBuilder(ImageNodeIndexer indexer,
                        ImageNodeRepository nodeRepository,
                        ImageFileRepository fileRepository,
                        JobQueue jobQueue) {
        this.indexer = indexer;
        this.nodeRepository = nodeRepository;
        this.fileRepository = fileRepository;
        this.jobQueue = jobQueue;
    }

    @Override
    public boolean supports(LibraryDomain domain) {
        return domain == LibraryDomain.IMAGE;
    }

    @Override
    @Transactional
    public void onFileDiscovered(ScannedFileDiscovered event) {
        switch (event.kind()) {
            case IMAGE -> attachLooseImage(event);
            case ARCHIVE -> registerArchive(event);
            default -> { /* VIDEO / IGNORED 与图片域无关 */ }
        }
    }

    @Override
    @Transactional
    public void onFileChanged(ScannedFileChanged event) {
        // 压缩包的页表是「上一次解包解出来的快照」。包被重打过（size / mtime 变了），
        // 或者外接盘挂回来（reactivated），快照都可能已经不对，必须重建。
        //
        // 这里能真正排出第二个任务，靠的是 dedup_key 的部分唯一索引只覆盖
        // PENDING / RUNNING：上一轮的索引任务已经 SUCCEEDED，不构成冲突。
        // 而 ARCHIVE_INDEX 处理器本身是「先按 scanned_file_id 删旧行再整体重建」的
        // （Task 4 Step 4），所以重复执行不会留下重复页或错乱的页码。
        nodeRepository.findByArchiveScannedFileId(event.scannedFileId()).ifPresent(node -> {
            jobQueue.enqueue(ArchiveIndexJobHandler.JOB_TYPE,
                    "{\"scannedFileId\":" + event.scannedFileId()
                            + ",\"nodeId\":" + node.getId() + "}",
                    "archive-index:" + event.scannedFileId());
            log.info("压缩包内容变化，重排索引任务 node={} path={} reactivated={}",
                    node.getId(), event.relativePath(), event.reactivated());
        });

        // 散图不需要做任何事：image_file 挂在 scanned_file_id 上，
        // 换了内容还是同一行；从 MISSING 恢复也一样——语义层从来没删过它。
    }

    @Override
    @Transactional
    public void onFileVanished(ScannedFileVanished event) {
        // 语义层不做任何删除：物理层已标记 MISSING，节点与页仍在，
        // 用户的阅读进度、收藏、手工元数据全部保留。
        // 读取时由 ImagePageService 检查物理状态并返回明确错误。
        log.debug("图片文件不可用: {}", event.relativePath());
    }

    private void attachLooseImage(ScannedFileDiscovered event) {
        // 幂等保护：同一个物理文件只登记一次
        if (fileRepository.findByScannedFileIdAndArchiveEntryNameIsNull(
                event.scannedFileId()).isPresent()) {
            return;
        }
        ImageNode node = indexer.directoryNodeFor(event.libraryId(), event.relativePath());
        fileRepository.saveAndFlush(new ImageFile(
                event.scannedFileId(), node.getId(), fileNameOf(event.relativePath())));
    }

    private void registerArchive(ScannedFileDiscovered event) {
        ImageNode node = indexer.archiveNodeFor(
                event.libraryId(), event.relativePath(), event.scannedFileId());
        if (node == null) {
            log.warn("压缩包与同名目录并存，跳过建索引（目录页已可读）: {}", event.relativePath());
            return;
        }

        // dedup_key 保证同一个压缩包不会被重复排入索引任务
        jobQueue.enqueue(ArchiveIndexJobHandler.JOB_TYPE,
                "{\"scannedFileId\":" + event.scannedFileId()
                        + ",\"nodeId\":" + node.getId() + "}",
                "archive-index:" + event.scannedFileId());
        log.info("压缩包已登记为节点 id={} path={}", node.getId(), event.relativePath());
    }

    private static String fileNameOf(String relativePath) {
        int lastSlash = relativePath.lastIndexOf('/');
        return lastSlash < 0 ? relativePath : relativePath.substring(lastSlash + 1);
    }
}