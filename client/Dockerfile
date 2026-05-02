FROM node:22-alpine AS build
WORKDIR /app
COPY package.json tsconfig.json tsconfig.app.json vite.config.ts index.html ./
COPY src ./src
RUN npm install
RUN npm run build

FROM nginx:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
