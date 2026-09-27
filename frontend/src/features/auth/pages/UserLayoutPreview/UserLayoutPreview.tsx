import { useState } from 'react'
import { UserLayout } from '#/layouts/UserLayout'

// Development-only sample data. Account and credit features will supply live props.
export const UserLayoutPreview = () => {
  const [message, setMessage] = useState(
    'Shared layout preview for requester and courier pages.',
  )
  return (
    <UserLayout
      name="Alex Tan"
      availableCredits={15}
      hasUnreadNotifications
      brand={<a href="/preview/user-layout">FoC</a>}
      navigation={
        <>
          <a href="/preview/user-layout" aria-current="page">
            Home
          </a>
          <span aria-disabled="true">Locations</span>
          <span aria-disabled="true">My Errands</span>
        </>
      }
      onNotificationsClick={() =>
        setMessage(
          'Notifications action selected. Connect the notifications feature here.',
        )
      }
      onAccountClick={() =>
        setMessage(
          'Account action selected. Connect the account menu or profile route here.',
        )
      }
    >
      <h1>User layout preview</h1>
      <p role="status">{message}</p>
      <p>
        This preview uses sample account information. Page content and
        requester/courier switching belong to the home-page features.
      </p>
    </UserLayout>
  )
}
