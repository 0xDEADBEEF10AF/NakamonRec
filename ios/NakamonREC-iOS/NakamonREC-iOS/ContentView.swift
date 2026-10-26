import SwiftUI
import ReplayKit
import OSLog
import NakamonREC_Shared

private let logger = Logger(subsystem: "com.android.NakamonREC-iOS", category: "Host")

// MARK: - Main screen

struct ContentView: View {
    @State private var history: BattleHistory = BattleHistory()
    @State private var showDebugMenu = false
    @State private var nakamonRotation: Double = 180   // 起動時はアプリアイコンと同じ「NAKAMON が下」の状態。180° → 0° まで反時計回りに半周して着地
    @State private var hasAnimatedLogo: Bool = false
    @State private var isBroadcasting: Bool = BroadcastStatus.isEffectivelyActive()
    @State private var showFileManager = false
    @State private var showUserSettings = false
    @State private var showHelp = false
    @State private var showClearConfirm = false
    @State private var activeFileName: String = BattleHistoryStore.shared.activeFileName
    @State private var lastSeenRecordTimestamp: String = BroadcastStatus.lastRecordTimestamp
    // グランプリモード ON のときは REC ボタン周りの弧文字を NAKAMON → MONSTER (上) / GRANDPRIX (下) に切り替える
    // (モードの入れ忘れ・切り忘れをメイン画面で一目で分かるようにする、26.10.2)
    @State private var isGrandPrixMode: Bool = GrandPrixMode.isEnabled

    // App Store 更新通知 (1日1回チェック、バージョン単位で抑止可能)
    @State private var availableUpdate: AppStoreUpdateInfo? = nil
    @State private var showUpdateAlert = false
    @AppStorage("suppressedUpdateVersion") private var suppressedUpdateVersion = ""
    @AppStorage("lastUpdateCheckDate") private var lastUpdateCheckDate = ""

    private var totalWins: Int { history.totalWins }
    private var totalLosses: Int { history.totalLosses }
    private var totalMatches: Int { totalWins + totalLosses }
    private var winRate: Double {
        totalMatches > 0 ? Double(totalWins) / Double(totalMatches) * 100 : 0
    }

    private var appVersion: String {
        Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0.0"
    }

