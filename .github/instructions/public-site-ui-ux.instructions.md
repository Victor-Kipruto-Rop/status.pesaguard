---
description: "PesaGuard public-site UI/UX constraints and brand direction."
applyTo: "public.pesaguard.victorkipruto.com/**/*"
---

# PesaGuard public-site UI/UX

- Use the UI/UX Pro Max prompt at `.github/prompts/ui-ux-pro-max.prompt.md` for interface design and UX reviews. Treat its search results as recommendations, not as authority over this file or existing product decisions.
- Preserve the public site's existing green-and-white brand system. `styles/globals.css` is the source of truth for design tokens and system theme detection: light mode uses white surfaces with green accents; dark mode uses neutral charcoal surfaces with restrained green accents.
- Do not replace the current palette, introduce a competing accent theme, or add a manual theme selector unless explicitly requested.
- Keep the existing typography and Lucide icon conventions; do not add external font or icon dependencies for visual polish alone.
- Keep product status and data provenance explicit. Distinguish live, pilot, planned, simulated, and illustrative features; never label mock data or animations as live.
- Prioritize legible contrast, visible keyboard focus, accessible names, keyboard-operable navigation, reduced-motion support, and responsive layouts without horizontal overflow.
- Prefer the smallest coherent change that improves clarity, hierarchy, or task completion. Reuse existing components and tokens before adding new design abstractions.
