import { describe, it, expect } from 'vitest'
import { next, prev, goTo, setMode, visiblePages, type ReaderState } from '@/lib/reader'

const state = (over: Partial<ReaderState> = {}): ReaderState => ({
  total: 10, index: 0, mode: 'single', direction: 'ltr', ...over,
})

describe('单页模式', () => {
  it('next 前进一页', () => {
    expect(next(state({ index: 3 })).index).toBe(4)
  })

  it('prev 后退一页', () => {
    expect(prev(state({ index: 3 })).index).toBe(2)
  })

  it('停在首尾，不环绕', () => {
    // 环绕会让「翻到最后一页」变成「回到第一页」——读者会以为自己点错了
    expect(next(state({ index: 9 })).index).toBe(9)
    expect(prev(state({ index: 0 })).index).toBe(0)
  })

  it('visiblePages 只有当前页', () => {
    expect(visiblePages(state({ index: 3 }))).toEqual([3])
  })
})

describe('双页模式', () => {
  it('visiblePages 给出成对的两页', () => {
    expect(visiblePages(state({ mode: 'double', index: 0 }))).toEqual([0, 1])
    expect(visiblePages(state({ mode: 'double', index: 2 }))).toEqual([2, 3])
  })

  it('next 一次跳两页', () => {
    expect(next(state({ mode: 'double', index: 0 })).index).toBe(2)
    expect(prev(state({ mode: 'double', index: 4 })).index).toBe(2)
  })

  it('索引始终对齐到偶数', () => {
    // 从单页模式的第 5 页切到双页，落点必须是 4-5 这一对而不是 5-6
    expect(setMode(state({ index: 5 }), 'double').index).toBe(4)
  })

  it('总页数为奇数时最后一屏只有一页', () => {
    expect(visiblePages(state({ total: 9, mode: 'double', index: 8 }))).toEqual([8])
  })

  it('末尾不越界', () => {
    expect(next(state({ total: 10, mode: 'double', index: 8 })).index).toBe(8)
  })
})

describe('翻页方向', () => {
  it('rtl 下 visiblePages 的两页左右对调', () => {
    // 日漫从右往左读：右边是第 n 页，左边是第 n+1 页
    expect(visiblePages(state({ mode: 'double', index: 2, direction: 'rtl' })))
      .toEqual([3, 2])
  })

  it('方向不改变 next 的语义', () => {
    // next 永远是"读下去"。左右哪个键触发 next 由组件的按键映射决定，
    // 不是状态机的事——把方向混进 next 会让这里变成一张真值表
    expect(next(state({ index: 3, direction: 'rtl' })).index).toBe(4)
  })
})

describe('goTo', () => {
  it('夹在合法范围内', () => {
    expect(goTo(state(), 999).index).toBe(9)
    expect(goTo(state(), -5).index).toBe(0)
  })

  it('双页模式下对齐到偶数', () => {
    expect(goTo(state({ mode: 'double' }), 5).index).toBe(4)
  })
})

describe('连续滚动模式', () => {
  it('visiblePages 给出全部页', () => {
    expect(visiblePages(state({ total: 3, mode: 'continuous' }))).toEqual([0, 1, 2])
  })
})

describe('空节点', () => {
  it('零页时不崩', () => {
    const empty = state({ total: 0 })
    expect(visiblePages(empty)).toEqual([])
    expect(next(empty).index).toBe(0)
    expect(goTo(empty, 5).index).toBe(0)
  })
})
