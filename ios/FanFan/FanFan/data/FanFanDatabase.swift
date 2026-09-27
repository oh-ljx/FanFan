import Foundation
import SQLite3

struct Note {
    let id: Int64
    let mediaId: String
    let text: String
    let createdAt: Int64
    /** 被回复的评论 id；nil 为顶层评论。回复最多两层（评论→回复→回复的回复）。 */
    let parentId: Int64?
}

struct FlipSessionRow {
    var currentId: String?
    var forwardIds: [String]
    var roundComplete: Bool
    var updatedAt: Int64
}

/** Transactionally loaded boot state; the snapshot row can be absent on first launch. */
struct StoredFlipSessionState {
    let session: FlipSessionRow?
    let seenIds: [String]
    let remainingIds: [String]
    let visitStack: [String]
}

enum DatabaseError: Error {
    case open(String)
    case prepare(String)
    case step(String)
}

/**
 * SQLite 访问层（对应安卓版 Room 数据库，v5 结构）。
 * 所有方法都必须从 LibraryRepository 的串行队列调用。
 */
final class FanFanDatabase {
    private var handle: OpaquePointer?

    init(url: URL) throws {
        try FileManager.default.createDirectory(
            at: url.deletingLastPathComponent(),
            withIntermediateDirectories: true,
        )
        if sqlite3_open(url.path, &handle) != SQLITE_OK {
            throw DatabaseError.open(String(cString: sqlite3_errmsg(handle)))
        }
        try exec("PRAGMA journal_mode = WAL")
        try exec("PRAGMA synchronous = NORMAL")
        try createTables()
    }

    deinit {
        sqlite3_close(handle)
    }

    private func createTables() throws {
        try exec("""
            CREATE TABLE IF NOT EXISTS favorites(
                mediaId TEXT PRIMARY KEY NOT NULL,
                likedAt INTEGER NOT NULL
            )
            """)
        try exec("""
            CREATE TABLE IF NOT EXISTS notes(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                mediaId TEXT NOT NULL,
                text TEXT NOT NULL,
                createdAt INTEGER NOT NULL,
                parentId INTEGER
            )
            """)
        // 旧版应用内回收站已移除；升级时清掉遗留标记，让照片重新正常显示。
        try exec("DROP TABLE IF EXISTS trash")
        try exec("""
            CREATE TABLE IF NOT EXISTS seen(
                mediaId TEXT PRIMARY KEY NOT NULL,
                seenAt INTEGER NOT NULL
            )
            """)
        try exec("""
            CREATE TABLE IF NOT EXISTS hidden_album(
                albumId TEXT PRIMARY KEY NOT NULL,
                hiddenAt INTEGER NOT NULL
            )
            """)
        try exec("""
            CREATE TABLE IF NOT EXISTS flip_session(
                sessionKey INTEGER PRIMARY KEY NOT NULL,
                currentId TEXT,
                forwardIds TEXT NOT NULL,
                roundComplete INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL
            )
            """)
        try exec("""
            CREATE TABLE IF NOT EXISTS flip_remaining(
                position INTEGER PRIMARY KEY NOT NULL,
                mediaId TEXT NOT NULL UNIQUE
            )
            """)
        try exec("""
            CREATE TABLE IF NOT EXISTS flip_visit(
                position INTEGER PRIMARY KEY NOT NULL,
                mediaId TEXT NOT NULL
            )
            """)
    }

    // MARK: - SQLite helpers

    private func exec(_ sql: String) throws {
        if sqlite3_exec(handle, sql, nil, nil, nil) != SQLITE_OK {
            throw DatabaseError.step("\(String(cString: sqlite3_errmsg(handle))) — \(sql)")
        }
    }

    private func prepare(_ sql: String) throws -> OpaquePointer {
        var stmt: OpaquePointer?
        guard sqlite3_prepare_v2(handle, sql, -1, &stmt, nil) == SQLITE_OK, let stmt else {
            throw DatabaseError.prepare(String(cString: sqlite3_errmsg(handle)))
        }
        return stmt
    }

