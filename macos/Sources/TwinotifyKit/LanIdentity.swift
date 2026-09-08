import CryptoKit
import Foundation
import Security

/// Installation-local TLS key and certificate. The persistent private key stays
/// in the login Keychain. Its public pin is sent only inside authenticated E2EE.
public final class LanIdentity: @unchecked Sendable {
    public let identity: SecIdentity
    public let pin: Data

    public init(namespace: String) throws {
        let label = namespace + ".lan.tls.v1"
        let tag = Data(label.utf8)
        let keyQuery: [String: Any] = [kSecClass as String: kSecClassKey,
            kSecAttrApplicationTag as String: tag, kSecAttrKeyType as String: kSecAttrKeyTypeECSECPrimeRandom,
            kSecAttrKeyClass as String: kSecAttrKeyClassPrivate, kSecReturnRef as String: true,
            kSecUseDataProtectionKeychain as String: false]
        var result: CFTypeRef?
        let keyStatus = SecItemCopyMatching(keyQuery as CFDictionary, &result)
        let certQuery: [String: Any] = [kSecClass as String: kSecClassCertificate, kSecAttrLabel as String: label,
            kSecReturnRef as String: true, kSecUseDataProtectionKeychain as String: false]
        var certificateResult: CFTypeRef?
        let certStatus = SecItemCopyMatching(certQuery as CFDictionary, &certificateResult)
        guard [errSecSuccess, errSecItemNotFound].contains(certStatus),
              [errSecSuccess, errSecItemNotFound].contains(keyStatus) else {
            throw VaultError.status(keyStatus == errSecSuccess ? certStatus : keyStatus)
        }
        let key: SecKey
        if keyStatus == errSecSuccess {
            guard let result, CFGetTypeID(result) == SecKeyGetTypeID() else { throw StorageError.repairRequired }
            key = (result as! SecKey)
        } else {
            guard certStatus == errSecItemNotFound else { throw StorageError.repairRequired }
            var error: Unmanaged<CFError>?
            guard let generated = SecKeyCreateRandomKey([
                kSecAttrKeyType as String: kSecAttrKeyTypeECSECPrimeRandom, kSecAttrKeySizeInBits as String: 256,
                kSecUseDataProtectionKeychain as String: false,
                kSecPrivateKeyAttrs as String: [kSecAttrIsPermanent as String: true,
                    kSecAttrApplicationTag as String: tag, kSecAttrLabel as String: label]
            ] as CFDictionary, &error) else { throw LanError.unavailable }
            key = generated
        }
        let certificate: SecCertificate
        if certStatus == errSecSuccess {
            guard let certificateResult, CFGetTypeID(certificateResult) == SecCertificateGetTypeID() else { throw StorageError.repairRequired }
            certificate = (certificateResult as! SecCertificate)
        } else {
            certificate = try Self.certificate(key: key)
            let status = SecItemAdd([kSecClass as String: kSecClassCertificate, kSecValueRef as String: certificate,
                kSecAttrLabel as String: label, kSecUseDataProtectionKeychain as String: false] as CFDictionary, nil)
            guard status == errSecSuccess else { throw VaultError.status(status) }
        }
        var identity: SecIdentity?
        let status = SecIdentityCreateWithCertificate(nil, certificate, &identity)
        guard status == errSecSuccess, let identity else { throw VaultError.status(status) }
        self.identity = identity
        pin = try Self.spkiPin(certificate)
    }

