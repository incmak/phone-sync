#!/usr/bin/env bash
set -Eeuo pipefail

die() { echo "lan-product-evidence: $*" >&2; exit 1; }

required_children=(
  lan-direct-delivery
  lan-direct-reverse-delivery
  lan-direct-dismiss
  lan-direct-update
  lan-direct-peer-dismiss
  lan-direct-call-state
  lan-direct-snapshot-receipt
  lan-relay-fallback-return
  lan-restart-persistence
  lan-direct-burst-backpressure
  lan-direct-unpair-during-traffic
)

mode_of() {
  if stat -f %Lp "$1" >/dev/null 2>&1; then stat -f %Lp "$1"; else stat -c %a "$1"; fi
}

require_event() {
  local file=$1 event=$2
  jq -e --arg event "$event" '.events | index($event) != null' "$file" >/dev/null || die "$(basename "$(dirname "$file")") missing $event"
}

scan_artifact() {
  local file=$1 label=$2
  [[ -f "$file" && ! -L "$file" && "$(mode_of "$file")" == 600 ]] || die "$label missing or unsafe"
  [[ $(wc -c <"$file") -le 1048576 ]] || die "$label exceeds size bound"
  jq -e . "$file" >/dev/null || die "$label is not valid JSON"
  ! grep -Eqi '(^|[^a-z])(ssid|bssid|secret|token|password|private_key|seed|nonce|credential|cookie|signature|certificate|ciphertext|notification_text|title|port)([^a-z]|$)|([0-9]{1,3}\.){3}[0-9]{1,3}|(wss?|https?)://|authorization[[:space:]]*:[[:space:]]*bearer' "$file" || die "$label contains forbidden evidence"
}

verify_artifact_set() {
  local dir=$1 name=$2 status=$3 leaf count
  count=$(find "$dir" -mindepth 1 -maxdepth 1 -type f | wc -l | tr -d ' ')
  [[ "$count" == 4 ]] || die "$name artifact inventory is incomplete"
  for leaf in scenario-result.json state.json timeline.json metrics.json; do
    scan_artifact "$dir/$leaf" "$name/$leaf"
  done
  jq -se --arg name "$name" --arg status "$status" '
    .[0] as $result | .[1] as $state | .[2] as $timeline | .[3] as $metrics |
    ($state | type == "object" and keys == (["after","before","scenario","status"] | sort) and .scenario == $name and .status == $status and .before == $result.before and .after == $result.after) and
    ($timeline | type == "object" and keys == (["events","scenario","status"] | sort) and .scenario == $name and .status == $status and .events == $result.events) and
    ($metrics | type == "object" and keys == (["after","converged","scenario","status"] | sort) and .scenario == $name and .status == $status and .after == $result.after and (.converged | type == "object" and keys == (["A","B"] | sort) and all(.[]; type == "boolean")))
  ' "$dir/scenario-result.json" "$dir/state.json" "$dir/timeline.json" "$dir/metrics.json" >/dev/null || die "$name derived artifacts are inconsistent or non-contract"
}

