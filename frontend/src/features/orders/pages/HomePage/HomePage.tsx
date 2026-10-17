import { useEffect } from 'react'
import { useSearch } from '@tanstack/react-router'
import { rememberHomeMode } from '#/features/orders/utils/homeMode'
import { CourierHomePage } from '#/features/orders/pages/CourierHomePage/CourierHomePage'
import { RequesterHomePage } from '#/features/orders/pages/RequesterHomePage/RequesterHomePage'

export const HomePage = () => {
  const { mode } = useSearch({ from: '/authenticated/user/home' })

  useEffect(() => {
    if (mode) rememberHomeMode(mode)
  }, [mode])

  return mode === 'courier' ? <CourierHomePage /> : <RequesterHomePage />
}
