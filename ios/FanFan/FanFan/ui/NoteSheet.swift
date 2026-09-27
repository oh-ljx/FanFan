import SwiftUI
import UIKit

private let commentSurface = Color(hex: 0xF7F7F7)
private let commentInk = Color(hex: 0x171717)
private let commentMuted = Color(hex: 0x858585)
private let commentField = Color(hex: 0xECEDEC)
private let commentDivider = Color(hex: 0xE5E5E5)

/** 回复最多两层：顶层评论（0）→ 回复（1）→ 回复的回复（2），第 2 层不能再被回复。 */
private let maxReplyDepth = 2

/** 每层回复相对父评论的右偏移量。 */
private let replyIndent: CGFloat = 24

/** 评论面板高度占屏幕的比例；翻翻/查看页压缩舞台时用同一比例。 */
let notePanelHeightFraction: CGFloat = 0.44

/**
 * 把按时间排序的扁平评论整理成父→子的先序列表，元素为 (评论, 缩进层级)。
 * 父评论已不存在的孤儿（老数据）按顶层评论展示。
 */
func threadNotes(_ notes: [Note]) -> [(note: Note, depth: Int)] {
    let childrenByParent = Dictionary(grouping: notes, by: { $0.parentId })
    var visited: Set<Int64> = []
    var result: [(Note, Int)] = []

    func visit(_ note: Note, _ depth: Int) {
        guard visited.insert(note.id).inserted else { return }
        result.append((note, depth))
        for child in childrenByParent[note.id] ?? [] {
            visit(child, min(depth + 1, maxReplyDepth))
        }
    }
    for root in childrenByParent[nil] ?? [] { visit(root, 0) }
    for note in notes { visit(note, 0) }
    return result
}

/**
 * 轻量评论层；位移由调用方和主内容共用的动画进度驱动。
 * 面板直接延伸到手势条所在的屏幕最底部。
 */
struct NoteSheet: View {
    let notes: [Note]
    /** 0 = 收起在屏幕外，1 = 完全展开。 */
    let progress: CGFloat
    let screenSize: CGSize
    let onDismiss: () -> Void
    let onSend: (String, Int64?) -> Void
    let onDelete: (Note) -> Void

    @State private var draft = ""
    @State private var replyTarget: Note?
    @State private var keyboardHeight: CGFloat = 0
    @FocusState private var fieldFocused: Bool

    private var canSend: Bool { !draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }

    /** 回复目标可能刚被删掉，落回普通评论模式 */
    private var activeReplyTarget: Note? {
        replyTarget.flatMap { target in notes.contains { $0.id == target.id } ? target : nil }
    }

    private var threaded: [(note: Note, depth: Int)] { threadNotes(notes) }

    var body: some View {
        let panelHeight = screenSize.height * notePanelHeightFraction
        VStack(spacing: 0) {
            // 点击面板上方区域关闭
            Color.clear
                .contentShape(Rectangle())
                .onTapGesture(perform: onDismiss)
                .frame(height: max(0, screenSize.height - panelHeight - keyboardHeight))

            panel
                .frame(height: panelHeight)
                .padding(.bottom, keyboardHeight)
        }
        .frame(maxWidth: .infinity)
        .offset(y: panelHeight * (1 - min(1, max(0, progress))))
        .onReceive(
            NotificationCenter.default.publisher(for: UIResponder.keyboardWillChangeFrameNotification),
        ) { note in
            let endFrame = note.userInfo?[UIResponder.keyboardFrameEndUserInfoKey] as? CGRect ?? .zero
            let duration = note.userInfo?[UIResponder.keyboardAnimationDurationUserInfoKey] as? Double ?? 0.25
            let height = max(0, UIScreen.main.bounds.height - endFrame.minY)
            withAnimation(.easeOut(duration: duration)) { keyboardHeight = height }
        }
    }

