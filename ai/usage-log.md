# AI Usage Log

## Date/Time

2026-11-02 22:48 to 2026-11-03 11:39

### Tool

Codex

### Prompt/Command

Implement AI-assisted errand creation according to the agreed requirements and local implementation plan. Add multi-turn chat, supplier selection, audio transcription, structured draft validation, and draft application to the existing creation form.

### Output Summary

Implemented the frontend chat interface and backend AI endpoints. Added an OpenAI gateway, supplier validation, structured draft handling, frontend schema validation, recording controls, and automated tests.

### Action Taken

- [ ] Accepted as-is
- [x] Modified
- [ ] Rejected

### Author Notes

I reviewed the implementation and requested subsequent adjustments. Users can inspect and apply the generated draft before proceeding through the existing review, order creation, and credit checks. Automated tests, frontend type checking, linting, and build checks were run during implementation.

---

## Date/Time

2026-11-03 to 2026-11-05

### Tool

Codex

### Prompt/Command

Fix integration issues in AI-assisted errand creation, including outdated form context, feature availability, deployment configuration, diagnostic logging, and a race condition between stopping a recording and starting transcription.

### Output Summary

Added form-change handling and capability checks. Corrected Compose configuration, added upstream error diagnostics, expanded Terraform test coverage, centralized model defaults, and fixed recording state handling and the capability request timeout.

### Action Taken

- [ ] Accepted as-is
- [x] Modified
- [ ] Rejected

### Author Notes

I reviewed the reported issues and requested the corresponding fixes. Form changes now prompt users to continue with the current AI draft or restart from the updated form. The recording fix keeps input and sending disabled until the audio is handed over to transcription. Regression tests covered delayed recording events, and the capability request was given a 15-second timeout.

---

## Date/Time

2026-11-06 01:04–01:16

### Tool

Codex

### Prompt/Command

Resolve conflicts when merging the updated main branch into AI-Assisted-Creation. Integrate the AI feature with the updated form, credit validation, and ECS configuration, then verify and commit the merge locally.

### Output Summary

Resolved five merge conflicts, integrated AI draft application with the updated form and credit checks, and adapted the AI deployment configuration to the updated ECS structure. Added a regression test for an AI-generated reward exceeding the user's available balance.

### Action Taken

- [x] Accepted as-is
- [ ] Modified
- [ ] Rejected

### Author Notes

The merge result passed 307 frontend tests, 26 order-service AI-related tests, and 7 Terraform mock tests. Frontend build and ESLint checks also passed. I subsequently completed local integration testing with the project's OpenAI key and reported that it passed on November 6 at 11:05.
