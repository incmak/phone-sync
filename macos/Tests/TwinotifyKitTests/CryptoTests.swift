import Foundation
import Testing
@testable import TwinotifyKit

private func vector() throws -> [String: String] {
    let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
    return try JSONDecoder().decode([String: String].self, from: Data(contentsOf:
        root.appendingPathComponent("proto/crypto/known-answer-v1.json")))
}

private func bytes(_ hex: String) -> Data {
    Data(stride(from: 0, to: hex.count, by: 2).map { offset in
        let start = hex.index(hex.startIndex, offsetBy: offset)
        return UInt8(hex[start..<hex.index(start, offsetBy: 2)], radix: 16)!
    })
}

@Test func sharedKnownAnswers() throws {
    let v = try vector()
    func b(_ key: String) -> Data { bytes(v[key]!) }
    #expect(try WireCrypto.nonce(prefix: b("nonce_prefix"), counter: 1) == b("nonce"))
    #expect(try WireCrypto.seal(b("plaintext"), nonce: b("nonce"), peerPublicKey: b("b_box_public"),
                              secretKey: b("a_box_secret")) == b("ciphertext"))
    #expect(try WireCrypto.open(b("ciphertext"), nonce: b("nonce"), peerPublicKey: b("a_box_public"),
                              secretKey: b("b_box_secret")) == b("plaintext"))
    #expect(try WireCrypto.sign(b("plaintext"), secretKey: b("a_sign_secret")) == b("message_signature"))
    #expect(try WireCrypto.fingerprint(encryptionKey: b("a_box_public"), signingKey: b("a_sign_public")) == v["fingerprint"])
    let a = try PairingTranscript.initiator(token: v["token"]!, aEncryptionKey: b("a_box_public"),
                                           aSigningKey: b("a_sign_public"), bEncryptionKey: b("b_box_public"),
                                           bSigningKey: b("b_sign_public"))
    #expect(a == b("initiator_message"))
    let sigA = try WireCrypto.sign(a, secretKey: b("a_sign_secret"))
    #expect(sigA == b("initiator_signature"))
    let transcriptB = try PairingTranscript.responder(initiatorTranscript: a, initiatorSignature: sigA)
    #expect(transcriptB == b("responder_message"))
    #expect(try WireCrypto.sign(transcriptB, secretKey: b("b_sign_secret")) == b("responder_signature"))
    let notify = PairingTranscript.notify(token: v["token"]!, role: "B", deviceID: "dev-b")
    #expect(notify == b("notify_message"))
    #expect(try WireCrypto.sign(notify, secretKey: b("b_sign_secret")) == b("notify_signature"))
    #expect(WireCrypto.verify(b("responder_signature"), message: transcriptB, publicKey: b("b_sign_public")))
    #expect(!WireCrypto.verify(b("responder_signature"), message: a, publicKey: b("b_sign_public")))
    var tampered = b("ciphertext"); tampered[0] ^= 1
    #expect(throws: CryptoError.self) {
        try WireCrypto.open(tampered, nonce: b("nonce"), peerPublicKey: b("a_box_public"), secretKey: b("b_box_secret"))
    }
}

@Test func rejectsInvalidSizesAndZeroCounter() {
    #expect(throws: CryptoError.self) { try WireCrypto.nonce(prefix: Data(repeating: 0, count: 16), counter: 0) }
    #expect(throws: CryptoError.self) { try WireCrypto.sign(Data(), secretKey: Data(repeating: 0, count: 32)) }
}
