import { Link } from '@tanstack/react-router'
import { LoginForm } from './components/LoginForm'
import styles from '#/features/auth/styles/authPage.module.scss'

export const LoginPage = () => {
  return (
    <>
      <header className={styles.header}>
        <h1 className={styles.title}>Welcome Back</h1>
      </header>
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
