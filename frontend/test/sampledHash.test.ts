import { describe, it, expect } from 'vitest'
import { sampledHash, hasSubtleCrypto } from '@/lib/sampledHash'

const WINDOW = 1024 * 1024

/**
 * `crypto.getRandomValues` 单次调用上限是 65536 字节（Web Crypto 规范），
 * 大文件测试需要分块填充。返回值显式标注 `Uint8Array<ArrayBuffer>`（而不是
 * 默认宽化成 `Uint8Array<ArrayBufferLike>`）——否则后面 `new Blob([bytes])`
 * 在这个仓库的 TypeScript 版本下过不了类型检查（`BlobPart` 只接受具体的
 * `ArrayBuffer` 视图，不接受泛化的 `ArrayBufferLike`）。
 */
function randomBytes(size: number): Uint8Array<ArrayBuffer> {
  const bytes = new Uint8Array(size)
  const CHUNK = 65536
  for (let offset = 0; offset < size; offset += CHUNK) {
    crypto.getRandomValues(bytes.subarray(offset, Math.min(offset + CHUNK, size)))
  }
  return bytes
}

function blobOf(bytes: Uint8Array<ArrayBuffer>): Blob {
  return new Blob([bytes])
}

function concatBytes(...parts: Uint8Array<ArrayBuffer>[]): Uint8Array<ArrayBuffer> {
  const total = parts.reduce((sum, p) => sum + p.length, 0)
  const out = new Uint8Array(total)
  let offset = 0
  for (const part of parts) {
    out.set(part, offset)
    offset += part.length
  }
  return out
}

/** 与 `sampledHash.ts` 内部用的编码方式相同（8 字节大端序），但独立写一遍。 */
function bigEndianSize(size: number): Uint8Array<ArrayBuffer> {
  const buf = new ArrayBuffer(8)
  new DataView(buf).setBigUint64(0, BigInt(size), false)
  return new Uint8Array(buf)
}

async function digestHex(bytes: Uint8Array<ArrayBuffer>): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', bytes)
  return Array.from(new Uint8Array(digest))
    .map((b) => b.toString(16).padStart(2, '0'))
    .join('')
}

describe('hasSubtleCrypto', () => {
  it('在这个测试环境（Node 内置 Web Crypto）里为 true', () => {
    expect(hasSubtleCrypto()).toBe(true)
  })
})

describe('sampledHash', () => {
  it('空文件：只对"8 字节长度前缀（全零）"摘要', async () => {
    const expected = await digestHex(bigEndianSize(0))
    expect(await sampledHash(blobOf(new Uint8Array(0)))).toBe(expected)
  })

  it('小文件：长度前缀 + 整份内容一起摘要（独立构造期望值，验证拼接顺序与大端序编码）', async () => {
    const content = randomBytes(10)
    const expected = await digestHex(concatBytes(bigEndianSize(10), content))
    expect(await sampledHash(blobOf(content))).toBe(expected)
  })

  it('边界：恰好 2MB 时走"整份文件"路径，不是采样路径', async () => {
    const size = 2 * WINDOW
    const content = randomBytes(size)
    const expected = await digestHex(concatBytes(bigEndianSize(size), content))
    expect(await sampledHash(blobOf(content))).toBe(expected)
  })

  it('边界：2MB + 1 字节时切到"首 1MB + 尾 1MB"的采样路径（顺序：先头后尾）', async () => {
    const size = 2 * WINDOW + 1
    const content = randomBytes(size)
    const head = content.slice(0, WINDOW)
    const tail = content.slice(size - WINDOW)
    const expected = await digestHex(concatBytes(bigEndianSize(size), head, tail))
    expect(await sampledHash(blobOf(content))).toBe(expected)
  })

  it('大文件：只改中段字节，哈希不变（证明中段确实没有参与摘要）', async () => {
    const size = 5 * WINDOW
    const content = randomBytes(size)
    const original = await sampledHash(blobOf(content))

    const mutated = content.slice()
    mutated[Math.floor(size / 2)] ^= 0xff // 只翻转正中间一个字节
    expect(await sampledHash(blobOf(mutated))).toBe(original)
  })

  it('大文件：改头部或尾部字节，哈希必须变化（证明头尾确实参与了摘要）', async () => {
    const size = 5 * WINDOW
    const content = randomBytes(size)
    const original = await sampledHash(blobOf(content))

    const headMutated = content.slice()
    headMutated[0] ^= 0xff
    expect(await sampledHash(blobOf(headMutated))).not.toBe(original)

    const tailMutated = content.slice()
    tailMutated[size - 1] ^= 0xff
    expect(await sampledHash(blobOf(tailMutated))).not.toBe(original)
  })

  it('长度必须参与摘要：前缀相同、长度不同的文件哈希不同', async () => {
    const shortContent = randomBytes(1024)
    const longContent = new Uint8Array(2048)
    longContent.set(shortContent, 0)
    // 后 1024 字节全零——如果实现忘了把 size 编码进摘要，两者会被误判为同一份内容
    expect(await sampledHash(blobOf(shortContent))).not.toBe(await sampledHash(blobOf(longContent)))
  })

  it('输出是 64 个字符的小写十六进制，匹配后端 contentHash 的校验正则', async () => {
    const hash = await sampledHash(blobOf(randomBytes(128)))
    expect(hash).toMatch(/^[0-9a-f]{64}$/)
  })
})