    private func bind(_ stmt: OpaquePointer, _ index: Int32, _ value: String) {
        sqlite3_bind_text(stmt, index, value, -1, unsafeBitCast(-1, to: sqlite3_destructor_type.self))
    }

    private func bind(_ stmt: OpaquePointer, _ index: Int32, _ value: Int64) {
        sqlite3_bind_int64(stmt, index, value)
    }

    private func bind(_ stmt: OpaquePointer, _ index: Int32, _ value: Int) {
        sqlite3_bind_int64(stmt, index, Int64(value))
    }

    private func bindOptional(_ stmt: OpaquePointer, _ index: Int32, _ value: String?) {
        if let value { bind(stmt, index, value) } else { sqlite3_bind_null(stmt, index) }
    }

    private func bindOptional(_ stmt: OpaquePointer, _ index: Int32, _ value: Int64?) {
        if let value { bind(stmt, index, value) } else { sqlite3_bind_null(stmt, index) }
    }

    private func run(_ sql: String, _ binders: (OpaquePointer) -> Void = { _ in }) throws {
        let stmt = try prepare(sql)
        defer { sqlite3_finalize(stmt) }
        binders(stmt)
        let rc = sqlite3_step(stmt)
        guard rc == SQLITE_DONE else {
            throw DatabaseError.step(String(cString: sqlite3_errmsg(handle)))
        }
    }

    private func query<T>(_ sql: String, _ binders: (OpaquePointer) -> Void = { _ in },
                          _ map: (OpaquePointer) -> T) throws -> [T] {
        let stmt = try prepare(sql)
        defer { sqlite3_finalize(stmt) }
        binders(stmt)
        var rows: [T] = []
        while true {
            let rc = sqlite3_step(stmt)
            if rc == SQLITE_ROW {
                rows.append(map(stmt))
            } else if rc == SQLITE_DONE {
                break
            } else {
                throw DatabaseError.step(String(cString: sqlite3_errmsg(handle)))
            }
        }
        return rows
    }

    private func string(_ stmt: OpaquePointer, _ index: Int32) -> String {
        String(cString: sqlite3_column_text(stmt, index))
    }

    private func optionalString(_ stmt: OpaquePointer, _ index: Int32) -> String? {
        sqlite3_column_type(stmt, index) == SQLITE_NULL
            ? nil
            : String(cString: sqlite3_column_text(stmt, index))
    }

    private func withTransaction<T>(_ body: () throws -> T) throws -> T {
        try exec("BEGIN IMMEDIATE")
        do {
            let result = try body()
            try exec("COMMIT")
            return result
        } catch {
            try? exec("ROLLBACK")
            throw error
        }
    }

    // MARK: - Favorites

    func favoriteIds() throws -> [String] {
        try query("SELECT mediaId FROM favorites") { string($0, 0) }
    }

    func addFavorite(_ mediaId: String, likedAt: Int64) throws {
        try run("INSERT OR REPLACE INTO favorites(mediaId, likedAt) VALUES(?, ?)") {
            bind($0, 1, mediaId); bind($0, 2, likedAt)
        }
    }

    func removeFavorite(_ mediaId: String) throws {
        try run("DELETE FROM favorites WHERE mediaId = ?") { bind($0, 1, mediaId) }
    }

    // MARK: - Notes

    func notesFor(_ mediaId: String) throws -> [Note] {
        try query(
            "SELECT id, mediaId, text, createdAt, parentId FROM notes WHERE mediaId = ? ORDER BY createdAt ASC",
            { bind($0, 1, mediaId) },
        ) { stmt in
            Note(
                id: sqlite3_column_int64(stmt, 0),
                mediaId: string(stmt, 1),
                text: string(stmt, 2),
                createdAt: sqlite3_column_int64(stmt, 3),
                parentId: sqlite3_column_type(stmt, 4) == SQLITE_NULL
                    ? nil : sqlite3_column_int64(stmt, 4),
            )
        }
    }

