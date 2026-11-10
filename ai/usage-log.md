# AI Usage Log

## LOH WAI KIT

### Date/Time

2026-09-23 to 2026-09-28

#### Tool

Codex

#### Prompt/Command

Implement frontend authentication integration, including login, session restoration, protected routes, authenticated requests, and logout. Apply subsequent changes requested during review.

#### Output Summary

Implemented login integration, in-memory access-token storage, shared refresh requests, protected routing, and logout handling. Updated Axios interception to refresh after a 401 response and retry the request once. Subsequently separated token storage and authentication coordination, removed the previous session module, and updated related tests and documentation.

#### Action Taken

- [ ] Accepted as-is
- [x] Modified
- [ ] Rejected

#### Author Notes

I reviewed the implementation and requested simplification based on teammate feedback. The retained implementation restores sessions through refresh cookies and retries business requests once after a 401 response. A subsequent logout revision was reverted after review. Automated tests and manual integration checks were completed during implementation.

---

### Date/Time

2026-09-30 23:32 to 2026-10-01 00:18

#### Tool

Codex

#### Prompt/Command

Restrict registration to NUS email addresses ending in @u.nus.edu or @nus.edu.sg. Add frontend validation and backend enforcement without adding further restrictions to login or session refresh.

#### Output Summary

Added registration email-domain validation, backend enforcement, error handling, and focused tests. Applied the domain policy to the existing backend email-update operation. Adjusted the registration validation message following UI review.

#### Action Taken

- [ ] Accepted as-is
- [x] Modified
- [ ] Rejected

#### Author Notes

I reviewed the implementation and requested adjustments to the placement of the validation message. Registration accepts the two agreed NUS email domains, with matching backend enforcement. Backend checks and manual integration testing passed.

---

### Date/Time

2026-10-08 to 2026-10-17

#### Tool

Codex

#### Prompt/Command

Implement registration email verification using Spring Mail and Gmail SMTP. Add verification links, automatic confirmation, and a resend option. Require verification before login and address subsequent review feedback.

#### Output Summary

Implemented email-token persistence, mail delivery, verification and resend endpoints, registration and login changes, and the frontend verification page. Prevented direct email changes through the ordinary profile-update endpoint. Added local Gmail SMTP instructions in SETUP.md, automated tests, and subsequent review fixes.

#### Action Taken

- [ ] Accepted as-is
- [x] Modified
- [ ] Rejected

#### Author Notes

I reviewed the implementation and requested UI, documentation, and test adjustments. Users verify their registered email through an emailed link and can request another verification email when needed. Automated checks and CI passed. I also completed manual integration testing using Gmail SMTP.

---

### Date/Time

2026-10-10 to 2026-10-17

#### Tool

Codex

#### Prompt/Command

Implement verified email changes from the profile page. Send a verification link to the proposed address, update the email after confirmation, and preserve the current login session. Apply subsequent review fixes.

#### Output Summary

Implemented email-change request and confirmation endpoints, profile-page controls, and confirmation-page handling using the existing email-token functionality. Added notification to the previous email address and required verification for administrative email updates. Grouped email-change endpoints under /users and updated tests and documentation.

#### Action Taken

- [ ] Accepted as-is
- [x] Modified
- [ ] Rejected

#### Author Notes

I reviewed the implementation and requested simpler form handling, fewer redundant tests, and fixes responding to teammate feedback. The existing email remains active until the new address is verified, and confirmation preserves the current login session. Automated checks and CI passed. Manual integration testing confirmed the email-change functionality.

---

### Date/Time

2026-10-11 to 2026-10-18

#### Tool

Codex

#### Prompt/Command

Implement Forgot Password from the login page using emailed password-reset links. Reuse existing email-token and password-update functionality, and address independent and teammate review findings.

#### Output Summary

Added password-reset request and submission endpoints, frontend request and reset pages, and password-reset emails. Reused password validation and password-saving logic. Revoked refresh tokens and pending password-reset and email-change links after password changes. Added uniform cooldown responses, explicit error handling, reset-completion notifications, and focused tests. Allowed password recovery for unverified accounts and marked the email verified after a successful reset.

#### Action Taken

- [ ] Accepted as-is
- [x] Modified
- [ ] Rejected

#### Author Notes

I reviewed the implementation and requested fixes based on independent and teammate feedback. Users reset their password through an emailed link and then log in with the new password. A successful reset revokes existing refresh tokens and pending recovery or email-change links. Automated checks and CI passed. I manually verified the normal reset flow and completion notification; the remaining suggested manual scenarios were not tested.

---

### Date/Time

2026-11-02 22:48 to 2026-11-03 11:39

#### Tool

Codex

#### Prompt/Command

Implement AI-assisted errand creation according to the agreed requirements and local implementation plan. Add multi-turn chat, supplier selection, audio transcription, structured draft validation, and draft application to the existing creation form.

#### Output Summary

