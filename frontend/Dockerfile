FROM node:20-alpine AS build
WORKDIR /app
ARG VITE_OIDC_AUTHORITY=http://localhost:3000
ENV VITE_OIDC_AUTHORITY=$VITE_OIDC_AUTHORITY
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginx:alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
