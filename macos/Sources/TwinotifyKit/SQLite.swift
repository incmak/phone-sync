import CSQLite
import Foundation

enum SQLValue: Equatable, Sendable {
    case integer(Int64), text(String), blob(Data), null
}

public enum StorageError: Error, Equatable {
    case database(Int32), unsupportedVersion(Int64), repairRequired, nonceExhausted, capacityExceeded
}

/// Owned by DurableStore's actor. FULLMUTEX additionally protects initialization
/// and teardown; no connection or prepared statement escapes this module.
final class SQLiteConnection: @unchecked Sendable {
    private var handle: OpaquePointer?
    private let transient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)

    init(path: String) throws {
        let result = sqlite3_open_v2(path, &handle, SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE | SQLITE_OPEN_FULLMUTEX, nil)
        guard result == SQLITE_OK else {
            sqlite3_close(handle)
            handle = nil
            throw StorageError.database(result)
        }
        sqlite3_busy_timeout(handle, 5_000)
        do {
            try execute("PRAGMA foreign_keys=ON")
            try execute("PRAGMA journal_mode=WAL")
            try execute("PRAGMA synchronous=FULL")
        } catch {
            sqlite3_close(handle)
            handle = nil
            throw error
        }
    }

    deinit { sqlite3_close(handle) }

    @discardableResult
    func execute(_ sql: String, _ bindings: [SQLValue] = []) throws -> [[String: SQLValue]] {
        var statement: OpaquePointer?
        let prepare = sqlite3_prepare_v2(handle, sql, -1, &statement, nil)
        guard prepare == SQLITE_OK else { throw StorageError.database(prepare) }
        defer { sqlite3_finalize(statement) }
        guard sqlite3_bind_parameter_count(statement) == Int32(bindings.count) else { throw StorageError.repairRequired }
        for (offset, value) in bindings.enumerated() {
            let index = Int32(offset + 1)
            let result: Int32
            switch value {
            case .integer(let number): result = sqlite3_bind_int64(statement, index, number)
            case .text(let string):
                result = string.withCString { sqlite3_bind_text(statement, index, $0, Int32(string.utf8.count), transient) }
            case .blob(let data):
                if data.isEmpty { result = sqlite3_bind_zeroblob(statement, index, 0) }
                else { result = data.withUnsafeBytes { sqlite3_bind_blob(statement, index, $0.baseAddress, Int32(data.count), transient) } }
            case .null: result = sqlite3_bind_null(statement, index)
            }
            guard result == SQLITE_OK else { throw StorageError.database(result) }
        }
        var rows: [[String: SQLValue]] = []
        while true {
            let result = sqlite3_step(statement)
            if result == SQLITE_DONE { return rows }
            guard result == SQLITE_ROW else { throw StorageError.database(result) }
            var row: [String: SQLValue] = [:]
            for index in 0..<sqlite3_column_count(statement) {
                let name = String(cString: sqlite3_column_name(statement, index))
                switch sqlite3_column_type(statement, index) {
                case SQLITE_INTEGER: row[name] = .integer(sqlite3_column_int64(statement, index))
                case SQLITE_TEXT:
                    let count = Int(sqlite3_column_bytes(statement, index))
                    let bytes = sqlite3_column_text(statement, index)!
                    row[name] = .text(String(decoding: UnsafeBufferPointer(start: bytes, count: count), as: UTF8.self))
                case SQLITE_BLOB:
                    let count = Int(sqlite3_column_bytes(statement, index))
                    row[name] = .blob(count == 0 ? Data() : Data(bytes: sqlite3_column_blob(statement, index)!, count: count))
                case SQLITE_NULL: row[name] = .null
                default: throw StorageError.repairRequired
                }
            }
            rows.append(row)
        }
    }

    func transaction<T>(_ operation: () throws -> T) throws -> T {
        try execute("BEGIN IMMEDIATE")
        do {
            let result = try operation()
            try execute("COMMIT")
            return result
        } catch {
            _ = try? execute("ROLLBACK")
            throw error
        }
    }
}
