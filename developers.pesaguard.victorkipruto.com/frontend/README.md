# PesaGuard Developer Platform

Responsive React and TypeScript frontend for the PesaGuard developer experience.
The starter includes the workspace shell, overview, API explorer, credential,
webhook, usage, and activity views.

## Run locally

```powershell
npm install
npm run dev
```

Create a production build with `npm run build`; preview it with `npm run preview`.

## Current integration boundary

This frontend is a UI preview and is not connected to developer authentication,
API-key management, request execution, webhook delivery, or live analytics.
Dashboard metrics, keys, activity, and webhook examples are illustrative
fixtures, identified in the interface as preview or sample data. The API
explorer does not send requests to the PesaGuard API. Do not enter real
credentials into the preview.

Documentation and public site links point to the existing PesaGuard websites.
The color, typography, and device-driven theme tokens mirror those sites; see
`design-system/pesaguard-developer-platform/pages/dashboard.md`.

## Project structure

The supplied `structure.txt` is the target architecture for the broader
developer platform. This frontend implements its application entry point,
shared layout, UI primitives, and initial dashboard workflows first. Feature
areas that require backend contracts—authentication, organizations, projects,
credentials, webhooks, usage, security, and production access—must be wired to
verified API endpoints before being presented as operational.
