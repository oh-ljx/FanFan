import Combine
import Foundation

let FLIP_VISIT_STACK_LIMIT = 512

/**
 * A durable description of the current round.
 *
 * [remainingIds] is an already shuffled deck. Its first item is the next random target, so
 * restoring a process never reshuffles or starts from the newest photo again. [visitStack] and
 * [forwardStack] are ordered from oldest/farthest to newest/nearest, so their last items are the
 * next DOWN and replayed UP targets respectively.
 */
struct FlipSessionSnapshot {
    var currentId: String?
    var remainingIds: [String]
    var visitStack: [String]
    var roundComplete: Bool
    var forwardStack: [String]
}

enum VisitMutation {
    case none
    case push(String)
    case pop
}

/** Compact DB mutation used for the steady-state swipe path. */
enum FlipSessionPersistence {
    /** Replace the normalized deck/stack. Used only for initialization, restart and reconciliation. */
    case replace
    /** Update the singleton row, remove shownId from the deck and mutate the stack once. */
    case incremental(VisitMutation)
}

/**
 * One persistence event emitted after the in-memory state is fully updated.
 *
 * - `shownId` must be upserted into `seen` in the same transaction as the session.
 * - A non-nil `resetSeenTo` replaces the whole seen table in that same transaction.
 */
struct FlipSessionUpdate {
    let currentId: String?
    let roundComplete: Bool
    let shownId: String?
    let resetSeenTo: Set<String>?
    let persistence: FlipSessionPersistence
    /** Frozen when the update is created so the asynchronous writer cannot observe later swipes. */
    let forwardIds: [String]
    /** Lazily materialized full snapshot; steady-state swipes avoid the O(library size) list copy. */
    let snapshotProvider: () -> FlipSessionSnapshot

    var snapshot: FlipSessionSnapshot { snapshotProvider() }
}

/** Minimal media record used by the indexed session engine. */
struct FlipMediaRecord {
    let id: String
    let dateTaken: Int64
}

/** Local calendar day. */
private func localDayKey(_ timestampMs: Int64) -> Int64 {
    let offsetMs = Int64(TimeZone.current.secondsFromGMT(
        for: Date(timeIntervalSince1970: TimeInterval(timestampMs) / 1000),
    )) * 1000
    return floorDiv(timestampMs + offsetMs, 86_400_000)
}

private func floorDiv(_ a: Int64, _ b: Int64) -> Int64 {
    let q = a / b
    return (a % b != 0 && ((a < 0) != (b < 0))) ? q - 1 : q
}

/**
 * Indexed round engine. All steady-state lookups are O(1); rebuilding the indexes
 * happens only when the media set changes. Only touched from the main thread via FlipSession.
 */
final class FlipSessionState {
    private var orderedMedia: [FlipMediaRecord]
    private var recordById: [String: FlipMediaRecord] = [:]
    private var dayIdsByMediaId: [String: [String]] = [:]
    private var dayPositionByMediaId: [String: Int] = [:]

    private var seenIds: Set<String>
    private var remainingIds: [String] = []
    private var remainingSet: Set<String> = []
    private var visitStack: [String] = []
    private var forwardStack: [String] = []
    private var activeSeenCount = 0

    private(set) var currentId: String?
    private(set) var roundComplete = false

    var seenCount: Int { activeSeenCount }

    // 占位值；init 里会被真正的初始更新覆盖。声明默认值是为了满足 Swift 的初始化顺序检查。
    private(set) var initialUpdate = FlipSessionUpdate(
        currentId: nil,
        roundComplete: false,
        shownId: nil,
        resetSeenTo: nil,
        persistence: .replace,
        forwardIds: [],
        snapshotProvider: {
            FlipSessionSnapshot(
                currentId: nil, remainingIds: [], visitStack: [], roundComplete: false, forwardStack: [],
            )
        },
    )

