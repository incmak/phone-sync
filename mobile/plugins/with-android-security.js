const fs = require('fs');
const path = require('path');
const { withAndroidManifest, withDangerousMod } = require('expo/config-plugins');

const DATA_EXTRACTION_RULES = `<?xml version="1.0" encoding="utf-8"?>
<data-extraction-rules>
  <cloud-backup>
    <exclude domain="root" path="." />
    <exclude domain="file" path="." />
    <exclude domain="database" path="." />
    <exclude domain="sharedpref" path="." />
    <exclude domain="external" path="." />
    <exclude domain="device_root" path="." />
    <exclude domain="device_file" path="." />
    <exclude domain="device_database" path="." />
    <exclude domain="device_sharedpref" path="." />
  </cloud-backup>
  <device-transfer>
    <exclude domain="root" path="." />
    <exclude domain="file" path="." />
    <exclude domain="database" path="." />
    <exclude domain="sharedpref" path="." />
    <exclude domain="external" path="." />
    <exclude domain="device_root" path="." />
    <exclude domain="device_file" path="." />
    <exclude domain="device_database" path="." />
    <exclude domain="device_sharedpref" path="." />
  </device-transfer>
</data-extraction-rules>
`;

function applyBackupProtection(androidManifest) {
  const application = androidManifest.manifest.application?.[0];
  if (!application) {
    throw new Error('Generated Android manifest has no application element');
  }
  application.$ = application.$ || {};
  application.$['android:allowBackup'] = 'false';
  application.$['android:fullBackupContent'] = 'false';
  application.$['android:dataExtractionRules'] = '@xml/twinotify_data_extraction_rules';
  return androidManifest;
}

function withAndroidSecurity(config) {
  const withManifest = withAndroidManifest(config, (manifestConfig) => {
    manifestConfig.modResults = applyBackupProtection(manifestConfig.modResults);
    return manifestConfig;
  });
  return withDangerousMod(withManifest, ['android', async (androidConfig) => {
    const xmlDir = path.join(androidConfig.modRequest.platformProjectRoot, 'app', 'src', 'main', 'res', 'xml');
    fs.mkdirSync(xmlDir, { recursive: true });
    fs.writeFileSync(path.join(xmlDir, 'twinotify_data_extraction_rules.xml'), DATA_EXTRACTION_RULES);
    return androidConfig;
  }]);
}

module.exports = withAndroidSecurity;
module.exports.DATA_EXTRACTION_RULES = DATA_EXTRACTION_RULES;
module.exports.applyBackupProtection = applyBackupProtection;