    func addNote(mediaId: String, text: String, createdAt: Int64, parentId: Int64?) throws {
        try run("INSERT INTO notes(mediaId, text, createdAt, parentId) VALUES(?, ?, ?, ?)") {
            bind($0, 1, mediaId); bind($0, 2, text); bind($0, 3, createdAt); bindOptional($0, 4, parentId)
        }
    }

    /** 删除评论并级联删掉它的回复；回复最多两层，一条语句覆盖到第三层 id。 */
    func removeNote(_ id: Int64) throws {
        try run("""
            DELETE FROM notes WHERE id = ? OR parentId = ?
            OR parentId IN (SELECT id FROM notes WHERE parentId = ?)
            """) {
            bind($0, 1, id); bind($0, 2, id); bind($0, 3, id)
        }
    }

    func noteCounts() throws -> [String: Int] {
        let rows = try query("SELECT mediaId, COUNT(*) FROM notes GROUP BY mediaId") {
            (string($0, 0), Int(sqlite3_column_int64($0, 1)))
        }
        return Dictionary(uniqueKeysWithValues: rows)
    }

    // MARK: - Hidden albums

    func hiddenAlbumIds() throws -> [String] {
        try query("SELECT albumId FROM hidden_album") { string($0, 0) }
    }

    func hideAlbum(_ albumId: String, hiddenAt: Int64) throws {
        try run("INSERT OR REPLACE INTO hidden_album(albumId, hiddenAt) VALUES(?, ?)") {
            bind($0, 1, albumId); bind($0, 2, hiddenAt)
        }
    }

    func unhideAlbum(_ albumId: String) throws {
        try run("DELETE FROM hidden_album WHERE albumId = ?") { bind($0, 1, albumId) }
    }

    // MARK: - Seen

    func seenIds() throws -> [String] {
        try query("SELECT mediaId FROM seen") { string($0, 0) }
    }

    private func addSeen(_ mediaId: String, seenAt: Int64) throws {
        try run("INSERT OR REPLACE INTO seen(mediaId, seenAt) VALUES(?, ?)") {
            bind($0, 1, mediaId); bind($0, 2, seenAt)
        }
    }

    // MARK: - Delete media records

    /** 删除媒体后清掉喜欢、评论和已看记录。 */
    func deleteMediaRecords(_ ids: [String]) throws {
        guard !ids.isEmpty else { return }
        try withTransaction {
            for id in ids {
                try run("DELETE FROM favorites WHERE mediaId = ?") { bind($0, 1, id) }
                try run("DELETE FROM notes WHERE mediaId = ?") { bind($0, 1, id) }
                try run("DELETE FROM seen WHERE mediaId = ?") { bind($0, 1, id) }
            }
        }
    }

    // MARK: - Flip session

    func loadFlipSessionState() throws -> StoredFlipSessionState {
        try withTransaction {
            let session = try query(
                "SELECT currentId, forwardIds, roundComplete, updatedAt FROM flip_session WHERE sessionKey = 0 LIMIT 1",
            ) { stmt in
                FlipSessionRow(
                    currentId: optionalString(stmt, 0),
                    forwardIds: Self.decodeIdList(string(stmt, 1)),
                    roundComplete: sqlite3_column_int64(stmt, 2) != 0,
                    updatedAt: sqlite3_column_int64(stmt, 3),
                )
            }.first
            let seen = try seenIds()
            let remaining = try query(
                "SELECT mediaId FROM flip_remaining ORDER BY position ASC",
            ) { string($0, 0) }
            let visits = try query(
                "SELECT mediaId FROM flip_visit ORDER BY position ASC",
            ) { string($0, 0) }
            return StoredFlipSessionState(
                session: session,
                seenIds: seen,
                remainingIds: remaining,
                visitStack: visits,
            )
        }
    }

