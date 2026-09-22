import { useRef } from 'react'
import { useNavigate } from '@tanstack/react-router'
import { AuthHeader } from '#/features/auth/components/AuthHeader'
import { RegisterForm } from '#/features/auth/components/RegisterForm'
import { AuthFooter } from '#/features/auth/components/AuthFooter'
import { useRegister } from '#/features/auth/hooks/useRegister'
import type { RegisterFormValues } from '#/features/auth/schemas/register.schema'

/**
 * Connects registration input to the registration request.
 * Navigates to login after an account is created.
 */
export const RegisterPage = () => {
    const navigate = useNavigate()
    const registration = useRegister()

    // Block repeated submissions before the pending state reaches the form.
    const submissionInProgress = useRef(false)

    const handleValidSubmit = (values: RegisterFormValues) => {
        if (submissionInProgress.current) {
            return
        }

        submissionInProgress.current = true

        const { name, email, password } = values

        registration.mutate(
            { name, email, password },
            {
                onSuccess: () => {
                    void navigate({
                        to: '/login',
                        search: { registered: true },
                        replace: true,
                    })
                },
                onSettled: () => {
                    submissionInProgress.current = false
                },
            },
        )
    }

    return (
        <>
            <AuthHeader title="Create your account" />

            <RegisterForm
                onValidSubmit={handleValidSubmit}
                isSubmitting={registration.isPending || registration.isSuccess}
                submitError={registration.errorMessage}
            />

            <AuthFooter page="register" />
        </>
    )
}
