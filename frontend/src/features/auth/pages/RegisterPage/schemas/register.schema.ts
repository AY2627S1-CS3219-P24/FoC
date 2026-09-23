import { z } from 'zod'

// Match the current backend email constraint after normalization.
const emailPattern = /^\b[a-z0-9._%+-]+@[a-z0-9.-]+\.[a-z]{2,}\b$/

// Preserve password whitespace because it is part of the credential.
export const registerSchema = z
  .object({
    name: z.string().trim().min(1, 'Please enter your name.'),

    email: z
      .string()
      .trim()
      .toLowerCase()
      .min(1, 'Please enter your email.')
      .regex(emailPattern, 'Please enter a valid email address.'),

    password: z
      .string()
      .min(1, 'Please enter your password.')
      .min(8, 'Password must contain at least 8 characters.'),

    confirmPassword: z.string().min(1, 'Please confirm your password.'),
  })
  .refine((values) => values.password === values.confirmPassword, {
    message: 'Passwords do not match.',
    path: ['confirmPassword'],
  })

export type RegisterFormValues = z.infer<typeof registerSchema>