Implemented the frontend chat interface and backend AI endpoints. Added an OpenAI gateway, supplier validation, structured draft handling, frontend schema validation, recording controls, and automated tests.

#### Action Taken

- [ ] Accepted as-is
- [x] Modified
- [ ] Rejected

#### Author Notes

I reviewed the implementation and requested subsequent adjustments. Users can inspect and apply the generated draft before proceeding through the existing review, order creation, and credit checks. Automated tests, frontend type checking, linting, and build checks were run during implementation.

---

### Date/Time

2026-11-03 to 2026-11-05

#### Tool

Codex

#### Prompt/Command

Fix integration issues in AI-assisted errand creation, including outdated form context, feature availability, deployment configuration, diagnostic logging, and a race condition between stopping a recording and starting transcription.

#### Output Summary

Added form-change handling and capability checks. Corrected Compose configuration, added upstream error diagnostics, expanded Terraform test coverage, centralized model defaults, and fixed recording state handling and the capability request timeout.

#### Action Taken

- [ ] Accepted as-is
- [x] Modified
- [ ] Rejected

#### Author Notes

I reviewed the reported issues and requested the corresponding fixes. Form changes now prompt users to continue with the current AI draft or restart from the updated form. The recording fix keeps input and sending disabled until the audio is handed over to transcription. Regression tests covered delayed recording events, and the capability request was given a 15-second timeout.

---

### Date/Time

2026-11-06 01:04–01:16

#### Tool

Codex

#### Prompt/Command

Resolve conflicts when merging the updated main branch into AI-Assisted-Creation. Integrate the AI feature with the updated form, credit validation, and ECS configuration, then verify and commit the merge locally.

#### Output Summary

Resolved five merge conflicts, integrated AI draft application with the updated form and credit checks, and adapted the AI deployment configuration to the updated ECS structure. Added a regression test for an AI-generated reward exceeding the user's available balance.

#### Action Taken

- [x] Accepted as-is
- [ ] Modified
- [ ] Rejected

#### Author Notes

The merge result passed 307 frontend tests, 26 order-service AI-related tests, and 7 Terraform mock tests. Frontend build and ESLint checks also passed. I subsequently completed local integration testing with the project's OpenAI key and reported that it passed on November 6 at 11:05.

---

## ARIFOVAS KRISTUPAS

### Date/Time

2026-11-03 00:27–00:31

#### Tool

Codex

#### Prompt/Command

Check whether the supplier seed CSV files are included in the Docker build and fix the missing resources. Copy the two required CSVs explicitly.

#### Output Summary

Added explicit Docker COPY inputs for the two supplier seed CSVs. Built the supplier image and checked that its application JAR contained both resources and that they matched the source files.

#### Action Taken

- [ ] Accepted as-is
- [x] Modified
- [ ] Rejected

#### Author Notes

The issue came from a code review comment supplied in the chat. I requested the fix and then asked for explicit filenames rather than copying the entire CSV directory.

---

### Date/Time

2026-11-03 00:44–00:46

#### Tool

Codex

#### Prompt/Command

The label “Open now · Closes at 23:59” does not account for shops that operate 24 hours.

#### Output Summary

Updated supplier status labels and weekly schedules to recognise the existing 00:00–23:59 seed-data convention as 24-hour opening. Corrected the final-minute edge case in frontend logic and API filtering.

#### Action Taken

- [x] Accepted as-is
- [ ] Modified
- [ ] Rejected

#### Author Notes

I identified the incorrect display. The fix also addressed the associated 23:59 filtering behaviour. The transcript reports passing frontend and backend tests, TypeScript, and ESLint.

---

### Date/Time

2026-11-03 00:30–00:31

#### Tool

Codex

#### Prompt/Command

Check the supplier sorting logic and simplify it while keeping it understandable.

#### Output Summary

Explained duplicate variable declarations in the pasted comparator and compared two implementations. Updated the comparator to use explicit boolean comparisons, sorting open suppliers first and then alphabetically.

#### Action Taken

- [ ] Accepted as-is
- [x] Modified
- [ ] Rejected

#### Author Notes

I questioned whether the shorter numeric conversion was less understandable and requested the explicit boolean version. The transcript reports that TypeScript and ESLint checks passed.

---

### Date/Time

2026-11-05 15:17–15:18

#### Tool

Codex

#### Prompt/Command

Diagnose ERR_MODULE_NOT_FOUND when running realtime-service/src/index.ts and check the persisted IDE run configuration in .run.

#### Output Summary

Identified that the IDE was launching TypeScript directly through Node instead of the service’s tsx development script. Copied the existing RealtimeService run-configuration template into .run so IntelliJ could load it.

#### Action Taken

- [x] Accepted as-is
- [ ] Modified
- [ ] Rejected

#### Author Notes

I clarified that the IDE configuration should be persisted in .run. The change reused the existing template. The transcript does not confirm a successful launch afterward.
