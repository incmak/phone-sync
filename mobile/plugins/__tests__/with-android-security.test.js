const {
  DATA_EXTRACTION_RULES,
  applyBackupProtection,
} = require('../with-android-security');

describe('withAndroidSecurity', () => {
  it('disables app backup and points Android 12+ at deny-all extraction rules', () => {
    const manifest = {
      manifest: {
        application: [{ $: { 'android:name': '.MainApplication' } }],
      },
    };

    applyBackupProtection(manifest);

    expect(manifest.manifest.application[0].$).toMatchObject({
      'android:allowBackup': 'false',
      'android:fullBackupContent': 'false',
      'android:dataExtractionRules': '@xml/twinotify_data_extraction_rules',
    });
  });

  it('denies every domain for cloud backup and device transfer', () => {
    expect(DATA_EXTRACTION_RULES).toContain('<cloud-backup>');
    expect(DATA_EXTRACTION_RULES).toContain('<device-transfer>');
    expect(DATA_EXTRACTION_RULES.match(/<exclude domain="root" path="\." \/>/g)).toHaveLength(2);
  });

  it('is registered in Expo config', () => {
    const { android, plugins } = require('../../app.json').expo;
    expect(plugins).toContain('./plugins/with-android-security');
    expect(android.blockedPermissions).toEqual(expect.arrayContaining([
      'android.permission.SYSTEM_ALERT_WINDOW',
      'android.permission.RECORD_AUDIO',
      'android.permission.READ_EXTERNAL_STORAGE',
      'android.permission.WRITE_EXTERNAL_STORAGE',
    ]));
  });
});
