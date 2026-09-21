import './AuthHeader.scss'

type AuthHeaderProps = {
  title: string
}

/**
 * Shared page heading for login and registration.
 */
export const AuthHeader = ({ title }: AuthHeaderProps) => {
  return (
    <header className="auth-header">
      <h1 className="auth-header__title">{title}</h1>
    </header>
  )
}
