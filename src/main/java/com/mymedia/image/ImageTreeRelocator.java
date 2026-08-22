package com.mymedia.image;

import com.mymedia.library.LibraryDomain;
import com.mymedia.library.LibraryService;
import com.mymedia.scan.event.ScannedFileRelocated;
import com.mymedia.shared.MaterializedPath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 把物理层的「文件移动」翻译成语义层的「节点改名 / 子树移动」。
 *
 * <p>为什么需要它：物理层只知道一个个文件换了路径。若语义层照单全收地
 * 逐文件重挂，一个目录改名就会变成"建一个新节点、丢下一个旧节点"——
 * 旧节点上的阅读进度、收藏、阅读模式覆盖、刮削结果全部报废。
 *
 * <p>判定规则：把本轮的移动按（旧目录 → 新目录）分组。如果某组的文件数
 * <b>等于</b>旧目录节点下的全部文件数，说明整个目录搬走了，于是改名/移动节点本身；
 * 否则只是零散文件搬家，逐个重挂即可。
 *
 * <p>缓冲区是进程内的：应用在扫描中途崩溃会丢掉本轮判定，下次扫描退化成
 * 逐文件重挂。代价是一次目录级用户数据丢失，换来的是不引入额外的状态表——
 * 在单实例部署下这个取舍是划算的。
 */
@Service
class ImageTreeRelocator {

    private static final Logger log = LoggerFactory.getLogger(ImageTreeRelocator.class);

    private final Map<Long, List<Relocation>> pending = new ConcurrentHashMap<>();

    private final LibraryService libraryService;
    private final ImageNodeIndexer indexer;
    private final ImageNodeRepository nodeRepository;
    private final ImageFileRepository fileRepository;
    private final JdbcTemplate jdbc;

    ImageTreeRelocator(LibraryService libraryService,
                       ImageNodeIndexer indexer,
                       ImageNodeRepository nodeRepository,
                       ImageFileRepository fileRepository,
                       JdbcTemplate jdbc) {
        this.libraryService = libraryService;
        this.indexer = indexer;
        this.nodeRepository = nodeRepository;
        this.fileRepository = fileRepository;
        this.jdbc = jdbc;
    }

    @EventListener
    void on(ScannedFileRelocated event) {
        if (libraryService.getById(event.libraryId()).getDomain() != LibraryDomain.IMAGE) {
            return;
        }
        pending.computeIfAbsent(event.libraryId(),
                        key -> Collections.synchronizedList(new ArrayList<>()))
                .add(new Relocation(event.scannedFileId(), event.oldPath(), event.newPath()));
    }

    @Transactional
    void applyPending(Long libraryId) {
        List<Relocation> batch = pending.remove(libraryId);
        if (batch == null || batch.isEmpty()) {
            return;
        }

        Map<DirectoryMove, List<Relocation>> groups = new LinkedHashMap<>();
        for (Relocation relocation : batch) {
            DirectoryMove move = new DirectoryMove(
                    directoryOf(relocation.oldPath()), directoryOf(relocation.newPath()));
            groups.computeIfAbsent(move, key -> new ArrayList<>()).add(relocation);
        }

        // 浅的目录先处理：父目录一旦整体搬走，它下面各层的组会自动落到正确位置，
        // 后续的 resolveDirectory 会返回 null，走逐文件重挂的兜底路径（此时是空操作）。
        List<DirectoryMove> ordered = groups.keySet().stream()
                .sorted(Comparator.comparingInt(move -> segmentCount(move.oldDirectory())))
                .toList();

        for (DirectoryMove move : ordered) {
            List<Relocation> moved = groups.get(move);
            if (!tryMoveWholeDirectory(libraryId, move, moved)) {
                reattachIndividually(libraryId, moved);
            }
        }
    }

