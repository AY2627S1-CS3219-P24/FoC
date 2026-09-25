# Authentication

This feature connects registration and login pages to User Service and manages
session recovery, protected routes, authenticated requests, and logout.
Registration returns the user to login; successful login opens `/app`.

## Structure and responsibilities

```text
auth/
├── api/          # Send auth requests to User Service and return its response data
├── assets/       # Background image used by login and registration
├── components/   # Show loading, failure, and retry controls during session recovery
├── hooks/        # Start login/register requests and track their progress and errors
├── layouts/      # Arrange auth pages and hide signed-in pages during logout
├── lib/          # Keep the login session and attach tokens to business requests
├── pages/
│   ├── LoginPage/
│   │   ├── components/   # Email/password inputs and field errors
│   │   └── schemas/      # Rules for validating login input
│   └── RegisterPage/
│       ├── components/   # Registration inputs and field errors
│       └── schemas/      # Input rules, including password confirmation
├── styles/       # Styles shared by auth forms and pages
└── types/        # TypeScript types for data sent to and returned by User Service
```

Tests sit beside the code they verify. Each page owns its form and validation
schema; shared authentication behavior stays at the feature level.

| Part                                                                                               | Responsibility                                                                                                                                                                              |
| -------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| [LoginPage](pages/LoginPage/LoginPage.tsx) and [RegisterPage](pages/RegisterPage/RegisterPage.tsx) | Receive valid form values, call useLogin/useRegister, pass loading and error state to the form, and navigate after success.                                                                 |
| Page-local forms and schemas                                                                       | React Hook Form manages fields and validation timing; Zod validates and normalizes input. Forms receive submission state and server errors from their page.                                 |
| [useLogin](hooks/useLogin.ts) and [useRegister](hooks/useRegister.ts)                              | Call the login/register API function, track whether the request is running or completed, and turn request errors into messages the page can display.                                        |
| [api/](api/)                                                                                       | Choose the backend URL and request body, send the HTTP request through [axiosClient](../../lib/axiosClient.ts), and return response data to the caller. See the four functions below.       |
| [authSession](lib/authSession.ts)                                                                  | Keep the current token in memory, obtain a new one when needed, share an ongoing refresh request, and notify the UI when logout starts, fails, or finishes.                                 |
| [authenticatedClient](lib/authenticatedClient.ts)                                                  | Add the access token to the Authorization header. If a request returns 401, obtain or reuse a newer token and retry at most once. Reject old-session results after logout or another login. |
| [AuthLayout](layouts/AuthLayout.tsx)                                                               | Supply the shared background and card around login and registration pages.                                                                                                                  |
| [ProtectedSessionBoundary](layouts/ProtectedSessionBoundary.tsx)                                   | Render signed-in child pages normally. During logout, hide them and show progress or a retry button; after logout succeeds, navigate to login.                                              |
| [SessionRecoveryFeedback](components/SessionRecoveryFeedback.tsx)                                  | Show a loading message while checking whether the user is still signed in. If the check fails because of a service error, show a retry button.                                              |

Outside this feature, [routes.tsx](../../routes.tsx) connects layouts, pages, and
session checks. [App.tsx](../../App.tsx) provides the Query client and Router.
[AppHomePage](../../pages/AppHomePage/AppHomePage.tsx) is the current minimal
signed-in screen with a **Log out** button.

### What the API functions do

These functions send requests and return data. Their callers decide what to
show, where to navigate, and whether to save the returned token.

| Function                                    | Request sent to User Service                                                       | Result returned to its caller                                                                                            |
| ------------------------------------------- | ---------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------ |
| [registerUser](api/registerUser.api.ts)     | `POST /auth/register` with name, email, and password                               | The newly created user's profile, used by the registration flow to confirm success. It does not log the user in.         |
| [loginUser](api/loginUser.api.ts)           | `POST /auth/login` with email and password                                         | `accessToken` and `expiresAt`. LoginPage passes them to `establishSession()` before opening `/app`.                      |
| [refreshSession](api/refreshSession.api.ts) | `POST /auth/refresh` with no request body; the browser supplies the refresh cookie | A new access token and expiry time for `authSession` to save.                                                            |
| [logoutUser](api/logoutUser.api.ts)         | `POST /auth/logout` with no request body; the browser supplies the refresh cookie  | No response data. Completion tells `authSession` that the logout request succeeded; failure lets it show retry feedback. |

The backend sets or clears the refresh cookie through response headers; these
functions do not read its value. Request errors are passed to the caller:
login/register hooks choose page messages, while `authSession` handles refresh
and logout outcomes.

## How the pieces work together

### Registration and login

```text
Form validates input → Page → useLogin/useRegister → api function → User Service
                          ↓ successful response
             Register: /login with success notice
             Login: establishSession(tokens) → /app
```

Forms submit validated values: surrounding whitespace is removed from names
and emails, emails are lowercased, and passwords retain their original characters. The registration page sends only `name`,
`email`, and `password`, excluding `confirmPassword`.

The page passes request state and error messages back to the form. Invalid input
stays in the form; a failed request allows another submission. Registration does
not create a logged-in session.

### Session recovery and protected requests

```text
/ → /app → protected route beforeLoad → ensureSession()
                                        ├── usable token → render page
                                        └── refresh API → render or show feedback

Business API function → authenticatedClient → ensureSession() → request with token
```

The access token lives in memory. After a reload, the frontend calls
`POST /auth/refresh`; the browser supplies the backend's HttpOnly refresh cookie.
Frontend code does not read that cookie. Concurrent recovery calls in the same
page instance share one request.

