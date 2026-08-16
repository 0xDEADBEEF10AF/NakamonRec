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

    // MARK: - 16:9 (iPhone SE 系) プロファイル
    //
    // SE3 実測 (750×1334, 2026-08-16 改訂): SELECT.png (水色フォーカス角) との NCC 照合で
    // 角の実位置を直接測定した。x=0.7680 (576px、4枚全てで一致)、静止位置 P1 0.4940 /
    // P2 0.6904 / P3 0.8868 (P3 はリスト下端 y≈1184 で角が見切れる)。
    // 行ピッチ 0.1964H (262px、自己相関の鋭ピーク)。P3 = P1 + 2×262 = 1183 が
    // P3 フォーカス画像の下端水色スリット (y1169-1184) と一致し三点整合。
    // ※旧値 (x0.787 / 0.536/0.744/0.953 / ピッチ278px) は水色角でない別構造物の
    //   誤測定で、固定校正の緑枠が角に載らない実害を出した — 使用禁止。
    // パーティ一覧の最大スクロール量 0.1687H (225px、相互相関で実測)。
    // 可動域 225 < ピッチ 262 のため「静止位置から上へ可動域ぶんの帯域窓」で
    // 各パーティの存在帯域 (P1 0.33-0.51 / P2 0.52-0.70 / P3 0.72-0.90) は互いに素になり、
    // どのスクロール位置で選んでも位置だけから一意にパーティ特定できる。

    /// 16:9 プロファイル判定。SE3 = h/w 1.78、19.5:9 iPhone = 2.16 で間は大きく空いている。
    /// スクショ/配信フレーム/画面サイズのどれで判定しても同じ結果になる
    public static func isWide16x9(width: Double, height: Double) -> Bool {
        guard width > 0 else { return false }
        return height / width < 1.85
    }

    /// 16:9 のパーティ枠静止位置 (未スクロール時)。
    /// heightRatio はコンテンツ画素で正方形になるよう 16:9 の縦横比で換算
    public static let partySelectROIs16x9: [CalibrationROI] = (0..<3).map { i in
        let centersY: [Double] = [0.4940, 0.6904, 0.8868]
        return CalibrationROI(
            centerXRatio: 0.7680,
            centerYRatio: centersY[i],
            widthRatio:  50.0 / 1080.0,
            heightRatio: (50.0 / 1080.0) * (750.0 / 1334.0),
            searchHMarginRatio: 30.0 / 1080.0,
            searchVMarginRatio: 100.0 / 2364.0
        )
    }

    /// 16:9 の行ピッチ。P3 は未スクロール校正画像で見切れるため P2 + pitch で外挿する
    public static let partyRowPitch16x9 = 262.0 / 1334.0
    /// 16:9 の実行時スクロール吸収 (上方向) = 実測の最大スクロール量
    public static let partyScrollAllowanceVRatio16x9 = 225.0 / 1334.0
    /// 16:9 の実行時下方向マージン。帯域同士の緩衝 (~0.04H) を保つためバウンス吸収ぶんのみ
    public static let partyDownMarginVRatio16x9 = 15.0 / 1334.0

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

    /// 単一画面ぶんだけをデフォルトに戻す。
    /// wide16x9 = true のときパーティ選択は 16:9 プロファイルの既定値に戻す
    public static func reset(screen: CalibrationScreen, wide16x9: Bool = false) {
        var cfg = load()
        switch screen {
        case .partySelect: cfg.partySelectROIs = wide16x9 ? CalibrationDefaults.partySelectROIs16x9
                                                          : CalibrationDefaults.partySelectROIs
        case .battlePrep:
            cfg.battlePrepVSROI = CalibrationDefaults.battlePrepVSROI
            cfg.battlePrepMonsterROIs = CalibrationDefaults.battlePrepMonsterROIs
        case .win:  cfg.winROI = CalibrationDefaults.winROI
        case .lose: cfg.loseROI = CalibrationDefaults.loseROI
        }
        save(cfg)
    }
}
