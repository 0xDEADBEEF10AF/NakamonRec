import Foundation
import CoreGraphics

/// グランプリの勝敗画面に重なる「レーティングパネル」から、現在のレーティングと
/// 必要レーティング(あと)を読み取る。純 Swift / CoreGraphics 実装 (OpenCV 非依存)。
///
/// 手法 (プロトタイプで実スクショ 6 枚 12/12 検証済み):
/// - 数字はゲームの固定ビットマップフォント。0〜9・小数点のグリフを Resource
///   (grandprix_glyphs.json、24×36 二値) として同梱し、切り出した桁を 24×36 に
///   正規化して pixel 一致率で分類する。
/// - 行位置は 1080×2364 基準の固定バンドを画面幅でスケール (UI は幅アンカー+上寄せ)。
/// - 現在レーティング行は白のみ。必要レーティングは「あと」ラベルと隣接するため、
///   右詰め run から桁幅 (<=20px 相当) のボックスだけを採用して数値部を切り出す。
/// - 変動レーティングは読まない (連続する現在値の差分で導出できるため)。
public enum RatingPanelReader {

    /// 読み取り結果
    public struct Reading: Equatable, Sendable {
        public let currentRating: Double
        public let neededRating: Double?   // ランクアップ戦 / GM 帯では nil
        public var borderRating: Double? { neededRating.map { currentRating + $0 } }
    }

    // MARK: - 1080×2364 基準ジオメトリ (幅でスケールする)
    private static let baseWidth = 1080.0
    private static let curYTop = 1165.0, curYBot = 1214.0     // 現在のレーティング
    private static let curXMin = 620.0,  curXMax = 1010.0
    private static let needYTop = 1438.0, needYBot = 1468.0    // 必要レーティング「あと」値
    private static let needXMin = 640.0,  needXMax = 1010.0
    private static let glyphW = 24, glyphH = 36

    /// 勝敗画面 (レーティングパネル表示中) の CGImage から読み取る。
    /// パネルが見つからない/数値が妥当でない場合は nil。
    public static func read(_ image: CGImage) -> Reading? {
        guard let px = Pixels(image) else { return nil }
        let s = Double(image.width) / baseWidth        // 幅スケール
        func scaleY(_ v: Double) -> Int { Int(v * s) }
        func scaleX(_ v: Double) -> Int { Int(v * s) }

        // 現在のレーティング (白のみ、右詰め run)
        let curBoxes = numberBoxes(px, yTop: scaleY(curYTop), yBot: scaleY(curYBot),
                                   xMin: scaleX(curXMin), xMax: scaleX(curXMax), allowColor: false)
        let curStr = String(curBoxes.map { classify(bitmap(px, box: $0, yTop: scaleY(curYTop), yBot: scaleY(curYBot), allowColor: false)) })
        guard let current = Double(curStr) else { return nil }

        // 必要レーティング (白、桁幅で「あと」かなを除外、妥当性ゲート)
        let needRun = neededBoxes(px, yTop: scaleY(needYTop), yBot: scaleY(needYBot),
                                  xMin: scaleX(needXMin), xMax: scaleX(needXMax), scale: s)
        let needStr = String(needRun.map { classify(bitmap(px, box: $0, yTop: scaleY(needYTop), yBot: scaleY(needYBot), allowColor: false)) })
        let needed: Double? = (needStr.contains(where: { $0.isNumber }) ? Double(needStr) : nil)

        return Reading(currentRating: current, neededRating: needed)
    }

    // MARK: - ピクセルバッファ

    private final class Pixels {
        let w: Int, h: Int
        private let ctx: CGContext
        private let p: UnsafeMutablePointer<UInt8>
        init?(_ img: CGImage) {
            w = img.width; h = img.height
            guard w > 0, h > 0,
                  let c = CGContext(data: nil, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4,
                                    space: CGColorSpaceCreateDeviceRGB(),
                                    bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return nil }
            c.draw(img, in: CGRect(x: 0, y: 0, width: w, height: h))
            guard let data = c.data else { return nil }
            ctx = c
            p = data.bindMemory(to: UInt8.self, capacity: w * h * 4)
        }
        /// 数字画素: 白は常に。シアン(変動+)/赤(変動-)は allowColor のみ。
        /// 白限定にすると勝利画面の色つき紙吹雪を誤検出しない。
        func isInk(_ x: Int, _ y: Int, allowColor: Bool) -> Bool {
            guard x >= 0, x < w, y >= 0, y < h else { return false }
            let i = (y * w + x) * 4
            let r = Int(p[i]), g = Int(p[i + 1]), b = Int(p[i + 2])
            if (r * 299 + g * 587 + b * 114) / 1000 > 165 { return true }
            if allowColor {
                if r < 110 && g > 140 && b > 140 { return true }   // cyan
                if r > 150 && g < 110 && b < 110 { return true }    // red
            }
            return false
        }
    }

