import { describe, expect, it } from 'vitest'
import { registerSchema } from './register.schema'

const validValues = {
  name: 'Jamie Loh',
  email: 'jamie@example.com',
  password: 'abcdefgh',
  confirmPassword: 'abcdefgh',
}

describe('registerSchema', () => {
  it('accepts valid registration values', () => {
    const result = registerSchema.safeParse(validValues)

    expect(result.success).toBe(true)
  })

  it('normalizes the name and email', () => {
    const result = registerSchema.safeParse({
      ...validValues,
      name: '  Jamie Loh  ',
      email: '  JAMIE@EXAMPLE.COM  ',
    })

    expect(result.success).toBe(true)

    if (result.success) {
      expect(result.data.name).toBe('Jamie Loh')
      expect(result.data.email).toBe('jamie@example.com')
    }
  })

  it.each(['', '   '])('rejects a blank name: %j', (name) => {
    const result = registerSchema.safeParse({
      ...validValues,
      name,
    })

    expect(result.success).toBe(false)

    if (!result.success) {
      expect(result.error.issues).toEqual(
        expect.arrayContaining([expect.objectContaining({ path: ['name'] })]),
      )
    }
  })

  it.each(['', 'jamie', 'jamie@', 'jamie@example.com extra'])(
    'rejects an invalid email: %j',
    (email) => {
      const result = registerSchema.safeParse({
        ...validValues,
        email,
      })

      expect(result.success).toBe(false)

      if (!result.success) {
        expect(result.error.issues).toEqual(
          expect.arrayContaining([
            expect.objectContaining({ path: ['email'] }),
          ]),
        )
      }
    },
  )

  it('rejects a seven-character password', () => {
    const result = registerSchema.safeParse({
      ...validValues,
      password: 'abcdefg',
      confirmPassword: 'abcdefg',
    })

    expect(result.success).toBe(false)

    if (!result.success) {
      expect(result.error.issues).toEqual(
        expect.arrayContaining([
          expect.objectContaining({ path: ['password'] }),
        ]),
      )
    }
  })

  it.each(['', '        ', '\t\t\t\t\t\t\t\t', '\n\n\n\n\n\n\n\n'])(
    'reports the required error first for a blank password: %j',
    (password) => {
      const result = registerSchema.safeParse({
        ...validValues,
        password,
        confirmPassword: password,
      })

      expect(result.success).toBe(false)

      if (!result.success) {
        const passwordIssue = result.error.issues.find(
          (issue) => issue.path[0] === 'password',
        )

        expect(passwordIssue?.message).toBe('Please enter your password.')
      }
    },
  )

  it('assigns a password mismatch to confirmPassword', () => {
    const result = registerSchema.safeParse({
      ...validValues,
      confirmPassword: 'different-password',
    })

    expect(result.success).toBe(false)

    if (!result.success) {
      expect(result.error.issues).toEqual(
        expect.arrayContaining([
          expect.objectContaining({
            path: ['confirmPassword'],
            message: 'Passwords do not match.',
          }),
        ]),
      )
    }
  })

  it.each(['', '        ', '\t\t\t\t\t\t\t\t', '\n\n\n\n\n\n\n\n'])(
    'rejects a blank confirmation password: %j',
    (confirmPassword) => {
      const result = registerSchema.safeParse({
        ...validValues,
        confirmPassword,
      })

      expect(result.success).toBe(false)

      if (!result.success) {
        expect(result.error.issues).toEqual(
          expect.arrayContaining([
            expect.objectContaining({
              path: ['confirmPassword'],
              message: 'Please confirm your password.',
            }),
          ]),
        )
      }
    },
  )

  it.each([' abcdefgh ', 'abcd efgh'])(
    'preserves spaces in passwords: %j',
    (password) => {
      const result = registerSchema.safeParse({
        ...validValues,
        password,
        confirmPassword: password,
      })

      expect(result.success).toBe(true)

      if (result.success) {
        expect(result.data.password).toBe(password)
        expect(result.data.confirmPassword).toBe(password)
      }
    },
  )
})
