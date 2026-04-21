import ExpoModulesCore

public class TwinotifyCoreModule: Module {
  public func definition() -> ModuleDefinition {
    Name("TwinotifyCore")

    AsyncFunction("ping") { (_ relayUrl: String) -> String in
      throw Exception(name: "UnsupportedPlatform", description: "twinotify-core is Android-only in v1")
    }
  }
}