    private static let updateCheckDayFormatter: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "yyyy-MM-dd"
        f.locale = Locale(identifier: "en_US_POSIX")
        return f
    }()

    /// App Store の最新バージョンを1日1回チェックし、新しければダイアログを出す。
    /// GitHub Releases チェックはストア一本化 (2026-08-15) で廃止。
    /// チェック失敗時は日付を記録しない (同日中の次回起動で再試行)。
    private func checkForStoreUpdate() async {
        let today = Self.updateCheckDayFormatter.string(from: Date())
        guard lastUpdateCheckDate != today else { return }
        guard let info = await AppStoreUpdateChecker.fetch() else { return }
        lastUpdateCheckDate = today
        guard AppStoreUpdateChecker.isNewer(info.version, than: appVersion),
              info.version != suppressedUpdateVersion else { return }
        availableUpdate = info
        showUpdateAlert = true
    }

    var body: some View {
        NavigationStack {
            ZStack {
                Color.black.ignoresSafeArea()

                VStack(spacing: 12) {
                    // 上部ステータス行 (バージョン / ?)
                    topStatusRow

                    // 上部アクションバー (JSON ファイル / カメラ)
                    topActionBar

                    Spacer()

                    // 中央 (NAKAMON カーブテキスト + REC ボタン)
                    centerBlock

                    Spacer()

                    // 下部 (戦績サマリ / 🗑)
                    bottomActionBar
                }
                .padding(.horizontal, 12)
            }
            .navigationBarHidden(true)
            .onAppear {
                reloadHistory()
                isGrandPrixMode = GrandPrixMode.isEnabled
                playLogoIntroIfNeeded()
                // ハートビート鮮度込みの実効状態 (Extension が finished を呼べず死んだ
                // 場合の STOP 表示残留を自動修復する)
                isBroadcasting = BroadcastStatus.isEffectivelyActive()
            }
            .task {
                // Extension からの放送状態と戦績更新シグナルを 0.5 秒ごとに polling。
                // SwiftUI の .task は view が消えると自動でキャンセルされる
                while !Task.isCancelled {
                    let active = BroadcastStatus.isEffectivelyActive()
                    if active != isBroadcasting {
                        isBroadcasting = active
                        if !active {
                            reloadHistory()
                        }
                    }
                    // 新しい戦績が保存されたら (放送中でも) サマリを更新
                    let ts = BroadcastStatus.lastRecordTimestamp
                    if ts != lastSeenRecordTimestamp {
                        lastSeenRecordTimestamp = ts
                        reloadHistory()
                    }
                    try? await Task.sleep(nanoseconds: 500_000_000)
                }
            }
            .task {
                await checkForStoreUpdate()
            }
            .alert("新しいバージョンがあります", isPresented: $showUpdateAlert, presenting: availableUpdate) { info in
                Button("App Store を開く") {
                    UIApplication.shared.open(info.storeURL)
                }
                Button("このバージョンは通知しない") {
                    suppressedUpdateVersion = info.version
                }
                Button("後で", role: .cancel) {}
            } message: { info in
                Text("Ver \(info.version) が利用可能です。")
            }
        }
        .sheet(isPresented: $showDebugMenu) {
            DebugMenuView()
                .presentationDetents([.large])
        }
        .sheet(isPresented: $showFileManager) {
            JSONFileManagerView {
                reloadHistory()
            }
            .presentationDetents([.fraction(0.5), .large])
        }
        .sheet(isPresented: $showUserSettings, onDismiss: {
            // 設定シートでグランプリモードを切り替えた直後に弧文字へ反映
            isGrandPrixMode = GrandPrixMode.isEnabled
        }) {
            UserSettingsSheet()
        }
        .sheet(isPresented: $showHelp) {
            HelpView()
        }
    }

    private func reloadHistory() {
        history = BattleHistoryStore.shared.loadActive()
        activeFileName = BattleHistoryStore.shared.activeFileName
    }

    /// 初回のみ NAKAMON ロゴが反時計回りに 1 周してから着地するアニメーション
    private func playLogoIntroIfNeeded() {
        guard !hasAnimatedLogo else { return }
        hasAnimatedLogo = true
        // 値を 360° から 0° に向かって減らすと、視覚的には反時計回り (1 revolution) になる
        withAnimation(.easeOut(duration: 1.8)) {
            nakamonRotation = 0
        }
    }

    // MARK: - Top status row

    private var topStatusRow: some View {
        HStack {
            Text("Ver \(appVersion)")
                .font(.caption)
                .foregroundStyle(.gray)
                .onLongPressGesture(minimumDuration: 0.7) {
                    // 隠しデバッグメニュー (フライトレコーダー等)
                    showDebugMenu = true
                }

            Spacer()

            Button {
                showHelp = true
            } label: {
                Image(systemName: "questionmark")
                    .font(.callout.bold())
                    .foregroundStyle(.gray)
                    .frame(width: 28, height: 28)
            }
        }
        .padding(.top, 4)
    }

    // MARK: - Top action bar (JSON + camera)

    private var topActionBar: some View {
        HStack(spacing: 8) {
            // JSON ファイル管理: タップでファイル一覧を開く
            Button {
                showFileManager = true
            } label: {
                HStack(spacing: 8) {
                    Image(systemName: "tray.full")
                        .foregroundStyle(.gray)
                    Text(activeFileName)
                        .foregroundStyle(.white)
                        .lineLimit(1)
                    Spacer()
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 10)
                .background(Color.cardBackground)
                .clipShape(RoundedRectangle(cornerRadius: 12))
            }

            // ユーザー設定シート (校正 / 軽負荷モード)
            Button {
                showUserSettings = true
            } label: {
                Image(systemName: "camera")
                    .font(.title3)
                    .foregroundStyle(.gray)
                    .frame(width: 46, height: 46)
                    .background(Color.cardBackground)
                    .clipShape(RoundedRectangle(cornerRadius: 12))
            }
        }
    }

    // MARK: - Center (NAKAMON + REC)

    private var centerBlock: some View {
        ZStack {
            Group {
                if isGrandPrixMode {
                    // グランプリモード: 上弧 MONSTER / 下弧 GRANDPRIX。どちらも REC ボタンと同心・同じ半径で、
                    // 文字のボタン側の縁がボタン外周から等距離になる (上は基線側、下は文字の天側が内側)。
                    // 1 文字あたり 18° の等角配置 (NAKAMON の 20° より少し詰めて 9 文字の GRANDPRIX を収める)
                    CurvedText(
                        text: "MONSTER",
                        radius: 112,
                        spread: .degrees(18 * 6),
                        font: .system(size: 32, weight: .bold, design: .default),
                        color: Color.white
                    )
                    CurvedText(
                        text: "GRANDPRIX",
                        radius: 112,
                        spread: .degrees(18 * 8),
                        font: .system(size: 32, weight: .bold, design: .default),
                        color: Color.white,
                        placement: .bottom
                    )
                } else {
                    CurvedText(
                        text: "NAKAMON",
                        radius: 112,
                        spread: .degrees(120),
                        font: .system(size: 34, weight: .bold, design: .default),
                        color: Color.white   // #FFFFFF (フル不透明)
                    )
                }
            }
            .rotationEffect(.degrees(nakamonRotation))

            BroadcastButton(isBroadcasting: isBroadcasting)
                .frame(width: 175, height: 175)
        }
        .frame(maxWidth: .infinity)
    }

    // MARK: - Bottom action bar (stats + trash)

    private var bottomActionBar: some View {
        HStack(spacing: 8) {
            NavigationLink {
                BattleHistoryView()
            } label: {
                HStack(spacing: 10) {
                    Image(systemName: "chart.bar.xaxis")
                        .foregroundStyle(.gray)
                    Text(RateFormat.percent(winRate))
                        .foregroundStyle(Color.recCoral)
                        .font(.body.bold())
                    Text("\(totalMatches) Matches")
                        .foregroundStyle(.white)
                        .font(.caption)
                    Spacer(minLength: 0)
                    Text("\(totalWins)W")
                        .foregroundStyle(Color.sideMy)
                        .font(.caption.bold())
                    Text("-")
                        .foregroundStyle(.gray)
                        .font(.caption)
                    Text("\(totalLosses)L")
                        .foregroundStyle(Color.sideEnemy)
                        .font(.caption.bold())
                }
                .padding(.horizontal, 12)
                .padding(.vertical, 12)
                .background(Color.cardBackground)
                .clipShape(RoundedRectangle(cornerRadius: 12))
            }

            // 現在のアクティブファイルの戦績をクリア (誤操作防止のため確認ダイアログ経由)
            Button {
                showClearConfirm = true
            } label: {
                Image(systemName: "trash")
                    .font(.title3)
                    .foregroundStyle(Color.recCoral)
                    .frame(width: 46, height: 46)
                    .background(Color.cardBackground)
                    .clipShape(RoundedRectangle(cornerRadius: 12))
            }
            .confirmationDialog(
                "「\(stripJsonExt(activeFileName))」の戦績を全件削除しますか?",
                isPresented: $showClearConfirm,
                titleVisibility: .visible
            ) {
                Button("全件削除", role: .destructive) {
                    BattleHistoryStore.shared.clearActive()
                    reloadHistory()
                }
                Button("キャンセル", role: .cancel) {}
            } message: {
                Text("この操作は取り消せません。ファイル自体は残り、レコードのみが空になります。")
            }
        }
        .padding(.bottom, 4)
    }

    private func stripJsonExt(_ name: String) -> String {
        name.hasSuffix(".json") ? String(name.dropLast(5)) : name
    }
}

