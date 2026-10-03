FROM docker.io/library/node:22-alpine AS build

WORKDIR /src
COPY package.json .
RUN npm install
COPY . .
# Leave VITE_HOME_API_BASE_URL unset so the SPA uses same-origin /api
# (proxied by nginx in this image, or by Vite in local dev).
RUN npm run build

FROM docker.io/library/nginx:1.27-alpine
RUN apk add --no-cache gettext \
	&& mkdir -p /tmp/nginx/client_temp /tmp/nginx/proxy_temp \
	/tmp/nginx/fastcgi_temp /tmp/nginx/uwsgi_temp /tmp/nginx/scgi_temp \
	&& chown -R 101:101 /tmp/nginx
COPY nginx.conf.template /etc/nginx/nginx.conf.template
COPY docker-entrypoint.sh /docker-entrypoint.sh
RUN chmod +x /docker-entrypoint.sh
COPY --from=build /src/dist /usr/share/nginx/html
EXPOSE 8080

USER 101:101
ENV HOME_API_UPSTREAM=http://home-api:8082
ENTRYPOINT ["/docker-entrypoint.sh"]
