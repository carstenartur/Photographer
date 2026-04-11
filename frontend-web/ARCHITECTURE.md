# frontend-web Architecture

## Overview

The `frontend-web` module is a React single-page application built with Vite.
It provides the browser-based UI for live preview, capture control, and
stack review. In production, it is bundled into the `app` module's JAR as
static resources.

## Tech Stack

| Technology | Purpose |
|---|---|
| React 19 | UI component library |
| Vite | Build tool and dev server with HMR |
| ESLint | Code linting |
| frontend-maven-plugin | Maven integration (Node install, npm install, npm build) |

## Build Pipeline

1. `frontend-maven-plugin` installs Node.js (v20.19.0) and runs `npm install`
2. `npm run build` produces optimized static assets in `dist/`
3. The `app` module's `maven-resources-plugin` copies `dist/` into
   `classes/static/` at package time
4. Spring Boot serves these files as the default web root

## Development

```bash
cd frontend-web
npm install
npm run dev      # Vite dev server with hot-reload on http://localhost:5173
npm run lint     # ESLint checks
npm run build    # Production build to dist/
```

## Key Directories

```
frontend-web/
├── public/            # Static assets served as-is
├── src/               # React components and application code
├── index.html         # Vite entry point
├── vite.config.js     # Vite configuration
├── eslint.config.js   # ESLint configuration
├── package.json       # npm dependencies and scripts
└── pom.xml            # Maven wrapper for frontend-maven-plugin
```
