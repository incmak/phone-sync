const {
  patchProjectBuildGradle,
  patchSettingsGradle,
} = require('../with-android-toolchain-workarounds');

const BASE_GRADLE = `apply plugin: "expo-root-project"
apply plugin: "com.facebook.react.rootproject"
`;
const BASE_SETTINGS = `rootProject.name = 'Twinotify'

include ':app'
includeBuild(expoAutolinking.reactNativeGradlePlugin)
`;

describe('withAndroidToolchainWorkarounds', () => {
  it('disables only dependency lint analysis tasks affected by the AGP crash', () => {
    const patched = patchProjectBuildGradle(BASE_GRADLE);

    expect(patched).toContain(
      'subproject.name in ["react-native-worklets", "react-native-reanimated"]'
    );
    expect(patched).toContain('task.name.startsWith("lintAnalyze")');
    expect(patched).toContain('task.enabled = false');
    expect(patched).not.toContain('subproject.name == "twinotify-core"');
    expect(patched).not.toContain('task.name.startsWith("lintDebug")');
  });

  it('permits only Expo core’s source-less generated unit-test task', () => {
    const patched = patchProjectBuildGradle(BASE_GRADLE);

    expect(patched).toContain('subproject.name == "expo-modules-core"');
    expect(patched).toContain('task.name == "testDebugUnitTest"');
    expect(patched).toContain('task.failOnNoDiscoveredTests = false');
    expect(patched).not.toContain('testDebugUnitTest.enabled = false');
  });

  it('makes debug assembly fail closed unless owned lint and JVM tests execute', () => {
    const patched = patchProjectBuildGradle(BASE_GRADLE);

    expect(patched).toContain('verifyTwinotifyOwnedAndroidChecks');
    expect(patched).toContain('project(":app").tasks.named("lintDebug")');
    expect(patched).toContain('project(":twinotify-core").tasks.named("lintDebug")');
    expect(patched).toContain(
      'project(":twinotify-core").tasks.named("testDebugUnitTest")'
    );
    expect(patched).toContain('!ownedTask.enabled || !ownedTask.state.executed');
    expect(patched).toContain('ownedTask.state.noSource || ownedTask.state.failure != null');
    expect(patched).toContain('assembleDebug.dependsOn(ownedChecks)');
  });

  it('is idempotent', () => {
    const patched = patchProjectBuildGradle(BASE_GRADLE);

    expect(patchProjectBuildGradle(patched)).toBe(patched);
  });

  it('fails closed when the generated Gradle anchor changes', () => {
    expect(() => patchProjectBuildGradle('// unexpected template')).toThrow(
      'React Native root-project plugin anchor was not found'
    );
  });

  it('includes the dedicated action fixture from its tracked source directory', () => {
    const patched = patchSettingsGradle(BASE_SETTINGS);

    expect(patched).toContain("include ':notification-action-fixture'");
    expect(patched).toContain(
      "project(':notification-action-fixture').projectDir = new File(rootProject.projectDir, '../fixtures/notification-action-fixture')"
    );
    expect(patchSettingsGradle(patched)).toBe(patched);
  });

  it('fails closed when the generated settings anchor changes', () => {
    expect(() => patchSettingsGradle('// unexpected template')).toThrow(
      'Android app settings anchor was not found'
    );
  });
});