    @ViewBuilder
    private var panel: some View {
        VStack(spacing: 0) {
            // 标题栏
            ZStack {
                Text("\(notes.count) 条评论")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(commentInk)
                HStack {
                    Spacer()
                    Button(action: onDismiss) {
                        Image(systemName: "xmark")
                            .font(.system(size: 17, weight: .medium))
                            .foregroundStyle(commentInk)
                            .frame(width: 44, height: 44)
                    }
                    .accessibilityLabel("关闭评论")
                    .padding(.trailing, 6)
                }
            }
            .frame(height: 56)

            Divider().overlay(commentDivider)

            if notes.isEmpty {
                Text("还没有评论")
                    .font(.system(size: 14))
                    .foregroundStyle(commentMuted)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                ScrollView {
                    LazyVStack(spacing: 0) {
                        ForEach(Array(threaded.enumerated()), id: \.element.note.id) { index, entry in
                            let note = entry.note
                            let depth = entry.depth
                            let replyable = depth < maxReplyDepth
                            HStack(alignment: .center, spacing: 0) {
                                VStack(alignment: .leading, spacing: 4) {
                                    Text(note.text)
                                        .font(.system(size: 15))
                                        .lineSpacing(6)
                                        .foregroundStyle(commentInk)
                                        .frame(maxWidth: .infinity, alignment: .leading)
                                    Text(formatNoteTime(note.createdAt))
                                        .font(.system(size: 12))
                                        .foregroundStyle(commentMuted)
                                }
                                .padding(.leading, 2)
                                .padding(.trailing, 8)
                                Button {
                                    if replyTarget?.id == note.id { replyTarget = nil }
                                    onDelete(note)
                                } label: {
                                    Image(systemName: "trash")
                                        .font(.system(size: 16))
                                        .foregroundStyle(commentMuted)
                                        .frame(width: 44, height: 44)
                                }
                                .accessibilityLabel("删除评论")
                            }
                            .padding(.leading, replyIndent * CGFloat(depth))
                            .padding(.vertical, 8)
                            .contentShape(RoundedRectangle(cornerRadius: 10))
                            .onTapGesture {
                                guard replyable else { return }
                                if replyTarget?.id == note.id {
                                    replyTarget = nil
                                } else {
                                    replyTarget = note
                                    fieldFocused = true
                                }
                            }
                            if index < threaded.count - 1 {
                                Divider().overlay(commentDivider)
                            }
                        }
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 6)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            }

            Divider().overlay(commentDivider)

            if let target = activeReplyTarget {
                HStack {
                    Text("回复：\(target.text)")
                        .font(.system(size: 13))
                        .foregroundStyle(commentMuted)
                        .lineLimit(1)
                        .truncationMode(.tail)
                    Spacer()
                    Text("取消")
                        .font(.system(size: 13))
                        .foregroundStyle(Color.pine)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 4)
                        .contentShape(RoundedRectangle(cornerRadius: 6))
                        .onTapGesture { replyTarget = nil }
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 4)
            }

            HStack(spacing: 8) {
                commentInput

                Button {
                    let text = draft.trimmingCharacters(in: .whitespacesAndNewlines)
                    guard !text.isEmpty else { return }
                    onSend(text, activeReplyTarget?.id)
                    draft = ""
                    replyTarget = nil
                } label: {
                    Image(systemName: "paperplane.fill")
                        .font(.system(size: 17))
                        .foregroundStyle(.white.opacity(canSend ? 1 : 0.7))
                        .frame(width: 44, height: 44)
                        .background(Color.pine.opacity(canSend ? 1 : 0.18))
                        .clipShape(Circle())
                }
                .disabled(!canSend)
                .accessibilityLabel("保存评论")
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .padding(.bottom, bottomSafeArea)
        }
        .background(commentSurface)
        .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
    }

    @ViewBuilder
    private var commentInput: some View {
        let prompt = activeReplyTarget != nil ? "回复评论…" : "写评论…"
        if #available(iOS 16.0, *) {
            TextField(prompt, text: $draft, axis: .vertical)
                .lineLimit(1...3)
                .font(.system(size: 14))
                .foregroundStyle(commentInk)
                .tint(.pine)
                .focused($fieldFocused)
                .padding(.horizontal, 14)
                .padding(.vertical, 10)
                .background(commentField)
                .clipShape(RoundedRectangle(cornerRadius: 22))
        } else {
            ZStack(alignment: .topLeading) {
                if draft.isEmpty {
                    Text(prompt)
                        .font(.system(size: 14))
                        .foregroundStyle(commentMuted)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 11)
                        .allowsHitTesting(false)
                }
                TextEditor(text: $draft)
                    .font(.system(size: 14))
                    .foregroundStyle(commentInk)
                    .tint(.pine)
                    .focused($fieldFocused)
                    .frame(height: 62)
            }
            .background(commentField)
            .clipShape(RoundedRectangle(cornerRadius: 22))
        }
    }

    private var bottomSafeArea: CGFloat {
        UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap(\.windows)
            .first { $0.isKeyWindow }?
            .safeAreaInsets.bottom ?? 0
    }
}

private let noteTimeFormatter: DateFormatter = {
    let formatter = DateFormatter()
    formatter.locale = Locale(identifier: "zh_CN")
    formatter.dateFormat = "M月d日 HH:mm"
    return formatter
}()

private func formatNoteTime(_ timestampMs: Int64) -> String {
    noteTimeFormatter.string(from: Date(timeIntervalSince1970: TimeInterval(timestampMs) / 1000))
}
