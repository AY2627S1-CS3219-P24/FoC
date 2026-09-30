import { z } from 'zod'

const emailPattern = /^\b[a-z0-9._%+-]+@[a-z0-9.-]+\.[a-z]{2,}\b$/

export const userSchema = z.object({
  name: z.string().trim().min(1, 'Please enter a name.').max(255),
  email: z
    .string()
    .trim()
    .toLowerCase()
    .min(1, 'Please enter an email address.')
    .max(320)
    .regex(emailPattern, 'Please enter a valid email address.'),
  roles: z.array(z.enum(['USER', 'ADMIN'])).min(1, 'Select at least one role.'),
})

export type UserFormValues = z.infer<typeof userSchema>
