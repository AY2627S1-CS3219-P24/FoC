import { describe, expect, it } from 'vitest'
import { isOpenAt } from './supplierFormat'

const at = (hours: number, minutes = 0) => new Date(2026, 0, 1, hours, minutes)

describe('isOpenAt', () => {
  it('handles a same-day schedule', () => {
    expect(isOpenAt('09:00', '18:00', at(8, 59))).toBe(false)
    expect(isOpenAt('09:00', '18:00', at(9))).toBe(true)
    expect(isOpenAt('09:00', '18:00', at(18))).toBe(false)
  })

  it('handles a schedule that closes after midnight', () => {
    expect(isOpenAt('11:00', '02:00', at(23))).toBe(true)
    expect(isOpenAt('11:00', '02:00', at(1, 30))).toBe(true)
    expect(isOpenAt('11:00', '02:00', at(2))).toBe(false)
    expect(isOpenAt('11:00', '02:00', at(10))).toBe(false)
  })
})
