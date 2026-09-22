import { axiosClient } from '#/lib/axiosClient'
import type {
    RegisterUserRequest,
    UserProfileDto,
} from '#/features/auth/types/auth.types'

/**
 * Registers a user with validated, normalized input.
 * Registration does not create a login session.
 */
export const registerUser = async (
    request: RegisterUserRequest,
): Promise<UserProfileDto> => {
    const { name, email, password } = request

    // Explicitly select API fields so form-only values are never sent.
    const response = await axiosClient.post<UserProfileDto>(
        '/auth/register',
        { name, email, password },
    )

    return response.data
}
