# Shared user layout

`UserLayout` supplies the header, page shell, skip link and main content area for
requester and courier routes. Keep mode switching and page content in `orders`.
Login/register retain `AuthLayout`; admin pages should use their own layout.

Supply `name`, `navigation`, and optional account/credit values from the owning
features. Navigation accepts React nodes so callers can use TanStack `Link` with
`aria-current="page"`. Supply a linked FoC wordmark through `brand` when the home
route is available. The header does not fetch data or manage authentication.

`onAccountClick` and `onNotificationsClick` connect to the owning feature's UI.
Without those callbacks, account information is static and notifications are
disabled. Missing credit data displays "Balance unavailable"; zero is displayed
as zero. Missing or failed photos fall back to initials. For authenticated photo
endpoints, supply an object URL fetched by the auth feature, which also owns its
cleanup.

Use `UserLayout` as a route layout with no children to render its `Outlet`, or
supply children for a composed page. Child content must not add a second `main`.
The authentication feature must protect the parent route when live session
integration becomes available; this visual layout is not an authentication guard.

Run `npm run dev` and open `/preview/user-layout` for a development-only preview.
It uses sample account data and callback feedback, not real notifications or an
account menu. The preview route is excluded from production. Test at desktop and
mobile widths, tab through controls, and use the skip link to reach the content.
