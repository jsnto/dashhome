# dashhome frontend — the built React app served by nginx.
#
# Two stages:
#   build — Node builds the React frontend into dist/
#   final — nginx serves dist/ on :8000 and proxies /api/ to the
#           backend compose service (same-origin, no CORS)

# --- build: the React app ----------------------------------------------------
FROM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

# --- final: nginx runtime -----------------------------------------------------
FROM nginx:1.27-alpine
COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 8000
