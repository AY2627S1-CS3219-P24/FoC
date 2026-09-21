import { Outlet } from '@tanstack/react-router'
import './AuthLayout.scss'

/**
 * Shared layout for registration and login pages.
 * Provides the background, card, and branding for auth routes.
 */
export const AuthLayout = () => {
  return (
    <main className="auth-layout">
      <div className="auth-layout__card">
        <p className="auth-layout__brand">FoC</p>
        <Outlet />
      </div>
    </main>
  )
}
