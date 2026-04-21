// Reexport the native module. On web, it will be resolved to TwinotifyCoreModule.web.ts
// and on native platforms to TwinotifyCoreModule.ts
export { default } from './src/TwinotifyCoreModule';
export { default as TwinotifyCoreView } from './src/TwinotifyCoreView';
export * from  './src/TwinotifyCore.types';
