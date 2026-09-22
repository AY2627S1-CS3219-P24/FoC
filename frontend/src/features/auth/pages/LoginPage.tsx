import { useSearch } from '@tanstack/react-router'
import { AuthHeader } from '#/features/auth/components/AuthHeader'
import { LoginForm } from '#/features/auth/components/LoginForm'
import { AuthFooter } from '#/features/auth/components/AuthFooter'
import './LoginPage.scss'

/**
 * Login screen displayed inside AuthLayout.
 */
export const LoginPage = () => {
    const { registered } = useSearch({ strict: false })

    return (
        <>
            <AuthHeader title="Welcome Back" />

            {registered && (
                <p className="login-page__notice" role="status">
                    Account created successfully. Please log in.
                </p>
            )}

            <LoginForm />
            <AuthFooter page="login" />
        </>
    )
}