// MARK: - Curved text

/// 円弧に沿って文字を配置するテキスト
struct CurvedText: View {
    /// 弧の位置。`.top` は円の上側 (文字の天が外向き)、`.bottom` は円の下側 (文字の天が中心向き = 正立のまま読める)
    enum Placement { case top, bottom }

    let text: String
    let radius: CGFloat
    let spread: Angle
    let font: Font
    let color: Color
    var placement: Placement = .top

    var body: some View {
        ZStack {
            let chars = Array(text)
            ForEach(0..<chars.count, id: \.self) { idx in
                let progress: Double = chars.count == 1 ? 0.5 : Double(idx) / Double(chars.count - 1)
                // 上弧は左→右が時計回り (正の角度)、下弧は左→右が反時計回りなので符号を反転する。
                // 文字自体も同じ角度で回るため、下弧では各文字の天が常に円の中心を向く
                let direction: Double = placement == .top ? 1 : -1
                let angle = Angle(degrees: direction * spread.degrees * (progress - 0.5))
                Text(String(chars[idx]))
                    .font(font)
                    .foregroundColor(color)
                    .offset(y: placement == .top ? -radius : radius)
                    .rotationEffect(angle)
            }
        }
    }
}

// MARK: - Broadcast button (REC / STOP トグル)

