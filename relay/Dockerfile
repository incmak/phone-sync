# syntax=docker/dockerfile:1.6
# Build context: repo root (contains /proto and /relay)
FROM golang:1.23-alpine AS build
WORKDIR /src
COPY relay/go.mod relay/go.sum ./relay/
RUN cd relay && go mod download
COPY proto/ ./proto/
COPY relay/ ./relay/
# Inline make sync-proto: mkdir + cp (avoids apk network dep in build image)
RUN mkdir -p relay/internal/server/schemas && cp proto/*.schema.json relay/internal/server/schemas/
RUN cd relay && CGO_ENABLED=0 GOOS=linux go build -ldflags="-s -w" -o /out/relay ./cmd/relay

FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=build /out/relay /relay
EXPOSE 8080
USER nonroot:nonroot
ENTRYPOINT ["/relay"]
