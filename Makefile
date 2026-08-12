.PHONY: sync-proto proto-test relay-test relay-verify relay-build deployment-test clean

sync-proto:
	mkdir -p relay/internal/server/schemas relay/internal/server/fixtures
	rm -f relay/internal/server/schemas/*.schema.json
	rm -rf relay/internal/server/fixtures/*
	cp proto/*.schema.json relay/internal/server/schemas/
	cp -R proto/fixtures/. relay/internal/server/fixtures/

proto-test: sync-proto
	cd relay && go test ./internal/server -run 'TestValidator|TestProtocolFixtures' -count=1

relay-test: sync-proto
	cd relay && go test ./... -race -count=1

relay-verify: sync-proto
	@test -z "$$(cd relay && gofmt -l .)"
	cd relay && go vet ./...
	cd relay && go test ./... -race -count=1
	docker build -t twinotify-relay:verify -f relay/Dockerfile .

relay-build: sync-proto
	cd relay && go build -o ../bin/relay ./cmd/relay

deployment-test:
	./deploy/assert-compose.sh

clean:
	rm -rf relay/internal/server/schemas relay/internal/server/fixtures bin