verify_common_result() {
  local name=$1 file=$2
  jq -e --arg name "$name" '
    def integer_between($min; $max):
      type == "number" and floor == . and . >= $min and . <= $max;
    def optional_stable_code($key):
      (has($key) | not) or (.[$key] | type == "string" and test("^[a-z][a-z0-9_]{0,63}$"));
    def observation:
      type == "object" and
      ((keys - ["health","transport","call_capture_enabled","call_capture_health_code","outbox","active_inbound","pending_materialization","mirror","sequence","terminal","loop_events","route","route_phase","queued_bytes","route_generation","peer_evidence","pending_local_count","awaiting_peer_count","held_by_relay_count","delivery_reason","user_content_kind","receipt_at_ms","error_code","paired","custody_counts","peer_receipt_count","snapshot_digest_count","snapshot_begin_count","snapshot_end_count","snapshot_commit_count","user_dismiss_count","unpair_inbound_count","unpair_outcome","active_queue_count","active_queue_bytes","peak_queue_count","peak_queue_bytes"]) | length == 0) and
      ([has("health"),has("call_capture_enabled"),has("outbox"),has("active_inbound"),has("pending_materialization"),has("mirror"),has("sequence"),has("terminal"),has("loop_events"),has("route"),has("route_phase"),has("queued_bytes"),has("route_generation"),has("peer_evidence"),has("pending_local_count"),has("awaiting_peer_count"),has("held_by_relay_count"),has("delivery_reason"),has("user_content_kind"),has("paired"),has("custody_counts"),has("peer_receipt_count"),has("snapshot_digest_count"),has("snapshot_begin_count"),has("snapshot_end_count"),has("snapshot_commit_count"),has("user_dismiss_count"),has("unpair_inbound_count"),has("active_queue_count"),has("active_queue_bytes"),has("peak_queue_count"),has("peak_queue_bytes")] | all) and
      (.health | type == "string" and test("^[a-z][a-z0-9_-]{0,63}$")) and
      (.call_capture_enabled | type == "boolean") and (.mirror | type == "boolean") and
      (.terminal | type == "boolean") and (.paired | type == "boolean") and
      (.route == "lan" or .route == "relay" or .route == "none") and
      (.route_phase == "idle" or .route_phase == "connecting" or .route_phase == "authenticated" or .route_phase == "reconnecting") and
      (.peer_evidence == "direct" or .peer_evidence == "recent" or .peer_evidence == "stale" or .peer_evidence == "unknown") and
      (.delivery_reason == "none" or .delivery_reason == "no_route" or .delivery_reason == "waiting_for_peer" or .delivery_reason == "relay_holding" or .delivery_reason == "lan_bootstrap_waiting" or .delivery_reason == "lan_binding_conflict" or .delivery_reason == "peer_version_incompatible") and
      (.user_content_kind == "notifications" or .user_content_kind == "sync_updates") and
      (.outbox | integer_between(0; 2000)) and
      (.active_inbound | integer_between(0; 1000000000)) and
      (.pending_materialization | integer_between(0; 1000000000)) and
      (.sequence | integer_between(0; 1000000000)) and
      (.loop_events | integer_between(0; 1000000000)) and
      (.queued_bytes | integer_between(0; 134217728)) and
      (.route_generation | integer_between(0; 1000000000)) and
      (.pending_local_count | integer_between(0; 2000)) and
      (.awaiting_peer_count | integer_between(0; 2000)) and
      (.held_by_relay_count | integer_between(0; 2000)) and
      (.active_queue_count | integer_between(0; 2000)) and
      (.active_queue_bytes | integer_between(0; 134217728)) and
      (.peak_queue_count | integer_between(0; 2000)) and
      (.peak_queue_bytes | integer_between(0; 134217728)) and
      ([.peer_receipt_count,.snapshot_digest_count,.snapshot_begin_count,.snapshot_end_count,.snapshot_commit_count,.user_dismiss_count,.unpair_inbound_count] | all(.[]; integer_between(0; 1000000000))) and
      ((has("receipt_at_ms") | not) or (.receipt_at_ms | integer_between(0; 9999999999999))) and
      optional_stable_code("call_capture_health_code") and optional_stable_code("error_code") and optional_stable_code("unpair_outcome") and
      (.custody_counts | type == "object" and keys == (["lan","relay"] | sort) and
        ([.lan,.relay] | all(.[];
          type == "object" and
          keys == (["call_state","notif_cancel","notif_post","notif_update","peer_receipt","state_digest","state_snapshot_begin","state_snapshot_end","state_snapshot_item","unpair"] | sort) and
          all(.[]; integer_between(0; 1000000000))
        )));
    def route_evidence:
      type == "object" and
      ((keys - ["route","phase","route_generation","queued_count","queued_bytes","peer_evidence","pending_local_count","awaiting_peer_count","held_by_relay_count","delivery_reason","user_content_kind","receipt_at_ms","error_code"]) | length == 0) and
      ([has("route"),has("phase"),has("route_generation"),has("queued_count"),has("queued_bytes"),has("peer_evidence"),has("pending_local_count"),has("awaiting_peer_count"),has("held_by_relay_count"),has("delivery_reason"),has("user_content_kind")] | all) and
      (.route == "lan" or .route == "relay") and .phase == "authenticated" and
      (.route_generation | integer_between(0; 1000000000)) and
      (.queued_count | integer_between(0; 2000)) and
      .queued_count == .pending_local_count and
      (.pending_local_count | integer_between(0; 2000)) and
      (.awaiting_peer_count | integer_between(0; 2000)) and
      (.held_by_relay_count | integer_between(0; 2000)) and
      (.peer_evidence == "direct" or .peer_evidence == "recent" or .peer_evidence == "stale" or .peer_evidence == "unknown") and
      (.delivery_reason == "none" or .delivery_reason == "no_route" or .delivery_reason == "waiting_for_peer" or .delivery_reason == "relay_holding" or .delivery_reason == "lan_bootstrap_waiting" or .delivery_reason == "lan_binding_conflict" or .delivery_reason == "peer_version_incompatible") and
      (.user_content_kind == "notifications" or .user_content_kind == "sync_updates") and
      (.queued_bytes | integer_between(0; 134217728)) and
      ((has("receipt_at_ms") | not) or (.receipt_at_ms | integer_between(0; 9999999999999))) and
      optional_stable_code("error_code");
    type == "object" and
    ((keys - ["scenario","status","events","before","after","error_code","route","route_transitions"]) | length == 0) and
    ([has("scenario"),has("status"),has("events"),has("before"),has("after"),has("route")] | all) and
    .scenario == $name and .status == "passed" and (has("error_code") | not) and
    (.events | type == "array" and length <= 10000 and all(.[]; type == "string" and length <= 256)) and
    (.before | type == "object" and keys == (["A","B"] | sort) and all(.[]; observation)) and
    (.after | type == "object" and keys == (["A","B"] | sort) and all(.[]; observation)) and
    (.route | route_evidence) and .route.route == "lan" and
    ((has("route_transitions") | not) or (.route_transitions | type == "array" and length <= 16 and all(.[]; route_evidence)))
  ' "$file" >/dev/null || die "$name result failed common closed-world assertions"
}

