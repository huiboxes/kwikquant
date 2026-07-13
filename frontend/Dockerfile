# 前端 nginx 镜像(可选;CI 默认 scp dist 到服务器 nginx)
# 用法:docker build -f frontend/Dockerfile -t kwikquant-frontend frontend/
FROM node:22-alpine AS build
WORKDIR /app
RUN corepack enable
COPY package.json pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile
COPY . .
RUN pnpm build

FROM nginx:1.27-alpine
COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
