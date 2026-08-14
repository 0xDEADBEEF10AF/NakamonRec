import Foundation

/// 校正対象 4 画面
public enum CalibrationScreen: String, Codable, CaseIterable, Identifiable, Sendable {
    case partySelect
    case battlePrep
    case win
    case lose

    public var id: String { rawValue }
}

/// 1 つの ROI の校正データ。座標・サイズは 1080-ref (= Pixel 10 Pro) の比率。
/// - center は ROI 中心位置の比率
/// - width/height はテンプレートの (校正後の) サイズ比率
/// - searchHMargin / searchVMargin は本番マッチング時に matchTemplate が探索する余白 (薄緑塗りに対応)
public struct CalibrationROI: Codable, Hashable, Sendable {
    public var centerXRatio: Double
    public var centerYRatio: Double
    public var widthRatio: Double
    public var heightRatio: Double
    public var searchHMarginRatio: Double
    public var searchVMarginRatio: Double

    public init(centerXRatio: Double, centerYRatio: Double,
                widthRatio: Double, heightRatio: Double,
                searchHMarginRatio: Double, searchVMarginRatio: Double) {
        self.centerXRatio = centerXRatio
        self.centerYRatio = centerYRatio
        self.widthRatio = widthRatio
        self.heightRatio = heightRatio
        self.searchHMarginRatio = searchHMarginRatio
        self.searchVMarginRatio = searchVMarginRatio
    }
}

/// 全画面の ROI 構成。App Group JSON `calibration_data.json` に永続化される。
public struct CalibrationConfig: Codable, Sendable {
    public var partySelectROIs: [CalibrationROI]   // [P1, P2, P3]
    public var battlePrepVSROI: CalibrationROI
    public var battlePrepMonsterROIs: [CalibrationROI]  // [my0..my3, enemy0..enemy3]
    public var winROI: CalibrationROI
    public var loseROI: CalibrationROI

    public init(partySelectROIs: [CalibrationROI],
                battlePrepVSROI: CalibrationROI,
                battlePrepMonsterROIs: [CalibrationROI],
                winROI: CalibrationROI,
                loseROI: CalibrationROI) {
        self.partySelectROIs = partySelectROIs
        self.battlePrepVSROI = battlePrepVSROI
        self.battlePrepMonsterROIs = battlePrepMonsterROIs
        self.winROI = winROI
        self.loseROI = loseROI
    }
}

/// Pixel 10 Pro 1080×2364 リファレンスのデフォルト値。`デフォルト` ボタン用 / 未校正時のフォールバック。
public enum CalibrationDefaults {
    // パーティ選択 3 box (Android partySelectBoxes 由来)
    // SELECT.png は 50×50 だが iOS では計算上 50/1080, 50/2364 比率にする
    public static let partySelectROIs: [CalibrationROI] = (0..<3).map { i in
        let centersY: [Double] = [0.436, 0.605, 0.774]
        return CalibrationROI(
            centerXRatio: 0.787,
            centerYRatio: centersY[i],
            widthRatio:  50.0 / 1080.0,
            heightRatio: 50.0 / 2364.0,
            searchHMarginRatio: 30.0 / 1080.0,
            searchVMarginRatio: 100.0 / 2364.0
        )
    }

    /// パーティ選択の実行時判定に加える上方向スクロール吸収分 (@1080×2364-ref で 100px)。
    /// リストのスクロールでハイライト枠は上方向にしか動かないため、探索窓は
    /// 「上 = searchVMargin + この値 / 下 = searchVMargin」の非対称 (上200/下100) になる。
    /// 対称窓 API では「中心を上へ半分ずらした ±(margin+半分) 窓」として扱う。
    /// 実行時判定 (NakamonCaptureEngine)・校正画面の薄緑表示/スコアテストで共用。
    public static let partyScrollAllowanceVRatio = 100.0 / 2364.0

    // 対戦じゅんびの VS ロゴ (VS_FM 242×148 を Pixel 10 Pro 1080 基準)
    public static let battlePrepVSROI = CalibrationROI(
        centerXRatio: 0.5,
        centerYRatio: 0.23,
        widthRatio:  242.0 / 1080.0,
        heightRatio: 148.0 / 2364.0,
        searchHMarginRatio: 100.0 / 1080.0,
        searchVMarginRatio: 150.0 / 2364.0
    )

