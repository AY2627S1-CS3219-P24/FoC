import { AxiosHeaders } from 'axios'
import type { AxiosResponse } from 'axios'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { axiosClient } from '#/lib/axiosClient'
import type {
    RegisterUserRequest,
    UserProfileDto,
} from '#/features/auth/types/auth.types'
import { registerUser } from './registerUser.api'

const request: RegisterUserRequest = {
    name: 'Jamie Loh',
    email: 'jamie@example.com',
    password: 'abcdefgh',
}

const profile: UserProfileDto = {
    id: 'user-123',
    name: 'Jamie Loh',
    email: 'jamie@example.com',
    roles: ['USER'],
}

// Model a successful Axios response without contacting the backend.
const successResponse: AxiosResponse<UserProfileDto> = {
    data: profile,
    status: 201,
    statusText: 'Created',
    headers: {},
    config: {
        headers: new AxiosHeaders(),
    },
}

afterEach(() => {
    // Restore the shared client's original method after each test.
    vi.restoreAllMocks()
})

describe('registerUser', () => {
    it('sends only backend registration fields', async () => {
        const post = vi
            .spyOn(axiosClient, 'post')
            .mockResolvedValue(successResponse)

        // Extra fields can exist at runtime despite the request type.
        const formValues = {
            ...request,
            confirmPassword: 'abcdefgh',
        }

        await registerUser(formValues)

        // Check the complete payload so extra fields cannot slip through.
        expect(post).toHaveBeenCalledTimes(1)
        expect(post).toHaveBeenCalledWith('/auth/register', {
            name: 'Jamie Loh',
            email: 'jamie@example.com',
            password: 'abcdefgh',
        })
    })

    it('returns the user profile from the response', async () => {
        vi.spyOn(axiosClient, 'post').mockResolvedValue(successResponse)

        const result = await registerUser(request)

        expect(result).toEqual(profile)
    })

    it('propagates request errors to the caller', async () => {
        const error = new Error('Registration request failed')

        vi.spyOn(axiosClient, 'post').mockRejectedValue(error)

        // Preserve the original error for the caller's error handling.
        await expect(registerUser(request)).rejects.toBe(error)
    })
})