    static func certificate(key: SecKey) throws -> SecCertificate {
        var error: Unmanaged<CFError>?
        guard let publicKey = SecKeyCopyPublicKey(key),
              let publicBytes = SecKeyCopyExternalRepresentation(publicKey, &error) as Data?, publicBytes.count == 65 else { throw LanError.unavailable }
        let spki = DER.sequence(DER.ecAlgorithm + DER.value(3, Data([0]) + publicBytes))
        let name = DER.sequence(DER.value(0x31, DER.sequence(DER.value(6, Data([0x55, 4, 3])) + DER.value(0x0C, Data("Twinotify LAN".utf8)))))
        var serial = Data(count: 16)
        let randomStatus = serial.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, $0.count, $0.baseAddress!) }
        guard randomStatus == errSecSuccess else { throw LanError.unavailable }
        serial[0] &= 0x7F; serial[0] |= 1
        let validity = DER.sequence(DER.value(0x17, Data("200101000000Z".utf8)) + DER.value(0x17, Data("490101000000Z".utf8)))
        let tbs = DER.sequence(DER.value(0xA0, DER.value(2, Data([2]))) + DER.value(2, serial)
            + DER.signatureAlgorithm + name + validity + name + spki)
        guard let signature = SecKeyCreateSignature(key, .ecdsaSignatureMessageX962SHA256, tbs as CFData, &error) as Data?,
              let cert = SecCertificateCreateWithData(nil, DER.sequence(tbs + DER.signatureAlgorithm + DER.value(3, Data([0]) + signature)) as CFData) else {
            throw LanError.unavailable
        }
        return cert
    }

    /// Hash the certificate's exact SubjectPublicKeyInfo DER, matching Java's
    /// X509Certificate.publicKey.encoded (not its EC point representation).
    static func spkiPin(_ certificate: SecCertificate) throws -> Data {
        var certificateReader = DER.Reader(Data(SecCertificateCopyData(certificate) as Data))
        let outer = try certificateReader.read(tag: 0x30)
        var outerReader = DER.Reader(outer.body)
        let tbs = try outerReader.read(tag: 0x30)
        var fields = DER.Reader(tbs.body)
        if fields.nextTag == 0xA0 { _ = try fields.read(tag: 0xA0) }
        _ = try fields.read(tag: 2) // serial
        for _ in 0..<4 { _ = try fields.read(tag: 0x30) } // signature, issuer, validity, subject
        let spki = try fields.read(tag: 0x30)
        return Data(SHA256.hash(data: spki.full))
    }
}

private enum DER {
    static let signatureAlgorithm = Data([0x30,0x0A,0x06,0x08,0x2A,0x86,0x48,0xCE,0x3D,0x04,0x03,0x02])
    static let ecAlgorithm = Data([0x30,0x13,0x06,0x07,0x2A,0x86,0x48,0xCE,0x3D,0x02,0x01,0x06,0x08,0x2A,0x86,0x48,0xCE,0x3D,0x03,0x01,0x07])
    static func sequence(_ bytes: Data) -> Data { value(0x30, bytes) }
    static func value(_ tag: UInt8, _ bytes: Data) -> Data {
        let length: Data
        if bytes.count < 128 { length = Data([UInt8(bytes.count)]) }
        else if bytes.count < 256 { length = Data([0x81, UInt8(bytes.count)]) }
        else { length = Data([0x82, UInt8(bytes.count >> 8), UInt8(bytes.count & 255)]) }
        return Data([tag]) + length + bytes
    }
    struct Reader {
        let bytes: Data
        var offset = 0
        init(_ bytes: Data) { self.bytes = bytes }
        var nextTag: UInt8? { offset < bytes.count ? bytes[offset] : nil }
        mutating func read(tag: UInt8) throws -> (body: Data, full: Data) {
            let start = offset
            guard offset + 2 <= bytes.count, bytes[offset] == tag else { throw LanError.authentication }
            offset += 1
            var length = Int(bytes[offset]); offset += 1
            if length & 0x80 != 0 {
                let count = length & 0x7F
                guard (1...3).contains(count), offset + count <= bytes.count else { throw LanError.authentication }
                length = 0
                for _ in 0..<count { length = (length << 8) | Int(bytes[offset]); offset += 1 }
            }
            guard length <= 65_536, offset + length <= bytes.count else { throw LanError.authentication }
            let body = bytes.subdata(in: offset..<(offset + length)); offset += length
            return (body, bytes.subdata(in: start..<offset))
        }
    }
}
