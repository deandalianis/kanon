FROM node:22-alpine AS builder
WORKDIR /app

COPY apps/workbench-web/package*.json ./
RUN npm ci

COPY apps/workbench-web ./
RUN npm run build

FROM nginx:1.27-alpine
COPY apps/workbench-web/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=builder /app/dist /usr/share/nginx/html

EXPOSE 80
