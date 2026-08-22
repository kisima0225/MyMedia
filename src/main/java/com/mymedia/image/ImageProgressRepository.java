package com.mymedia.image;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

interface ImageProgressRepository extends JpaRepository<ImageProgress, ImageProgress.Key> {

    Optional<ImageProgress> findByUserIdAndImageNodeId(Long userId, Long imageNodeId);

    /**
     * 「继续阅读」：还没翻到最后一页的书，按最近阅读时间倒序。
     *
     * <p>用 {@code totalPageCount}（子树总页数）而不是直属页数：一个节点若还有
     * 子目录没读完，它本来就不算读完 —— 这正是想要的语义。
     */
    @Query("""
            SELECT p FROM ImageProgress p, ImageNode n
            WHERE p.imageNodeId = n.id
              AND p.userId = :userId
              AND p.pageIndex < n.totalPageCount - 1
            ORDER BY p.updatedAt DESC
            """)
    List<ImageProgress> findContinueReading(@Param("userId") Long userId, Pageable pageable);
}
