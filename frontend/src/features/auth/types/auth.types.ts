import type { RegisterFormValues } from '#/features/auth/pages/RegisterPage/schemas/register.schema'

/** Registration payload; password confirmation is only used by the form. */
export type RegisterUserRequest = Pick<
  RegisterFormValues,
  'name' | 'email' | 'password'
>

/** User profile returned by the registration endpoint. */
export type UserProfileDto = {
  id: string
  email: string
  name: string
  roles: string[]
}
