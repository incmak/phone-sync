import Foundation
import Synchronization
import Testing
@testable import TwinotifyKit

private actor HandoffSignal {
    private var fired = false
    private var waiters: [CheckedContinuation<Void, Never>] = []
    func wait() async {
        if fired { return }
        await withCheckedContinuation { waiters.append($0) }
    }
    func fire() {
        fired = true
        let pending = waiters; waiters = []
        for waiter in pending { waiter.resume() }
    }
}

@Test(.timeLimit(.minutes(1))) func handoffWaitsForOldDrainerCleanupBeforeReleasingCandidate() async throws {
    let relayStarted = HandoffSignal(), cleanupStarted = HandoffSignal(), allowCleanup = HandoffSignal()
    let released = Mutex(false)
    let task = Task {
        let winner = try await routeHandoff(workers: [
            { @Sendable in
                await relayStarted.fire()
                do { try await Task.sleep(for: .seconds(60)) }
                catch {
                    await cleanupStarted.fire()
                    await allowCleanup.wait()
                    throw error
                }
                return 0
            },
            { @Sendable in await relayStarted.wait(); return 1 }
        ], close: { _ in })
        released.withLock { $0 = true }
        return winner
    }
    await cleanupStarted.wait()
    #expect(!released.withLock { $0 })
    await allowCleanup.fire()
    #expect(try await task.value == 1)
    #expect(released.withLock { $0 })
}

@Test(.timeLimit(.minutes(1))) func cancelledHandoffClosesWinnerAndLateCandidate() async throws {
    let loserStarted = HandoffSignal(), loserCancelled = HandoffSignal(), allowLateCandidate = HandoffSignal()
    let closed = Mutex<[Int]>([])
    let task = Task {
        try await routeHandoff(workers: [
            { @Sendable in await loserStarted.wait(); return 1 },
            { @Sendable in
                await loserStarted.fire()
                do { try await Task.sleep(for: .seconds(60)) }
                catch { await loserCancelled.fire(); await allowLateCandidate.wait() }
                return 2
            }
        ], close: { value in closed.withLock { $0.append(value) } })
    }
    await loserCancelled.wait()
    task.cancel()
    await allowLateCandidate.fire()
    await #expect(throws: CancellationError.self) { try await task.value }
    #expect(closed.withLock { $0.sorted() } == [1, 2])
}
