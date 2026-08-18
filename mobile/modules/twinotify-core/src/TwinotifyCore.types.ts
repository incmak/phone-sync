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
  | 'identity_mismatch'
  | 'invalid_frame'
  | 'commit_failed'
  | 'cancelled';

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
