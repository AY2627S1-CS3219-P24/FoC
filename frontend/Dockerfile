# syntax=docker/dockerfile:1

FROM node:22-alpine AS build
WORKDIR /app

COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci

COPY frontend/ ./
RUN npm run build

FROM nginx:alpine AS runtime
RUN apk add --no-cache curl openssl \
    && rm -rf /usr/share/nginx/html/*

COPY --from=build /app/dist/ /usr/share/nginx/html/
COPY docker/nginx/default.conf /etc/nginx/conf.d/default.conf
COPY docker/nginx/security-headers.conf /etc/nginx/snippets/security-headers.conf
COPY docker/nginx/entrypoint.sh /entrypoint.sh
COPY docker/nginx/generate-certs.sh /generate-certs.sh

EXPOSE 80 443
ENTRYPOINT ["/bin/sh", "/entrypoint.sh"]
