import { describe, it, expect } from 'vitest'
import { sliceFile, missingChunks, uploadProgress } from '@/lib/chunker'

const blobOf = (size: number) => new Blob([new Uint8Array(size)])

describe('sliceFile', () => {
  it('整除时切成等份', () => {
    const parts = sliceFile(blobOf(300), 100)
    expect(parts).toHaveLength(3)
    expect(parts[0]).toEqual({ index: 0, start: 0, end: 100 })
    expect(parts[2]).toEqual({ index: 2, start: 200, end: 300 })
  })

  it('不整除时最后一片较短', () => {
    const parts = sliceFile(blobOf(250), 100)
    expect(parts).toHaveLength(3)
    expect(parts[2]).toEqual({ index: 2, start: 200, end: 250 })
  })

  it('文件比一片还小时只有一片', () => {
    expect(sliceFile(blobOf(50), 100)).toEqual([{ index: 0, start: 0, end: 50 }])
  })

  it('空文件切出零片', () => {
    expect(sliceFile(blobOf(0), 100)).toEqual([])
  })

  it('片大小非法时抛错而不是产出无限片', () => {
    // 一个 0 或负数的 chunkSize 会让上传循环永远跑不完
    expect(() => sliceFile(blobOf(100), 0)).toThrow()
    expect(() => sliceFile(blobOf(100), -1)).toThrow()
  })
})

describe('missingChunks', () => {
  it('算出还没传的片号', () => {
    expect(missingChunks(5, [0, 2, 4])).toEqual([1, 3])
  })

  it('全传完时为空', () => {
    expect(missingChunks(3, [0, 1, 2])).toEqual([])
  })

  it('一片都没传时全都缺', () => {
    expect(missingChunks(3, [])).toEqual([0, 1, 2])
  })

  it('忽略越界与重复的已收片号', () => {
    // 后端返回的 received 是权威的，但前端不该因为一个意外值就算错
    expect(missingChunks(3, [0, 0, 99, -1])).toEqual([1, 2])
  })
})

describe('uploadProgress', () => {
  it('给出 0 到 1 之间的比例', () => {
    expect(uploadProgress(4, [0, 1])).toBe(0.5)
    expect(uploadProgress(4, [])).toBe(0)
    expect(uploadProgress(4, [0, 1, 2, 3])).toBe(1)
  })

  it('零片时算作已完成而不是除以零', () => {
    expect(uploadProgress(0, [])).toBe(1)
  })
})
