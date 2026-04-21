// Reexport the native module. On web, it will be resolved to PhoneSyncCoreModule.web.ts
// and on native platforms to PhoneSyncCoreModule.ts
export { default } from './src/PhoneSyncCoreModule';
export { default as PhoneSyncCoreView } from './src/PhoneSyncCoreView';
export * from  './src/PhoneSyncCore.types';
