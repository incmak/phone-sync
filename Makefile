.PHONY: sync-proto proto-test relay-test relay-ci-test relay-verify relay-build deployment-test mobile-verify host-verify verify e2e-emulator e2e-offline-pairing release-audit clean

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
	cd mobile && npm test -- --runInBand
	cd mobile && npx expo-doctor
	cd mobile && npx expo prebuild --platform android --clean --no-install
	cd mobile/android && ./gradlew --no-daemon lintDebug testDebugUnitTest compileDebugAndroidTestKotlin assembleDebug

host-verify: proto-test
	cd mobile && npm ci
	cd mobile && npm run typecheck
	cd mobile && npm test -- --runInBand
	cd e2e && go test ./... -race -count=1
	cd e2e && go vet ./...
	./e2e/scripts/validate-workflow.sh
	./e2e/scripts/preflight_test.sh
	./scripts/verify-offline-pairing-evidence.sh --self-test
	./scripts/verify-release-evidence.sh --self-test
	./scripts/verify-android-release_test.sh
	./scripts/verify-host-workflows.sh
	./scripts/verify-host-workflows_test.sh
	./scripts/verify-generated-clean.sh

e2e-emulator: relay-build mobile-verify
	./e2e/scripts/run-two-emulators.sh

e2e-offline-pairing:
	@test -n "$(E2E_DEVICE_A)" -a -n "$(E2E_DEVICE_B)" -a "$(E2E_DEVICE_A)" != "$(E2E_DEVICE_B)" || { echo "two explicit distinct E2E_DEVICE_A/E2E_DEVICE_B serials are required" >&2; exit 2; }
	@test -n "$(E2E_PACKET_EVIDENCE_SHA256)" -a -n "$(E2E_DNS_EVIDENCE_SHA256)" || { echo "operator-captured packet and DNS evidence hashes are required" >&2; exit 2; }
	@test -n "$(E2E_OFFLINE_PAIRING_EVIDENCE_DIR)" || { echo "E2E_OFFLINE_PAIRING_EVIDENCE_DIR is required" >&2; exit 2; }
	cd e2e && go run ./cmd/twinotify-e2e -scenario offline-pairing -serial-a "$(E2E_DEVICE_A)" -serial-b "$(E2E_DEVICE_B)" -internet-blocked -packet-evidence-sha256 "$(E2E_PACKET_EVIDENCE_SHA256)" -dns-evidence-sha256 "$(E2E_DNS_EVIDENCE_SHA256)" -evidence-dir "$(abspath $(E2E_OFFLINE_PAIRING_EVIDENCE_DIR))"
	./scripts/verify-offline-pairing-evidence.sh "$(E2E_OFFLINE_PAIRING_EVIDENCE_DIR)"

verify: proto-test relay-verify mobile-verify
	./scripts/verify-generated-clean.sh

release-audit: verify
	@test -n "$(RELEASE_EVIDENCE_DIR)" || { echo "RELEASE_EVIDENCE_DIR is required" >&2; exit 2; }
	./scripts/verify-release-evidence.sh "$(RELEASE_EVIDENCE_DIR)"

clean:
	rm -rf relay/internal/server/schemas relay/internal/server/fixtures bin
