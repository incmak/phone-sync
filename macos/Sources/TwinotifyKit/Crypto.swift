import CryptoKit
import Foundation
import Sodium

public enum CryptoError: Error {
    case invalidKey, invalidNonce, authenticationFailed, encryptionFailed
}

/// Pairwise wire cryptography. Persistence must allocate and commit a fresh nonce
/// before sealing; retries must reuse the resulting bytes.
public enum WireCrypto {
    public static func seal(_ plaintext: Data, nonce: Data, peerPublicKey: Data, secretKey: Data) throws -> Data {
        guard nonce.count == 24 else { throw CryptoError.invalidNonce }
        guard peerPublicKey.count == 32, secretKey.count == 32 else { throw CryptoError.invalidKey }
        guard let ciphertext = Sodium().box.seal(message: Array(plaintext), recipientPublicKey: Array(peerPublicKey),
                                                senderSecretKey: Array(secretKey), nonce: Array(nonce)) else {
            throw CryptoError.encryptionFailed
        }
        return Data(ciphertext)
    }

    public static func open(_ ciphertext: Data, nonce: Data, peerPublicKey: Data, secretKey: Data) throws -> Data {
        guard nonce.count == 24 else { throw CryptoError.invalidNonce }
        guard peerPublicKey.count == 32, secretKey.count == 32 else { throw CryptoError.invalidKey }
        guard ciphertext.count >= 16,
              let plaintext = Sodium().box.open(authenticatedCipherText: Array(ciphertext), senderPublicKey: Array(peerPublicKey),
                                                recipientSecretKey: Array(secretKey), nonce: Array(nonce)) else {
            throw CryptoError.authenticationFailed
        }
        return Data(plaintext)
    }

    public static func sign(_ message: Data, secretKey: Data) throws -> Data {
        guard secretKey.count == 64 else { throw CryptoError.invalidKey }
        guard let signature = Sodium().sign.signature(message: Array(message), secretKey: Array(secretKey)) else {
            throw CryptoError.invalidKey
        }
        return Data(signature)
    }

    public static func verify(_ signature: Data, message: Data, publicKey: Data) -> Bool {
        guard signature.count == 64, publicKey.count == 32 else { return false }
        return Sodium().sign.verify(message: Array(message), publicKey: Array(publicKey), signature: Array(signature))
    }

    public static func fingerprint(encryptionKey: Data, signingKey: Data) throws -> String {
        guard encryptionKey.count == 32, signingKey.count == 32 else { throw CryptoError.invalidKey }
        let hex = SHA256.hash(data: encryptionKey + signingKey).map { String(format: "%02X", $0) }.joined()
        return stride(from: 0, to: 64, by: 4).map { offset in
            let start = hex.index(hex.startIndex, offsetBy: offset)
            return String(hex[start..<hex.index(start, offsetBy: 4)])
        }.joined(separator: "-")
    }

    public static func nonce(prefix: Data, counter: UInt64) throws -> Data {
        guard prefix.count == 16, counter > 0 else { throw CryptoError.invalidNonce }
        var bigEndian = counter.bigEndian
        return prefix + withUnsafeBytes(of: &bigEndian) { Data($0) }
    }
}

public enum PairingTranscript {
    public static func initiator(token: String, aEncryptionKey: Data, aSigningKey: Data,
                                 bEncryptionKey: Data, bSigningKey: Data) throws -> Data {
        guard [aEncryptionKey, aSigningKey, bEncryptionKey, bSigningKey].allSatisfy({ $0.count == 32 }) else {
            throw CryptoError.invalidKey
        }
        return Data(token.utf8) + aEncryptionKey + aSigningKey + bEncryptionKey + bSigningKey
    }

    public static func responder(initiatorTranscript: Data, initiatorSignature: Data) throws -> Data {
        guard initiatorSignature.count == 64 else { throw CryptoError.invalidKey }
        return Data("twinotify-pair-confirm-b-v1\n".utf8) + initiatorTranscript + initiatorSignature
    }

    public static func notify(token: String, role: String, deviceID: String) -> Data {
        Data("twinotify-pair-notify-v1\n\(token)\n\(role)\n\(deviceID)".utf8)
    }
}
