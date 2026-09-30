import { describe, expect, it } from 'vitest'
import {
  closesAfterMidnight,
  formatTodayHours,
  formatWeeklyHours,
  isOpenAt,
} from './openingHours'
import { DAYS_OF_WEEK } from '../types/supplier.types'
import type { DayOfWeek, OpeningHours } from '../types/supplier.types'

// say test, 2026-01-05 is Monday
const at = (dayOffset: number, hours: number, minutes = 0) =>
  new Date(2026, 0, 5 + dayOffset, hours, minutes)
const MONDAY = 0
const TUESDAY = 1
const SATURDAY = 5

const on = (
  days: ReadonlyArray<DayOfWeek>,
  opensAt: string,
  closesAt: string,
): Array<OpeningHours> =>
  days.map((dayOfWeek) => ({ dayOfWeek, opensAt, closesAt }))

const weekdays = DAYS_OF_WEEK.slice(0, 5)

describe('isOpenAt', () => {
  it('uses the hours of the current day', () => {
    const hours = on(weekdays, '09:00:00', '18:00:00')
    expect(isOpenAt(hours, at(MONDAY, 8, 59))).toBe(false)
    expect(isOpenAt(hours, at(MONDAY, 9))).toBe(true)
    expect(isOpenAt(hours, at(MONDAY, 18))).toBe(false)
    expect(isOpenAt(hours, at(SATURDAY, 12))).toBe(false)
  })

  it('carries hours past midnight into the next morning', () => {
    const hours = on(['MONDAY'], '18:00', '02:00')
    expect(isOpenAt(hours, at(MONDAY, 23))).toBe(true)
    expect(isOpenAt(hours, at(TUESDAY, 1, 30))).toBe(true)
    expect(isOpenAt(hours, at(TUESDAY, 2))).toBe(false)
    expect(isOpenAt(hours, at(MONDAY, 1))).toBe(false) // Monday's early hours belong to Sunday, which is closed
  })
})

describe('formatWeeklyHours', () => {
  it('groups consecutive days with the same hours', () => {
    expect(
      formatWeeklyHours([
        ...on(weekdays, '08:00:00', '20:00:00'),
        ...on(['SATURDAY'], '10:00:00', '16:00:00'),
      ]),
    ).toEqual(['Mon – Fri 08:00 – 20:00', 'Sat 10:00 – 16:00'])
  })

  it('summarises identical hours on every day', () => {
    expect(formatWeeklyHours(on(DAYS_OF_WEEK, '09:00', '18:00'))).toEqual([
      'Daily 09:00 – 18:00',
    ])
  })

  it('marks hours that run past midnight and handles no hours', () => {
    expect(formatWeeklyHours(on(['FRIDAY'], '18:00', '03:00'))).toEqual([
      'Fri 18:00 – 03:00 (next day)',
    ])
    expect(formatWeeklyHours([])).toEqual(['Closed'])
  })
})

describe('formatTodayHours', () => {
  it("shows today's hours or that it is closed", () => {
    const hours = on(weekdays, '09:00', '18:00')
    expect(formatTodayHours(hours, at(MONDAY, 12))).toBe('09:00 – 18:00')
    expect(formatTodayHours(hours, at(SATURDAY, 12))).toBe('Closed today')
  })
})

describe('closesAfterMidnight', () => {
  it('is true when the closing time is earlier than the opening time', () => {
    expect(
      closesAfterMidnight({
        dayOfWeek: 'MONDAY',
        opensAt: '18:00',
        closesAt: '02:00',
      }),
    ).toBe(true)
    expect(
      closesAfterMidnight({
        dayOfWeek: 'MONDAY',
        opensAt: '09:00',
        closesAt: '18:00',
      }),
    ).toBe(false)
  })
})