/// Android 版と同じ挙動: 録画中は STOP (水色) に切り替わり、タップで放送停止を Extension に要求する。
struct BroadcastButton: UIViewRepresentable {
    /// 現在放送中か。true なら STOP 表示、false なら REC 表示
    var isBroadcasting: Bool

    // REC (放送開始前) のカラー
    private static let recFill = UIColor(red: 0xF0/255.0, green: 0x91/255.0, blue: 0x99/255.0, alpha: 1.0) // #F09199
    private static let recText = UIColor(red: 0xFF/255.0, green: 0xE6/255.0, blue: 0xEB/255.0, alpha: 1.0) // #FFE6EB
    private static let recBorder = UIColor(red: 0xFF/255.0, green: 0xE6/255.0, blue: 0xEB/255.0, alpha: 1.0).cgColor // #FFE6EB (フル不透明)

    // STOP (放送中) のカラー
    private static let stopFill = UIColor(red: 0x90/255.0, green: 0xD7/255.0, blue: 0xEC/255.0, alpha: 1.0) // #90D7EC
    private static let stopText = UIColor(red: 0.90, green: 0.98, blue: 1.0, alpha: 1.0)                   // 薄水色
    private static let stopBorder = UIColor(red: 0.90, green: 0.98, blue: 1.0, alpha: 0.6).cgColor

    func makeUIView(context: Context) -> UIView {
        let container = UIView()
        container.backgroundColor = .clear

        // 非表示の RPSystemBroadcastPickerView (実際のピッカー起動を担当)
        let picker = RPSystemBroadcastPickerView(frame: .zero)
        picker.preferredExtension = "com.android.NakamonREC-iOS.NakamonREC-ScreenCapture"
        picker.showsMicrophoneButton = false
        picker.translatesAutoresizingMaskIntoConstraints = false
        picker.isHidden = true
        container.addSubview(picker)

        // 見た目用ボタン (前面に表示しタップを受ける)
        let button = UIButton(type: .custom)
        button.translatesAutoresizingMaskIntoConstraints = false
        button.titleLabel?.font = .systemFont(ofSize: 28, weight: .bold)
        button.layer.borderWidth = 6   // Android の MaterialButton stroke と近い太さに合わせる
        container.addSubview(button)

        NSLayoutConstraint.activate([
            button.topAnchor.constraint(equalTo: container.topAnchor),
            button.bottomAnchor.constraint(equalTo: container.bottomAnchor),
            button.leadingAnchor.constraint(equalTo: container.leadingAnchor),
            button.trailingAnchor.constraint(equalTo: container.trailingAnchor),
        ])

        context.coordinator.picker = picker
        context.coordinator.button = button
        button.addTarget(context.coordinator,
                         action: #selector(Coordinator.tap),
                         for: .touchUpInside)

        applyAppearance(to: button, isBroadcasting: isBroadcasting)
        context.coordinator.isBroadcasting = isBroadcasting
        return container
    }

