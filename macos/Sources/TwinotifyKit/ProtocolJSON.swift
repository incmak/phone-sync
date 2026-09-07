import Foundation

public enum ProtocolError: Error, Equatable { case invalidJSON, invalidSchema, invalidPacket, identityMismatch }

public indirect enum JSONValue: Codable, Sendable, Equatable {
    case object([String: JSONValue]), array([JSONValue]), string(String), integer(Int64), number(Double), bool(Bool), null

    public init(from decoder: any Decoder) throws {
        let container = try decoder.singleValueContainer()
        if container.decodeNil() { self = .null }
        else if let value = try? container.decode(Bool.self) { self = .bool(value) }
        else if let value = try? container.decode(Int64.self) { self = .integer(value) }
        else if let value = try? container.decode(Double.self), value.isFinite { self = .number(value) }
        else if let value = try? container.decode(String.self) { self = .string(value) }
        else if let value = try? container.decode([String: JSONValue].self) { self = .object(value) }
        else { self = .array(try container.decode([JSONValue].self)) }
    }

    public func encode(to encoder: any Encoder) throws {
        var container = encoder.singleValueContainer()
        switch self {
        case .object(let value): try container.encode(value)
        case .array(let value): try container.encode(value)
        case .string(let value): try container.encode(value)
        case .integer(let value): try container.encode(value)
        case .number(let value): try container.encode(value)
        case .bool(let value): try container.encode(value)
        case .null: try container.encodeNil()
        }
    }
    public subscript(_ key: String) -> JSONValue? { object?[key] }
    public var object: [String: JSONValue]? { if case .object(let value) = self { return value }; return nil }
    public var array: [JSONValue]? { if case .array(let value) = self { return value }; return nil }
    public var string: String? { if case .string(let value) = self { return value }; return nil }
    public var integer: Int64? { if case .integer(let value) = self { return value }; return nil }
    public var boolean: Bool? { if case .bool(let value) = self { return value }; return nil }
    var numeric: Double? {
        switch self { case .integer(let n): return Double(n); case .number(let n): return n; default: return nil }
    }
    public func encoded() throws -> Data { try JSONEncoder().encode(self) }
    public static func parse(_ data: Data) throws -> JSONValue {
        // Validate duplicate decoded keys and depth before decoding semantic values.
        try RawEnvelope.validateObject(data)
        return try JSONDecoder().decode(Self.self, from: data)
    }
}

