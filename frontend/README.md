# FoC Frontend

## Quick start

1. Install Node.js 22.12 or newer and npm.
2. From the repository root, run:
   ```bash
   cd frontend
   npm install
   npm run dev
   ```
3. Open [http://localhost:3000](http://localhost:3000).

## Authentication pages

- `/register`: registration form with client-side validation.
- `/login`: login form with client-side validation.

Backend authentication integration, session restoration, and logout are tracked
separately in FOC-19. Valid form submissions do not currently send API requests.

## Development checks

Run these commands from the `frontend` directory:

```bash
npm run test:run
npm exec --no -- tsc --noEmit
npm run lint
npm run check
npm run build
```
