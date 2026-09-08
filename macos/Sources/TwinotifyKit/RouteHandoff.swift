import Foundation

/// Discovery may finish before the current drainer stops. Never release its
/// candidate to the next drainer until every losing worker has joined.
func routeHandoff<Candidate: Sendable>(
    workers: [@Sendable () async throws -> Candidate],
    close: @escaping @Sendable (Candidate) -> Void
) async throws -> Candidate {
    precondition(!workers.isEmpty)
    return try await withThrowingTaskGroup(of: Candidate.self) { group in
        for worker in workers { group.addTask(operation: worker) }
        do {
            let winner = try await group.next()!
            group.cancelAll()
            while !group.isEmpty {
                if let abandoned = try? await group.next() { close(abandoned) }
            }
            if Task.isCancelled { close(winner); throw CancellationError() }
            return winner
        } catch {
            group.cancelAll()
            while !group.isEmpty {
                if let abandoned = try? await group.next() { close(abandoned) }
            }
            throw error
        }
    }
}
