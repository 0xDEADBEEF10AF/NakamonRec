import SwiftUI
import NakamonREC_Shared

/// 戦績 JSON ファイル管理 (Android 「ファイルを選択」ダイアログ相当)
/// - 「.json」を省いたプレーン名のリスト
/// - タップでファイル操作ダイアログを開く
/// - 下部に 新規作成 / ファイルマージ / CSVインポート / 閉じる
struct JSONFileManagerView: View {
    /// ファイル切替・リネーム・削除が発生したときに親へ通知 (履歴の再読込に使う)
    var onChange: () -> Void
    @Environment(\.dismiss) private var dismiss

    @State private var files: [String] = []
    @State private var activeName: String = BattleHistoryStore.shared.activeFileName
    @State private var fileOpsTarget: FileTarget? = nil
    @State private var showCreatePrompt = false
    @State private var renameTarget: FileTarget? = nil
    @State private var pendingDelete: FileTarget? = nil
    @State private var errorMessage: String? = nil
    @State private var infoMessage: String? = nil
    @State private var csvShareURL: ShareURL? = nil
    @State private var showCSVImporter = false
    @State private var showMergeSheet = false

    private struct FileTarget: Identifiable {
        let name: String
        var id: String { name }
    }
    private struct ShareURL: Identifiable {
        let url: URL
        var id: String { url.path }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            // タイトル
            Text("ファイルを選択")
                .font(.headline)
                .foregroundStyle(Color.gray)
                .padding(.horizontal, 16)
                .padding(.top, 16)
                .padding(.bottom, 12)

            Divider().background(Color.gray.opacity(0.3))

            // ファイル一覧
            ScrollView {
                VStack(spacing: 0) {
                    ForEach(files, id: \.self) { name in
                        Button {
                            fileOpsTarget = FileTarget(name: name)
                        } label: {
                            HStack {
                                Text(stripExtension(name))
                                    .foregroundStyle(.white)
                                Spacer()
                            }
                            .contentShape(Rectangle())
                            .padding(.horizontal, 16)
                            .padding(.vertical, 14)
                            .background(name == activeName ? Color.white.opacity(0.08) : .clear)
                        }
                        Divider().background(Color.gray.opacity(0.2))
                    }
                }
            }

            // 下部アクション行
            HStack(spacing: 0) {
                bottomAction("新規作成") { showCreatePrompt = true }
                bottomAction("ファイルマージ") { showMergeSheet = true }
                bottomAction("CSVインポート") { showCSVImporter = true }
                bottomAction("閉じる") { dismiss() }
            }
            .padding(.vertical, 6)
        }
        .background(Color.cardBackground)
        .onAppear(perform: reload)
        .sheet(item: $fileOpsTarget) { target in
            FileOpsDialog(
                fileName: stripExtension(target.name),
                isActive: target.name == activeName,
                onUse: {
                    if target.name != activeName {
                        BattleHistoryStore.shared.activeFileName = target.name
                        activeName = target.name
                        onChange()
                    }
                },
                onRename: {
                    renameTarget = target
                },
                onDelete: {
                    pendingDelete = target
                },
                onExportCSV: {
                    exportCSV(forFile: target.name)
                }
            )
            .presentationDetents([.fraction(0.45)])
        }
        .sheet(item: $csvShareURL) { share in
            ShareSheet(items: [share.url])
        }
        .sheet(isPresented: $showCSVImporter) {
            DocumentImportPicker { url in
                importCSV(from: url)
            }
        }
        .sheet(isPresented: $showMergeSheet) {
            FileMergeView { newFileName in
                if !newFileName.isEmpty {
                    BattleHistoryStore.shared.activeFileName = newFileName
                    activeName = newFileName
                    reload()
                    onChange()
                }
            }
        }
        .sheet(isPresented: $showCreatePrompt) {
            FileNamePrompt(title: "新しいファイル", initialName: defaultNewFileName()) { name in
                if BattleHistoryStore.shared.createFile(name: name) {
                    reload()
                } else {
                    errorMessage = "作成できませんでした。同名のファイルが既に存在する可能性があります。"
                }
            }
            .presentationDetents([.fraction(0.3)])
        }
        .sheet(item: $renameTarget) { target in
            FileNamePrompt(title: "リネーム", initialName: stripExtension(target.name)) { newName in
                if BattleHistoryStore.shared.renameFile(from: target.name, to: newName) {
                    activeName = BattleHistoryStore.shared.activeFileName
                    reload()
                    onChange()
                } else {
                    errorMessage = "リネームできませんでした。"
                }
            }
            .presentationDetents([.fraction(0.3)])
        }
        .confirmationDialog(
            "「\(pendingDelete.map { stripExtension($0.name) } ?? "")」を削除しますか？",
            isPresented: Binding(
                get: { pendingDelete != nil },
                set: { if !$0 { pendingDelete = nil } }),
            titleVisibility: .visible
        ) {
            Button("削除", role: .destructive) {
                if let target = pendingDelete {
                    if BattleHistoryStore.shared.deleteFile(name: target.name) {
                        activeName = BattleHistoryStore.shared.activeFileName
                        reload()
                        onChange()
                    } else {
                        errorMessage = "削除できませんでした。最後の 1 ファイルは削除できません。"
                    }
                }
                pendingDelete = nil
            }
            Button("キャンセル", role: .cancel) { pendingDelete = nil }
        }
        .alert("エラー", isPresented: Binding(
            get: { errorMessage != nil },
            set: { if !$0 { errorMessage = nil } })) {
            Button("OK") { errorMessage = nil }
        } message: {
            Text(errorMessage ?? "")
        }
        .alert("お知らせ", isPresented: Binding(
            get: { infoMessage != nil },
            set: { if !$0 { infoMessage = nil } })) {
            Button("OK") { infoMessage = nil }
        } message: {
            Text(infoMessage ?? "")
        }
    }

    private func bottomAction(_ title: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title)
                .font(.callout)
                .foregroundStyle(Color.recCoral)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 10)
        }
    }

    private func reload() {
        var list = BattleHistoryStore.shared.availableFiles()
        if list.isEmpty {
            BattleHistoryStore.shared.createFile(name: activeName)
            list = BattleHistoryStore.shared.availableFiles()
        }
        files = list
        activeName = BattleHistoryStore.shared.activeFileName
    }

    private func stripExtension(_ name: String) -> String {
        name.hasSuffix(".json") ? String(name.dropLast(5)) : name
    }

    /// 新規作成時のデフォルト名 `record_yyyyMMddHHmmss`
    /// (ファイル名を考える手間を省きたいユーザー向け)
    private func defaultNewFileName() -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone.current
        formatter.dateFormat = "yyyyMMddHHmmss"
        return "record_\(formatter.string(from: Date()))"
    }

    // MARK: - CSV export / import

    private func exportCSV(forFile name: String) {
        let history = BattleHistoryStore.shared.load(fileName: name)
        let csv = CSVSupport.encode(history)
        let base = stripExtension(name)
        if let url = writeCSVToTempFile(content: csv, baseName: base) {
            csvShareURL = ShareURL(url: url)
        } else {
            errorMessage = "CSV ファイルの書き出しに失敗しました。"
        }
    }

    private func importCSV(from url: URL) {
        // Document Picker は asCopy: true なので security scope 不要だが念のため
        let didAccessSecurity = url.startAccessingSecurityScopedResource()
        defer {
            if didAccessSecurity { url.stopAccessingSecurityScopedResource() }
        }
        guard let data = try? Data(contentsOf: url),
              let text = String(data: data, encoding: .utf8)
                ?? String(data: data, encoding: .shiftJIS) else {
            errorMessage = "CSV ファイルを読み込めませんでした。"
            return
        }
        let records = CSVSupport.decode(text)
        guard !records.isEmpty else {
            errorMessage = "CSV から戦績を 1 件も読み取れませんでした。"
            return
        }
        // 新規ファイル名: 元 CSV 名 (.csv 除く) を使用
        let origName = url.deletingPathExtension().lastPathComponent
        let candidate = origName.isEmpty ? "imported_\(Date().timeIntervalSince1970)" : origName
        // 既存と衝突しないよう接尾辞付与
        var target = candidate
        var counter = 1
        let existing = BattleHistoryStore.shared.availableFiles()
        while existing.contains("\(target).json") {
            target = "\(candidate)_\(counter)"
            counter += 1
        }
        var history = BattleHistory()
        history.records = records
        history.recomputeTotals()
        BattleHistoryStore.shared.save(history, fileName: "\(target).json")
        BattleHistoryStore.shared.activeFileName = "\(target).json"
        activeName = "\(target).json"
        reload()
        onChange()
        infoMessage = "CSV から \(records.count) 件のレコードを読み込み、新規ファイル「\(target)」を作成しました。"
    }
}