/// Evaluates the bounded keyword vocabulary used by the bundled authoritative
/// schemas. Unknown assertion keywords fail closed when schemas are loaded.
struct SchemaValidator: Sendable {
    let schemas: [String: JSONValue]
    init() throws {
        schemas = try ProtocolSchemas.data.mapValues { try JSONDecoder().decode(JSONValue.self, from: $0) }
        for schema in schemas.values { try Self.checkVocabulary(schema) }
    }
    func validate(_ value: JSONValue, schema name: String) throws {
        guard let schema = schemas[name] else { throw ProtocolError.invalidSchema }
        guard matches(value, schema, root: schema) else { throw ProtocolError.invalidPacket }
    }
    private static func checkVocabulary(_ schema: JSONValue) throws {
        let known: Set<String> = ["$schema", "$id", "$defs", "$ref", "title", "default", "type", "properties", "required",
            "additionalProperties", "const", "enum", "allOf", "anyOf", "oneOf", "not", "if", "then", "else", "minimum",
            "maximum", "minLength", "maxLength", "pattern", "format", "contentEncoding", "items", "minItems", "maxItems",
            "contains", "minContains", "maxContains", "uniqueItems", "dependentRequired"]
        guard let object = schema.object, Set(object.keys).isSubset(of: known) else { throw ProtocolError.invalidSchema }
        for key in ["properties", "$defs"] {
            for value in object[key]?.object?.values ?? [:].values { try checkVocabulary(value) }
        }
        for key in ["items", "contains", "not", "if", "then", "else"] {
            if let value = object[key] { try checkVocabulary(value) }
        }
        for key in ["allOf", "anyOf", "oneOf"] {
            for value in object[key]?.array ?? [] { try checkVocabulary(value) }
        }
    }
    private func matches(_ value: JSONValue, _ schema: JSONValue, root: JSONValue, depth: Int = 0) -> Bool {
        guard depth <= 64, let rules = schema.object else { return false }
        let child: (JSONValue, JSONValue) -> Bool = { matches($0, $1, root: root, depth: depth + 1) }
        if let ref = rules["$ref"]?.string {
            let target: JSONValue?
            if ref.hasPrefix("#/$defs/") { target = root["$defs"]?[String(ref.dropFirst(8))] }
            else { target = schemas[ref.replacingOccurrences(of: "https://twinotify.app/schemas/", with: "").replacingOccurrences(of: ".schema.json", with: "")] }
            guard let target, matches(value, target, root: ref.hasPrefix("#") ? root : target, depth: depth + 1) else { return false }
        }
        if let type = rules["type"] {
            let types = type.array?.compactMap(\.string) ?? [type.string ?? ""]
            guard types.contains(where: { name in
                switch (name, value) {
                case ("object", .object), ("array", .array), ("string", .string), ("integer", .integer),
                     ("number", .integer), ("number", .number), ("boolean", .bool), ("null", .null): return true
                default: return false
                }
            }) else { return false }
        }
        if let constant = rules["const"], value != constant { return false }
        if let choices = rules["enum"]?.array, !choices.contains(value) { return false }
        if let all = rules["allOf"]?.array, !all.allSatisfy({ child(value, $0) }) { return false }
        if let any = rules["anyOf"]?.array, !any.contains(where: { child(value, $0) }) { return false }
        if let one = rules["oneOf"]?.array, one.filter({ child(value, $0) }).count != 1 { return false }
        if let not = rules["not"], child(value, not) { return false }
        if let condition = rules["if"], let branch = rules[child(value, condition) ? "then" : "else"], !child(value, branch) { return false }
        if let object = value.object {
            let required = rules["required"]?.array?.compactMap(\.string) ?? []
            guard required.allSatisfy({ object[$0] != nil }) else { return false }
            let properties = rules["properties"]?.object ?? [:]
            if rules["additionalProperties"] == .bool(false), !Set(object.keys).isSubset(of: Set(properties.keys)) { return false }
            for (key, item) in object { if let rule = properties[key], !child(item, rule) { return false } }
            for (key, dependencies) in rules["dependentRequired"]?.object ?? [:] where object[key] != nil {
                guard (dependencies.array ?? []).allSatisfy({ object[$0.string ?? ""] != nil }) else { return false }
            }
        }
        if let string = value.string {
            let count = Int64(string.unicodeScalars.count)
            if let min = rules["minLength"]?.integer, count < min { return false }
            if let max = rules["maxLength"]?.integer, count > max { return false }
            if let pattern = rules["pattern"]?.string, string.range(of: pattern, options: .regularExpression) == nil { return false }
            if rules["format"]?.string == "uuid", UUID(uuidString: string) == nil { return false }
            // contentEncoding is an annotation in JSON Schema. Crypto/image boundaries decode bytes explicitly.
        }
        if let number = value.numeric {
            if let min = rules["minimum"]?.numeric, number < min { return false }
            if let max = rules["maximum"]?.numeric, number > max { return false }
        }
        if let items = value.array {
            if let min = rules["minItems"]?.integer, items.count < min { return false }
            if let max = rules["maxItems"]?.integer, items.count > max { return false }
            if let itemRule = rules["items"], !items.allSatisfy({ child($0, itemRule) }) { return false }
            if rules["uniqueItems"] == .bool(true) {
                for index in items.indices where items[..<index].contains(items[index]) { return false }
            }
            if let contains = rules["contains"] {
                let count = items.filter { child($0, contains) }.count
                if count < (rules["minContains"]?.integer ?? 1) || count > (rules["maxContains"]?.integer ?? Int64.max) { return false }
            }
        }
        return true
    }
}

public struct InnerEvent: Sendable {
    public let messageID: String
    public let originDevice: String
    public let type: String
    public let canonicalID: String?
    public let sequence: Int64?
    public let createdAt: Int64
    public let expiresAt: Int64
    public let payload: JSONValue
}

public struct AuthenticatedEvent: Sendable {
    public let inner: InnerEvent
    public let digest: String
}

