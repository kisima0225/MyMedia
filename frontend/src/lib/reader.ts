export type ReaderMode = 'single' | 'double' | 'continuous'
export type ReaderDirection = 'ltr' | 'rtl'

export interface ReaderState {
  readonly total: number
  readonly index: number
  readonly mode: ReaderMode
  readonly direction: ReaderDirection
}

const step = (mode: ReaderMode) => (mode === 'double' ? 2 : 1)

/** 双页模式下索引必须落在偶数上，否则每一屏的配对会整体错位一页。 */
const align = (index: number, mode: ReaderMode) =>
  mode === 'double' ? index - (index % 2) : index

const clamp = (index: number, total: number) =>
  total === 0 ? 0 : Math.min(Math.max(index, 0), total - 1)

/** 读下去。**停在末页，不环绕**——环绕会让读者以为自己点错了。 */
export function next(state: ReaderState): ReaderState {
  return goTo(state, state.index + step(state.mode))
}

export function prev(state: ReaderState): ReaderState {
  return goTo(state, state.index - step(state.mode))
}

export function goTo(state: ReaderState, index: number): ReaderState {
  return { ...state, index: align(clamp(index, state.total), state.mode) }
}

export function setMode(state: ReaderState, mode: ReaderMode): ReaderState {
  return { ...state, mode, index: align(clamp(state.index, state.total), mode) }
}

/**
 * 当前屏上显示哪几页，**按显示顺序**返回。
 *
 * `rtl` 下双页的两页左右对调：日漫从右往左读，右边是第 n 页、左边是 n+1 页。
 * 把这个对调放在这里而不是放进模板，是因为它是一条规则、不是一段样式。
 */
export function visiblePages(state: ReaderState): number[] {
  if (state.total === 0) return []

  if (state.mode === 'continuous') {
    return Array.from({ length: state.total }, (_, i) => i)
  }
  if (state.mode === 'single') {
    return [state.index]
  }
  const pair = state.index + 1 < state.total ? [state.index, state.index + 1] : [state.index]
  return state.direction === 'rtl' ? [...pair].reverse() : pair
}