    // MARK: - セグメンテーション

    /// バンド内の数字ボックスを右詰め連続 run で返す (ラベルとの大ギャップで停止)。
    // gapMax=40: 桁間ギャップ (narrow な "1" が並ぶと ~22px になる) は繋ぎ、ラベルとの
    // 大ギャップ (~300px) では停止する。18 だと "2115.9" の "11" 間 22px で切れて先頭を落とす。
    private static func numberBoxes(_ px: Pixels, yTop: Int, yBot: Int, xMin: Int, xMax: Int,
                                    allowColor: Bool, gapMax: Int = 40) -> [(x0: Int, x1: Int)] {
        guard yTop >= 0, yBot < px.h, xMin >= 0, xMax <= px.w, xMin < xMax, yTop <= yBot else { return [] }
        var colHas = [Bool](repeating: false, count: xMax - xMin)
        for x in xMin..<xMax {
            for y in yTop...yBot where px.isInk(x, y, allowColor: allowColor) { colHas[x - xMin] = true; break }
        }
        var boxes: [(Int, Int)] = []
        var cs = -1
        for xi in 0..<colHas.count {
            let x = xi + xMin
            if colHas[xi] { if cs < 0 { cs = x } }
            else if cs >= 0 { if x - cs >= 2 { boxes.append((cs, x - 1)) }; cs = -1 }
        }
        if cs >= 0 { boxes.append((cs, xMax - 1)) }
        guard !boxes.isEmpty else { return [] }
        var run: [(Int, Int)] = [boxes.removeLast()]
        while let last = boxes.last {
            if run.first!.0 - last.1 <= gapMax { run.insert(last, at: 0); boxes.removeLast() } else { break }
        }
        return run.map { (x0: $0.0, x1: $0.1) }
    }

    /// 必要レーティングの数値ボックス: 右詰め run から桁幅 (<=20px @1080、スケール適用)
    /// の間だけ採用し、幅の広い「あと」かなで停止する。
    private static func neededBoxes(_ px: Pixels, yTop: Int, yBot: Int, xMin: Int, xMax: Int,
                                    scale: Double) -> [(x0: Int, x1: Int)] {
        let run = numberBoxes(px, yTop: yTop, yBot: yBot, xMin: xMin, xMax: xMax, allowColor: false)
        let maxDigitW = Int(20.0 * scale)
        var out: [(x0: Int, x1: Int)] = []
        for box in run.reversed() {
            if box.x1 - box.x0 + 1 <= maxDigitW { out.insert(box, at: 0) } else { break }
        }
        return out
    }

    /// ボックスを 24×36 二値ビットマップに正規化 (タイトな縦範囲でトリミング)。
    private static func bitmap(_ px: Pixels, box: (x0: Int, x1: Int), yTop: Int, yBot: Int, allowColor: Bool) -> [Bool] {
        var top = yBot, bot = yTop
        for y in yTop...yBot {
            for x in box.x0...box.x1 where px.isInk(x, y, allowColor: allowColor) {
                if y < top { top = y }; if y > bot { bot = y }
            }
        }
        if top > bot { top = yTop; bot = yBot }
        let bw = box.x1 - box.x0 + 1, bh = bot - top + 1
        var out = [Bool](repeating: false, count: glyphW * glyphH)
        for ty in 0..<glyphH {
            for tx in 0..<glyphW {
                let sx = box.x0 + tx * bw / glyphW
                let sy = top + ty * bh / glyphH
                out[ty * glyphW + tx] = px.isInk(sx, sy, allowColor: allowColor)
            }
        }
        return out
    }

    // MARK: - 分類 (同梱グリフとの pixel 一致率)

    private static func classify(_ bm: [Bool]) -> Character {
        var best: Character = "?"; var bestScore = -1
        for (ch, glyph) in glyphTable {
            var agree = 0
            for i in 0..<bm.count where bm[i] == glyph[i] { agree += 1 }
            if agree > bestScore { bestScore = agree; best = ch }
        }
        return best
    }

    // MARK: - グリフ辞書 (Resource からロード、char → 24×36 二値)

    private static let glyphTable: [Character: [Bool]] = {
        guard let url = Bundle.module.url(forResource: "grandprix_glyphs", withExtension: "json"),
              let data = try? Data(contentsOf: url),
              let raw = try? JSONDecoder().decode([String: [String]].self, from: data) else {
            return [:]
        }
        var table: [Character: [Bool]] = [:]
        for (key, rows) in raw {
            let ch: Character = (key == "dot") ? "." : Character(key)
            var bits = [Bool](); bits.reserveCapacity(glyphW * glyphH)
            for row in rows { for c in row { bits.append(c == "#") } }
            if bits.count == glyphW * glyphH { table[ch] = bits }
        }
        return table
    }()
}
