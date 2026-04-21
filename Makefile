.PHONY: sync-proto relay-test relay-build clean

sync-proto:
	mkdir -p relay/internal/server/schemas
	rm -f relay/internal/server/schemas/*.schema.json
	cp proto/*.schema.json relay/internal/server/schemas/

relay-test: sync-proto
	cd relay && go test ./... -race -count=1

relay-build: sync-proto
	cd relay && go build -o ../bin/relay ./cmd/relay

clean:
	rm -rf relay/internal/server/schemas bin