    init(media: [FlipMediaRecord], initialSeen: Set<String> = [], snapshot: FlipSessionSnapshot? = nil) {
        orderedMedia = Self.normalize(media)
        seenIds = initialSeen
        rebuildIndexes()
        if let snapshot {
            restore(snapshot)
            // The restored deck is already stored in the DB. Only replace it when reconciliation
            // filtered stale rows or appended newly discovered media.
            if matches(snapshot) {
                initialUpdate = incrementalUpdate(shownId: currentId)
            } else {
                initialUpdate = replacementUpdate(shownId: currentId)
            }
        } else {
            startWithoutSnapshot()
            initialUpdate = replacementUpdate(shownId: currentId)
        }
    }

    func snapshot() -> FlipSessionSnapshot {
        FlipSessionSnapshot(
            currentId: currentId,
            remainingIds: remainingIds,
            visitStack: visitStack,
            roundComplete: roundComplete,
            forwardStack: forwardStack,
        )
    }

    func contains(_ id: String) -> Bool { recordById[id] != nil }

    func sameDayIds(_ id: String) -> [String] { dayIdsByMediaId[id] ?? [] }

    func targetFor(_ direction: FlipDirection) -> String? {
        switch direction {
        case .up: return forwardTarget() ?? remainingIds.first
        case .down: return visitStack.last { $0 != currentId && contains($0) }
        case .left: return dayNeighbor(+1)
        case .right: return dayNeighbor(-1)
        }
    }

    /** Returns nil when targetId is stale or is not the currently advertised target. */
    @discardableResult
    func commit(_ direction: FlipDirection, targetId: String) -> FlipSessionUpdate? {
        guard targetFor(direction) == targetId else { return nil }

        let previousId = currentId
        let replayingForward = direction == .up && forwardTarget() == targetId
        switch direction {
        case .down:
            while let last = visitStack.popLast() {
                if last == targetId { break }
            }
            if let previousId { pushForward(previousId) }
        case .up:
            if let previousId { pushVisit(previousId) }
            if replayingForward {
                while let last = forwardStack.popLast() {
                    if last == targetId { break }
                }
            } else {
                // Invalid/stale forward entries must not survive a newly generated branch.
                forwardStack.removeAll()
            }
        case .left, .right:
            // 同一天内的横向浏览只是平行翻看：不回看栈、不清前进栈，
            // 竖直方向（下一张/回看）的落点不受左右翻影响。
            break
        }

        currentId = targetId
        markShown(targetId)
        let visit: VisitMutation = switch direction {
        case .down: .pop
        case .up: previousId.map(VisitMutation.push) ?? .none
        case .left, .right: .none
        }
        return incrementalUpdate(shownId: targetId, visit: visit)
    }

    /**
     * Reconciles additions, removals and reordering without discarding the current round.
     * Existing deck order is preserved; newly discovered unseen IDs are shuffled once and appended.
     */
    func reconcile(_ newMedia: [FlipMediaRecord]) -> FlipSessionUpdate {
        let previousCurrent = currentId
        // 被删照片原属的同一天分组和位置，用于删除后优先落在当天的相邻照片上。
        let removedDayIds = previousCurrent.flatMap { dayIdsByMediaId[$0] }
        let removedDayIndex = previousCurrent.flatMap { dayPositionByMediaId[$0] }
        orderedMedia = Self.normalize(newMedia)
        rebuildIndexes()

        retainValidVisitStack()
        retainValidForwardStack()
        retainValidRemainingIds()

        var shownId: String? = nil
        if currentId == nil || !contains(currentId!) {
            currentId = sameDayNeighborFallback(removedDayIds, removedDayIndex)
                ?? forwardTarget()
                ?? remainingIds.first
                ?? popVisitFallback()
                ?? popForwardFallback()
                ?? orderedMedia.first?.id
            shownId = currentId
        }

        if let id = currentId {
            let wasSeen = seenIds.contains(id)
            markShown(id)
            if !wasSeen { shownId = id }
        }
        appendMissingUnseen()

        if orderedMedia.isEmpty {
            currentId = nil
            roundComplete = false
        } else if !remainingIds.isEmpty || forwardTarget() != nil {
            // A newly added/unseen item reopens an otherwise completed round.
            roundComplete = false
        }

        // Persist a corrected snapshot even when only metadata/order changed.
        return replacementUpdate(shownId: shownId == previousCurrent ? nil : shownId)
    }

