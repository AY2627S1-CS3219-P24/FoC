import { Link, useSearch, useNavigate } from '@tanstack/react-router'
import { useRef } from 'react'

import { establishSession } from '#/features/auth/lib/authSession'
import { useLogin } from '#/features/auth/hooks/useLogin'

import type { LoginFormValues } from './schemas/login.schema'
import { LoginForm } from './components/LoginForm'

import pageStyles from './LoginPage.module.scss'
import styles from '#/features/auth/styles/authPage.module.scss'

export const LoginPage = () => {
  const { registered } = useSearch({ from: '/auth/login' })
  const login = useLogin()
  const navigate = useNavigate()

  // Acquire synchronously and retain the lock until successful navigation.
  const submissionInProgress = useRef(false)

  const handleValidSubmit = (values: LoginFormValues) => {
    if (submissionInProgress.current || login.isSuccess) return

    submissionInProgress.current = true
    login.mutate(values, {
      onSuccess: (tokens) => {
        establishSession(tokens)
        void navigate({ to: '/app', replace: true })
      },
      onError: () => {
        submissionInProgress.current = false
      },
    })
  }

  return (
    <>
      <header className={styles.header}>
        <h1 className={styles.title}>Welcome Back</h1>
      </header>
      {!login.isSuccess && registered && (
        <p className={pageStyles.notice} role="status">
          Account created successfully. Please log in.
        </p>
      )}
      <LoginForm
        onValidSubmit={handleValidSubmit}
        isSubmitting={login.isPending}
        isSuccess={login.isSuccess}
        submitError={login.errorMessage}
      />
      <p className={styles.footer}>
        Don't have an account?{' '}
        <Link className={styles.link} to="/register">
          Sign up
        </Link>
      </p>
    </>
  )
}
