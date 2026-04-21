import ExpoModulesCore

public class PhoneSyncCoreModule: Module {
  public func definition() -> ModuleDefinition {
    Name("PhoneSyncCore")

    AsyncFunction("ping") { (_ relayUrl: String) -> String in
      throw Exception(name: "UnsupportedPlatform", description: "phone-sync-core is Android-only in v1")
    }
  }
}