    func updateUIView(_ uiView: UIView, context: Context) {
        context.coordinator.isBroadcasting = isBroadcasting
        if let btn = context.coordinator.button {
            applyAppearance(to: btn, isBroadcasting: isBroadcasting)
            // 円形に保つため、レイアウト後にコーナーラディウスを調整
            DispatchQueue.main.async {
                btn.layer.cornerRadius = min(btn.bounds.width, btn.bounds.height) / 2
            }
        }
    }

    private func applyAppearance(to button: UIButton, isBroadcasting: Bool) {
        if isBroadcasting {
            button.setTitle("STOP", for: .normal)
            button.setTitleColor(Self.stopText, for: .normal)
            button.backgroundColor = Self.stopFill
            button.layer.borderColor = Self.stopBorder
        } else {
            button.setTitle("REC", for: .normal)
            button.setTitleColor(Self.recText, for: .normal)
            button.backgroundColor = Self.recFill
            button.layer.borderColor = Self.recBorder
        }
    }

    func makeCoordinator() -> Coordinator { Coordinator() }

    final class Coordinator: NSObject {
        weak var picker: RPSystemBroadcastPickerView?
        weak var button: UIButton?
        var isBroadcasting: Bool = false

        @objc func tap() {
            // iOS 27 以降: ホストアプリ内の ScreenCaptureKit 経路 (ScreenCaptureKitRecorder)。
            //   REC = システムピッカーで「画面全体」を選ばせて SCStream 開始 / STOP = 即停止。
            //   ただし Extension 経路の放送が動いている (コントロールセンターから開始した等) なら
            //   recorder は停止できないので、従来どおりシステムシートに任せる。
            #if canImport(ScreenCaptureKit)
            if #available(iOS 27.0, *) {
                let recorder = ScreenCaptureKitRecorder.shared
                if recorder.isRunning || recorder.isStarting {
                    logger.log("STOP tapped → ScreenCaptureKit stop")
                    recorder.stop()
                    return
                }
                if !isBroadcasting {
                    logger.log("REC tapped → ScreenCaptureKit start")
                    recorder.start()
                    return
                }
                logger.log("STOP tapped while legacy broadcast active → system sheet")
            }
            #endif

            // iOS 26 以前 (または Extension 経路の放送中): REC / STOP のいずれも
            // RPSystemBroadcastPickerView を起動するだけ。
            // ピッカーは iOS 側が放送中か否かを判定して、「ブロードキャストを開始」
            // または「ブロードキャストを停止」シートを自動で出し分けてくれる。
            // (Extension 側で finishBroadcastWithError: を呼ばないので、
            // 「次の理由により停止しました」ダイアログも出ない。)
            logger.log("\(self.isBroadcasting ? "STOP" : "REC") tapped → open system sheet")
            guard let picker else { return }
            if let innerButton = findButton(in: picker) {
                innerButton.sendActions(for: .touchUpInside)
            } else {
                logger.error("Broadcast picker inner button not found")
            }
        }

        private func findButton(in view: UIView) -> UIButton? {
            if let b = view as? UIButton { return b }
            for sub in view.subviews {
                if let b = findButton(in: sub) { return b }
            }
            return nil
        }
    }
}

// MARK: - Hidden debug menu (long-press version)

private struct DebugMenuView: View {
    @Environment(\.dismiss) private var dismiss
    @AppStorage("suppressedUpdateVersion") private var suppressedUpdateVersion = ""
    @State private var updateCheckMessage: String? = nil
    @State private var saveBurstFrame: Bool = DiagnosticsExporter.saveBurstFrameEnabled
    @State private var diagMessage: String? = nil

