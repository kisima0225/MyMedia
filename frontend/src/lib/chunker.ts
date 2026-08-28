export interface ChunkRange {
  readonly index: number
  readonly start: number
  readonly end: number
}

export function sliceFile(file: Blob, chunkSize: number): ChunkRange[] {
  if (!Number.isFinite(chunkSize) || chunkSize <= 0) {
    throw new RangeError(`分片大小必须为正数，收到 ${chunkSize}`)
  }
  const parts: ChunkRange[] = []
  for (let start = 0; start < file.size; start += chunkSize) {
    parts.push({
      index: parts.length,
      start,
      end: Math.min(start + chunkSize, file.size),
    })
  }
  return parts
}

/** 断点续传的核心：后端告诉我们收到了哪些片，我们只补缺的。 */
export function missingChunks(total: number, received: number[]): number[] {
  const seen = new Set(received.filter((i) => Number.isInteger(i) && i >= 0 && i < total))
  return Array.from({ length: total }, (_, i) => i).filter((i) => !seen.has(i))
}

export function uploadProgress(total: number, done: number[]): number {
  return total === 0 ? 1 : done.length / total
}
