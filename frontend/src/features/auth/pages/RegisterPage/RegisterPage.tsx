import { Link } from '@tanstack/react-router'
import { RegisterForm } from './components/RegisterForm'
import styles from '#/features/auth/styles/authPage.module.scss'

export const RegisterPage = () => {
  return (
    <>
      <header className={styles.header}>
        <h1 className={styles.title}>Create your account</h1>
      </header>
      <RegisterForm />
      <p className={styles.footer}>
        Already have an account?{' '}
        <Link className={styles.link} to="/login">
          Log in
        </Link>
      </p>
    </>
  )
}