    private boolean tryMoveWholeDirectory(Long libraryId, DirectoryMove move,
                                          List<Relocation> moved) {
        if (move.oldDirectory().equals(move.newDirectory())) {
            return false;      // 同目录内改名，节点结构不变（压缩包除外，交给兜底路径）
        }
        ImageNode oldNode = indexer.resolveDirectory(libraryId, move.oldDirectory());
        if (oldNode == null || oldNode.getSourceKind() != ImageSourceKind.DIRECTORY) {
            return false;
        }
        // 旧目录下的文件全都在本组里 → 整个目录搬走了
        if (fileRepository.countByNodeId(oldNode.getId()) != moved.size()) {
            return false;
        }

        // 顶层目录整体改名时，组里的文件可能全在深层子目录下：底层目录的
        // 「整组搬走」只是表象，真正移动的是最顶层的祖先。逐层上溯——
        // 只要祖先的整棵子树都搬走了（子树页数 == 本组移动数），就继续上溯，
        // 从最顶层候选开始尝试搬迁；目标位置被真实内容占据时退到下一层。
        List<String> oldSegments = segmentsOf(move.oldDirectory());
        List<String> newSegments = segmentsOf(move.newDirectory());
        List<ImageNode> chain = new ArrayList<>();
        ImageNode current = oldNode;
        int level = oldSegments.size();
        while (current != null) {
            chain.add(current);
            if (!wholeSubtreeMoved(current, oldSegments, level, moved)) {
                break;
            }
            level--;
            if (level == 0) {
                break;
            }
            current = nodeRepository.findById(current.getParentId()).orElse(null);
        }
        Collections.reverse(chain);

        // chain 从最顶层祖先开始：第 i 个候选对应旧目录的前 (topLevel + i) 段
        int topLevel = oldSegments.size() - (chain.size() - 1);
        for (int i = 0; i < chain.size(); i++) {
            String oldDir = joinSegments(oldSegments, topLevel + i);
            String newDir = joinSegments(newSegments, topLevel + i);
            if (relocateNode(libraryId, chain.get(i),
                    parentDirectoryOf(newDir), lastSegmentOf(newDir))) {
                return true;
            }
        }
        return false;
    }

    /** 祖先目录的整棵子树（物化路径前缀）都随本组搬走了。 */
    private boolean wholeSubtreeMoved(ImageNode node, List<String> oldSegments,
                                      int level, List<Relocation> moved) {
        long pagesInSubtree = jdbc.queryForObject("""
                SELECT count(*) FROM image_file f
                JOIN image_node n ON n.id = f.node_id
                WHERE n.materialized_path LIKE ? || '%'
                """, Long.class, node.getMaterializedPath());
        String prefix = joinSegments(oldSegments, level);
        long relocated = moved.stream()
                .filter(relocation -> relocation.oldPath().startsWith(prefix + "/"))
                .count();
        return pagesInSubtree == relocated;
    }

    private void reattachIndividually(Long libraryId, List<Relocation> moved) {
        for (Relocation relocation : moved) {
            var archiveNode = nodeRepository.findByArchiveScannedFileId(relocation.scannedFileId());
            if (archiveNode.isPresent()) {
                // 压缩包换了位置或改了名：节点跟着走，页仍挂在它下面
                relocateNode(libraryId, archiveNode.get(),
                        directoryOf(relocation.newPath()),
                        stripExtension(fileNameOf(relocation.newPath())));
                continue;
            }
            ImageNode target = indexer.directoryNodeFor(libraryId, relocation.newPath());
            fileRepository.findByScannedFileId(relocation.scannedFileId())
                    .forEach(file -> file.reattachTo(target.getId()));
        }
    }

    /**
     * 把节点挪到新父之下并改成新名字，然后<b>一条 UPDATE 重写整棵子树的路径</b>。
     *
     * @return 是否完成；目标位置被真实内容占据时返回 {@code false}，由调用方兜底
     */
    private boolean relocateNode(Long libraryId, ImageNode node,
                                 String newParentDirectory, String newName) {
        ImageNode newParent = newParentDirectory.isEmpty()
                ? null
                : indexer.directoryPathNode(libraryId, newParentDirectory);
        Long newParentId = newParent == null ? null : newParent.getId();

        if (newParentId != null && newParentId.equals(node.getId())) {
            return false;      // 不能把节点挂到自己下面
        }

        if (!clearGhostAt(libraryId, newParentId, newName, node.getId())) {
            return false;
        }

        String oldPathPrefix = node.getMaterializedPath();
        String oldSortPrefix = node.getSortPath();
        String newParentPath = newParent == null
                ? MaterializedPath.rootPath() : newParent.getMaterializedPath();
        String newParentSortPath = newParent == null
                ? MaterializedPath.rootPath() : newParent.getSortPath();

        // 先改名（重算 sort_key），再移动（重算两条路径与深度）——
        // moveTo 用的是改名后的 sort_key，顺序不能反。
        node.rename(newName, newParentSortPath);
        node.moveTo(newParentId, newParentPath, newParentSortPath);
        nodeRepository.saveAndFlush(node);

        rewriteSubtree(libraryId, node.getId(),
                oldPathPrefix, node.getMaterializedPath(),
                oldSortPrefix, node.getSortPath());

        log.info("图片节点已跟随目录移动 id={} -> {}", node.getId(), node.getMaterializedPath());
        return true;
    }

