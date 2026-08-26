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
export type DeliveryRoute = 'lan' | 'relay' | 'none';

export type DeliveryRoutePhase =
  | 'idle'
  | 'connecting'
  | 'authenticated'
  | 'reconnecting';

/**
 * The complete public description of delivery. It deliberately carries no
 * endpoint, address, SSID, port, or peer identifier, so rendering it cannot
 * leak private network detail.
 */
export interface RouteStatus {
  route: DeliveryRoute;
  phase: DeliveryRoutePhase;
  queued_count: number;
  route_generation?: number;
}

export type RouteStatusEvent = (status: RouteStatus) => void;

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
