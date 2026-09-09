# frontend-web

React single-page application for the Focus Stack browser UI.
Built with Vite and packaged into the Spring Boot app JAR for production deployment.

## Features

- Live View preview (MJPEG stream from the camera)
- Capture controls for focus-stacking workflows
- Stack gallery with before/after comparison
- Responsive layout for desktop and tablet

## Prerequisites

- Node.js 24 LTS (CI and Maven use 24.21.0); see `package.json` for compatible alternatives.
- Node compatibility is enforced during installation by `.npmrc`.

## Development

```bash
npm ci
npm run dev      # Vite dev server with HMR on http://localhost:5173
npm run lint     # ESLint checks
npm run build    # Production build to dist/
```

## Maven Integration

The `frontend-maven-plugin` in `pom.xml` handles Node installation and build
as part of the Maven reactor. No manual Node setup is needed when building
from the repository root.
