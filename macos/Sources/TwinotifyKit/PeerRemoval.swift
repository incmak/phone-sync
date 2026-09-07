import Foundation

public enum PeerRemoval {
    /// Caller has stopped and joined the link's sessions. Local cleanup can finish
    /// offline, while the disabled peer record retains only remote cleanup credentials.
    public static func revoke(peer: PeerLink, store: DurableStore, allowDebugLoopback: Bool = false) async throws {
        guard let current = try await store.peers().first(where: { $0.id == peer.id }), current.lifecycle == .removing else {
            throw DeliveryStoreError.invalidTransition
        }
        let endpoint = try RelayEndpoint(current.relayURL, allowDebugLoopback: allowDebugLoopback)
        var request = URLRequest(url: try endpoint.url("pair/revoke"))
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONValue.object(["pair_id": .string(current.pairID)]).encoded()
        request.setValue("Bearer " + (try await store.mintJWT(pairID: current.pairID, nowSeconds: Int64(Date().timeIntervalSince1970))),
                         forHTTPHeaderField: "Authorization")
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 15; config.timeoutIntervalForResource = 20
        let session = URLSession(configuration: config, delegate: RelayHTTPDelegate(), delegateQueue: nil)
        defer { session.invalidateAndCancel() }
        let (_, response) = try await session.data(for: request)
        guard let status = (response as? HTTPURLResponse)?.statusCode, status == 204 || status == 401 else {
            throw PairingError.invalidResponse
        }
        // 401 is terminal only because removal intent was persisted before this request.
        try await store.finishRemoval(current.id)
    }
}