verify_child() {
  local name=$1 dir=$2 file="$2/scenario-result.json"
  [[ -d "$dir" && ! -L "$dir" && "$(mode_of "$dir")" == 700 ]] || die "$name directory missing or unsafe"
  [[ $(find "$dir" -mindepth 1 -maxdepth 1 -print | wc -l | tr -d ' ') == 4 ]] || die "$name contains unexpected artifacts"
  verify_artifact_set "$dir" "$name" passed
  verify_common_result "$name" "$file"
  require_event "$file" "predicate:A.route.lan"
  require_event "$file" "predicate:B.route.lan"
  case "$name" in
    lan-direct-delivery)
      require_event "$file" "predicate:terminal.converged"
      jq -e '(.after.A.custody_counts.lan.notif_post - .before.A.custody_counts.lan.notif_post) >= 1 and (.after.A.peer_receipt_count - .before.A.peer_receipt_count) >= 1' "$file" >/dev/null || die "$name observed custody/receipt deltas are incomplete"
      ;;
    lan-direct-reverse-delivery)
      require_event "$file" "post:B:n-lan-direct-reverse-delivery"
      require_event "$file" "predicate:A.tracked.sequence:1"
      require_event "$file" "predicate:B.custody.lan:notif_post:1"
      require_event "$file" "predicate:B.peer-receipt.delta:1"
      require_event "$file" "predicate:direct.terminal"
      jq -e '
        ([.events[] | select(startswith("route:post:B:n-lan-direct-reverse-delivery:lan:g"))] | length) == 1 and
        (.after.B.custody_counts.lan.notif_post - .before.B.custody_counts.lan.notif_post) >= 1 and
        (.after.B.peer_receipt_count - .before.B.peer_receipt_count) >= 1
      ' "$file" >/dev/null || die "$name observed reverse route/custody/receipt deltas are incomplete"
      ;;
    lan-direct-dismiss)
      require_event "$file" "predicate:B.mirror.absent:n-lan-direct-dismiss"
      require_event "$file" "predicate:terminal.converged"
      jq -e '(.after.A.custody_counts.lan.notif_post - .before.A.custody_counts.lan.notif_post) >= 1 and (.after.A.custody_counts.lan.notif_cancel - .before.A.custody_counts.lan.notif_cancel) >= 1 and (.after.A.peer_receipt_count - .before.A.peer_receipt_count) >= 2' "$file" >/dev/null || die "$name observed custody/receipt deltas are incomplete"
      ;;
    lan-direct-update)
      require_event "$file" "predicate:B.tracked.sequence:3"
      require_event "$file" "predicate:A.custody.lan:notif_update:2"
      require_event "$file" "predicate:A.peer-receipt.delta:3"
      require_event "$file" "predicate:direct.terminal"
      jq -e '(.after.A.custody_counts.lan.notif_update - .before.A.custody_counts.lan.notif_update) >= 2 and (.after.A.peer_receipt_count - .before.A.peer_receipt_count) >= 3' "$file" >/dev/null || die "$name observed custody/receipt deltas are incomplete"
      ;;
    lan-direct-peer-dismiss)
      require_event "$file" "predicate:B.user-dismiss.delta:1"
      require_event "$file" "predicate:B.tracked.no-resurrection"
      require_event "$file" "predicate:B.custody.lan:notif_cancel:1"
      require_event "$file" "predicate:B.peer-receipt.delta:1"
      require_event "$file" "predicate:direct.terminal"
      jq -e '(.after.B.user_dismiss_count - .before.B.user_dismiss_count) >= 1 and (.after.B.custody_counts.lan.notif_cancel - .before.B.custody_counts.lan.notif_cancel) >= 1 and (.after.B.peer_receipt_count - .before.B.peer_receipt_count) >= 1' "$file" >/dev/null || die "$name observed dismissal/custody/receipt deltas are incomplete"
      ;;
    lan-direct-call-state)
      for state in RINGING ACTIVE IDLE; do require_event "$file" "predicate:B.call.semantic:$state"; done
      require_event "$file" "predicate:A.custody.lan:call_state:3"
      require_event "$file" "predicate:A.peer-receipt.delta:3"
      require_event "$file" "predicate:direct.terminal"
      jq -e '(.after.A.custody_counts.lan.call_state - .before.A.custody_counts.lan.call_state) >= 3 and (.after.A.peer_receipt_count - .before.A.peer_receipt_count) >= 3' "$file" >/dev/null || die "$name observed call custody/receipt deltas are incomplete"
      ;;
    lan-direct-snapshot-receipt)
      for event in \
        'predicate:B.snapshot.digest.delta:1' \
        'predicate:B.snapshot.begin.delta:1' \
        'predicate:B.snapshot.end.delta:1' \
        'predicate:B.snapshot.commit.delta:1'; do require_event "$file" "$event"; done
      for event in \
        'predicate:A.custody.lan:state_digest:1' \
        'predicate:A.custody.lan:state_snapshot_begin:1' \
        'predicate:A.custody.lan:state_snapshot_item:1' \
        'predicate:A.custody.lan:state_snapshot_end:1' \
        'predicate:direct.terminal'; do require_event "$file" "$event"; done
      jq -e '
        (.after.B.snapshot_digest_count - .before.B.snapshot_digest_count) >= 1 and
        (.after.B.snapshot_begin_count - .before.B.snapshot_begin_count) >= 1 and
        (.after.B.snapshot_end_count - .before.B.snapshot_end_count) >= 1 and
        (.after.B.snapshot_commit_count - .before.B.snapshot_commit_count) >= 1 and
        (.after.A.custody_counts.lan.state_digest - .before.A.custody_counts.lan.state_digest) >= 1 and
        (.after.A.custody_counts.lan.state_snapshot_begin - .before.A.custody_counts.lan.state_snapshot_begin) >= 1 and
        (.after.A.custody_counts.lan.state_snapshot_item - .before.A.custody_counts.lan.state_snapshot_item) >= 1 and
        (.after.A.custody_counts.lan.state_snapshot_end - .before.A.custody_counts.lan.state_snapshot_end) >= 1
      ' "$file" >/dev/null || die "$name observed snapshot/custody deltas are incomplete"
      ;;
    lan-relay-fallback-return)
      require_event "$file" "predicate:A.route.relay"
      require_event "$file" "predicate:B.route.relay"
      require_event "$file" "predicate:A.custody.relay:notif_post:1"
      require_event "$file" "predicate:A.custody.lan:notif_post:1"
      require_event "$file" "predicate:A.peer-receipt.delta:2"
      require_event "$file" "predicate:direct.terminal"
      jq -e '
        ([.events[] | select(startswith("route:post:A:n-lan-relay-fallback-return-relay:relay:g"))] | length) == 1 and
        ([.events[] | select(startswith("route:post:A:n-lan-relay-fallback-return-lan:lan:g"))] | length) == 1 and
        ([.events[] | select(startswith("route:post:A:n-lan-relay-fallback-return-relay:relay:g"))][0] as $relay |
          [.events[] | select(startswith("route:post:A:n-lan-relay-fallback-return-lan:lan:g"))][0] as $lan |
          (.events | index($relay)) < (.events | index($lan))) and
        (.after.A.custody_counts.relay.notif_post - .before.A.custody_counts.relay.notif_post) >= 1 and
        (.after.A.custody_counts.lan.notif_post - .before.A.custody_counts.lan.notif_post) >= 1 and
        (.after.A.peer_receipt_count - .before.A.peer_receipt_count) >= 2 and
        (.route_transitions | length == 4) and
        ([.route_transitions[].route] == ["relay","lan","relay","lan"]) and
        ([range(1; .route_transitions | length) as $i | .route_transitions[$i].route_generation > .route_transitions[$i-1].route_generation] | all) and
        ([.route_transitions[] | select(.route == "lan")] | all(.[]; .peer_evidence == "direct")) and
        ([.route_transitions[] | select(.route == "relay")] | all(.[]; .peer_evidence != "direct")) and
        ([.after.A,.after.B] | all(.[]; .route == "lan" and .route_phase == "authenticated" and .terminal == true))
      ' "$file" >/dev/null || die "$name ordered relay-return evidence is incomplete"
      ;;
    lan-restart-persistence)
      for event in \
        'predicate:A.outbox.nonzero' \
        'force-stop:A' 'restart:A' \
        'force-stop:B' 'restart:B' \
        'predicate:A.custody.lan:notif_post:2' \
        'predicate:A.peer-receipt.delta:2' \
        'predicate:direct.terminal'; do require_event "$file" "$event"; done
      jq -e '
        (.events | index("predicate:A.outbox.nonzero")) < (.events | index("force-stop:A")) and
        (.events | index("force-stop:A")) < (.events | index("restart:A")) and
        (.events | index("restart:A")) < (.events | index("force-stop:B")) and
        (.events | index("force-stop:B")) < (.events | index("restart:B")) and
        ([.events[] | select(startswith("route:post:A:n-lan-restart-persistence-before-a-restart:lan:g"))] | length) == 1 and
        ([.events[] | select(startswith("route:post:A:n-lan-restart-persistence-after-b-restart:lan:g"))] | length) == 1 and
        ([.events[] | select(. == "predicate:A.route.lan")] | length) >= 3 and
        ([.events[] | select(. == "predicate:B.route.lan")] | length) >= 3 and
        (.after.A.custody_counts.lan.notif_post - .before.A.custody_counts.lan.notif_post) >= 2 and
        (.after.A.peer_receipt_count - .before.A.peer_receipt_count) >= 2 and
        ([.after.A,.after.B] | all(.[]; .route == "lan" and .route_phase == "authenticated" and .terminal == true))
      ' "$file" >/dev/null || die "$name restart ordering/persistence evidence is incomplete"
      ;;
    lan-direct-burst-backpressure)
      local burst_event burst_count post_count unique_posts
      burst_event=$(jq -r '[.events[] | select(startswith("predicate:B.burst.unique:"))] | if length == 1 then .[0] else empty end' "$file")
      [[ "$burst_event" =~ ^predicate:B\.burst\.unique:([0-9]+)$ ]] || die "burst unique result assertion missing"
      burst_count=${BASH_REMATCH[1]}
      (( burst_count >= 2 && burst_count <= 1000 )) || die "burst count is outside 2..1000"
      post_count=$(jq '[.events[] | select(startswith("post:A:burst-"))] | length' "$file")
      unique_posts=$(jq '[.events[] | select(startswith("post:A:burst-"))] | unique | length' "$file")
      [[ "$post_count" == "$burst_count" && "$unique_posts" == "$burst_count" ]] || die "burst terminal outcomes are incomplete or duplicated"
      require_event "$file" "predicate:A.custody.lan:notif_post:$burst_count"
      require_event "$file" "predicate:A.peer-receipt.delta:$burst_count"
      require_event "$file" "predicate:A.queue.peak-bounded"
      jq -e --argjson count "$burst_count" '(.after.A.custody_counts.lan.notif_post - .before.A.custody_counts.lan.notif_post) >= $count and (.after.A.peer_receipt_count - .before.A.peer_receipt_count) >= $count and .after.A.peak_queue_count > 0 and .after.A.peak_queue_count <= 2000 and .after.A.peak_queue_bytes > 0 and .after.A.peak_queue_bytes <= 134217728 and .after.A.active_queue_count == 0 and .after.A.active_queue_bytes == 0 and .after.A.outbox == 0 and .after.B.outbox == 0' "$file" >/dev/null || die "burst observed custody/receipt/queue bounds or terminal zero are absent"
      ;;
    lan-direct-unpair-during-traffic)
      local producer_event producer_count
      producer_event=$(jq -r '[.events[] | select(startswith("burst-start:A:"))] | if length == 1 then .[0] else empty end' "$file")
      [[ "$producer_event" =~ ^burst-start:A:([0-9]+)$ ]] || die "unpair bounded producer evidence is missing"
      producer_count=${BASH_REMATCH[1]}
      (( producer_count >= 2 && producer_count <= 1000 )) || die "unpair producer count is outside 2..1000"
      require_event "$file" "predicate:A.active-queue.nonzero"
      require_event "$file" "control:A:local-unpair"
      require_event "$file" "predicate:A.unpair.custody"
      require_event "$file" "predicate:B.unpair.inbound.delta:1"
      require_event "$file" "predicate:both.unpaired.stable"
      jq -e '
        (.events | index("predicate:A.active-queue.nonzero")) < (.events | index("control:A:local-unpair")) and
        (.events | index("control:A:local-unpair")) < (.events | index("predicate:A.unpair.custody")) and
        (.events | index("predicate:A.unpair.custody")) < (.events | index("predicate:B.unpair.inbound.delta:1")) and
        (.events | index("predicate:B.unpair.inbound.delta:1")) < (.events | index("predicate:both.unpaired.stable")) and
        ([.after.A,.after.B] | all(.[]; .paired == false and .health == "stopped" and .outbox == 0 and .active_inbound == 0 and .pending_materialization == 0 and .active_queue_count == 0 and .active_queue_bytes == 0)) and
        .after.A.unpair_outcome == "lan" and .after.B.unpair_inbound_count >= 1
      ' "$file" >/dev/null || die "unpair custody/order/stable wipe evidence is incomplete"
      ;;
  esac
}