    private func upsertFlipSession(_ row: FlipSessionRow) throws {
        try run("""
            INSERT OR REPLACE INTO flip_session(sessionKey, currentId, forwardIds, roundComplete, updatedAt)
            VALUES(0, ?, ?, ?, ?)
            """) {
            bindOptional($0, 1, row.currentId)
            bind($0, 2, Self.encodeIdList(row.forwardIds))
            bind($0, 3, row.roundComplete ? 1 : 0)
            bind($0, 4, row.updatedAt)
        }
    }

    /**
     * A normal swipe is a bounded transaction: one seen upsert, one deck delete, one metadata
     * upsert and at most one visit-stack insert/delete. It never rewrites the full deck.
     */
    func applyFlipSessionIncrement(
        session: FlipSessionRow,
        seen: String?,
        removeRemainingId: String?,
        pushVisitId: String?,
        popVisit: Bool,
        maxVisitCount: Int,
        seenAt: Int64,
    ) throws {
        try withTransaction {
            if let seen { try addSeen(seen, seenAt: seenAt) }
            if let removeRemainingId {
                try run("DELETE FROM flip_remaining WHERE mediaId = ?") { bind($0, 1, removeRemainingId) }
            }
            if popVisit {
                try run("DELETE FROM flip_visit WHERE position = (SELECT MAX(position) FROM flip_visit)")
            } else if let pushVisitId {
                let next = try query(
                    "SELECT COALESCE(MAX(position), -1) + 1 FROM flip_visit",
                ) { sqlite3_column_int64($0, 0) }.first ?? 0
                try run("INSERT OR REPLACE INTO flip_visit(position, mediaId) VALUES(?, ?)") {
                    bind($0, 1, next); bind($0, 2, pushVisitId)
                }
                let count = try query("SELECT COUNT(*) FROM flip_visit") {
                    Int(sqlite3_column_int64($0, 0))
                }.first ?? 0
                let overflow = count - maxVisitCount
                if overflow > 0 {
                    try run("""
                        DELETE FROM flip_visit WHERE position IN
                        (SELECT position FROM flip_visit ORDER BY position ASC LIMIT ?)
                        """) { bind($0, 1, overflow) }
                }
            }
            try upsertFlipSession(session)
        }
    }

    /** Initialization/reconcile/restart replace the normalized ordered state atomically. */
    func replaceFlipSessionState(
        session: FlipSessionRow,
        remaining: [String],
        visits: [String],
        replaceSeen: Bool,
        seen: [String],
        shown: String?,
        now: Int64,
    ) throws {
        try withTransaction {
            if replaceSeen {
                try exec("DELETE FROM seen")
                for id in seen { try addSeen(id, seenAt: now) }
            } else if let shown {
                try addSeen(shown, seenAt: now)
            }
            try exec("DELETE FROM flip_remaining")
            try exec("DELETE FROM flip_visit")
            for (index, id) in remaining.enumerated() {
                try run("INSERT OR REPLACE INTO flip_remaining(position, mediaId) VALUES(?, ?)") {
                    bind($0, 1, index); bind($0, 2, id)
                }
            }
            for (index, id) in visits.enumerated() {
                try run("INSERT OR REPLACE INTO flip_visit(position, mediaId) VALUES(?, ?)") {
                    bind($0, 1, index); bind($0, 2, id)
                }
            }
            try upsertFlipSession(session)
        }
    }

    /** Tolerant codec: malformed tokens are ignored so a damaged row can still be reconciled. */
    static func encodeIdList(_ ids: [String]) -> String { ids.joined(separator: ",") }

    static func decodeIdList(_ encoded: String) -> [String] {
        encoded.isEmpty ? [] : encoded.split(separator: ",").map(String.init)
    }
}