    /**
     * 目标位置若已被一个空壳节点占着，先清掉。
     *
     * <p>空壳从哪来：扫描时先发布「发现新文件」再做改名配对，前者已经按新路径
     * 建好了节点，随后配对成功、新的 scanned_file 被删除，节点就空在那里了。
     * 不清掉它，改名过去会撞上兄弟唯一约束。
     *
     * @return 目标位置是否可用
     */
    private boolean clearGhostAt(Long libraryId, Long parentId, String name, Long movingNodeId) {
        var occupant = parentId == null
                ? nodeRepository.findByLibraryIdAndParentIdIsNullAndName(libraryId, name)
                : nodeRepository.findByLibraryIdAndParentIdAndName(libraryId, parentId, name);
        if (occupant.isEmpty() || occupant.get().getId().equals(movingNodeId)) {
            return true;
        }
        ImageNode ghost = occupant.get();
        boolean empty = fileRepository.countByNodeId(ghost.getId()) == 0
                && nodeRepository.findByParentIdOrderBySortKey(ghost.getId()).isEmpty();
        if (!empty) {
            log.debug("目标位置已有真实内容，放弃整目录移动: {}", name);
            return false;
        }
        nodeRepository.delete(ghost);
        nodeRepository.flush();
        return true;
    }

    /**
     * 子树路径重写：<b>一条前缀替换 UPDATE，不逐层递归</b>（spec §7.1 明确要求）。
     *
     * <p>深度直接在 SQL 里由新路径算出来：路径形如 {@code /1/17/93/}，
     * 深度就是斜杠数减一。
     *
     * <p>SET 子句右侧的 {@code materialized_path} 取的是<b>旧值</b>——
     * PostgreSQL 的 UPDATE 用行的原始值求值所有表达式，所以一条语句里
     * 既能读旧值又能写新值。
     */
    private void rewriteSubtree(Long libraryId, Long nodeId,
                                String oldPathPrefix, String newPathPrefix,
                                String oldSortPrefix, String newSortPrefix) {
        // 实体层的改动先落盘，否则下面这条原生 SQL 看不到它
        nodeRepository.flush();

        jdbc.update("""
                UPDATE image_node
                SET materialized_path = ? || substring(materialized_path FROM ?),
                    sort_path         = ? || substring(sort_path FROM ?),
                    depth = length(? || substring(materialized_path FROM ?))
                            - length(replace(? || substring(materialized_path FROM ?), '/', ''))
                            - 1
                WHERE library_id = ?
                  AND materialized_path LIKE ?
                  AND id <> ?
                """,
                newPathPrefix, oldPathPrefix.length() + 1,
                newSortPrefix, oldSortPrefix.length() + 1,
                newPathPrefix, oldPathPrefix.length() + 1,
                newPathPrefix, oldPathPrefix.length() + 1,
                libraryId,
                oldPathPrefix + "%",
                nodeId);
    }

    private static String directoryOf(String relativePath) {
        int lastSlash = relativePath.lastIndexOf('/');
        return lastSlash < 0 ? "" : relativePath.substring(0, lastSlash);
    }

    private static String fileNameOf(String relativePath) {
        int lastSlash = relativePath.lastIndexOf('/');
        return lastSlash < 0 ? relativePath : relativePath.substring(lastSlash + 1);
    }

    private static String parentDirectoryOf(String directoryPath) {
        int lastSlash = directoryPath.lastIndexOf('/');
        return lastSlash < 0 ? "" : directoryPath.substring(0, lastSlash);
    }

    private static String lastSegmentOf(String directoryPath) {
        int lastSlash = directoryPath.lastIndexOf('/');
        return lastSlash < 0 ? directoryPath : directoryPath.substring(lastSlash + 1);
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot <= 0 ? fileName : fileName.substring(0, dot);
    }

    private static int segmentCount(String directoryPath) {
        return directoryPath.isEmpty() ? 0 : directoryPath.split("/").length;
    }

    private static List<String> segmentsOf(String directoryPath) {
        return directoryPath.isEmpty()
                ? List.of()
                : List.of(directoryPath.split("/"));
    }

    private static String joinSegments(List<String> segments, int count) {
        return String.join("/", segments.subList(0, count));
    }

    private record Relocation(Long scannedFileId, String oldPath, String newPath) {
    }

    private record DirectoryMove(String oldDirectory, String newDirectory) {
    }
}