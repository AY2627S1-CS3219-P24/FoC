import { axiosClient } from '#/lib/axiosClient'
import type {
  RegisterUserRequest,
  UserProfileDto,
} from '#/features/auth/types/auth.types'

export const registerUser = async (
  request: RegisterUserRequest,
): Promise<UserProfileDto> => {
  const { name, email, password } = request

  const response = await axiosClient.post<UserProfileDto>('/auth/register', {
    name,
    email,
    password,
  })

  return response.data
}