    var body: some View {
        NavigationStack {
            List {
                Section("デバッグ") {
                    NavigationLink {
                        BattleLogViewerView()
                    } label: {
                        Label("直近の解析ログ", systemImage: "doc.text.magnifyingglass")
                    }

                    Button {
                        // 手動チェックは抑止設定を無視する (Android のデバッグメニューと同挙動)
                        Task {
                            let current = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "0"
                            if let info = await AppStoreUpdateChecker.fetch() {
                                if AppStoreUpdateChecker.isNewer(info.version, than: current) {
                                    await UIApplication.shared.open(info.storeURL)
                                } else {
                                    updateCheckMessage = "現在最新バージョンです (Ver \(current))"
                                }
                            } else {
                                updateCheckMessage = "チェックに失敗しました"
                            }
                        }
                    } label: {
                        Label("アプリのアップデートを確認", systemImage: "arrow.triangle.2.circlepath")
                            .foregroundStyle(.white)
                    }

                    Button {
                        if suppressedUpdateVersion.isEmpty {
                            updateCheckMessage = "現在抑止されている通知はありません"
                        } else {
                            suppressedUpdateVersion = ""
                            updateCheckMessage = "アップデート通知を再有効化しました"
                        }
                    } label: {
                        Label(suppressedUpdateVersion.isEmpty
                              ? "アップデート通知を再有効化 (抑止なし)"
                              : "アップデート通知を再有効化 (Ver \(suppressedUpdateVersion) を抑止中)",
                              systemImage: "bell.badge")
                            .foregroundStyle(.white)
                    }

                    NavigationLink {
                        ThresholdAdjustView()
                    } label: {
                        Label("マッチング閾値を調整", systemImage: "slider.horizontal.3")
                    }
                }

                // 診断資料の取り出し (Mac 側で解析するとき用)
                Section("診断") {
                    Toggle(isOn: $saveBurstFrame) {
                        Label("バースト先頭フレームを保存", systemImage: "photo.badge.arrow.down")
                    }
                    .onChange(of: saveBurstFrame) { _, newValue in
                        DiagnosticsExporter.saveBurstFrameEnabled = newValue
                    }
                    Button {
                        diagMessage = DiagnosticsExporter.exportToDocuments()
                    } label: {
                        Label("診断ファイルを Documents へ書き出し", systemImage: "square.and.arrow.up.on.square")
                            .foregroundStyle(.white)
                    }
                }

                // iOS 27 ScreenCaptureKit (アプリ本体プロセス内キャプチャ) の検証プロトタイプ。
                // 26.x では項目自体を出さない。
                #if canImport(ScreenCaptureKit)
                if #available(iOS 27.0, *) {
                    Section("iOS 27 検証") {
                        NavigationLink {
                            ScreenCaptureKitProbeView()
                        } label: {
                            Label("ScreenCaptureKit 検証 (プロトタイプ)", systemImage: "rectangle.dashed.badge.record")
                        }
                        // 通知の見た目確認 (実記録とは無関係。許可未取得なら先に許可ダイアログ)。
                        // 3 秒後に届くので、押したらホームに戻って確認する
                        Button {
                            CaptureNotifier.requestAuthorizationIfNeeded()
                            CaptureNotifier.previewBattleStart()
                        } label: {
                            Label("通知プレビュー: 戦闘開始 (3 秒後・ホームで確認)", systemImage: "bell")
                                .foregroundStyle(.white)
                        }
                        Button {
                            CaptureNotifier.requestAuthorizationIfNeeded()
                            CaptureNotifier.previewBattleEnd()
                        } label: {
                            Label("通知プレビュー: 戦闘終了", systemImage: "bell")
                                .foregroundStyle(.white)
                        }
                        Button {
                            CaptureNotifier.requestAuthorizationIfNeeded()
                            CaptureNotifier.previewGrandPrixRecorded()
                        } label: {
                            Label("通知プレビュー: GP記録", systemImage: "bell")
                                .foregroundStyle(.white)
                        }
                        Button {
                            CaptureNotifier.requestAuthorizationIfNeeded()
                            CaptureNotifier.previewGrandPrixUnread()
                        } label: {
                            Label("通知プレビュー: GP未取得", systemImage: "bell")
                                .foregroundStyle(.white)
                        }
                        Button {
                            CaptureNotifier.requestAuthorizationIfNeeded()
                            CaptureNotifier.previewIdleWarning()
                        } label: {
                            Label("通知プレビュー: REC 放置警告 (停止ボタン付き)", systemImage: "bell.badge")
                                .foregroundStyle(.white)
                        }
                    }
                }
                #endif
            }
            .alert("アップデート", isPresented: Binding(
                get: { updateCheckMessage != nil },
                set: { if !$0 { updateCheckMessage = nil } })) {
                Button("OK") { updateCheckMessage = nil }
            } message: {
                Text(updateCheckMessage ?? "")
            }
            .alert("診断", isPresented: Binding(
                get: { diagMessage != nil },
                set: { if !$0 { diagMessage = nil } })) {
                Button("OK") { diagMessage = nil }
            } message: {
                Text(diagMessage ?? "")
            }
            .navigationTitle("デバッグメニュー")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("閉じる") { dismiss() }
                }
            }
        }
    }
}

