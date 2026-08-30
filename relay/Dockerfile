# syntax=docker/dockerfile:1.6@sha256:ac85f380a63b13dfcefa89046420e1781752bab202122f8f50032edf31be0021
# Build context: repo root (contains /proto and /relay)
FROM golang:1.25.13-alpine@sha256:1e0126852075c9c60731c8ba49088448b91f63e2aed97ca9d1a9791622a05946 AS build
ARG VERSION=dev
WORKDIR /src
COPY relay/go.mod relay/go.sum ./relay/
RUN cd relay && go mod download
COPY proto/ ./proto/
COPY relay/ ./relay/
# Inline make sync-proto: mkdir + cp (avoids apk network dep in build image)
RUN mkdir -p relay/internal/server/schemas && cp proto/*.schema.json relay/internal/server/schemas/
RUN mkdir -p /out/runtime/data /out/runtime/backups \
    && cd relay \
    && CGO_ENABLED=0 GOOS=linux go build -trimpath -ldflags="-s -w -X main.buildVersion=${VERSION}" -o /out/relay ./cmd/relay

FROM gcr.io/distroless/static-debian12:nonroot@sha256:afa5c872c891853ca7fcf1f12c3edb23f7eeef36189728842dd51042ff57f7ab
ARG VERSION=dev
ARG VCS_REF=unknown
ARG BUILD_DATE=unknown
LABEL org.opencontainers.image.title="Twinotify Relay" \
      org.opencontainers.image.description="Untrusted encrypted notification relay" \
      org.opencontainers.image.source="https://github.com/incmak/phone-sync" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.revision="${VCS_REF}" \
      org.opencontainers.image.created="${BUILD_DATE}"
COPY --from=build --chown=65532:65532 /out/relay /relay
COPY --from=build --chown=65532:65532 /out/runtime/data /data
COPY --from=build --chown=65532:65532 /out/runtime/backups /backups
EXPOSE 8080
USER 65532:65532
ENTRYPOINT ["/relay"]
