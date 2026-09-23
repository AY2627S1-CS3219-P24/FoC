import { Link, useSearch } from '@tanstack/react-router'
import pageStyles from './LoginPage.module.scss'
import { LoginForm } from './components/LoginForm'
import styles from '#/features/auth/styles/authPage.module.scss'

export const LoginPage = () => {
  const { registered } = useSearch({ from: '/auth/login' })
  return (
    <>
      <header className={styles.header}>
        <h1 className={styles.title}>Welcome Back</h1>
      </header>
      {registered && (
        <p className={pageStyles.notice} role="status">
          Account created successfully. Please log in.
        </p>
      )}
      <LoginForm />
      <p className={styles.footer}>
        Don't have an account?{' '}
        <Link className={styles.link} to="/register">
          Sign up
        </Link>
      </p>
    </>
  )
}
