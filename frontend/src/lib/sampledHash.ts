/** 首尾各采样的字节数，必须与后端 shared.SampledHash.SAMPLE_WINDOW 完全一致。 */
const SAMPLE_WINDOW = 1024 * 1024

/**
 * `crypto.subtle` 只在安全上下文（HTTPS 或 localhost）里可用。调用方应先检查这个
 * 再决定是否显示/启用上传入口，而不是让页面在一个 `undefined.digest` 上崩掉。
 */
export function hasSubtleCrypto(): boolean {
  return typeof crypto !== 'undefined' && !!crypto.subtle
}

/**
 * 复刻后端 `com.mymedia.shared.SampledHash.of(Path, long)`（已逐字读过该源码）：
 *
 * 1. 8 字节大端序的文件长度先参与摘要；
 * 2. 若 `size <= 2 * SAMPLE_WINDOW`（2MB）：整份文件参与摘要；
 *    否则只取首 1MB + 尾 1MB（顺序：先头后尾）；
 * 3. 对上述内容整体做一次 SHA-256，转成 64 个字符的小写十六进制。
 *
 * Java 端是三次独立的 `MessageDigest.update()`（长度、头、尾），这里改成先把
 * 三段字节拼成一个连续缓冲区、一次性调用 `crypto.subtle.digest`——SHA-256 是纯
 * 顺序处理的分组哈希算法，对同一段连续字节，分次 `update()` 与一次性摘要的结果
 * 完全相同，这一点已经用 Node 内置 `crypto`（`test/sampledHash.test.ts`）与后端
 * 源码逻辑分别核对过。
 *
 * **这个算法一个字节都不能改**：算出来的值要在 `POST /api/upload/sessions` 时
 * 用于秒传比对、在分片合并完成后用于最终校验，两边算法对不上，秒传永远不命中、
 * 断点续传合并后永远校验失败。
 */
export async function sampledHash(file: Blob): Promise<string> {
  const size = file.size
  const sizeBuffer = new ArrayBuffer(8)
  new DataView(sizeBuffer).setBigUint64(0, BigInt(size), false)

  const sampleBlob =
    size <= 2 * SAMPLE_WINDOW
      ? file
      : new Blob([file.slice(0, SAMPLE_WINDOW), file.slice(size - SAMPLE_WINDOW, size)])

  const sampleBytes = await sampleBlob.arrayBuffer()
  const combined = new Uint8Array(8 + sampleBytes.byteLength)
  combined.set(new Uint8Array(sizeBuffer), 0)
  combined.set(new Uint8Array(sampleBytes), 8)

  const digest = await crypto.subtle.digest('SHA-256', combined)
  return toHex(digest)
}

function toHex(buffer: ArrayBuffer): string {
  return Array.from(new Uint8Array(buffer))
    .map((b) => b.toString(16).padStart(2, '0'))
    .join('')
}
