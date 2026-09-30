import { z } from 'zod'

const emailPattern = /^\b[a-z0-9._%+-]+@[a-z0-9.-]+\.[a-z]{2,}\b$/

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
      .refine((value) => value.trim().length > 0, {
        message: 'Please enter your password.',
      })
      .min(8, 'Password must contain at least 8 characters.'),

    confirmPassword: z.string().refine((value) => value.trim().length > 0, {
      message: 'Please confirm your password.',
    }),
  })
  .refine((values) => values.password === values.confirmPassword, {
    message: 'Passwords do not match.',
    path: ['confirmPassword'],
  })

export type RegisterFormValues = z.infer<typeof registerSchema>
