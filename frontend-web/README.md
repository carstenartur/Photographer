# frontend-web

React single-page application for the Focus Stack browser UI.
Built with Vite and packaged into the Spring Boot app JAR for production deployment.

## Features

- Live View preview (MJPEG stream from the camera)
- Capture controls for focus-stacking workflows
- Stack gallery with before/after comparison
- Responsive layout for desktop and tablet

## Prerequisites

- Node.js ^20.19.0 or ≥22.12.0

## Development

```bash
npm install
npm run dev      # Vite dev server with HMR on http://localhost:5173
npm run lint     # ESLint checks
npm run build    # Production build to dist/
```

## Maven Integration

The `frontend-maven-plugin` in `pom.xml` handles Node installation and build
as part of the Maven reactor. No manual Node setup is needed when building
from the repository root.
