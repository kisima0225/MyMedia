package com.mymedia.upload.web;

import com.mymedia.upload.UploadSession;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

import java.time.Instant;
import java.util.List;

final class UploadDto {

    private UploadDto() {
    }

    /**
     * @param contentHash 64 位小写十六进制。<b>格式在这里就卡死</b>——
     *                    它会被拼进 SQL 参数与日志，早失败比晚失败好
     */
    record CreateRequest(
            @NotBlank String filename,
            @Positive long totalSize,
            @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String contentHash,
            @NotNull Long targetLibraryId) {
    }

    /**
     * @param receivedChunks 已经收到的分片下标，升序。客户端据此只补传缺的那些，
     *                       这就是断点续传的全部机制
     */
    record Response(
            Long id,
            String status,
            boolean instant,
            String filename,
            long totalSize,
            int chunkSize,
            int totalChunks,
            List<Integer> receivedChunks,
            Long scannedFileId,
            String relativePath,
            String lastError,
            Instant completedAt) {

        static Response from(UploadSession session, List<Integer> receivedChunks) {
            return new Response(
                    session.getId(),
                    session.getStatus().name(),
                    session.isInstant(),
                    session.getFilename(),
                    session.getTotalSize(),
                    session.getChunkSize(),
                    session.getTotalChunks(),
                    receivedChunks,
                    session.getScannedFileId(),
                    session.getRelativePath(),
                    session.getLastError(),
                    session.getCompletedAt());
        }
    }
}
