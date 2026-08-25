package com.mymedia.upload;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

interface UploadSessionRepository extends JpaRepository<UploadSession, Long> {

    /**
     * 把会话从「收片中」推进到「合并中」，<b>只有一个调用者能拿到 1</b>。
     *
     * <p>两片几乎同时到达时两个线程都会看到「片齐了」。判断与写入压成一条
     * 条件 UPDATE，竞争就由数据库解决——和计划 05 的
     * {@code UPDATE … WHERE cover_asset_id IS NULL} 是同一个手法。
     *
     * <p>{@code clearAutomatically} 让持久化上下文里那份旧状态失效，
     * 否则同一个事务里随后读到的还是 {@code RECEIVING}。
     */
    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("""
            UPDATE UploadSession s
               SET s.status = com.mymedia.upload.UploadStatus.ASSEMBLING
             WHERE s.id = :id
               AND s.status = com.mymedia.upload.UploadStatus.RECEIVING
            """)
    int markAssemblingIfReceiving(@Param("id") Long id);
}
