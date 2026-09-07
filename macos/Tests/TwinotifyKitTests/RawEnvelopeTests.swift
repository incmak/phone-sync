import Foundation
import Testing
@testable import TwinotifyKit

@Test func preservesEnvelopeSpelling() throws {
    let envelope = Data(#"{ "ciphertext":"a\"}b", "origin_device":"dev-\u0061", "v":2 }"#.utf8)
    let frame = try RawEnvelope.put(envelope: envelope)
    #expect(try RawEnvelope.extract(from: frame) == envelope)
    let canonical = Data(#"{"ciphertext":"a\"}b","origin_device":"dev-a","v":2}"#.utf8)
    #expect(RawEnvelope.digest(envelope) != RawEnvelope.digest(canonical))
}

@Test func rejectsAmbiguityAndMalformedFrames() {
    for raw in [
        #"{"envelope":{},"envel\u006fpe":{}}"#,
        #"{"envelope":{"v":2,"v":1}}"#,
        #"{"envelope":{},}"#,
        #"{"envelope":{} } junk"#,
        #"{"envelope":"{}"}"#,
        #"{"envelope":{"s":"\q"}}"#,
        #"{"envelope":{"n":01}}"#,
        #"{"envelope":{"a":[1,]}}"#,
        #"{"missing":{}}"#
    ] {
        #expect(throws: FrameError.self) { try RawEnvelope.extract(from: Data(raw.utf8)) }
    }
    #expect(throws: FrameError.self) {
        try RawEnvelope.extract(from: Data(repeating: 32, count: RawEnvelope.maximumFrameBytes + 1))
    }
    let deep = "{\"envelope\":" + String(repeating: "[", count: 66) + "0" + String(repeating: "]", count: 66) + "}"
    #expect(throws: FrameError.self) { try RawEnvelope.extract(from: Data(deep.utf8)) }
}