    func roundFinished() -> Bool {
        !orderedMedia.isEmpty && remainingIds.isEmpty && forwardTarget() == nil
    }

    func completeRound() -> FlipSessionUpdate {
        if roundFinished() { roundComplete = true }
        return incrementalUpdate()
    }

    func restartRound() -> FlipSessionUpdate {
        roundComplete = false
        visitStack.removeAll()
        forwardStack.removeAll()
        seenIds.removeAll()

        let validCurrent = currentId.flatMap { contains($0) ? $0 : nil }
        let shuffled = orderedMedia.map(\.id).shuffled()
        currentId = validCurrent ?? shuffled.first
        remainingIds = shuffled.filter { $0 != currentId }
        remainingSet = Set(remainingIds)
        activeSeenCount = 0
        if let id = currentId { markShown(id) }

        let keep = currentId.map { Set([$0]) } ?? []
        return replacementUpdate(shownId: currentId, resetSeenTo: keep)
    }

    private func startWithoutSnapshot() {
        visitStack.removeAll()
        forwardStack.removeAll()
        remainingIds.removeAll()
        remainingSet.removeAll()

        let unseenDeck = orderedMedia.map(\.id).filter { !seenIds.contains($0) }.shuffled()

        if !unseenDeck.isEmpty {
            currentId = unseenDeck.first
            remainingIds = Array(unseenDeck.dropFirst())
            remainingSet = Set(remainingIds)
            markShown(unseenDeck[0])
            roundComplete = false
            return
        }

        // Old databases can already contain a completed seen set but no session row.
        currentId = orderedMedia.map(\.id).shuffled().first
        if let id = currentId { markShown(id) }
        roundComplete = !orderedMedia.isEmpty
    }

    private func restore(_ snapshot: FlipSessionSnapshot) {
        visitStack = snapshot.visitStack.filter { contains($0) }
        forwardStack = snapshot.forwardStack.filter { contains($0) }
        remainingIds = snapshot.remainingIds.filter { contains($0) && !seenIds.contains($0) }
        remainingSet = Set(remainingIds)

        currentId = snapshot.currentId.flatMap { contains($0) ? $0 : nil }
            ?? popVisitFallback()
            ?? popForwardFallback()
            ?? remainingIds.first
            ?? orderedMedia.first?.id

        if let id = currentId { markShown(id) }
        appendMissingUnseen()
        roundComplete = snapshot.roundComplete && roundFinished()
    }

    private func appendMissingUnseen() {
        let missing = orderedMedia.map(\.id)
            .filter { $0 != currentId && !seenIds.contains($0) && !remainingSet.contains($0) }
            .shuffled()
        for id in missing {
            remainingIds.append(id)
            remainingSet.insert(id)
        }
    }

    private func markShown(_ id: String) {
        guard contains(id) else { return }
        if !seenIds.contains(id) {
            seenIds.insert(id)
            activeSeenCount += 1
        }
        if remainingSet.remove(id) != nil {
            remainingIds.removeAll { $0 == id }
        }
    }

    private func dayNeighbor(_ offset: Int) -> String? {
        guard let id = currentId,
              let ids = dayIdsByMediaId[id],
              let index = dayPositionByMediaId[id] else { return nil }
        let target = index + offset
        return ids.indices.contains(target) ? ids[target] : nil
    }

