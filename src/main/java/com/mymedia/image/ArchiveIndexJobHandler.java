package com.mymedia.image;

import com.mymedia.jobs.Job;
import com.mymedia.jobs.JobHandler;
import com.mymedia.library.LibraryService;
import com.mymedia.scan.ScannedFile;
import com.mymedia.scan.ScannedFileQueryService;
import com.mymedia.shared.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.util.List;

/**
 * 为一个压缩包建立页索引。
 *
 * <p>为什么要建索引：不建的话每次翻页都得重新打开压缩包、重新读一遍中央目录区。
 * 一本 500 页的漫画从头读到尾就是 500 次目录区扫描。索引一次写进 {@code image_file}，
 * 之后翻页只按 id 定位条目。
 *
 * <p>为什么索引完成后要重算计数：ARCHIVE_INDEX 排在 LibraryScanCompleted 之后执行，
 * 扫描收尾的重算（{@link ImageLibraryRecalculator#recalculate}）跑在前面，等本任务把页
 * 填进 {@code image_file} 时，节点计数已经算完了。不在这里刷新一次，新索引的压缩包
 * 会一直显示 0 页、readable=false，直到下一次扫描。复用批量重算（每条库几条 SQL），
 * 与「批量重算，不做实时递归统计」的设计一致。
 */
@Component
class ArchiveIndexJobHandler implements JobHandler {

    static final String JOB_TYPE = "ARCHIVE_INDEX";

    private static final Logger log = LoggerFactory.getLogger(ArchiveIndexJobHandler.class);

    private final ObjectMapper objectMapper;
    private final ScannedFileQueryService scannedFiles;
    private final LibraryService libraryService;
    private final ImageArchiveReader archiveReader;
    private final ImageNodeRepository nodeRepository;
    private final ImageFileRepository fileRepository;
    private final ImageLibraryRecalculator libraryRecalculator;

    ArchiveIndexJobHandler(ObjectMapper objectMapper,
                           ScannedFileQueryService scannedFiles,
                           LibraryService libraryService,
                           ImageArchiveReader archiveReader,
                           ImageNodeRepository nodeRepository,
                           ImageFileRepository fileRepository,
                           ImageLibraryRecalculator libraryRecalculator) {
        this.objectMapper = objectMapper;
        this.scannedFiles = scannedFiles;
        this.libraryService = libraryService;
        this.archiveReader = archiveReader;
        this.nodeRepository = nodeRepository;
        this.fileRepository = fileRepository;
        this.libraryRecalculator = libraryRecalculator;
    }

    @Override
    public String jobType() {
        return JOB_TYPE;
    }

    @Override
    @Transactional
    public void handle(Job job) throws Exception {
        JsonNode payload = objectMapper.readTree(job.getPayload());
        JsonNode idNode = payload.get("scannedFileId");
        if (idNode == null || !idNode.canConvertToLong()) {
            throw new IllegalArgumentException(
                    "ARCHIVE_INDEX 任务缺少 scannedFileId: " + job.getPayload());
        }
        Long scannedFileId = idNode.asLong();

        ScannedFile scanned = scannedFiles.getById(scannedFileId);
        ImageNode node = nodeRepository.findByArchiveScannedFileId(scannedFileId)
                .orElseThrow(() -> new NotFoundException(
                        "压缩包没有对应节点 scannedFileId=" + scannedFileId));

        Path root = Path.of(libraryService.getById(scanned.getLibraryId()).getRootPath());
        Path archive = root.resolve(scanned.getRelativePath());

        List<ArchivePage> pages = archiveReader.listPages(archive);

        // 幂等：重建前先清掉旧行。页码由本次排序整体决定，
        // 增量比对不值得 —— 归档内容变了本来就该整本重排。
        fileRepository.deleteByScannedFileId(scannedFileId);
        fileRepository.flush();

        for (int i = 0; i < pages.size(); i++) {
            fileRepository.save(new ImageFile(
                    scannedFileId, node.getId(), pages.get(i).entryName(), i));
        }
        fileRepository.flush();

        // 页是在扫描收尾的重算之后才写进来的，必须自己刷新节点计数，
        // 否则新索引的压缩包 directPageCount=0、readable=false，要等下次扫描才可读。
        libraryRecalculator.recalculate(scanned.getLibraryId());

        log.info("压缩包索引完成 node={} pages={} path={}",
                node.getId(), pages.size(), scanned.getRelativePath());
    }
}