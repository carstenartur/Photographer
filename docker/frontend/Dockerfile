# Stage 1: Build the React application
# NOTE: This is a placeholder Dockerfile. The frontend-web module does not exist yet.
# Uncomment and adjust once the React application is created.
FROM node:20-alpine AS build

WORKDIR /app

# Copy package files for dependency caching
# COPY frontend-web/package.json frontend-web/package-lock.json ./
# RUN npm ci

# Copy source and build
# COPY frontend-web/ ./
# RUN npm run build

# Stage 2: Serve with nginx
FROM nginx:alpine

# Copy custom nginx config if needed
# COPY docker/frontend/nginx.conf /etc/nginx/conf.d/default.conf

# Copy built assets from build stage
# COPY --from=build /app/build /usr/share/nginx/html

# Placeholder: serve default nginx page until frontend is built
EXPOSE 80

CMD ["nginx", "-g", "daemon off;"]
