/* global jest */

const mockReact = require('react');
const { BackHandler: mockBackHandler, View: mockView } = require('react-native');

const mockStorage = new Map();
let mockDarkTheme = false;

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
    useTheme: () => twTheme({ dark: mockDarkTheme }),
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

let activeOfflineStatusListener = null;
let lastOfflineStatusListener = null;
let lastOfflineSubscriptionRemove = jest.fn();
let hardwareBackHandler = null;
let mockCameraPermission = { granted: true };
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
    if (event === 'onOfflinePairingStatus') {
      activeOfflineStatusListener = listener;
      lastOfflineStatusListener = listener;
      lastOfflineSubscriptionRemove = jest.fn(() => {
        if (activeOfflineStatusListener === listener) activeOfflineStatusListener = null;
      });
    }
    return { remove: lastOfflineSubscriptionRemove };
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
  startLanOnlySyncService: jest.fn(),
  stopSyncService: jest.fn(),
  getPreferLan: jest.fn(async () => true),
  setPreferLan: jest.fn(),
  retryRoute: jest.fn(),
  getRouteStatus: jest.fn(async () => ({ route: 'none', phase: 'idle', queued_count: 0, route_generation: 0 })),
  getSyncStatus: jest.fn(async () => ({ state: 'DISCONNECTED', queuedCount: 0 })),
  getMetrics: jest.fn(async () => ({ mirroredToday: 0, blockedToday: 0, latencyMs: 0 })),
  unpair: jest.fn(),
};

jest.mock('./modules/twinotify-core/src/TwinotifyCoreModule', () => ({
  __esModule: true,
  default: mockTwinotifyCore,
}));

jest.mock('expo-camera', () => ({
  CameraView: (props) => mockReact.createElement(mockView, { ...props, testID: 'camera-view' }),
  useCameraPermissions: jest.fn(() => [mockCameraPermission, jest.fn()]),
}));

jest.mock('react-native-qrcode-svg', () => ({
  __esModule: true,
  default: (props) => mockReact.createElement(mockView, { ...props, testID: 'qr-renderer' }),
}));

global.__TEST_ROUTER__ = mockRouter;
global.__SET_SEARCH_PARAMS__ = (params) => { mockLocalSearchParams = params; };
global.__TWINOTIFY_CORE__ = mockTwinotifyCore;
jest.spyOn(mockBackHandler, 'addEventListener').mockImplementation((event, listener) => {
  if (event === 'hardwareBackPress') hardwareBackHandler = listener;
  return { remove: jest.fn(() => {
    if (hardwareBackHandler === listener) hardwareBackHandler = null;
  }) };
});

global.__EMIT_OFFLINE_STATUS__ = (status) => { activeOfflineStatusListener?.(status); };
global.__EMIT_STALE_OFFLINE_STATUS__ = (status) => { lastOfflineStatusListener?.(status); };
global.__GET_LAST_OFFLINE_REMOVE__ = () => lastOfflineSubscriptionRemove;
global.__PRESS_HARDWARE_BACK__ = () => hardwareBackHandler?.();
global.__SET_CAMERA_PERMISSION__ = (permission) => { mockCameraPermission = permission; };
global.__SET_DARK_THEME__ = (dark) => { mockDarkTheme = dark; };
global.__RESET_OFFLINE_TEST_STATE__ = () => {
  mockStorage.clear();
  mockLocalSearchParams = {};
  activeOfflineStatusListener = null;
  lastOfflineStatusListener = null;
  lastOfflineSubscriptionRemove = jest.fn();
  hardwareBackHandler = null;
  mockCameraPermission = { granted: true };
  mockDarkTheme = false;
  Object.values(mockRouter).forEach((mock) => mock.mockClear());
  Object.values(mockTwinotifyCore).forEach((mock) => {
    if (typeof mock?.mockClear === 'function') mock.mockClear();
  });
  mockTwinotifyCore.getDeviceDisplayName.mockResolvedValue('Android phone');
  mockTwinotifyCore.getOfflinePairingStatus.mockResolvedValue(idleStatus);
  mockTwinotifyCore.getPairStatus.mockResolvedValue({ paired: false });
};
