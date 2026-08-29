const { withProjectBuildGradle, withSettingsGradle } = require('expo/config-plugins');

const ANCHOR = 'apply plugin: "com.facebook.react.rootproject"';
const MARKER = '// Twinotify: isolate AGP lint crash in third-party Kotlin scripts.';
const SETTINGS_ANCHOR = "include ':app'";
const FIXTURE_MARKER = "include ':notification-action-fixture'";
const FIXTURE_SETTINGS = `${FIXTURE_MARKER}
project(':notification-action-fixture').projectDir = new File(rootProject.projectDir, '../fixtures/notification-action-fixture')`;
const WORKAROUND = `${MARKER}
// Android lint issue 430991549 is fixed in AGP 9, but React Native 0.86 pins
// AGP 8.12. Keep lint enabled for the app and twinotify-core; only the two
// affected dependency analyzers are skipped until the toolchain carries the fix.
subprojects { subproject ->
  if (subproject.name in ["react-native-worklets", "react-native-reanimated"]) {
    subproject.tasks.configureEach { task ->
      if (task.name.startsWith("lintAnalyze")) {
        task.enabled = false
      }
    }
  }
}

// Gradle 9 fails this Expo task even though its compiled test source set is
// empty. Preserve the task and all owned tests; permit zero discovered tests
// only for this exact generated third-party task.
subprojects { subproject ->
  if (subproject.name == "expo-modules-core") {
    subproject.tasks.matching { task ->
      task.name == "testDebugUnitTest"
    }.configureEach { task ->
      task.failOnNoDiscoveredTests = false
    }
  }
}

// Assembly is the last canonical native gate. Make it independently enforce
// the owned lint and JVM-test tasks so dependency workarounds cannot silently
// turn the unchanged command line into a false pass.
def ownedChecks = tasks.register("verifyTwinotifyOwnedAndroidChecks")
gradle.projectsEvaluated {
  def requiredOwnedTasks = [
    project(":app").tasks.named("lintDebug").get(),
    project(":twinotify-core").tasks.named("lintDebug").get(),
    project(":twinotify-core").tasks.named("testDebugUnitTest").get()
  ]
  ownedChecks.configure { verificationTask ->
    verificationTask.dependsOn(requiredOwnedTasks)
    verificationTask.doLast {
      requiredOwnedTasks.each { ownedTask ->
        if (!ownedTask.enabled || !ownedTask.state.executed ||
            ownedTask.state.noSource || ownedTask.state.failure != null) {
          throw new GradleException(
            "Required owned Android check did not execute successfully: \${ownedTask.path}"
          )
        }
      }
    }
  }
  project(":app").tasks.named("assembleDebug").configure { assembleDebug ->
    assembleDebug.dependsOn(ownedChecks)
  }
}`;

function patchProjectBuildGradle(contents) {
  if (contents.includes(MARKER)) {
    return contents;
  }
  if (!contents.includes(ANCHOR)) {
    throw new Error('React Native root-project plugin anchor was not found');
  }

  return contents.replace(ANCHOR, `${ANCHOR}\n\n${WORKAROUND}`);
}

function patchSettingsGradle(contents) {
  if (contents.includes(FIXTURE_MARKER)) {
    return contents;
  }
  if (!contents.includes(SETTINGS_ANCHOR)) {
    throw new Error('Android app settings anchor was not found');
  }
  return contents.replace(SETTINGS_ANCHOR, `${SETTINGS_ANCHOR}\n${FIXTURE_SETTINGS}`);
}

function withAndroidToolchainWorkarounds(config) {
  const withBuildWorkarounds = withProjectBuildGradle(config, (gradleConfig) => {
    if (gradleConfig.modResults.language !== 'groovy') {
      throw new Error('Twinotify Android toolchain workarounds require Groovy');
    }
    gradleConfig.modResults.contents = patchProjectBuildGradle(
      gradleConfig.modResults.contents
    );
    return gradleConfig;
  });
  return withSettingsGradle(withBuildWorkarounds, (settingsConfig) => {
    if (settingsConfig.modResults.language !== 'groovy') {
      throw new Error('Twinotify Android fixture settings require Groovy');
    }
    settingsConfig.modResults.contents = patchSettingsGradle(
      settingsConfig.modResults.contents
    );
    return settingsConfig;
  });
}

module.exports = withAndroidToolchainWorkarounds;
module.exports.patchProjectBuildGradle = patchProjectBuildGradle;
module.exports.patchSettingsGradle = patchSettingsGradle;
