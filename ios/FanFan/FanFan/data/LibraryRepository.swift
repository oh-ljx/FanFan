import Foundation

struct FlipSessionBootState {
    let seenIds: Set<String>
    let snapshot: FlipSessionSnapshot?
}

/** 喜欢 / 留言 / 已看记录的访问入口。所有数据库读写都在一条串行队列上按序执行。 */
final class LibraryRepository {
    static let shared = LibraryRepository()

    private let queue = DispatchQueue(label: "com.ohljx.fanfan.db")
    private let db: FanFanDatabase

    private init() {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first!
        do {
            db = try FanFanDatabase(url: dir.appendingPathComponent("fanfan.db"))
        } catch {
            fatalError("无法打开本地数据库: \(error)")
        }
    }

    // MARK: - 调度

    private func read<T: Sendable>(_ work: @escaping (FanFanDatabase) throws -> T) async throws -> T {
        try await withCheckedThrowingContinuation { cont in
            queue.async {
                cont.resume(with: Result { try work(self.db) })
            }
        }
    }

    /** 不关心结果的写入；失败只记录日志。 */
    private func write(_ work: @escaping (FanFanDatabase) throws -> Void) {
        queue.async {
            do {
                try work(self.db)
            } catch {
                NSLog("FanFan DB write failed: \(error)")
            }
        }
    }

    // MARK: - Favorites

    func favoriteIds() async throws -> Set<String> {
        Set(try await read { try $0.favoriteIds() })
    }

    func addFavorite(_ mediaId: String) {
        write { try $0.addFavorite(mediaId, likedAt: nowMs()) }
    }

    func removeFavorite(_ mediaId: String) {
        write { try $0.removeFavorite(mediaId) }
    }

    // MARK: - Notes

    func notesFor(_ mediaId: String) async throws -> [Note] {
        try await read { try $0.notesFor(mediaId) }
    }

    func addNote(_ mediaId: String, text: String, parentId: Int64?) {
        write { try $0.addNote(mediaId: mediaId, text: text, createdAt: nowMs(), parentId: parentId) }
    }

    func removeNote(_ id: Int64) {
        write { try $0.removeNote(id) }
    }

    func noteCounts() async throws -> [String: Int] {
        try await read { try $0.noteCounts() }
    }

    /** 删除媒体后清掉本地相关记录。 */
    func deleteMediaRecords(_ ids: [String]) async throws {
        try await read { db in try db.deleteMediaRecords(ids) }
    }

    // MARK: - Hidden albums

    func hiddenAlbumIds() async throws -> Set<String> {
        Set(try await read { try $0.hiddenAlbumIds() })
    }

    func hideAlbum(_ albumId: String) {
        write { try $0.hideAlbum(albumId, hiddenAt: nowMs()) }
    }

    func unhideAlbum(_ albumId: String) {
        write { try $0.unhideAlbum(albumId) }
    }

    // MARK: - Flip session

    /**
     * Load seen IDs and the durable cursor/deck in one read.
     * 串行队列保证读发生在所有已入队的写之后（对应安卓版的 Barrier）。
     */
    func flipSessionBootState() async throws -> FlipSessionBootState {
        try await read { db in
            let stored = try db.loadFlipSessionState()
            return FlipSessionBootState(
                seenIds: Set(stored.seenIds),
                snapshot: stored.session.map {
                    FlipSessionSnapshot(
                        currentId: $0.currentId,
                        remainingIds: stored.remainingIds,
                        visitStack: stored.visitStack,
                        roundComplete: $0.roundComplete,
                        forwardStack: $0.forwardIds,
                    )
                },
            )
        }
    }

    /**
     * Persist one session mutation. Ordinary swipes use bounded normalized-table updates; full deck
     * replacement is reserved for initialization, reconciliation and round restart.
     * 非挂起的 UI 入口：更新按入队顺序由串行队列依次落库。
     */
    func enqueueFlipSessionUpdate(_ update: FlipSessionUpdate) {
        queue.async {
            // 会话写入失败时按 50ms 起步、指数退避重试，绝不让较新的游标盖过失败的旧事务。
            var retryDelayMs: UInt64 = 50
            for attempt in 0..<6 {
                do {
                    try self.applyFlipSessionUpdate(update)
                    return
                } catch {
                    NSLog("FanFan session write attempt \(attempt + 1) failed: \(error)")
                    if attempt < 5 {
                        Thread.sleep(forTimeInterval: TimeInterval(retryDelayMs) / 1000)
                        retryDelayMs = min(retryDelayMs * 2, 800)
                    }
                }
            }
        }
    }

    private func applyFlipSessionUpdate(_ update: FlipSessionUpdate) throws {
        let now = nowMs()
        let row = FlipSessionRow(
            currentId: update.currentId,
            forwardIds: update.forwardIds,
            roundComplete: update.roundComplete,
            updatedAt: now,
        )
        switch update.persistence {
        case .replace:
            let snapshot = update.snapshot
            try db.replaceFlipSessionState(
                session: row,
                remaining: snapshot.remainingIds,
                visits: snapshot.visitStack,
                replaceSeen: update.resetSeenTo != nil,
                seen: Array(update.resetSeenTo ?? []),
                shown: update.shownId,
                now: now,
            )
        case .incremental(let visit):
            var pushId: String? = nil
            var pop = false
            switch visit {
            case .none: break
            case .push(let id): pushId = id
            case .pop: pop = true
            }
            try db.applyFlipSessionIncrement(
                session: row,
                seen: update.shownId,
                removeRemainingId: update.shownId,
                pushVisitId: pushId,
                popVisit: pop,
                maxVisitCount: FLIP_VISIT_STACK_LIMIT,
                seenAt: now,
            )
        }
    }
}

func nowMs() -> Int64 {
    Int64(Date().timeIntervalSince1970 * 1000)
}