    /**
     * 删除当前照片后的落点：优先同一天右侧（时间更晚）的一张，右侧没有再看左侧，
     * 都由近及远跳过已不存在的条目；当天没有了才走常规的下一张/历史兜底。
     */
    private func sameDayNeighborFallback(_ dayIds: [String]?, _ index: Int?) -> String? {
        guard let dayIds, let index else { return nil }
        if index + 1 < dayIds.count {
            for position in (index + 1)..<dayIds.count {
                if contains(dayIds[position]) { return dayIds[position] }
            }
        }
        if index - 1 >= 0 {
            for position in stride(from: index - 1, through: 0, by: -1) {
                if contains(dayIds[position]) { return dayIds[position] }
            }
        }
        return nil
    }

    private func pushVisit(_ id: String) {
        guard contains(id) else { return }
        visitStack.append(id)
        while visitStack.count > FLIP_VISIT_STACK_LIMIT { visitStack.removeFirst() }
    }

    private func pushForward(_ id: String) {
        guard contains(id) else { return }
        forwardStack.append(id)
        while forwardStack.count > FLIP_VISIT_STACK_LIMIT { forwardStack.removeFirst() }
    }

    private func forwardTarget() -> String? {
        forwardStack.last { $0 != currentId && contains($0) }
    }

    private func popVisitFallback() -> String? {
        while let id = visitStack.popLast() {
            if contains(id) && id != currentId { return id }
        }
        return nil
    }

    private func popForwardFallback() -> String? {
        while let id = forwardStack.popLast() {
            if contains(id) && id != currentId { return id }
        }
        return nil
    }

    private func retainValidVisitStack() {
        visitStack = Array(visitStack.filter { contains($0) }.suffix(FLIP_VISIT_STACK_LIMIT))
    }

    private func retainValidForwardStack() {
        forwardStack = Array(forwardStack.filter { contains($0) }.suffix(FLIP_VISIT_STACK_LIMIT))
    }

    private func retainValidRemainingIds() {
        remainingIds = remainingIds.filter {
            contains($0) && $0 != currentId && !seenIds.contains($0)
        }
        remainingSet = Set(remainingIds)
    }

    private func rebuildIndexes() {
        recordById = Dictionary(uniqueKeysWithValues: orderedMedia.map { ($0.id, $0) })
        activeSeenCount = orderedMedia.count { seenIds.contains($0.id) }
        var groups: [Int64: [String]] = [:]
        var groupOrder: [Int64] = []
        for item in orderedMedia {
            let key = localDayKey(item.dateTaken)
            if groups[key] == nil { groupOrder.append(key) }
            groups[key, default: []].append(item.id)
        }

        var byId: [String: [String]] = [:]
        byId.reserveCapacity(orderedMedia.count)
        var positions: [String: Int] = [:]
        positions.reserveCapacity(orderedMedia.count)
        for key in groupOrder {
            let ids = groups[key]!
            // 同一天内按拍摄时间升序：向左翻（屏幕左侧）是当天更早的照片，向右翻是更晚的。
            let stableIds = ids.sorted {
                let l = recordById[$0]!
                let r = recordById[$1]!
                return l.dateTaken != r.dateTaken ? l.dateTaken < r.dateTaken : $0 < $1
            }
            for (index, id) in stableIds.enumerated() {
                byId[id] = stableIds
                positions[id] = index
            }
        }
        dayIdsByMediaId = byId
        dayPositionByMediaId = positions
    }

    private func replacementUpdate(
        shownId: String? = nil,
        resetSeenTo: Set<String>? = nil,
    ) -> FlipSessionUpdate {
        let snapshot = snapshot()
        return FlipSessionUpdate(
            currentId: snapshot.currentId,
            roundComplete: snapshot.roundComplete,
            shownId: shownId,
            resetSeenTo: resetSeenTo,
            persistence: .replace,
            forwardIds: snapshot.forwardStack,
            snapshotProvider: { snapshot },
        )
    }