// MARK: - Detection threshold adjustment UI

private struct ThresholdAdjustView: View {
    @State private var vsValue: Double = DetectionThresholdsConfig.vsThreshold
    @State private var winValue: Double = DetectionThresholdsConfig.winThreshold
    @State private var loseValue: Double = DetectionThresholdsConfig.loseThreshold
    @Environment(\.dismiss) private var dismiss

    private let range: ClosedRange<Double> = DetectionThresholdsConfig.minimum...DetectionThresholdsConfig.maximum
    private let step: Double = 0.05

    var body: some View {
        Form {
            Section {
                Text("VS / WIN / LOSE 検知の閾値を調整します (\(format(DetectionThresholdsConfig.minimum)) 〜 \(format(DetectionThresholdsConfig.maximum)))。\n値を上げると誤検知が減りますが、本来の検知も逃しやすくなります。")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            thresholdRow(label: "VS 検知", value: $vsValue, default: DetectionThresholdsConfig.defaultVS)
            thresholdRow(label: "WIN 検知", value: $winValue, default: DetectionThresholdsConfig.defaultWin)
            thresholdRow(label: "LOSE 検知", value: $loseValue, default: DetectionThresholdsConfig.defaultLose)

            Section {
                Button("デフォルトに戻す") {
                    vsValue = DetectionThresholdsConfig.defaultVS
                    winValue = DetectionThresholdsConfig.defaultWin
                    loseValue = DetectionThresholdsConfig.defaultLose
                }
                .foregroundStyle(.orange)
            }
        }
        .navigationTitle("マッチング閾値を調整")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button("保存") {
                    DetectionThresholdsConfig.vsThreshold = vsValue
                    DetectionThresholdsConfig.winThreshold = winValue
                    DetectionThresholdsConfig.loseThreshold = loseValue
                    dismiss()
                }
                .bold()
            }
        }
    }

    @ViewBuilder
    private func thresholdRow(label: String, value: Binding<Double>, default defaultValue: Double) -> some View {
        Section {
            HStack {
                Text(label).bold()
                Spacer()
                Text(format(value.wrappedValue))
                    .monospacedDigit()
                    .foregroundStyle(.white)
            }
            Slider(value: value, in: range, step: step)
            Text("デフォルト: \(format(defaultValue))")
                .font(.caption2)
                .foregroundStyle(.secondary)
        }
    }

    private func format(_ v: Double) -> String { String(format: "%.2f", v) }
}

#Preview {
    ContentView()
}