    // 対戦じゅんびの 8 モンスタースロット (NakamonCaptureEngine の slotConfigs と整合)
    // 中心は myParty y=1560/2364, enemy y=840/2364 (iOS 用シフト済み)
    public static let battlePrepMonsterROIs: [CalibrationROI] = {
        let myXs: [Double] = [196.0/1080.0, 391.0/1080.0, 585.0/1080.0, 780.0/1080.0]
        let enemyXs: [Double] = [201.0/1080.0, 396.0/1080.0, 590.0/1080.0, 785.0/1080.0]
        let myY = 1560.0 / 2364.0
        let enemyY = 840.0 / 2364.0
        let w: Double = 80.0 / 1080.0
        let h: Double = 130.0 / 2364.0
        // 本番探索範囲: 160×210 (テンプレ + 各辺 40px) を実現するように search margins を計算
        let hMarg = 40.0 / 1080.0
        let vMarg = 40.0 / 2364.0
        var rois: [CalibrationROI] = []
        for x in myXs {
            rois.append(CalibrationROI(centerXRatio: x, centerYRatio: myY,
                                       widthRatio: w, heightRatio: h,
                                       searchHMarginRatio: hMarg, searchVMarginRatio: vMarg))
        }
        for x in enemyXs {
            rois.append(CalibrationROI(centerXRatio: x, centerYRatio: enemyY,
                                       widthRatio: w, heightRatio: h,
                                       searchHMarginRatio: hMarg, searchVMarginRatio: vMarg))
        }
        return rois
    }()

    // 勝利 / ざんねん (1 ROI ずつ)
    public static let winROI = CalibrationROI(
        centerXRatio: 0.5,
        centerYRatio: 0.25,
        widthRatio:  850.0 / 1080.0,
        heightRatio: 330.0 / 2364.0,
        searchHMarginRatio: 100.0 / 1080.0,
        searchVMarginRatio: 150.0 / 2364.0
    )
    public static let loseROI = CalibrationROI(
        centerXRatio: 0.5,
        centerYRatio: 0.25,
        widthRatio:  780.0 / 1080.0,
        heightRatio: 190.0 / 2364.0,
        searchHMarginRatio: 100.0 / 1080.0,
        searchVMarginRatio: 150.0 / 2364.0
    )

    public static let defaultConfig = CalibrationConfig(
        partySelectROIs: partySelectROIs,
        battlePrepVSROI: battlePrepVSROI,
        battlePrepMonsterROIs: battlePrepMonsterROIs,
        winROI: winROI,
        loseROI: loseROI
    )
}

/// CalibrationConfig を App Group に JSON 永続化するヘルパー。
public enum CalibrationStore {
    private static let appGroupID = "group.com.android.NakamonREC-iOS"
    private static let fileName = "calibration_data.json"

    private static var fileURL: URL? {
        guard let container = FileManager.default
            .containerURL(forSecurityApplicationGroupIdentifier: appGroupID) else {
            return nil
        }
        return container.appendingPathComponent(fileName)
    }

    /// 現在の構成 (未保存ならデフォルト)
    public static func load() -> CalibrationConfig {
        guard let url = fileURL,
              FileManager.default.fileExists(atPath: url.path),
              let data = try? Data(contentsOf: url),
              let cfg = try? JSONDecoder().decode(CalibrationConfig.self, from: data) else {
            return CalibrationDefaults.defaultConfig
        }
        return cfg
    }

    public static func save(_ config: CalibrationConfig) {
        guard let url = fileURL else { return }
        let encoder = JSONEncoder()
        encoder.outputFormatting = .prettyPrinted
        if let data = try? encoder.encode(config) {
            try? data.write(to: url, options: .atomic)
        }
    }

    /// 全画面をデフォルトに戻す
    public static func resetAll() {
        save(CalibrationDefaults.defaultConfig)
    }

    /// 単一画面ぶんだけをデフォルトに戻す
    public static func reset(screen: CalibrationScreen) {
        var cfg = load()
        switch screen {
        case .partySelect: cfg.partySelectROIs = CalibrationDefaults.partySelectROIs
        case .battlePrep:
            cfg.battlePrepVSROI = CalibrationDefaults.battlePrepVSROI
            cfg.battlePrepMonsterROIs = CalibrationDefaults.battlePrepMonsterROIs
        case .win:  cfg.winROI = CalibrationDefaults.winROI
        case .lose: cfg.loseROI = CalibrationDefaults.loseROI
        }
        save(cfg)
    }
}