// MARK: - File operations dialog (Android「ファイル操作: <name>」相当)

private struct FileOpsDialog: View {
    let fileName: String
    let isActive: Bool
    let onUse: () -> Void
    let onRename: () -> Void
    let onDelete: () -> Void
    let onExportCSV: () -> Void

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("ファイル操作: \(fileName)")
                .font(.headline)
                .foregroundStyle(Color.gray)
                .padding(.horizontal, 16)
                .padding(.top, 16)
                .padding(.bottom, 12)

            Divider().background(Color.gray.opacity(0.3))

            opsRow("このファイルを使用する", disabled: isActive) {
                onUse()
                dismiss()
            }
            opsRow("名前を変更する") {
                dismiss()
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) { onRename() }
            }
            opsRow("削除する") {
                dismiss()
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) { onDelete() }
            }
            opsRow("CSV にエクスポート") {
                dismiss()
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) { onExportCSV() }
            }

            Spacer(minLength: 0)
        }
        .background(Color.cardBackground)
    }

    private func opsRow(_ title: String,
                        disabled: Bool = false,
                        action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack {
                Text(title)
                    .foregroundStyle(disabled ? .gray : .white)
                Spacer()
            }
            .contentShape(Rectangle())
            .padding(.horizontal, 16)
            .padding(.vertical, 14)
        }
        .disabled(disabled)
    }
}

// MARK: - File name prompt

private struct FileNamePrompt: View {
    let title: String
    let initialName: String
    let onSubmit: (String) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var name: String

    init(title: String, initialName: String, onSubmit: @escaping (String) -> Void) {
        self.title = title
        self.initialName = initialName
        self._name = State(initialValue: initialName)
        self.onSubmit = onSubmit
    }

    var body: some View {
        NavigationStack {
            ZStack {
                Color.black.ignoresSafeArea()
                VStack(spacing: 16) {
                    TextField("ファイル名", text: $name)
                        .textFieldStyle(.roundedBorder)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                    Text(".json は自動付与されます")
                        .font(.caption)
                        .foregroundStyle(.gray)
                    Spacer()
                }
                .padding(.horizontal, 16)
                .padding(.top, 16)
            }
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("キャンセル") { dismiss() }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("OK") {
                        onSubmit(name)
                        dismiss()
                    }
                    .disabled(name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
        }
    }
}
