import { useState } from 'react'
import { TextInput } from '#/features/auth/components/TextInput/TextInput'
import { PrimaryButton } from '#/features/auth/components/PrimaryButton/PrimaryButton'
import { loginSchema } from '#/features/auth/schemas/login.schema'
import type { LoginFormValues } from '#/features/auth/schemas/login.schema'
import './LoginForm.scss'

type LoginFormProps = {
  onValidSubmit?: (values: LoginFormValues) => void
}

type LoginFieldErrors = Partial<Record<keyof LoginFormValues, string>>

/**
 * Validates login input and passes normalized values to the caller.
 * Field errors update on input after the first submission attempt.
 */
export const LoginForm = ({ onValidSubmit }: LoginFormProps) => {
  const [values, setValues] = useState<LoginFormValues>({
    email: '',
    password: '',
  })

  const [errors, setErrors] = useState<LoginFieldErrors>({})
  const [hasSubmitted, setHasSubmitted] = useState(false)

  const validate = (nextValues: LoginFormValues) => {
    const result = loginSchema.safeParse(nextValues)

    if (result.success) {
      setErrors({})
    } else {
      setErrors({
        email: result.error.issues.find((issue) => issue.path[0] === 'email')
          ?.message,
        password: result.error.issues.find(
          (issue) => issue.path[0] === 'password',
        )?.message,
      })
    }

    return result
  }

  const updateField = (field: keyof LoginFormValues, value: string) => {
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
      className="login-form"
      noValidate
      onSubmit={(event) => {
        event.preventDefault()
        setHasSubmitted(true)

        const result = validate(values)

        if (result.success) {
          onValidSubmit?.(result.data)
        }
      }}
    >
      <div className="login-form__field">
        <label htmlFor="login-email">Email</label>
        <TextInput
          id="login-email"
          name="email"
          type="email"
          autoComplete="username"
          required
          value={values.email}
          onValueChange={(value) => {
            updateField('email', value)
          }}
          aria-invalid={Boolean(errors.email)}
          aria-describedby={errors.email ? 'login-email-error' : undefined}
        />

        {errors.email && (
          <p id="login-email-error" className="login-form__error" role="alert">
            {errors.email}
          </p>
        )}
      </div>

      <div className="login-form__field">
        <label htmlFor="login-password">Password</label>
        <TextInput
          id="login-password"
          name="password"
          type="password"
          autoComplete="current-password"
          required
          value={values.password}
          onValueChange={(value) => {
            updateField('password', value)
          }}
          aria-invalid={Boolean(errors.password)}
          aria-describedby={
            errors.password ? 'login-password-error' : undefined
          }
        />

        {errors.password && (
          <p
            id="login-password-error"
            className="login-form__error"
            role="alert"
          >
            {errors.password}
          </p>
        )}
      </div>

      <PrimaryButton className="login-form__submit" type="submit">
        Log In
      </PrimaryButton>
    </form>
  )
}
