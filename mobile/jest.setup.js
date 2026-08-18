/* global jest */

const mockReact = require('react');
const { View: mockView } = require('react-native');

const mockStorage = new Map();

jest.mock('@react-native-async-storage/async-storage', () => ({
  getItem: jest.fn(async (key) => mockStorage.get(key) ?? null),
  setItem: jest.fn(async (key, value) => { mockStorage.set(key, value); }),
  multiRemove: jest.fn(async (keys) => { keys.forEach((key) => mockStorage.delete(key)); }),
  clear: jest.fn(async () => { mockStorage.clear(); }),
}));

jest.mock('./components/Theme', () => {
  const { twTheme } = jest.requireActual('./components/tokens');
  return {
    ThemeProvider: ({ children }) => children,
    useTheme: () => twTheme({ hue: 180, dark: false }),
    useThemeControls: () => ({
      theme: twTheme({ hue: 180, dark: false }),
      hue: 'mint',
      setHue: jest.fn(),
      darkOverride: 'light',
      setDarkOverride: jest.fn(),
    }),
  };
});

const mockRouter = {
  push: jest.fn(),
  replace: jest.fn(),
  back: jest.fn(),
};

let mockLocalSearchParams = {};

jest.mock('expo-router', () => ({
  router: mockRouter,
  Stack: ({ children }) => mockReact.createElement(mockView, null, children),
  useLocalSearchParams: () => mockLocalSearchParams,
  useNavigation: () => ({ addListener: jest.fn(() => jest.fn()) }),
}));

let offlineStatusListener = null;
const idleStatus = {
  role: null,
  phase: 'idle',
  sessionId: null,
  errorCode: null,
  peerDisplayName: null,
  sas: null,
  completed: false,
};

const mockTwinotifyCore = {
  getDeviceDisplayName: jest.fn(async () => 'Android phone'),
  startOfflinePairing: jest.fn(),
  joinOfflinePairing: jest.fn(),
  confirmOfflinePairing: jest.fn(),
  cancelOfflinePairing: jest.fn(),
  getOfflinePairingStatus: jest.fn(async () => idleStatus),
  addListener: jest.fn((event, listener) => {
    if (event === 'onOfflinePairingStatus') offlineStatusListener = listener;
    return { remove: jest.fn() };
  }),
  getPairStatus: jest.fn(async () => ({ paired: false })),
  startPairInitiator: jest.fn(),
  awaitPeerHello: jest.fn(),
  sendPeerHello: jest.fn(),
  getPublicKeys: jest.fn(),
  computeFingerprint: jest.fn(),
  deviceASignConfirmation: jest.fn(),
  sendConfirmationSig: jest.fn(),
  storePeerPubkeys: jest.fn(),
  awaitPairSig: jest.fn(),
  deviceBCompletePairing: jest.fn(),
  startSyncService: jest.fn(),
  unpair: jest.fn(),
};

jest.mock('./modules/twinotify-core/src/TwinotifyCoreModule', () => ({
  __esModule: true,
  default: mockTwinotifyCore,
}));

jest.mock('expo-camera', () => ({
  CameraView: (props) => mockReact.createElement(mockView, { ...props, testID: 'camera-view' }),
  useCameraPermissions: jest.fn(() => [{ granted: true }, jest.fn()]),
}));

jest.mock('react-native-qrcode-svg', () => ({
  __esModule: true,
  default: (props) => mockReact.createElement(mockView, { ...props, testID: 'qr-renderer' }),
}));

global.__TEST_ROUTER__ = mockRouter;
global.__SET_SEARCH_PARAMS__ = (params) => { mockLocalSearchParams = params; };
global.__TWINOTIFY_CORE__ = mockTwinotifyCore;
global.__EMIT_OFFLINE_STATUS__ = (status) => { offlineStatusListener?.(status); };
global.__RESET_OFFLINE_TEST_STATE__ = () => {
  mockStorage.clear();
  mockLocalSearchParams = {};
  offlineStatusListener = null;
  Object.values(mockRouter).forEach((mock) => mock.mockClear());
  Object.values(mockTwinotifyCore).forEach((mock) => {
    if (typeof mock?.mockClear === 'function') mock.mockClear();
  });
  mockTwinotifyCore.getDeviceDisplayName.mockResolvedValue('Android phone');
  mockTwinotifyCore.getOfflinePairingStatus.mockResolvedValue(idleStatus);
  mockTwinotifyCore.getPairStatus.mockResolvedValue({ paired: false });
};
