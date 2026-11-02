export const formatOrderTime = (value: string) =>
  new Intl.DateTimeFormat('en-SG', {
    dateStyle: 'medium',
    timeStyle: 'short',
    timeZone: 'Asia/Singapore',
  }).format(new Date(value))
export const expiryLabel = (minutes: number) =>
  minutes === 30
    ? '30 minutes'
    : `${minutes / 60} ${minutes === 60 ? 'hour' : 'hours'}`
export const remainingTime = (expiresAt: string, now: number) => {
  const minutes = Math.ceil((Date.parse(expiresAt) - now) / 60000)
  return minutes <= 0 ? 'Acceptance period ended' : `Expires in ${minutes} min`
}
