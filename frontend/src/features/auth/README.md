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
├── layouts/      # Arrange the background and card for auth pages
├── lib/          # Keep the login session and attach tokens to business requests
├── pages/
│   ├── LoginPage/
│   │   ├── components/   # Email/password inputs and field errors
│   │   └── schemas/      # Rules for validating login input
│   └── RegisterPage/
│       ├── components/   # Registration inputs and field errors
│       └── schemas/      # Input rules, including password confirmation
├── styles/       # Styles shared by auth forms and pages
├── types/        # TypeScript types for data sent to and returned by User Service
└── utils/        # Turn login and registration errors into display messages
```

The three production files in `lib/` have separate responsibilities:
`authRequest.ts` sends authentication HTTP requests; `authSession.ts` owns session
credentials and coordinates refresh/logout; `authInterceptors.ts` exports
`setupAuthInterceptors()` to attach request and response handling to Axios.

`ensureSession({ forceRefresh: true })` explicitly requests a refresh even when
the current token has not expired. `refreshPromise` shares that operation between
callers; `activeRefreshRequests` also tracks unfinished requests from older
sessions so logout can wait for them. `logoutPromise` shares an ongoing logout.
Logout returns `completed` after success or failure; `session-changed` means a
newer session replaced it and the caller must not navigate away from that session.

Tests sit beside the code they verify. Each page owns its form and validation
schema; shared authentication behavior stays at the feature level. Components
with related styles or tests use a same-named folder, such as
`components/SessionRecoveryFeedback/` and `pages/LoginPage/components/LoginForm/`.

| Part                                                                                               | Responsibility                                                                                                                                                                              |
| -------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| [LoginPage](pages/LoginPage/LoginPage.tsx) and [RegisterPage](pages/RegisterPage/RegisterPage.tsx) | Receive valid form values, call useLogin/useRegister, pass loading and error state to the form, and navigate after success.                                                                 |
| Page-local forms and schemas                                                                       | React Hook Form manages fields and validation timing; Zod validates and normalizes input. Forms receive submission state and server errors from their page.                                 |
| [useLogin](hooks/useLogin.ts) and [useRegister](hooks/useRegister.ts)                              | Call the login/register API function, track request progress, and use the error helpers in `utils/` to provide display messages.                                                            |
| [api/](api/)                                                                                       | Choose the backend URL and request body, send the HTTP request through fetch using [authRequest](lib/authRequest.ts), and return response data to the caller. See the four functions below. |
| [authSession](lib/authSession.ts)                                                                  | Keep the current token in memory, obtain a new one when needed, share an ongoing refresh request, and coordinate logout without maintaining UI state.                                       |
| [authInterceptors](lib/authInterceptors.ts)                                                        | Add the access token to the Authorization header. If a request returns 401, obtain or reuse a newer token and retry at most once. Reject old-session results after logout or another login. |
| [AuthLayout](layouts/AuthLayout/AuthLayout.tsx)                                                    | Supply the shared background and card around login and registration pages.                                                                                                                  |
| [SessionRecoveryFeedback](components/SessionRecoveryFeedback/SessionRecoveryFeedback.tsx)          | Show a loading message while checking whether the user is still signed in. If the check fails because of a service error, show a retry button.                                              |

Outside this feature, [routes.tsx](../../routes.tsx) connects layouts, pages, and
session checks. [App.tsx](../../App.tsx) provides the Query client and Router, installs
the auth interceptors on the shared Axios client, and connects session rejection
to login navigation.
[AppHomePage](../../pages/AppHomePage/AppHomePage.tsx) is the current minimal
signed-in screen with a **Log out** button.

### What the API functions do

These functions send requests and return data. Their callers decide what to
show, where to navigate, and whether to save the returned token.

| Function                                    | Request sent to User Service                                                       | Result returned to its caller                                                                                    |
| ------------------------------------------- | ---------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------- |
| [registerUser](api/registerUser.api.ts)     | `POST /auth/register` with name, email, and password                               | The newly created user's profile, used by the registration flow to confirm success. It does not log the user in. |
| [loginUser](api/loginUser.api.ts)           | `POST /auth/login` with email and password                                         | `accessToken` and `expiresAt`. LoginPage passes them to `establishSession()` before opening `/app`.              |
| [refreshSession](api/refreshSession.api.ts) | `POST /auth/refresh` with no request body; the browser supplies the refresh cookie | A new access token and expiry time for `authSession` to save.                                                    |
| [logoutUser](api/logoutUser.api.ts)         | `POST /auth/logout` with no request body; the browser supplies the refresh cookie  | No response data. `authSession` completes local logout whether this request succeeds or fails.                   |

The backend sets or clears the refresh cookie through response headers; these
functions do not read its value. Request errors are passed to the caller:
login/register hooks use the helpers in `utils/` to choose page messages, while `authSession` handles refresh
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

Business API function → axiosClient → auth interceptors → request with token
```

