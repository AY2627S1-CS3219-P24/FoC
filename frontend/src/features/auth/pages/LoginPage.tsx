import { AuthHeader } from '#/features/auth/components/AuthHeader'
import { LoginForm } from '#/features/auth/components/LoginForm'
import { AuthFooter } from '#/features/auth/components/AuthFooter'

/**
 * Login screen displayed inside AuthLayout.
 */
export const LoginPage = () => {
  return (
    <>
      <AuthHeader title="Welcome Back" />
      <LoginForm />
      <AuthFooter page="login" />
    </>
  )
}
