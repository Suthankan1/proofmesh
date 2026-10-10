# syntax=docker/dockerfile:1@sha256:4edf897a3ffa55b89f906fc8cc78afdb3f1834cc9c7083565e611a8a7d5fe99e

# Image index digests are reviewed build inputs; refresh explicitly for security updates.
# Stage 1: Build binaries
FROM golang:1.26.0@sha256:fb612b7831d53a89cbc0aaa7855b69ad7b0caf603715860cf538df854d047b84 AS builder

WORKDIR /src

# Leverage module caching
COPY go.mod go.sum ./
RUN go mod download

# Copy source trees
COPY cmd ./cmd
COPY internal ./internal
COPY migrations ./migrations

# Build statically compiled binaries with path trimming
RUN CGO_ENABLED=0 GOOS=linux go build -trimpath -o /out/gateway ./cmd/gateway
RUN CGO_ENABLED=0 GOOS=linux go build -trimpath -o /out/migrate ./cmd/migrate

# Stage 2: Hardened gateway runtime
FROM gcr.io/distroless/static-debian12:nonroot@sha256:afa5c872c891853ca7fcf1f12c3edb23f7eeef36189728842dd51042ff57f7ab AS gateway-runtime

USER nonroot:nonroot

COPY --from=builder /out/gateway /gateway

EXPOSE 8080

ENTRYPOINT ["/gateway"]

# Stage 3: Hardened migration runtime
FROM gcr.io/distroless/static-debian12:nonroot@sha256:afa5c872c891853ca7fcf1f12c3edb23f7eeef36189728842dd51042ff57f7ab AS migrate-runtime

USER nonroot:nonroot

COPY --from=builder /out/migrate /migrate

ENTRYPOINT ["/migrate"]
