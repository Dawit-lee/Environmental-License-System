# Built from frontend/ (compose sets the context). Both base images are multi-arch, so this builds on arm64 as is.
FROM node:24-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN VITE_DEMO_LOGIN=true npx vite build

FROM caddy:2.10-alpine
COPY --from=build /app/dist /srv
