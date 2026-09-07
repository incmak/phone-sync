import Foundation
import Testing
@testable import TwinotifyKit

@Test func pingIgnoresCloseErrorAfterPongAndDuplicateErrors() async throws {
    try await waitForWebSocketPing { callback in
        callback(nil)
        callback(URLError(.networkConnectionLost))
        callback(nil)
    }
    await #expect(throws: URLError.self) {
        try await waitForWebSocketPing { callback in
            callback(URLError(.networkConnectionLost))
            callback(URLError(.cancelled))
            callback(nil)
        }
    }
}

@Test func cancelledPingDoesNotNeedASocketCallback() async {
    let started = AsyncStream<Void>.makeStream()
    let task = Task {
        try await waitForWebSocketPing { _ in started.continuation.yield(()) }
    }
    for await _ in started.stream { break }
    task.cancel()
    let result = await task.result
    switch result {
    case .success: Issue.record("Cancelled ping unexpectedly succeeded")
    case .failure(let error): #expect(error is CancellationError)
    }
    started.continuation.finish()
}

@Test func racingPingCallbacksSettleOnce() async throws {
    try await waitForWebSocketPing { callback in
        DispatchQueue.concurrentPerform(iterations: 50) { _ in callback(nil) }
    }
}
