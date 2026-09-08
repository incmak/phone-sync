import CryptoKit
import Foundation
import Sodium

public enum LanError: Error, Equatable {
    case invalidFrame, oversized, authentication, pinMismatch, bindingConflict, unavailable, timeout, closed
}

public enum LanFrame: Sendable, Equatable {
    case hello(Data), helloAck(Data), put(Data), accepted(String, String), ping(Int64), pong(Int64), close(String)
    public static let maximumBodyBytes = 1_064_996
    public static let maximumEnvelopeBytes = 1_048_576

    public func encode() throws -> Data {
        var fields: [String: JSONValue] = ["v": .integer(1)]
        switch self {
        case .hello(let data), .helloAck(let data):
            guard data.count <= 16_384 else { throw LanError.oversized }
            fields["type"] = .string(isHello ? "lan.hello" : "lan.hello_ack")
            fields["data"] = .string(data.base64EncodedString())
        case .put(let data):
            guard data.count <= Self.maximumEnvelopeBytes, let text = String(data: data, encoding: .utf8) else { throw LanError.oversized }
            fields["type"] = .string("lan.put"); fields["envelope"] = .string(text)
        case .accepted(let id, let digest):
            fields["type"] = .string("lan.accepted"); fields["msg_id"] = .string(id); fields["envelope_sha256"] = .string(digest)
        case .ping(let token), .pong(let token):
            fields["type"] = .string(isPing ? "lan.ping" : "lan.pong"); fields["token"] = .integer(token)
        case .close(let code): fields["type"] = .string("lan.close"); fields["code"] = .string(code)
        }
        let body = try JSONValue.object(fields).encoded()
        guard body.count <= Self.maximumBodyBytes else { throw LanError.oversized }
        let framed = Data.be32(UInt32(body.count)) + body
        _ = try Self.decode(framed)
        return framed
    }
    private var isHello: Bool { if case .hello = self { true } else { false } }
    private var isPing: Bool { if case .ping = self { true } else { false } }

    public static func decode(_ data: Data) throws -> LanFrame {
        guard data.count >= 4 else { throw LanError.invalidFrame }
        let size = data.prefix(4).reduce(0) { ($0 << 8) | Int($1) }
        guard size > 0, size <= maximumBodyBytes else { throw LanError.oversized }
        guard data.count == size + 4 else { throw LanError.invalidFrame }
        let body = Data(data.dropFirst(4))
        try RawEnvelope.validateObject(body, maximumBytes: maximumBodyBytes)
        guard let fields = try JSONDecoder().decode(JSONValue.self, from: body).object,
              fields["v"] == .integer(1), let type = fields["type"]?.string else { throw LanError.invalidFrame }
        func keys(_ extra: Set<String>) throws {
            guard Set(fields.keys) == extra.union(["v", "type"]) else { throw LanError.invalidFrame }
        }
        switch type {
        case "lan.hello", "lan.hello_ack":
            try keys(["data"])
            guard let value = fields["data"]?.string, let bytes = Data(base64Encoded: value),
                  bytes.base64EncodedString() == value, bytes.count <= 16_384 else { throw LanError.invalidFrame }
            return type == "lan.hello" ? .hello(bytes) : .helloAck(bytes)
        case "lan.put":
            try keys(["envelope"])
            guard let text = fields["envelope"]?.string, text.utf8.count <= maximumEnvelopeBytes else { throw LanError.oversized }
            return .put(Data(text.utf8))
        case "lan.accepted":
            try keys(["msg_id", "envelope_sha256"])
            guard let id = fields["msg_id"]?.string, UUID(uuidString: id)?.uuidString.lowercased() == id,
                  let digest = fields["envelope_sha256"]?.string,
                  digest.range(of: "^[0-9a-f]{64}$", options: .regularExpression) != nil else { throw LanError.invalidFrame }
            return .accepted(id, digest)
        case "lan.ping", "lan.pong":
            try keys(["token"])
            guard let token = fields["token"]?.integer, token >= 0 else { throw LanError.invalidFrame }
            return type == "lan.ping" ? .ping(token) : .pong(token)
        case "lan.close":
            try keys(["code"])
            guard let code = fields["code"]?.string, code.range(of: "^[a-z][a-z0-9_]{0,63}$", options: .regularExpression) != nil else { throw LanError.invalidFrame }
            return .close(code)
        default: throw LanError.invalidFrame
        }
    }
}

public struct LanMaterial: Sendable {
    public let secret: Data
    public let contextDigest: Data
}

public enum LanCrypto {
    public static let alpn = "twinotify-lan/2"
    public static let exporterLabel = "EXPORTER-twinotify-lan-v1"