    private func incrementalUpdate(
        shownId: String? = nil,
        visit: VisitMutation = .none,
    ) -> FlipSessionUpdate {
        FlipSessionUpdate(
            currentId: currentId,
            roundComplete: roundComplete,
            shownId: shownId,
            resetSeenTo: nil,
            persistence: .incremental(visit),
            forwardIds: forwardStack,
            snapshotProvider: { [self] in snapshot() },
        )
    }

    private func matches(_ snapshot: FlipSessionSnapshot) -> Bool {
        snapshot.currentId == currentId &&
            snapshot.roundComplete == roundComplete &&
            snapshot.remainingIds == remainingIds &&
            snapshot.visitStack == visitStack &&
            snapshot.forwardStack == forwardStack
    }

    private static func normalize(_ media: [FlipMediaRecord]) -> [FlipMediaRecord] {
        var seen: Set<String> = []
        return media.filter { seen.insert($0.id).inserted }
    }
}

/** UI-facing browsing session. Persists every state change through `onStateChanged`. */
@MainActor
final class FlipSession: ObservableObject {
    @Published private(set) var media: [MediaItem]
    @Published private(set) var currentId: String? = nil
    @Published private(set) var roundComplete = false
    @Published private(set) var seenCount = 0

    private var itemById: [String: MediaItem]
    private let state: FlipSessionState
    private let onStateChanged: (FlipSessionUpdate) -> Void

    init(
        media: [MediaItem],
        initialSeen: Set<String> = [],
        initialSnapshot: FlipSessionSnapshot? = nil,
        onStateChanged: @escaping (FlipSessionUpdate) -> Void = { _ in },
    ) {
        let normalized = Self.normalize(media)
        self.media = normalized
        itemById = Dictionary(uniqueKeysWithValues: normalized.map { ($0.id, $0) })
        self.onStateChanged = onStateChanged
        state = FlipSessionState(
            media: normalized.map { FlipMediaRecord(id: $0.id, dateTaken: $0.dateTaken) },
            initialSeen: initialSeen,
            snapshot: initialSnapshot,
        )
        publish(state.initialUpdate)
    }

    var current: MediaItem? { currentId.flatMap { itemById[$0] } }

    func sameDayItems(_ item: MediaItem? = nil) -> [MediaItem] {
        let target = item ?? current
        guard let id = target?.id else { return [] }
        return state.sameDayIds(id).compactMap { itemById[$0] }
    }

    func targetFor(_ direction: FlipDirection) -> String? { state.targetFor(direction) }

    func commit(_ direction: FlipDirection, targetId: String) {
        if let update = state.commit(direction, targetId: targetId) {
            publish(update)
        }
    }

    /** Remove an item while preserving the deck, history and seen membership for a later restore. */
    func remove(_ id: String) {
        guard itemById[id] != nil else { return }
        applyMedia(media.filter { $0.id != id })
    }

    /** Reconcile a fresh photo library result without resetting the active round. */
    func reconcileMedia(_ newMedia: [MediaItem]) {
        applyMedia(newMedia)
    }

    func roundFinished() -> Bool { state.roundFinished() }

    func completeRound() {
        publish(state.completeRound())
    }

    func restartRound() {
        publish(state.restartRound())
    }

    private func applyMedia(_ newMedia: [MediaItem]) {
        let normalized = Self.normalize(newMedia)
        media = normalized
        itemById = Dictionary(uniqueKeysWithValues: normalized.map { ($0.id, $0) })
        publish(state.reconcile(normalized.map { FlipMediaRecord(id: $0.id, dateTaken: $0.dateTaken) }))
    }

    private func publish(_ update: FlipSessionUpdate) {
        currentId = update.currentId
        roundComplete = update.roundComplete
        seenCount = state.seenCount
        onStateChanged(update)
    }

    private static func normalize(_ media: [MediaItem]) -> [MediaItem] {
        var seen: Set<String> = []
        return media.filter { seen.insert($0.id).inserted }
    }
}
