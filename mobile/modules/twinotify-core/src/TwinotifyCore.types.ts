import type { StyleProp, ViewStyle } from 'react-native';

export type OnLoadEventPayload = {
  url: string;
};

export type TwinotifyCoreModuleEvents = {
  onChange: (params: ChangeEventPayload) => void;
};

export type ChangeEventPayload = {
  value: string;
};

/** Which route is carrying delivery. `none` means no route is authenticated. */
export type DeliveryRoute = 'lan' | 'bluetooth' | 'relay' | 'none';

export type DeliveryRoutePhase =
  | 'idle'
  | 'connecting'
  | 'authenticated'
  | 'reconnecting';

export type PeerEvidence = 'direct' | 'recent' | 'stale' | 'unknown';

export type DeliveryReason =
  | 'none'
  | 'no_route'
  | 'waiting_for_peer'
  | 'relay_holding'
  | 'lan_bootstrap_waiting'
  | 'lan_binding_conflict'
  | 'peer_version_incompatible';

export type UserContentKind = 'notifications' | 'sync_updates';

export type RecoveryIssue =
  | 'notification_access_required'
  | 'post_notifications_required'
  | 'background_start_denied';

/**
 * The complete public description of delivery. It deliberately carries no
 * endpoint, address, SSID, port, or peer identifier, so rendering it cannot
 * leak private network detail.
 */
export interface NativeDeliveryPresentation {
  state: 'direct' | 'relay' | 'reconnecting' | 'queued' | 'paused' | 'stopped' | 'unpaired';
  label: string;
  explanation: string;
  action: 'retry' | 'pair' | 'permissions' | null;
  queued_count: number;
  peer_line: 'Reachable now' | 'Checked in recently' | 'Not confirmed online' | null;
}

export interface RouteStatus {
  route: DeliveryRoute;
  phase: DeliveryRoutePhase;
  queued_count: number;
  pending_local_count: number;
  awaiting_peer_count: number;
  held_by_relay_count: number;
  peer_evidence: PeerEvidence;
  delivery_reason: DeliveryReason;
  user_content_kind: UserContentKind;
  route_generation: number;
  recovery_issue?: RecoveryIssue | null;
  presentation?: NativeDeliveryPresentation;
}

export type RouteStatusEvent = (status: RouteStatus) => void;

export type NotificationDetailState = 'ACTIVE' | 'CANCELLED' | 'GONE';

export type NotificationActionInvocationState =
  | 'PENDING'
  | 'DISPATCHED'
  | 'OUTCOME_UNKNOWN'
  | 'FAILED'
  | 'ACTION_GONE'
  | 'NOTIFICATION_GONE'
  | 'EXPIRED';

export interface NotificationDetailAction {
  actionId: string;
  title: string;
  semantic: number;
  reply: boolean;
  replyLabel: string | null;
  invocationId: string | null;
  invocationState: NotificationActionInvocationState | null;
}

export interface NotificationDetail {
  detailId: string;
  sourceAppName: string | null;
  sourcePackage: string;
  sourceAppIconDataUri: string | null;
  originDeviceLabel: string;
  title: string | null;
  text: string | null;
  subText: string | null;
  bigText: string | null;
  smallIconDataUri: string | null;
  largeIconDataUri: string | null;
  receivedAt: number;
  updatedAt: number;
  state: NotificationDetailState;
  isAutoCancel: boolean;
  actions: NotificationDetailAction[];
}

export type MirrorActionInvocationStatus =
  | 'queued'
  | 'locked'
  | 'gone'
  | 'invalid_reply'
  | 'failed';

export interface MirrorActionInvocationResult {
  status: MirrorActionInvocationStatus;
  invocationId: string | null;
}

export type OfflinePairingRole = 'initiator' | 'joiner';

export type OfflinePairingPhase =
  | 'idle'
  | 'advertising'
  | 'resolving'
  | 'tls_authenticated'
  | 'verify_code'
  | 'local_confirmed'
  | 'mutually_signed'
  | 'committed'
  | 'complete';

export type OfflinePairingErrorCode =
  | 'pair_session_active'
  | 'pair_session_not_found'
  | 'pair_session_mismatch'
  | 'pair_invalid_display_name'
  | 'pair_invalid_qr'
  | 'pair_runtime_unavailable'
  | 'expired'
  | 'tls_pin_mismatch'
  | 'identity_mismatch'
  | 'invalid_frame'
  | 'commit_failed'
  | 'cancelled'
  | 'peer_rejected'
  | 'wifi_permission_denied'
  | 'wifi_unavailable';

/** Secret-free native status. Raw QR/session material never belongs here. */
export interface OfflinePairingStatus {
  role: OfflinePairingRole | null;
  phase: OfflinePairingPhase;
  sessionId: string | null;
  errorCode: OfflinePairingErrorCode | null;
  peerDisplayName: string | null;
  sas: string | null;
  completed: boolean;
}

export type OfflinePairingStatusEvent = (status: OfflinePairingStatus) => void;

export type TwinotifyCoreViewProps = {
  url: string;
  onLoad: (event: { nativeEvent: OnLoadEventPayload }) => void;
  style?: StyleProp<ViewStyle>;
};
