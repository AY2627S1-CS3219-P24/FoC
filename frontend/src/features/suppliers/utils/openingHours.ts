import { DAYS_OF_WEEK } from '../types/supplier.types'
import type { DayOfWeek, OpeningHours } from '../types/supplier.types'

export const dayLabels: Record<DayOfWeek, string> = {
  MONDAY: 'Mon',
  TUESDAY: 'Tue',
  WEDNESDAY: 'Wed',
  THURSDAY: 'Thu',
  FRIDAY: 'Fri',
  SATURDAY: 'Sat',
  SUNDAY: 'Sun',
}

export const toHourMinute = (time: string) => time.slice(0, 5)

export const closesAfterMidnight = ({ opensAt, closesAt }: OpeningHours) =>
  toHourMinute(closesAt) < toHourMinute(opensAt)

// count monday as the first day
const dayIndexOf = (date: Date) => (date.getDay() + 6) % 7

const timeOf = (date: Date) =>
  `${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`

/**
 * if a supplier is open `now`
 */
export const isOpenAt = (hours: Array<OpeningHours>, now: Date) => {
  const today = DAYS_OF_WEEK[dayIndexOf(now)]
  const yesterday = DAYS_OF_WEEK[(dayIndexOf(now) + 6) % 7]
  const time = timeOf(now)

  return hours.some((entry) => {
    const opensAt = toHourMinute(entry.opensAt)
    const closesAt = toHourMinute(entry.closesAt)
    const overnight = closesAt < opensAt
    if (entry.dayOfWeek === today) {
      return time >= opensAt && (overnight || time < closesAt)
    }
    return entry.dayOfWeek === yesterday && overnight && time < closesAt
  })
}

const formatRange = (entry: OpeningHours) =>
  `${toHourMinute(entry.opensAt)} – ${toHourMinute(entry.closesAt)}${closesAfterMidnight(entry) ? ' (next day)' : ''}`

/**
 * design that the grouping consecutive days open same hours will be like
 * ["Mon – Fri 08:00 – 20:00", "Sat 10:00 – 16:00"].
 */
export const formatWeeklyHours = (hours: Array<OpeningHours>) => {
  const byDay = new Map(hours.map((entry) => [entry.dayOfWeek, entry]))
  if (byDay.size === 0) return ['Closed']

  const lines: Array<string> = []
  let start = 0
  while (start < DAYS_OF_WEEK.length) {
    const entry = byDay.get(DAYS_OF_WEEK[start])
    if (!entry) {
      start += 1
      continue
    }
    const range = formatRange(entry)
    let end = start
    while (
      end + 1 < DAYS_OF_WEEK.length &&
      byDay.has(DAYS_OF_WEEK[end + 1]) &&
      formatRange(byDay.get(DAYS_OF_WEEK[end + 1])!) === range
    ) {
      end += 1
    }
    if (start === 0 && end === DAYS_OF_WEEK.length - 1)
      return [`Daily ${range}`]
    const days =
      start === end
        ? dayLabels[DAYS_OF_WEEK[start]]
        : `${dayLabels[DAYS_OF_WEEK[start]]} – ${dayLabels[DAYS_OF_WEEK[end]]}`
    lines.push(`${days} ${range}`)
    start = end + 1
  }
  return lines
}

export const formatTodayHours = (hours: Array<OpeningHours>, now: Date) => {
  const entry = hours.find(
    ({ dayOfWeek }) => dayOfWeek === DAYS_OF_WEEK[dayIndexOf(now)],
  )
  return entry ? formatRange(entry) : 'Closed today'
}
