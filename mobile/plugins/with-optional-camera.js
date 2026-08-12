const { withAndroidManifest } = require('expo/config-plugins');

const CAMERA_FEATURE = 'android.hardware.camera';

module.exports = function withOptionalCamera(config) {
  return withAndroidManifest(config, (manifestConfig) => {
    const manifest = manifestConfig.modResults.manifest;
    const features = manifest['uses-feature'] ?? [];
    const cameraFeature = features.find(
      (feature) => feature.$?.['android:name'] === CAMERA_FEATURE
    );

    if (cameraFeature) {
      cameraFeature.$['android:required'] = 'false';
    } else {
      features.push({
        $: {
          'android:name': CAMERA_FEATURE,
          'android:required': 'false',
        },
      });
    }

    manifest['uses-feature'] = features;
    return manifestConfig;
  });
};
