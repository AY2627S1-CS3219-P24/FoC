import { AuthHeader } from '#/features/auth/components/AuthHeader'
import { RegisterForm } from '#/features/auth/components/RegisterForm'
import { AuthFooter } from '#/features/auth/components/AuthFooter'

/**
 * Registration screen displayed inside AuthLayout.
 */
export const RegisterPage = () => {
  return (
    <>
      <AuthHeader title="Create your account" />
      <RegisterForm />
      <AuthFooter page="register" />
    </>
  )
}
