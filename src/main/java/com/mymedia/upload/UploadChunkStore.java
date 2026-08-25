package com.mymedia.upload;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * {@code upload_chunk} 的读写。
 *
 * <p>用 {@code JdbcTemplate} 而不是 JPA：它只有三个动作，其中「记录一片」
 * 是一条带 {@code ON CONFLICT} 的 upsert——那正是 JPA 表达起来最别扭、
 * 而 SQL 表达起来最自然的一类操作。与计划 04 的
 * {@code ImageLibraryRecalculator} 是同一条取舍。
 */
@Component
class UploadChunkStore {

    private final JdbcTemplate jdbc;

    UploadChunkStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 同一片重传是幂等的：主键冲突时更新大小与时间，不报错。 */
    @Transactional
    void record(Long sessionId, int index, long size) {
        jdbc.update("""
                INSERT INTO upload_chunk (session_id, chunk_index, size)
                VALUES (?, ?, ?)
                ON CONFLICT (session_id, chunk_index)
                DO UPDATE SET size = EXCLUDED.size, received_at = now()
                """, sessionId, index, size);
    }

    @Transactional(readOnly = true)
    List<Integer> receivedIndexes(Long sessionId) {
        return jdbc.queryForList(
                "SELECT chunk_index FROM upload_chunk WHERE session_id = ? ORDER BY chunk_index",
                Integer.class, sessionId);
    }

    @Transactional(readOnly = true)
    int count(Long sessionId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM upload_chunk WHERE session_id = ?", Integer.class, sessionId);
        return count == null ? 0 : count;
    }
}
