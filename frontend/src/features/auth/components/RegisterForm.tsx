import { useState } from 'react'
import { TextInput } from '#/features/auth/components/TextInput/TextInput'
import { PrimaryButton } from '#/features/auth/components/PrimaryButton/PrimaryButton'
import { registerSchema } from '#/features/auth/schemas/register.schema'
import type { RegisterFormValues } from '#/features/auth/schemas/register.schema'
import './RegisterForm.scss'

type RegisterFormProps = {
  onValidSubmit?: (values: RegisterFormValues) => void
  isSubmitting?: boolean
  submitError?: string
}

type RegisterFieldErrors = Partial<Record<keyof RegisterFormValues, string>>

/**
 * Validates registration input and passes normalized values to the caller.
 * Revalidates all fields after the first submission attempt.
 */
export const RegisterForm = ({
  onValidSubmit,
  isSubmitting = false,
  submitError,
}: RegisterFormProps) => {
  const [values, setValues] = useState<RegisterFormValues>({
    name: '',
    email: '',
    password: '',
    confirmPassword: '',
  })

  const [errors, setErrors] = useState<RegisterFieldErrors>({})
  const [hasSubmitted, setHasSubmitted] = useState(false)

  const validate = (nextValues: RegisterFormValues) => {
    const result = registerSchema.safeParse(nextValues)

    if (result.success) {
      setErrors({})
    } else {
      setErrors({
        name: result.error.issues.find((issue) => issue.path[0] === 'name')
          ?.message,
        email: result.error.issues.find((issue) => issue.path[0] === 'email')
          ?.message,
        password: result.error.issues.find(
          (issue) => issue.path[0] === 'password',
        )?.message,
        confirmPassword: result.error.issues.find(
          (issue) => issue.path[0] === 'confirmPassword',
        )?.message,
      })
    }

    return result
  }

  const updateField = (field: keyof RegisterFormValues, value: string) => {
    const nextValues = {
      ...values,
      [field]: value,
    }

    setValues(nextValues)

    if (hasSubmitted) {
      validate(nextValues)
    }
  }

  return (
    <form
      className="register-form"
      noValidate
      aria-busy={isSubmitting}
      onSubmit={(event) => {
        event.preventDefault()

        // Ignore further submissions while the request is in progress.
        if (isSubmitting) {
          return
        }

        setHasSubmitted(true)

        const result = validate(values)

        if (result.success) {
          onValidSubmit?.(result.data)
        }
      }}
    >
      <div className="register-form__field">
        <label htmlFor="register-name">Name</label>
        <TextInput
          id="register-name"
          name="name"
          type="text"
          autoComplete="name"
          required
          disabled={isSubmitting}
          value={values.name}
          onValueChange={(value) => {
            updateField('name', value)
          }}
          aria-invalid={Boolean(errors.name)}
          aria-describedby={errors.name ? 'register-name-error' : undefined}
        />

        {errors.name && (
          <p
            id="register-name-error"
            className="register-form__error"
            role="alert"
          >
            {errors.name}
          </p>
        )}
      </div>

      <div className="register-form__field">
        <label htmlFor="register-email">Email</label>
        <TextInput
          id="register-email"
          name="email"
          type="email"
          autoComplete="username"
          required
          disabled={isSubmitting}
          value={values.email}
          onValueChange={(value) => {
            updateField('email', value)
          }}
          aria-invalid={Boolean(errors.email)}
          aria-describedby={errors.email ? 'register-email-error' : undefined}
        />

        {errors.email && (
          <p
            id="register-email-error"
            className="register-form__error"
            role="alert"
          >
            {errors.email}
          </p>
        )}
      </div>

      <div className="register-form__field">
        <label htmlFor="register-password">Password</label>
        <TextInput
          id="register-password"
          name="password"
          type="password"
          autoComplete="new-password"
          required
          disabled={isSubmitting}
          value={values.password}
          onValueChange={(value) => {
            updateField('password', value)
          }}
          aria-invalid={Boolean(errors.password)}
          aria-describedby={
            errors.password ? 'register-password-error' : undefined
          }
        />

        {errors.password && (
          <p
            id="register-password-error"
            className="register-form__error"
            role="alert"
          >
            {errors.password}
          </p>
        )}
      </div>

      <div className="register-form__field">
        <label htmlFor="register-confirm-password">Confirm password</label>
        <TextInput
          id="register-confirm-password"
          name="confirmPassword"
          type="password"
          autoComplete="new-password"
          required
          disabled={isSubmitting}
          value={values.confirmPassword}
          onValueChange={(value) => {
            updateField('confirmPassword', value)
          }}
          aria-invalid={Boolean(errors.confirmPassword)}
          aria-describedby={
            errors.confirmPassword
              ? 'register-confirm-password-error'
              : undefined
          }
        />

        {errors.confirmPassword && (
          <p
            id="register-confirm-password-error"
            className="register-form__error"
            role="alert"
          >
            {errors.confirmPassword}
          </p>
        )}
      </div>

      {submitError && (
        <p className="register-form__error" role="alert">
          {submitError}
        </p>
      )}

      <PrimaryButton
        className="register-form__submit"
        type="submit"
        disabled={isSubmitting}
      >
        {isSubmitting ? 'Creating account…' : 'Create Account'}
      </PrimaryButton>
    </form>
  )
}