    static func derive(local: PublicIdentity, peer: PublicIdentity, secretKey: Data) throws -> LanMaterial {
        guard local.deviceID != peer.deviceID, secretKey.count == 32,
              [local.encryptionKey, local.signingKey, peer.encryptionKey, peer.signingKey].allSatisfy({ $0.count == 32 }),
              let shared = Sodium().box.beforenm(recipientPublicKey: Array(peer.encryptionKey), senderSecretKey: Array(secretKey)) else {
            throw CryptoError.invalidKey
        }
        var context = Data("twinotify-lan-binding-context-v1\n".utf8)
        for identity in [local, peer].sorted(by: { Data($0.deviceID.utf8).lexicographicallyPrecedes(Data($1.deviceID.utf8)) }) {
            for bytes in [Data(identity.deviceID.utf8), identity.encryptionKey, identity.signingKey] {
                context += Data.be32(UInt32(bytes.count)) + bytes
            }
        }
        let digest = Data(SHA256.hash(data: context))
        let prk = hmac(key: digest, data: Data(shared))
        return LanMaterial(secret: hmac(key: prk, data: Data("twinotify-lan-secret-v1\n".utf8) + Data([1])), contextDigest: digest)
    }
    public static func advertisement(secret: Data, deviceID: String, day: Int64) throws -> String {
        let id = Data(deviceID.utf8), domain = Data("twinotify:lan-advertisement:v1".utf8)
        guard secret.count == 32, (1...256).contains(id.count) else { throw CryptoError.invalidKey }
        let input = Data.be32(UInt32(domain.count)) + domain + Data.be32(UInt32(id.count)) + id + Data.be64(UInt64(bitPattern: day))
        return hmac(key: secret, data: input).prefix(16).base64EncodedString()
            .replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
    }
    static func hmac(key: Data, data: Data) -> Data {
        Data(HMAC<SHA256>.authenticationCode(for: data, using: SymmetricKey(data: key)))
    }
}

struct LanHandshakeContext: Sendable {
    let initiator: String, acceptor: String
    let initiatorNonce: Data, acceptorNonce: Data, tlsContext: Data
    var sessionID: Data {
        Data(SHA256.hash(data: Data("twinotify-lan-handshake-session-v1".utf8) + tlsContext + initiatorNonce + acceptorNonce))
    }
    func transcript() throws -> Data {
        guard initiator != acceptor, [initiatorNonce, acceptorNonce, tlsContext].allSatisfy({ $0.count == 32 }) else { throw LanError.authentication }
        return try Data("twinotify-lan-handshake-transcript-v1".utf8)
            + Data.bounded(Data(initiator.utf8), maximum: 128) + Data.bounded(Data(acceptor.utf8), maximum: 128)
            + Data.bounded(initiatorNonce) + Data.bounded(acceptorNonce) + Data([1, 2]) + Data.be32(1)
            + Data.bounded(tlsContext) + Data.bounded(sessionID)
    }
    func signedMessage(role: UInt8) throws -> Data {
        try Data("twinotify-lan-handshake-signature-v1".utf8) + Data([role]) + transcript()
    }
    func hello(role: UInt8, signature: Data) throws -> Data {
        guard role == 1 || role == 2, signature.count == 64 else { throw LanError.authentication }
        return try Data.be32(1) + Data.bounded(Data((role == 1 ? initiator : acceptor).utf8), maximum: 128)
            + Data([role]) + Data.bounded(transcript(), maximum: 1024) + Data.bounded(signature, maximum: 128)
    }
    func verify(_ hello: Data, role: UInt8, publicKey: Data) throws {
        guard hello.count <= 2048 else { throw LanError.authentication }
        var reader = LanBinaryReader(bytes: hello)
        guard try reader.take(4) == Data.be32(1),
              try reader.bounded(maximum: 128) == Data((role == 1 ? initiator : acceptor).utf8),
              try reader.take(1) == Data([role]), try reader.bounded(maximum: 1024) == transcript() else { throw LanError.authentication }
        let signature = try reader.bounded(maximum: 128)
        guard reader.remaining == 0, try WireCrypto.verify(signature, message: signedMessage(role: role), publicKey: publicKey) else {
            throw LanError.authentication
        }
    }
}

struct LanBinaryReader {
    let bytes: Data
    var offset = 0
    var remaining: Int { bytes.count - offset }
    mutating func take(_ count: Int) throws -> Data {
        guard count >= 0, count <= remaining else { throw LanError.authentication }
        defer { offset += count }
        return bytes.subdata(in: offset..<(offset + count))
    }
    mutating func bounded(maximum: Int) throws -> Data {
        let count = try take(2).reduce(0) { ($0 << 8) | Int($1) }
        guard count > 0, count <= maximum else { throw LanError.authentication }
        return try take(count)
    }
}

extension Data {
    static func be32(_ value: UInt32) -> Data { var value = value.bigEndian; return Swift.withUnsafeBytes(of: &value) { Data($0) } }
    static func be64(_ value: UInt64) -> Data { var value = value.bigEndian; return Swift.withUnsafeBytes(of: &value) { Data($0) } }
    static func bounded(_ value: Data, maximum: Int = 32) throws -> Data {
        guard !value.isEmpty, value.count <= maximum, value.count <= UInt16.max else { throw LanError.authentication }
        var count = UInt16(value.count).bigEndian
        return Swift.withUnsafeBytes(of: &count) { Data($0) } + value
    }
}
