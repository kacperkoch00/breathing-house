FROM docker.io/library/node:22-alpine AS build

WORKDIR /src
COPY package.json .
RUN npm install
COPY . .
RUN npm run build

FROM docker.io/library/nginx:1.27-alpine
RUN mkdir -p /tmp/nginx/client_temp /tmp/nginx/proxy_temp \
	/tmp/nginx/fastcgi_temp /tmp/nginx/uwsgi_temp /tmp/nginx/scgi_temp \
	&& chown -R 101:101 /tmp/nginx
COPY nginx.conf /etc/nginx/nginx.conf
COPY --from=build /src/dist /usr/share/nginx/html
EXPOSE 8080

USER 101:101
ENTRYPOINT []
CMD ["nginx", "-g", "daemon off;"]