public struct ProtocolCodec: Sendable {
    private let validator: SchemaValidator
    public init() throws { validator = try SchemaValidator() }
    public func decodeInner(_ bytes: Data) throws -> InnerEvent {
        let json = try JSONValue.parse(bytes)
        try validator.validate(json, schema: "inner-event-v2")
        guard let messageID = json["msg_id"]?.string, let origin = json["origin_device"]?.string,
              let type = json["type"]?.string, let created = json["created_at"]?.integer,
              let expires = json["expires_at"]?.integer, expires > created, let payload = json["payload"] else {
            throw ProtocolError.invalidPacket
        }
        let canonicalID = json["canon_id"]?.string, sequence = json["sequence"]?.integer
        if ["notif.post", "notif.update"].contains(type) {
            try validator.validate(payload, schema: "notif-post")
            guard payload["canon_id"]?.string == canonicalID else { throw ProtocolError.invalidPacket }
        }
        if type == "peer.receipt" { try validator.validate(payload, schema: "peer-receipt") }
        let lifetimes: [String: Int64] = ["lan.bootstrap": 600_000, "relay.attach": 300_000, "peer.probe": 120_000,
            "notif.action.invoke": 120_000, "notif.action.result": 600_000, "call.control.invoke": 15_000, "call.control.result": 300_000]
        if let lifetime = lifetimes[type], expires - created != lifetime { throw ProtocolError.invalidPacket }
        if ["notif.action.invoke", "call.control.invoke"].contains(type), payload["invoked_at"]?.integer != created {
            throw ProtocolError.invalidPacket
        }
        if type == "notif.action.invoke", let text = payload["reply_text"]?.string, text.utf8.count > 4096 { throw ProtocolError.invalidPacket }
        if type == "peer.probe", payload["probe_id"]?.string != messageID { throw ProtocolError.invalidPacket }
        if type == "relay.attach" {
            guard let relay = payload["relay_url"]?.string else { throw ProtocolError.invalidPacket }
            _ = try RelayEndpoint(relay, allowDebugLoopback: false)
        }
        if type == "call.state" {
            guard let session = payload["call_session_id"]?.string, canonicalID == "call:" + session else { throw ProtocolError.invalidPacket }
            let ids = (payload["controls"]?.array ?? []).compactMap { $0["control_id"]?.string.flatMap(UUID.init(uuidString:)) }
            guard Set(ids).count == ids.count else { throw ProtocolError.invalidPacket }
        }
        if type == "call.control.invoke" {
            guard payload["invocation_id"] == payload["control_id"],
                  let session = payload["call_session_id"]?.string, payload["canon_id"]?.string == "call:" + session else { throw ProtocolError.invalidPacket }
        }
        return InnerEvent(messageID: messageID, originDevice: origin, type: type, canonicalID: canonicalID,
                          sequence: sequence, createdAt: created, expiresAt: expires, payload: payload)
    }

    public func authenticate(_ bytes: Data, peer: PeerLink, store: DurableStore) async throws -> AuthenticatedEvent {
        guard peer.lifecycle == .active, try await store.peers().contains(peer) else { throw ProtocolError.identityMismatch }
        let outer = try JSONValue.parse(bytes)
        try validator.validate(outer, schema: "envelope-encrypted")
        guard outer["v"] == .integer(2), outer["origin_device"]?.string == peer.deviceID,
              let nonce = outer["nonce"]?.string.flatMap({ Data(base64Encoded: $0) }), nonce.count == 24,
              let ciphertext = outer["ciphertext"]?.string.flatMap({ Data(base64Encoded: $0) }), ciphertext.count >= 16 else {
            throw ProtocolError.identityMismatch
        }
        let plaintext = try await store.decrypt(ciphertext, nonce: nonce, peerPublicKey: peer.encryptionKey)
        let inner = try decodeInner(plaintext)
        guard inner.originDevice == peer.deviceID,
              outer["msg_id"]?.string == inner.messageID,
              outer["created_at"]?.integer == inner.createdAt else { throw ProtocolError.identityMismatch }
        return AuthenticatedEvent(inner: inner, digest: RawEnvelope.digest(bytes))
    }

    public func seal(type: String, payload: JSONValue, peer: PeerLink, store: DurableStore,
                     now: Int64, lifetime: Int64 = 86_400_000) async throws -> StoredEnvelope {
        guard now >= 0, lifetime > 0, now <= Int64.max - lifetime else { throw ProtocolError.invalidPacket }
        let id = UUID().uuidString.lowercased()
        let inner = JSONValue.object(["v": .integer(2), "msg_id": .string(id), "origin_device": .string(store.identity.deviceID),
            "type": .string(type), "created_at": .integer(now), "expires_at": .integer(now + lifetime), "payload": payload])
        let bytes = try inner.encoded()
        _ = try decodeInner(bytes)
        let sealed = try await store.encrypt(bytes, peerPublicKey: peer.encryptionKey)
        let envelope = try JSONValue.object(["v": .integer(2), "type": .string("enc"), "msg_id": .string(id),
            "origin_device": .string(store.identity.deviceID), "created_at": .integer(now),
            "nonce": .string(sealed.nonce.base64EncodedString()), "ciphertext": .string(sealed.ciphertext.base64EncodedString())]).encoded()
        _ = try RawEnvelope.put(envelope: envelope)
        return StoredEnvelope(messageID: id, bytes: envelope, digest: RawEnvelope.digest(envelope), expiresAt: now + lifetime)
    }
}