verify() {
  local evidence_dir=$1 root_file="$1/scenario-result.json" children_dir="$1/children"
  command -v jq >/dev/null 2>&1 || die "jq is required"
  [[ -d "$evidence_dir" && ! -L "$evidence_dir" && "$(mode_of "$evidence_dir")" == 700 ]] || die "evidence directory missing or unsafe"
  verify_artifact_set "$evidence_dir" lan-product-correctness passed
  local entry leaf
  for entry in "$evidence_dir"/*; do
    [[ -e "$entry" || -L "$entry" ]] || continue
    leaf=$(basename "$entry")
    case "$leaf" in
      scenario-result.json|state.json|timeline.json|metrics.json|children) ;;
      *) die "aggregate contains unexpected artifact $leaf" ;;
    esac
  done
  verify_common_result lan-product-correctness "$root_file"
  [[ -d "$children_dir" && ! -L "$children_dir" && "$(mode_of "$children_dir")" == 700 ]] || die "children evidence directory missing or unsafe"
  local count
  count=$(find "$children_dir" -mindepth 1 -maxdepth 1 -print | wc -l | tr -d ' ')
  [[ "$count" == "${#required_children[@]}" ]] || die "completed child count is incomplete"
  local index name child_dir
  for index in "${!required_children[@]}"; do
    name=${required_children[$index]}
    printf -v child_dir '%s/%02d-%s' "$children_dir" "$((index + 1))" "$name"
    verify_child "$name" "$child_dir"
  done
  echo "direct LAN product evidence passed: $evidence_dir"
}

check_doc_status() {
  (($# >= 1)) || die "--check-doc-status requires at least one document"
  local repo_root doc line commit ref link link_path target targets checked_count scenario
  repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
  for doc in "$@"; do
    [[ -f "$doc" && ! -L "$doc" ]] || die "documentation input missing or unsafe: $doc"
    [[ $(wc -c <"$doc") -le 1048576 ]] || die "documentation input exceeds size bound: $doc"
    grep -Fqi 'pending physical two-phone run' "$doc" || die "missing pending physical two-phone run status: $doc"
    grep -Eqi 'eleven([ -]child|[ -]scenario|[ -]ordered)|eleven children|eleven scenarios' "$doc" || die "missing eleven-child aggregate status: $doc"
  done

  if grep -Ein 'runs? (the )?eight|eight[- ]child|eight scenarios' "$@" >/dev/null; then
    die "stale eight-child aggregate claim remains in documentation"
  fi

  if grep -Ein "Unpair doesn't notify peer|doesn't push an explicit unpair|LAN transport does not|Phase 3 doesn't|No LAN transport" "$@" >/dev/null; then
    die "stale live-state claim remains in documentation"
  fi

  checked_count=$(grep -Eh '^[[:space:]]*- \[[xX]\]' "$@" | wc -l | tr -d ' ' || true)
  (( checked_count >= 1 )) || die "no checked implementation status is documented"
  while IFS= read -r line; do
    [[ "$line" =~ commit[[:space:]]+\`([0-9a-f]{7,40})\` ]] || die "checked task lacks a commit citation: $line"
    commit=${BASH_REMATCH[1]}
    git -C "$repo_root" cat-file -e "$commit^{commit}" 2>/dev/null || die "checked task cites unreachable commit $commit"
    git -C "$repo_root" merge-base --is-ancestor "$commit" HEAD || die "checked task cites non-ancestor commit $commit"
  done < <(grep -Eh '^[[:space:]]*- \[[xX]\]' "$@" || true)

  if grep -Ein '^[[:space:]]*- \[[xX]\].*(physical|two-phone|handset|operator|no-uplink|packet|dns|connected device)' "$@" >/dev/null; then
    die "physical acceptance is checked without audited evidence"
  fi
  while IFS= read -r line; do
    grep -Fqi 'pending physical two-phone run' <<<"$line" || die "physical acceptance lacks explicit pending status: $line"
  done < <(grep -Ehi '^[[:space:]]*- \[ \].*(physical|two-phone|handset|operator|no-uplink|packet|dns|connected device)' "$@" || true)

  while IFS= read -r ref; do
    ref=${ref#\`}; ref=${ref%\`}
    if [[ "$ref" == *'*'* || "$ref" == *'?'* || "$ref" == *'['* ]]; then
      compgen -G "$repo_root/$ref" >/dev/null || die "documented repository path does not resolve: $ref"
    else
      [[ -e "$repo_root/$ref" ]] || die "documented repository path does not exist: $ref"
    fi
  done < <(grep -Eho '`(scripts|e2e|docs|mobile)/[^`[:space:]]+`' "$@" | sort -u || true)

  for doc in "$@"; do
    while IFS= read -r link; do
      link=${link#']('}; link=${link%')'}
      case "$link" in
        http://*|https://*|mailto:*|'#'*) continue ;;
      esac
      link_path=${link%%#*}
      [[ -n "$link_path" ]] || continue
      [[ -e "$(dirname "$doc")/$link_path" ]] || die "documented Markdown link does not resolve: $link"
    done < <(grep -Eo '\]\([^)]+\)' "$doc" || true)
  done

  targets=$(make -C "$repo_root" -qp 2>/dev/null | awk -F: '/^[A-Za-z0-9_.-]+:([^=]|$)/ {print $1}' | sort -u || true)
  while IFS= read -r target; do
    target=${target#make }
    grep -Fxq "$target" <<<"$targets" || die "documented make target does not exist: $target"
  done < <(grep -Eho 'make[[:space:]]+[A-Za-z0-9_.-]+' "$@" | sort -u || true)

  grep -Fq 'make e2e-lan-product' "$@" || die "aggregate Make invocation is undocumented"
  grep -Fq 'scripts/verify-lan-product-evidence.sh' "$@" || die "LAN evidence verifier path is undocumented"
  grep -Fq 'e2e/cmd/twinotify-e2e' "$@" || die "two-device CLI path is undocumented"
  for scenario in "${required_children[@]}" lan-product-correctness; do
    grep -Fq "\`$scenario\`" "$@" || die "required direct-LAN scenario is undocumented: $scenario"
  done
  while IFS= read -r scenario; do
    scenario=${scenario#\`}; scenario=${scenario%\`}
    case " ${required_children[*]} lan-product-correctness " in
      *" $scenario "*) ;;
      *) die "unknown direct-LAN scenario is documented: $scenario" ;;
    esac
  done < <(grep -Eho '`lan-[a-z0-9-]+`' "$@" | sort -u || true)
  echo "direct LAN documentation status passed"
}

write_derived_fixture() {
  local dir=$1
  jq '{scenario,status,before,after}' "$dir/scenario-result.json" >"$dir/state.json"
  jq '{scenario,status,events}' "$dir/scenario-result.json" >"$dir/timeline.json"
  jq '{scenario,status,converged:{A:true,B:true},after}' "$dir/scenario-result.json" >"$dir/metrics.json"
  chmod 600 "$dir/state.json" "$dir/timeline.json" "$dir/metrics.json"
}

write_fixture() {
  local base=$1
  rm -rf -- "$base"
  mkdir -m 700 -p "$base/children"
  chmod 700 "$base" "$base/children"
  local base_obs route transitions index name dir events after_a after_b
  base_obs='{"health":"connected","call_capture_enabled":false,"outbox":0,"active_inbound":0,"pending_materialization":0,"mirror":false,"sequence":0,"terminal":true,"loop_events":0,"route":"lan","route_phase":"authenticated","queued_bytes":0,"route_generation":1,"peer_evidence":"direct","pending_local_count":0,"awaiting_peer_count":0,"held_by_relay_count":0,"delivery_reason":"none","user_content_kind":"notifications","paired":true,"custody_counts":{"lan":{"notif_post":0,"notif_update":0,"notif_cancel":0,"call_state":0,"state_digest":0,"state_snapshot_begin":0,"state_snapshot_item":0,"state_snapshot_end":0,"unpair":0,"peer_receipt":0},"relay":{"notif_post":0,"notif_update":0,"notif_cancel":0,"call_state":0,"state_digest":0,"state_snapshot_begin":0,"state_snapshot_item":0,"state_snapshot_end":0,"unpair":0,"peer_receipt":0}},"peer_receipt_count":0,"snapshot_digest_count":0,"snapshot_begin_count":0,"snapshot_end_count":0,"snapshot_commit_count":0,"user_dismiss_count":0,"unpair_inbound_count":0,"active_queue_count":0,"active_queue_bytes":0,"peak_queue_count":0,"peak_queue_bytes":0}'
  route='{"route":"lan","phase":"authenticated","route_generation":1,"queued_count":0,"queued_bytes":0,"peer_evidence":"direct","pending_local_count":0,"awaiting_peer_count":0,"held_by_relay_count":0,"delivery_reason":"none","user_content_kind":"notifications"}'
  jq -cn --argjson observation "$base_obs" --argjson route "$route" '{scenario:"lan-product-correctness",status:"passed",events:[],before:{A:$observation,B:$observation},after:{A:$observation,B:$observation},route:$route}' >"$base/scenario-result.json"
  chmod 600 "$base/scenario-result.json"
  write_derived_fixture "$base"
  for index in "${!required_children[@]}"; do
    name=${required_children[$index]}
    printf -v dir '%s/children/%02d-%s' "$base" "$((index + 1))" "$name"
    mkdir -m 700 "$dir"
    after_a=$base_obs; after_b=$base_obs
    case "$name" in
      lan-direct-delivery)
        events='["predicate:A.route.lan","predicate:B.route.lan","post:A:n-lan-direct-delivery","predicate:terminal.converged"]'
        after_a=$(jq -cn --argjson v "$base_obs" '$v | .custody_counts.lan.notif_post=1 | .peer_receipt_count=1') ;;
      lan-direct-reverse-delivery)
        events='["predicate:A.route.lan","predicate:B.route.lan","post:B:n-lan-direct-reverse-delivery","route:post:B:n-lan-direct-reverse-delivery:lan:g1","predicate:A.tracked.sequence:1","predicate:B.custody.lan:notif_post:1","predicate:B.peer-receipt.delta:1","predicate:direct.terminal"]'
        after_b=$(jq -cn --argjson v "$base_obs" '$v | .custody_counts.lan.notif_post=1 | .peer_receipt_count=1') ;;
      lan-direct-dismiss)
        events='["predicate:A.route.lan","predicate:B.route.lan","predicate:B.mirror.absent:n-lan-direct-dismiss","predicate:terminal.converged"]'
        after_a=$(jq -cn --argjson v "$base_obs" '$v | .custody_counts.lan.notif_post=1 | .custody_counts.lan.notif_cancel=1 | .peer_receipt_count=2') ;;
      lan-direct-update)
        events='["predicate:A.route.lan","predicate:B.route.lan","predicate:B.tracked.sequence:3","predicate:A.custody.lan:notif_update:2","predicate:A.peer-receipt.delta:3","predicate:direct.terminal"]'
        after_a=$(jq -cn --argjson v "$base_obs" '$v | .custody_counts.lan.notif_post=1 | .custody_counts.lan.notif_update=2 | .peer_receipt_count=3') ;;
      lan-direct-peer-dismiss)
        events='["predicate:A.route.lan","predicate:B.route.lan","predicate:B.user-dismiss.delta:1","predicate:B.tracked.no-resurrection","predicate:B.custody.lan:notif_cancel:1","predicate:B.peer-receipt.delta:1","predicate:direct.terminal"]'
        after_b=$(jq -cn --argjson v "$base_obs" '$v | .custody_counts.lan.notif_cancel=1 | .peer_receipt_count=1 | .user_dismiss_count=1') ;;
      lan-direct-call-state)
        events='["predicate:A.route.lan","predicate:B.route.lan","predicate:B.call.semantic:RINGING","predicate:B.call.semantic:ACTIVE","predicate:B.call.semantic:IDLE","predicate:A.custody.lan:call_state:3","predicate:A.peer-receipt.delta:3","predicate:direct.terminal"]'
        after_a=$(jq -cn --argjson v "$base_obs" '$v | .custody_counts.lan.call_state=3 | .peer_receipt_count=3') ;;
      lan-direct-snapshot-receipt)
        events='["predicate:A.route.lan","predicate:B.route.lan","predicate:B.snapshot.digest.delta:1","predicate:B.snapshot.begin.delta:1","predicate:B.snapshot.end.delta:1","predicate:B.snapshot.commit.delta:1","predicate:A.custody.lan:state_digest:1","predicate:A.custody.lan:state_snapshot_begin:1","predicate:A.custody.lan:state_snapshot_item:1","predicate:A.custody.lan:state_snapshot_end:1","predicate:direct.terminal"]'
        after_a=$(jq -cn --argjson v "$base_obs" '$v | .custody_counts.lan.state_digest=1 | .custody_counts.lan.state_snapshot_begin=1 | .custody_counts.lan.state_snapshot_item=1 | .custody_counts.lan.state_snapshot_end=1')
        after_b=$(jq -cn --argjson v "$base_obs" '$v | .snapshot_digest_count=1 | .snapshot_begin_count=1 | .snapshot_end_count=1 | .snapshot_commit_count=1') ;;
      lan-relay-fallback-return)
        events='["lan-available:A:false","lan-available:B:false","predicate:A.route.relay","predicate:B.route.relay","lan-available:A:true","lan-available:B:true","predicate:A.route.lan","predicate:B.route.lan","lan-available:A:false","lan-available:B:false","predicate:A.route.relay","predicate:B.route.relay","post:A:n-lan-relay-fallback-return-relay","route:post:A:n-lan-relay-fallback-return-relay:relay:g4","predicate:B.tracked.sequence:1","predicate:A.custody.relay:notif_post:1","predicate:A.peer-receipt.delta:1","lan-available:A:true","lan-available:B:true","predicate:A.route.lan","predicate:B.route.lan","post:A:n-lan-relay-fallback-return-lan","route:post:A:n-lan-relay-fallback-return-lan:lan:g5","predicate:B.tracked.sequence:1","predicate:A.custody.lan:notif_post:1","predicate:A.peer-receipt.delta:2","predicate:direct.terminal"]'
        after_a=$(jq -cn --argjson v "$base_obs" '$v | .custody_counts.relay.notif_post=1 | .custody_counts.lan.notif_post=1 | .peer_receipt_count=2') ;;
      lan-restart-persistence)
        events='["predicate:A.route.lan","predicate:B.route.lan","post:A:n-lan-restart-persistence-before-a-restart","route:post:A:n-lan-restart-persistence-before-a-restart:lan:g1","predicate:A.outbox.nonzero","force-stop:A","restart:A","predicate:A.route.lan","predicate:B.route.lan","predicate:B.tracked.sequence:1","predicate:A.custody.lan:notif_post:1","predicate:A.peer-receipt.delta:1","force-stop:B","restart:B","predicate:A.route.lan","predicate:B.route.lan","post:A:n-lan-restart-persistence-after-b-restart","route:post:A:n-lan-restart-persistence-after-b-restart:lan:g2","predicate:B.tracked.sequence:1","predicate:A.custody.lan:notif_post:2","predicate:A.peer-receipt.delta:2","predicate:direct.terminal"]'
        after_a=$(jq -cn --argjson v "$base_obs" '$v | .custody_counts.lan.notif_post=2 | .peer_receipt_count=2') ;;
      lan-direct-burst-backpressure)
        events=$(jq -cn '["predicate:A.route.lan","predicate:B.route.lan"] + [range(1;9) | "post:A:burst-" + (tostring)] + ["predicate:B.burst.unique:8","predicate:A.custody.lan:notif_post:8","predicate:A.peer-receipt.delta:8","predicate:A.queue.peak-bounded","predicate:direct.terminal"]')
        after_a=$(jq -cn --argjson v "$base_obs" '$v + {peak_queue_count:8,peak_queue_bytes:2048,peer_receipt_count:8} | .custody_counts.lan.notif_post=8')
        ;;
      lan-direct-unpair-during-traffic)
        events='["predicate:A.route.lan","predicate:B.route.lan","burst-start:A:8","predicate:A.active-queue.nonzero","control:A:local-unpair","predicate:A.unpair.custody","predicate:B.unpair.inbound.delta:1","predicate:both.unpaired.stable"]'
        after_a=$(jq -cn --argjson v "$base_obs" '$v + {health:"stopped",terminal:false,paired:false,unpair_outcome:"lan"}')
        after_b=$(jq -cn --argjson v "$base_obs" '$v + {health:"stopped",terminal:false,paired:false,unpair_inbound_count:1}')
        ;;
    esac
    if [[ "$name" == lan-relay-fallback-return ]]; then
      transitions='[{"route":"relay","phase":"authenticated","route_generation":2,"queued_count":0,"queued_bytes":0,"peer_evidence":"recent","pending_local_count":0,"awaiting_peer_count":0,"held_by_relay_count":0,"delivery_reason":"none","user_content_kind":"notifications"},{"route":"lan","phase":"authenticated","route_generation":3,"queued_count":0,"queued_bytes":0,"peer_evidence":"direct","pending_local_count":0,"awaiting_peer_count":0,"held_by_relay_count":0,"delivery_reason":"none","user_content_kind":"notifications"},{"route":"relay","phase":"authenticated","route_generation":4,"queued_count":0,"queued_bytes":0,"peer_evidence":"recent","pending_local_count":0,"awaiting_peer_count":0,"held_by_relay_count":0,"delivery_reason":"none","user_content_kind":"notifications"},{"route":"lan","phase":"authenticated","route_generation":5,"queued_count":0,"queued_bytes":0,"peer_evidence":"direct","pending_local_count":0,"awaiting_peer_count":0,"held_by_relay_count":0,"delivery_reason":"none","user_content_kind":"notifications"}]'
      jq -cn --arg name "$name" --argjson events "$events" --argjson before "$base_obs" --argjson afterA "$after_a" --argjson afterB "$after_b" --argjson route "$route" --argjson transitions "$transitions" \
        '{scenario:$name,status:"passed",events:$events,before:{A:$before,B:$before},after:{A:$afterA,B:$afterB},route:$route,route_transitions:$transitions}' >"$dir/scenario-result.json"
    else
      jq -cn --arg name "$name" --argjson events "$events" --argjson before "$base_obs" --argjson afterA "$after_a" --argjson afterB "$after_b" --argjson route "$route" \
        '{scenario:$name,status:"passed",events:$events,before:{A:$before,B:$before},after:{A:$afterA,B:$afterB},route:$route}' >"$dir/scenario-result.json"
    fi
    chmod 600 "$dir/scenario-result.json"
    write_derived_fixture "$dir"
  done
}

self_test() {
  local tmp base

  [[ ${#required_children[@]} == 11 ]] || die "self-test requires the complete eleven-child contract"
  tmp=$(mktemp -d "${TMPDIR:-/tmp}/twinotify-lan-product-evidence.XXXXXX")
  trap 'rm -rf -- "$tmp"' EXIT
  base="$tmp/evidence"
  if "$0" "$base" >/dev/null 2>"$tmp/missing.err"; then die "self-test expected missing evidence failure"; fi
  write_fixture "$base"
  verify "$base" >/dev/null
  rm -rf -- "$base/children/05-lan-direct-peer-dismiss"
  if "$0" "$base" >/dev/null 2>"$tmp/child.err"; then die "self-test expected missing child failure"; fi
  write_fixture "$base"
  jq '.status="failed" | .error_code="fixture_failure"' "$base/children/04-lan-direct-update/scenario-result.json" >"$tmp/failed.json"
  mv "$tmp/failed.json" "$base/children/04-lan-direct-update/scenario-result.json"; chmod 600 "$base/children/04-lan-direct-update/scenario-result.json"
  if "$0" "$base" >/dev/null 2>"$tmp/failed.err"; then die "self-test expected failed child failure"; fi
  write_fixture "$base"
  jq 'del(.after.A.peak_queue_count)' "$base/children/10-lan-direct-burst-backpressure/scenario-result.json" >"$tmp/missing-observation.json"
  mv "$tmp/missing-observation.json" "$base/children/10-lan-direct-burst-backpressure/scenario-result.json"; chmod 600 "$base/children/10-lan-direct-burst-backpressure/scenario-result.json"
  if "$0" "$base" >/dev/null 2>"$tmp/observation.err"; then die "self-test expected missing observation failure"; fi
  write_fixture "$base"
  verify "$base" >/dev/null
  printf '%s\n' '{"scenario":"lan-product-correctness","status":"passed","converged":{},"after":{},"unexpected_secret":"private-value"}' >"$base/metrics.json"
  chmod 600 "$base/metrics.json"
  if "$0" "$base" >/dev/null 2>"$tmp/retained-secret.err"; then die "self-test expected retained secret artifact failure"; fi
  write_fixture "$base"
  jq 'del(.after.A.custody_counts)' "$base/children/04-lan-direct-update/scenario-result.json" >"$tmp/missing-custody.json"
  mv "$tmp/missing-custody.json" "$base/children/04-lan-direct-update/scenario-result.json"; chmod 600 "$base/children/04-lan-direct-update/scenario-result.json"
  if "$0" "$base" >/dev/null 2>"$tmp/missing-custody.err"; then die "self-test expected missing custody evidence failure"; fi
  write_fixture "$base"
  jq '.events |= map(select(startswith("route:post:B:n-lan-direct-reverse-delivery:lan:g") | not))' "$base/children/02-lan-direct-reverse-delivery/scenario-result.json" >"$tmp/reverse-route.json"
  mv "$tmp/reverse-route.json" "$base/children/02-lan-direct-reverse-delivery/scenario-result.json"; chmod 600 "$base/children/02-lan-direct-reverse-delivery/scenario-result.json"
  write_derived_fixture "$base/children/02-lan-direct-reverse-delivery"
  if "$0" "$base" >/dev/null 2>"$tmp/reverse-route.err"; then die "self-test expected hollow reverse evidence failure"; fi
  write_fixture "$base"
  jq '.events |= map(select(. != "predicate:A.custody.relay:notif_post:1"))' "$base/children/08-lan-relay-fallback-return/scenario-result.json" >"$tmp/fallback-custody.json"
  mv "$tmp/fallback-custody.json" "$base/children/08-lan-relay-fallback-return/scenario-result.json"; chmod 600 "$base/children/08-lan-relay-fallback-return/scenario-result.json"
  write_derived_fixture "$base/children/08-lan-relay-fallback-return"
  if "$0" "$base" >/dev/null 2>"$tmp/fallback-custody.err"; then die "self-test expected hollow fallback evidence failure"; fi
  write_fixture "$base"
  jq '.route_transitions[2].route_generation=.route_transitions[1].route_generation' "$base/children/08-lan-relay-fallback-return/scenario-result.json" >"$tmp/fallback-generation.json"
  mv "$tmp/fallback-generation.json" "$base/children/08-lan-relay-fallback-return/scenario-result.json"; chmod 600 "$base/children/08-lan-relay-fallback-return/scenario-result.json"
  if "$0" "$base" >/dev/null 2>"$tmp/fallback-generation.err"; then die "self-test expected non-increasing fallback generation failure"; fi
  write_fixture "$base"
  jq '.events |= map(select(. != "restart:B"))' "$base/children/09-lan-restart-persistence/scenario-result.json" >"$tmp/restart-order.json"
  mv "$tmp/restart-order.json" "$base/children/09-lan-restart-persistence/scenario-result.json"; chmod 600 "$base/children/09-lan-restart-persistence/scenario-result.json"
  write_derived_fixture "$base/children/09-lan-restart-persistence"
  if "$0" "$base" >/dev/null 2>"$tmp/restart-order.err"; then die "self-test expected hollow restart evidence failure"; fi
  write_fixture "$base"
  jq '.before.A.device_id="raw-device-id"' "$base/scenario-result.json" >"$tmp/root-unknown-observation.json"
  mv "$tmp/root-unknown-observation.json" "$base/scenario-result.json"; chmod 600 "$base/scenario-result.json"
  write_derived_fixture "$base"
  if "$0" "$base" >/dev/null 2>"$tmp/root-unknown-observation.err"; then die "self-test expected aggregate unknown observation failure"; fi
  write_fixture "$base"
  verify "$base" >/dev/null
  echo "direct LAN product evidence self-test passed"
  trap - EXIT
  rm -rf -- "$tmp"
}

if [[ ${1:-} == --self-test && $# -eq 1 ]]; then self_test; exit 0; fi
if [[ ${1:-} == --check-doc-status ]]; then shift; check_doc_status "$@"; exit 0; fi
[[ $# -eq 1 ]] || { echo "usage: $0 <evidence-dir> | --self-test | --check-doc-status <docs...>" >&2; exit 2; }
verify "$1"