The route waits before rendering protected content. A refresh 401 leads to
`/login`; a service failure shows recovery feedback. Its retry button calls
`router.invalidate()` to run the route check again.

The authenticated client also checks the session before a request. On 401, it
can refresh or reuse a newer token and retry once. The four authentication API functions
use `axiosClient`, which does not automatically refresh and retry requests.
This prevents a failed refresh request from starting another refresh itself.

### Logout

```text
AppHomePage button → authSession.logout() → session state notification
                                             ↓
                              ProtectedSessionBoundary hides content
                                             ↓
                  wait for pending refresh → logout API
                                             ├── success → /login
                                             └── failure → error + retry
```

The boundary uses `useSyncExternalStore` to re-render when the session module
announces a change in logout or login-required state. It stays mounted when the
application page is removed, so it can handle successful logout navigation.
Its retry button invokes the same `logout()` operation again. There is no
separate logout route or page.

Protected content remains hidden and automatic recovery stays blocked during
logout and after failure. Successful logout also blocks recovery in the current
page instance until a new login.

## Connecting new features

- Add signed-in routes under `protectedRoute` in [routes.tsx](../../routes.tsx),
  and include them in its `addChildren` list. This shares session recovery and
  logout feedback across protected pages.
- Put functions that call business endpoints in their owning feature's `api/` directory. Use
  `authenticatedClient` with developer-defined relative business API paths for
  endpoints that require authentication.
- Use feature hooks for business request state. Pages do not need to read tokens,
  build Authorization headers, or implement their own refresh handling.
- Call `logout()` from a signed-in navigation control. The shared boundary owns
  pending feedback, retry, and navigation after completion.

## Verification

Tests check request URLs and bodies, returned data, input validation, form interactions, page submissions,
session coordination, authenticated request retries, route recovery, and logout
feedback. API requests in these tests are mocked.

Run from the `frontend` directory:

```sh
npm run test:run
npm exec --no -- tsc --noEmit
npm run lint
npm run build
```

### Manual browser checks

Start the required backend infrastructure and User Service with the local dev
profile. From the `frontend` directory, start the frontend:

```sh
npm run dev
```

Open `http://localhost:3000`. Use this hostname consistently rather than switching
between `localhost` and `127.0.0.1`. Open DevTools and use **Network** to inspect
requests and **Application → Cookies** to inspect the refresh cookie. Enable
**Preserve log** in Network if you want to keep requests visible after navigation.

1. **Form validation and navigation**
   - Follow the links between `/login` and `/register`.
   - Submit empty forms and check that field errors appear. Correct the inputs
     and confirm the errors update without automatically submitting the form.
   - On registration, change either password field and check that the confirmation
     error updates. Check that whitespace-only passwords are rejected.
2. **Registration**
   - Register with an unused email. Expect `POST /auth/register` to return **201**,
     followed by navigation to `/login` with a registration success notice.
   - Try registering the same email again. Expect duplicate-email feedback and
     no navigation away from registration.
   - With a different unused email, stop User Service and submit. Check that an
     error appears and the form becomes usable again. Restart the service and
     retry without refreshing the page; registration should succeed.
3. **Login**
   - Submit an incorrect password for an existing account. Expect **401** and
     `Incorrect email or password.` on the page.
   - Correct the password and submit again. Expect **200** and navigation to
     `/app`. Check that the backend has set an HttpOnly refresh cookie.
   - While a submission is pending, verify that inputs and the submit button
     are disabled and repeated clicks do not create additional requests.
4. **Session recovery and access control**
   - While signed in, reload `/app`. Expect a successful `/auth/refresh` request
     and the signed-in page to reappear. Check that the refresh cookie is updated.
   - Open `/`; it should lead to `/app` and run the session check.
   - Stop User Service and reload `/app`. Protected content should stay hidden
     while recovery fails. Restart the service and click **Try again**; the
     session check should run again and the page should recover.
   - In a private browser window without a refresh cookie, open `/app`. Expect
     navigation to `/login` rather than access to the signed-in page.
5. **Logout and retry**
   - Click **Log out**. Protected content should disappear while logout is in
     progress. After a successful `/auth/logout` response, expect `/login` and
     removal of the refresh cookie. Opening `/app` again should return to login.
   - Log in again, stop User Service, and attempt logout. Check that an error
     and **Try again** appear while protected content remains hidden.
   - Restart the service and retry without reloading. Expect a new logout request
     and navigation to `/login` after it succeeds.

### Responsive layout checks

Use the DevTools device toolbar in **Responsive** mode and enter these viewport
widths and heights. Check login, registration, field/server errors, session
recovery feedback, and logout feedback.

| Viewport       | Check                                                                                                                                         |
| -------------- | --------------------------------------------------------------------------------------------------------------------------------------------- |
| **320 × 568**  | No horizontal overflow. Registration can scroll to the submit button and login link. All error messages and retry controls remain accessible. |
| **390 × 844**  | Background cropping and card spacing look reasonable. Labels, messages, and buttons are fully visible.                                        |
| **1440 × 900** | The authentication background covers the viewport, the card is centered with a reasonable width, and feedback screens remain readable.        |

Frontend tests use mocked requests. These manual checks verify real browser
cookie creation, rotation, and removal. Bearer-token requests to a real protected
business endpoint still need integration testing when such an endpoint is available.

## Acknowledgments

Use GPT-6-Astra to help generate and modify this README based on current implementation.