The access token lives in memory. After a reload, the frontend calls
`POST /auth/refresh`; the browser supplies the backend's HttpOnly refresh cookie.
Frontend code does not read that cookie. Concurrent recovery calls in the same
page instance share one request.

The route starts checking the session immediately and waits before rendering
protected content. It shows `Loading…` only if the check is still pending after
500ms, and shows the page as soon as recovery succeeds. A refresh 401 leads to
`/login`; a service failure shows recovery feedback. Its retry button calls
`router.invalidate()` to run the route check again.

The single shared `axiosClient` checks the session before business requests. On
401, its interceptors can refresh or reuse a newer token and retry once. A rejected
refresh or a second 401 for the current token clears the session and navigates to
login. Old requests cannot clear a newer login or its refreshed credentials.

The four authentication API functions use fetch instead, so they bypass Axios
interceptors. `authRequest` converts non-success HTTP responses into errors and
handles JSON and timeouts; it does not refresh or retry. The browser sends
same-origin cookies. Refresh has a 15-second timeout; logout has a 10-second
timeout. These bound client waiting, not backend processing.

### Logout

```text
AppHomePage button → authSession.logout()
                          ↓
               clear local session, block recovery
                          ↓
               wait for pending refresh → logout API
                          ↓ success, failure, or timeout
               AppHomePage navigates to /login
```

The page and **Log out** button stay unchanged while the operation runs. Repeated
clicks share the same pending operation. There is no progress screen, disabled
button, logout error, or retry UI. The caller navigates only if logout still
belongs to the same session; an old completion must not redirect a newer login.

Automatic recovery stays blocked in the current page instance until a new login.
If backend logout fails, its cookie may remain valid: a full reload or later visit
can restore that session, and the user can log out again. A client timeout does
not prove that the backend stopped processing. Logout waits for outstanding
refresh requests to settle before sending the request that clears the cookie.

## Connecting new features

- Add signed-in routes under `protectedRoute` in [routes.tsx](../../routes.tsx),
  and include them in its `addChildren` list. This applies the session check to those pages.
- Put functions that call business endpoints in their owning feature's `api/` directory. Use
  the shared `axiosClient` with developer-defined relative business API paths for
  endpoints that require authentication.
- Use feature hooks for business request state. Pages do not need to read tokens,
  build Authorization headers, or implement their own refresh handling.
- Call `logout()` from a signed-in navigation control and navigate to login when
  it returns `completed`. The control remains mounted while the operation runs; `session-changed`
  means another session replaced it, so the caller must not redirect that session.

## Verification

Tests check input validation, form interactions, page submissions, session
coordination, authenticated request retries, route recovery, and logout navigation.
The simple auth API functions do not have separate unit tests; page and session
tests mock these functions. Two focused `authRequest` tests check HTTP error
handling and timeout cancellation. Verify actual HTTP requests in the browser
checks below.

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
   - Fast recovery should not flash a loading message. With Network throttling,
     a check taking longer than 500ms should show `Loading…`; protected content
     should appear only after recovery succeeds.
   - Stop User Service and reload `/app`. Protected content should stay hidden
     while recovery fails. Restart the service and click **Try again**; the
     session check should run again and the page should recover.
   - In a private browser window without a refresh cookie, open `/app`. Expect
     navigation to `/login` rather than access to the signed-in page.
5. **Logout**
   - Click **Log out**. The page and button should remain unchanged while the
     request runs. Repeated clicks must not produce duplicate logout requests.
   - After a successful `/auth/logout` response, expect `/login` and removal of
     the refresh cookie. Opening `/app` again should return to login.
   - Log in again, stop User Service or switch offline, and attempt logout.
     After failure or timeout, expect `/login` without a logout error or retry UI.
   - Restore the connection and verify that logging in again works. If failed
     logout left a valid cookie, reloading the website may restore the earlier
     session; logging out again should clear it when the service is available.

### Responsive layout checks

Use the DevTools device toolbar in **Responsive** mode and enter these viewport
widths and heights. Check login, registration, field/server errors, session
recovery feedback, and the application logout button.

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
