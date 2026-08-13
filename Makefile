.PHONY: sync-proto proto-test relay-test relay-ci-test relay-verify relay-build deployment-test mobile-verify clean

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

relay-ci-test:
	@test "$$(grep -Foc "'deploy/**'" .github/workflows/relay.yml)" -eq 2 || { echo "relay workflow must trigger on deploy/** for push and pull_request" >&2; exit 1; }
	@grep -Fq 'run: make deployment-test' .github/workflows/relay.yml || { echo "relay workflow must run make deployment-test" >&2; exit 1; }
	@grep -Fq 'permissions:' .github/workflows/relay.yml && grep -Fq 'contents: read' .github/workflows/relay.yml || { echo "relay workflow must use read-only contents permission" >&2; exit 1; }
	@test "$$(grep -Ec '^[[:space:]]*- uses: [^[:space:]]+@[0-9a-f]{40}([[:space:]]|$$)' .github/workflows/relay.yml)" -eq "$$(grep -Ec '^[[:space:]]*- uses:' .github/workflows/relay.yml)" || { echo "relay workflow actions must use full commit SHAs" >&2; exit 1; }

relay-verify: sync-proto relay-ci-test
	@test -z "$$(cd relay && gofmt -l .)"
	cd relay && go vet ./...
	cd relay && go test ./... -race -count=1
	docker build -t twinotify-relay:verify -f relay/Dockerfile .

relay-build: sync-proto
	cd relay && go build -o ../bin/relay ./cmd/relay

deployment-test:
	./deploy/assert-compose.sh

mobile-verify: sync-proto
	cd mobile && npm ci
	cd mobile && npm run typecheck
	cd mobile && npx expo-doctor
	cd mobile && npx expo prebuild --platform android --clean --no-install
	cd mobile/android && ./gradlew --no-daemon lintDebug testDebugUnitTest assembleDebug

clean:
	rm -rf relay/internal/server/schemas relay/internal/server/fixtures bin
