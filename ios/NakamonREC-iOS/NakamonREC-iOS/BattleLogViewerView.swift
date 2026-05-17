import SwiftUI
import NakamonREC_Shared

/// フライトレコーダー: 直近 1 戦分の解析ログをテキスト表示
struct BattleLogViewerView: View {
    @State private var latestLog: String = ""
    @State private var previousLog: String? = nil
    @State private var showingClearAlert = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                section(title: "📋 最新のログ", body: latestLog)

                if let prev = previousLog {
                    section(title: "🗂 前回のログ", body: prev)
                }
            }
            .padding()
        }
        .navigationTitle("解析ログ")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    reload()
                } label: {
                    Image(systemName: "arrow.clockwise")
                }
            }
            ToolbarItem(placement: .topBarTrailing) {
                Button(role: .destructive) {
                    showingClearAlert = true
                } label: {
                    Image(systemName: "trash")
                }
            }
        }
        .onAppear(perform: reload)
        .alert("ログを削除しますか？", isPresented: $showingClearAlert) {
            Button("削除", role: .destructive) {
                BattleLogger.clear()
                reload()
            }
            Button("キャンセル", role: .cancel) {}
        } message: {
            Text("最新ログ・前回ログの両方を削除します。")
        }
    }

    private func section(title: String, body: String) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title)
                .font(.headline)
            Text(body.isEmpty ? "(空)" : body)
                .font(.system(.footnote, design: .monospaced))
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(10)
                .background(Color.secondary.opacity(0.1))
                .cornerRadius(8)
                .textSelection(.enabled)
        }
    }

    private func reload() {
        latestLog = BattleLogger.readLatest()
        previousLog = BattleLogger.readPrevious()
    }
}

#Preview {
    NavigationStack {
        BattleLogViewerView()
    }
}
