import Foundation

public struct RelayEndpoint: Sendable {
    public let http: URL
    public init(_ input: String, allowDebugLoopback: Bool = false) throws {
        guard input.utf8.count <= 512, var parts = URLComponents(string: input),
              parts.user == nil, parts.password == nil, parts.query == nil, parts.fragment == nil,
              let host = parts.host, !host.isEmpty, let scheme = parts.scheme?.lowercased(),
              ["https", "wss", "http", "ws"].contains(scheme) else { throw ProtocolError.invalidPacket }
        if ["http", "ws"].contains(scheme) {
            #if DEBUG || TWINOTIFY_E2E
            guard allowDebugLoopback && ["localhost", "127.0.0.1", "[::1]", "::1"].contains(host.lowercased()) else {
                throw ProtocolError.invalidPacket
            }
            #else
            throw ProtocolError.invalidPacket
            #endif
        }
        parts.scheme = ["https", "wss"].contains(scheme) ? "https" : "http"
        guard let url = parts.url else { throw ProtocolError.invalidPacket }
        self.http = url
    }
    public func url(_ path: String, websocket: Bool = false, query: [URLQueryItem] = []) throws -> URL {
        guard var parts = URLComponents(url: http.appendingPathComponent(path), resolvingAgainstBaseURL: false) else {
            throw ProtocolError.invalidPacket
        }
        if websocket { parts.scheme = parts.scheme == "https" ? "wss" : "ws" }
        if !query.isEmpty { parts.queryItems = query }
        guard let url = parts.url else { throw ProtocolError.invalidPacket }; return url
    }
}

extension DurableStore {
    public func mintJWT(pairID: String? = nil, nowSeconds: Int64) throws -> String {
        guard nowSeconds >= 0, nowSeconds <= Int64.max - 60 else { throw ProtocolError.invalidPacket }
        let header = Data(#"{"alg":"EdDSA","typ":"JWT"}"#.utf8)
        var payload: [String: JSONValue] = ["sub": .string(identity.deviceID), "jti": .string(UUID().uuidString.lowercased()),
                                          "iat": .integer(nowSeconds), "exp": .integer(nowSeconds + 60)]
        if let pairID { payload["pair_id"] = .string(pairID) }
        let signingInput = header.base64URL + "." + (try JSONValue.object(payload).encoded()).base64URL
        return signingInput + "." + (try sign(Data(signingInput.utf8))).base64URL
    }
}

private extension Data {
    var base64URL: String { base64EncodedString().replacingOccurrences(of: "+", with: "-")
        .replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "") }
}
