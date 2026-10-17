FROM node:24-alpine@sha256:44fce696f38ba8c987c4db89954051000bbd05ffd8d8659ba15ed4a8602e937f AS build
WORKDIR /build
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build
ARG SOURCE_REVISION=development
ARG RELEASE_ID=development
RUN node -e 'require("node:fs").writeFileSync("dist/release.json", JSON.stringify({sourceRevision: process.env.SOURCE_REVISION, releaseId: process.env.RELEASE_ID}) + "\n")'

FROM nginx:stable-alpine@sha256:970364228b1e4f932abc22d321932816782cca8a20b85a77552a7cfe9acfb3b5
ARG SOURCE_REVISION=development
LABEL org.opencontainers.image.source="https://github.com/dcfaight/developer-platform-lab" \
      org.opencontainers.image.revision="${SOURCE_REVISION}"
COPY nginx.conf /etc/nginx/nginx.conf
COPY --from=build /build/dist /usr/share/nginx/html
USER 101:101
EXPOSE 8443
HEALTHCHECK --interval=10s --timeout=3s --retries=3 \
    CMD wget -q -O /dev/null http://127.0.0.1:8080/healthz || exit 1
ENTRYPOINT ["nginx"]
CMD ["-g", "daemon off;"]
