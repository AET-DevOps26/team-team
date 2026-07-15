# The client is built on the host (scripts/dev-up.ps1 runs `npm ci` + `npm run build`
# in ./client before invoking docker compose). We ship only the compiled `dist/`
# into the image so the container never needs npm/node.
#
# Why the two-stage image was dropped: Cloudflare's bot filter rejects the TLS
# ClientHello fingerprint of processes inside Docker Desktop's WSL 2 VM for
# registry.npmjs.org / registry.yarnpkg.com. `npm ci` therefore fails with
# ERR_SSL_ALERT_HANDSHAKE_FAILURE regardless of --network host, base image
# (alpine vs bookworm-slim), TLS version, or maxsockets. The host itself is
# unaffected, so we do the build there.
FROM nginx:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY dist /usr/share/nginx/html
EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
