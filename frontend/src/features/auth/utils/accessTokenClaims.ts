export const hasAccessTokenRole = (
  accessToken: string | null,
  role: string,
): boolean => {
  const payload = accessToken?.split('.')[1]
  if (!payload) return false

  try {
    const claims: unknown = JSON.parse(
      atob(payload.replace(/-/g, '+').replace(/_/g, '/')),
    )
    if (typeof claims !== 'object' || claims === null || !('roles' in claims)) {
      return false
    }
    return Array.isArray(claims.roles) && claims.roles.includes(role)
  } catch {
    return false
  }
}
