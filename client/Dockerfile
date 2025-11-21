# --- 第一阶段：构建 React ---
FROM node:18-alpine as build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm install
COPY . .
RUN npm run build

# --- 第二阶段：Nginx 托管 ---
FROM nginx:alpine
# 复制构建好的静态文件
COPY --from=build /app/build /usr/share/nginx/html
# 复制我们刚才写的 nginx 配置
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]