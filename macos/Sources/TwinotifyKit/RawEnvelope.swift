import CryptoKit
import Foundation

public enum FrameError: Error, Equatable {
    case oversized, malformed, duplicateKey, missingEnvelope, invalidEnvelope
}

/// A bounded JSON scanner retains the original object slice before Foundation
/// decodes semantic fields. Duplicate decoded keys are rejected at every depth.
public enum RawEnvelope {
    public static let maximumFrameBytes = (1 << 20) + (4 << 10)

    public static func validateObject(_ data: Data, maximumBytes: Int = 1 << 20) throws {
        guard data.count <= maximumBytes, maximumBytes <= maximumFrameBytes else { throw FrameError.oversized }
        var scanner = Scanner(bytes: Array(data))
        _ = try scanner.object(depth: 0)
        scanner.whitespace()
        guard scanner.index == scanner.bytes.count else { throw FrameError.malformed }
    }

    public static func extract(from frame: Data) throws -> Data {
        guard frame.count <= maximumFrameBytes else { throw FrameError.oversized }
        var scanner = Scanner(bytes: Array(frame))
        let members = try scanner.object(depth: 0)
        scanner.whitespace()
        guard scanner.index == scanner.bytes.count else { throw FrameError.malformed }
        guard let range = members["envelope"] else { throw FrameError.missingEnvelope }
        guard scanner.bytes[range.lowerBound] == 123, range.count <= 1 << 20 else {
            throw FrameError.invalidEnvelope
        }
        return Data(scanner.bytes[range])
    }

    public static func put(envelope: Data) throws -> Data {
        guard envelope.count <= 1 << 20 else { throw FrameError.oversized }
        var scanner = Scanner(bytes: Array(envelope))
        _ = try scanner.object(depth: 0)
        scanner.whitespace()
        guard scanner.index == scanner.bytes.count else { throw FrameError.malformed }
        // Exclude whitespace outside the object; the custody digest covers the object itself.
        guard envelope.first == 123, envelope.last == 125 else { throw FrameError.invalidEnvelope }
        return Data(#"{"v":2,"type":"relay.put","envelope":"#.utf8) + envelope + Data("}".utf8)
    }

    public static func digest(_ envelope: Data) -> String {
        SHA256.hash(data: envelope).map { String(format: "%02x", $0) }.joined()
    }
}

private struct Scanner {
    let bytes: [UInt8]
    var index = 0

    mutating func whitespace() {
        while index < bytes.count, [9, 10, 13, 32].contains(bytes[index]) { index += 1 }
    }

    mutating func consume(_ byte: UInt8) throws {
        whitespace()
        guard index < bytes.count, bytes[index] == byte else { throw FrameError.malformed }
        index += 1
    }

    mutating func string() throws -> String {
        whitespace()
        let start = index
        try consume(34)
        while index < bytes.count {
            let byte = bytes[index]
            index += 1
            if byte == 34 {
                guard let string = try? JSONDecoder().decode(String.self, from: Data(bytes[start..<index])) else {
                    throw FrameError.malformed
                }
                return string
            }
            if byte == 92 {
                guard index < bytes.count else { throw FrameError.malformed }
                index += 1
            } else if byte < 32 { throw FrameError.malformed }
        }
        throw FrameError.malformed
    }

    mutating func object(depth: Int) throws -> [String: Range<Int>] {
        guard depth <= 64 else { throw FrameError.malformed }
        try consume(123)
        whitespace()
        var members: [String: Range<Int>] = [:]
        if index < bytes.count, bytes[index] == 125 { index += 1; return members }
        while true {
            let key = try string()
            guard members[key] == nil else { throw FrameError.duplicateKey }
            try consume(58)
            whitespace()
            let start = index
            try value(depth: depth + 1)
            members[key] = start..<index
            whitespace()
            guard index < bytes.count else { throw FrameError.malformed }
            if bytes[index] == 125 { index += 1; return members }
            try consume(44)
        }
    }

    mutating func value(depth: Int) throws {
        guard depth <= 64 else { throw FrameError.malformed }
        whitespace()
        guard index < bytes.count else { throw FrameError.malformed }
        switch bytes[index] {
        case 123: _ = try object(depth: depth)
        case 34: _ = try string()
        case 91:
            index += 1
            whitespace()
            if index < bytes.count, bytes[index] == 93 { index += 1; return }
            while true {
                try value(depth: depth + 1)
                whitespace()
                guard index < bytes.count else { throw FrameError.malformed }
                if bytes[index] == 93 { index += 1; return }
                try consume(44)
            }
        default:
            let start = index
            while index < bytes.count, ![9, 10, 13, 32, 44, 93, 125].contains(bytes[index]) { index += 1 }
            guard index > start else { throw FrameError.malformed }
            let token = Data(bytes[start..<index])
            // Foundation validates JSON numbers, booleans and null without changing their bytes.
            guard (try? JSONSerialization.jsonObject(with: token, options: .fragmentsAllowed)) != nil else {
                throw FrameError.malformed
            }
        }
    }
}
