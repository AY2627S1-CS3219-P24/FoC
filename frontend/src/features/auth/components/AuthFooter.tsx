import { Link } from '@tanstack/react-router'
import './AuthFooter.scss'

type AuthFooterProps = {
  page: 'login' | 'register'
}

/**
 * Navigation between login and registration pages.
 */
export const AuthFooter = ({ page }: AuthFooterProps) => {
  const isLoginPage = page === 'login'

  return (
    <p className="auth-footer">
      {isLoginPage ? "Don't have an account?" : 'Already have an account?'}{' '}
      <Link
        className="auth-footer__link"
        to={isLoginPage ? '/register' : '/login'}
      >
        {isLoginPage ? 'Sign up' : 'Log in'}
      </Link>
    </p>
  )
}
