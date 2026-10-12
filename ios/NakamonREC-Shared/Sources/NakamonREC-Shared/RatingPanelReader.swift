import Foundation
import CoreGraphics

/// グランプリの勝敗画面に重なる「レーティングパネル」から、現在のレーティングと
/// 必要レーティング(あと)を読み取る。純 Swift / CoreGraphics 実装 (OpenCV 非依存)。
///
/// - 数字はゲームの固定ビットマップフォント。0〜9・小数点のグリフを Resource
///   (grandprix_glyphs.json、24×36 二値) として同梱し、切り出した桁を 24×36 に正規化して分類。
/// - 行位置は 1080×2364 基準の固定バンドを画面幅でスケール (UI は幅アンカー+上寄せ) するのが
///   高速パス。ただし固定バンドは縦 ±5px しか許容しないため (2026-10-12 YouTube 素材で
///   約 60px のずれが判明)、読めないフレームでは **ラベルアンカー探索** に落ちる:
///   「現在の」(現在のレーティング行の先頭) をダイス係数で中央帯から探し、見つかった行を
///   基準に数字を読む。成功したオフセットは `GrandPrixMode.learnedRowOffsetY` に学習し、
///   次回以降は探索なしで即読みする (端末ごとのパネル位置は一定という前提)。
/// - 現在レーティング行は白のみ。必要レーティングは「あと」ラベルと隣接するため、
///   右詰め run から桁幅 (<=20px 相当) のボックスだけ採用して数値部を切り出す。
/// - 変動レーティングは読まない (連続する現在値の差分で導出できるため)。
/// - 「無警告誤記録をしない」: アンカー不一致 / 桁一致率不足 / 妥当性外はすべて nil。
public enum RatingPanelReader {

    /// 読み取り結果
    public struct Reading: Equatable, Sendable {
        public let currentRating: Double
        public let neededRating: Double?   // ランクアップ戦 / GM 帯では nil
        /// 実際に使った行オフセット (1080 基準 px、0 = 固定バンドそのまま)。
        /// RankTierReader にも同じオフセットを渡してエンブレム帯を追従させる。
        public let rowOffsetY: Int
        public var borderRating: Double? { neededRating.map { currentRating + $0 } }
    }

    // MARK: - 1080×2364 基準ジオメトリ (幅でスケールする)
    private static let baseWidth = 1080.0
    private static let curYTop = 1165.0, curYBot = 1214.0     // 現在のレーティング
    private static let curXMin = 620.0,  curXMax = 1010.0
    private static let needYTop = 1438.0, needYBot = 1468.0    // 必要レーティング「あと」値
    private static let needXMin = 640.0,  needXMax = 1010.0
    private static let glyphW = 24, glyphH = 36

    /// 桁分類の最低一致率。下回る桁があれば誤読とみなす (現在行→読み取り全体を破棄 /
    /// 必要行→必要値なしとして継続)。実測: 正しく読める桁 ≥0.85、スケール歪み
    /// (異アスペクト端末のスクショ紙芝居等) での誤分類 ≤0.74 — もっともらしい偽値
    /// (例 2208.1→7705.1) が妥当性ゲートを通過するのをここで防ぐ。
    private static let minAgreement = 0.82

    /// 小数点ルール: この幅 (1080 基準 px) 以下の箱は分類結果に関わらず「.」とみなし一致率ゲートを免除する。
    /// 実機では「.」8px に対して最細の数字「1」が 14px (現在行) なので誤判定の余地がない。
    /// 低画質入力では小数点だけ潰れて一致率 0.79 程度に落ち全体が棄却されるため (2026-10-12 実測)。
    private static let dotMaxWidthCur = 10.0
    private static let dotMaxWidthNeed = 7.0    // 必要行は数字が小さい (「.」~5px / 「1」~9px)

    // MARK: - ラベルアンカー探索 (1080 基準)
    /// 「現在の」アンカーの基準左上 (r3 実測)。探索で見つかった y との差が行オフセット。
    private static let anchorCurBaseX = 87, anchorCurBaseY = 1165
    /// 「必要」アンカーの基準左上。現在行からの相対 +272 で第 2 探索する。
    private static let anchorNeedBaseX = 450, anchorNeedBaseY = 1437
    /// 探索帯: 縦は画面高の 35〜65% (パネルは必ず中央に寄る)、横は左端〜320px (ラベルは左寄せ)
    private static let searchYMinRatio = 0.35, searchYMaxRatio = 0.65
    private static let searchXMax = 320
    /// 第 2 アンカーの探索窓 (現在行基準 +272 ± 40、x 400〜560)
    private static let needSearchDY = 40, needSearchX0 = 400, needSearchW = 160
    /// ダイス係数しきい値。実測: 実機 0.99〜1.00 / 低画質動画 0.77〜0.88 / 非パネル画面 ≤0.61
    private static let anchorThreshold = 0.72
    /// 粗探索の刻み (y 4px / x 2px) と密探索の半径
    private static let coarseStepY = 4, coarseStepX = 2, refineRadius = 4

    /// 直近の read() の診断文字列 (フライトログ用)。タイムアウト時に原因を切り分けるために残す。
    /// 例: "固定帯域: 箱なし / アンカー: 0.58@y1390 (しきい値未満)"
    nonisolated(unsafe) public static var lastDiagnostic: String = ""

    // MARK: - 読み取り (公開 API)

    /// 勝敗画面 (レーティングパネル表示中) の CGImage から読み取る。
    /// パネルが見つからない/数値が妥当でない/分類の信頼度が低い場合は nil。
    public static func read(_ image: CGImage) -> Reading? {
        guard let px = Pixels(image) else { return nil }
        let s = Double(image.width) / baseWidth        // 幅スケール
        var diag: [String] = []

        // 1) 高速パス: 固定バンド (Pixel 系 19.5:9〜20:9 はここで読める)
        if let r = readRows(px, scale: s, offsetY: 0, needOffsetY: 0, diag: &diag) { lastDiagnostic = ""; return r }

        // 2) 学習済みオフセット (以前アンカー探索で見つけた端末固有のずれ)
        if let learned = GrandPrixMode.learnedRowOffsetY, learned != 0,
           let r = readRows(px, scale: s, offsetY: learned, needOffsetY: learned, diag: &diag) {
            lastDiagnostic = ""
            return r
        }

        // 3) アンカー探索: 「現在の」を中央帯から探し、その行を基準に読む
        guard let anchorCur = anchors["current"] else { lastDiagnostic = "アンカー資源なし"; return nil }
        let canonH = Int(Double(image.height) / s)
        let by0 = Int(Double(canonH) * searchYMinRatio), by1 = Int(Double(canonH) * searchYMaxRatio)
        let band = canonicalBand(px, scale: s, x0: 0, y0: by0, w: searchXMax, h: max(0, by1 - by0))
        let m = slideMatch(anchorCur, in: band, stepX: coarseStepX, stepY: coarseStepY, refine: refineRadius)
        guard m.score >= anchorThreshold else {
            diag.append(String(format: "アンカー: %.2f@y%d (しきい値 %.2f 未満)", m.score, by0 + m.y, anchorThreshold))
            lastDiagnostic = diag.joined(separator: " / "); return nil
        }
        let offsetY = (by0 + m.y) - anchorCurBaseY
        // 必要行は第 2 アンカー「必要」で行位置を補正 (見つからなければ現在行と同じオフセット)
        var needOffsetY = offsetY
        if let anchorNeed = anchors["need"] {
            let ny0 = anchorNeedBaseY + offsetY - needSearchDY
            let band2 = canonicalBand(px, scale: s, x0: needSearchX0, y0: ny0, w: needSearchW,
                                      h: needSearchDY * 2 + anchorNeed.h)
            let m2 = slideMatch(anchorNeed, in: band2, stepX: 1, stepY: 1, refine: 0)
            if m2.score >= anchorThreshold { needOffsetY = (ny0 + m2.y) - anchorNeedBaseY }
        }
        diag.append(String(format: "アンカー: %.2f Δy%+d", m.score, offsetY))
        guard let r = readRows(px, scale: s, offsetY: offsetY, needOffsetY: needOffsetY, diag: &diag) else {
            lastDiagnostic = diag.joined(separator: " / "); return nil
        }
        // 端末固有のずれとして学習 (次回以降は探索なしで読める)
        GrandPrixMode.learnedRowOffsetY = offsetY
        lastDiagnostic = ""
        return r
    }

    // MARK: - 行の読み取り (オフセット指定)

    /// 固定バンドを offsetY (1080 基準 px) だけ縦にずらして読む。
    private static func readRows(_ px: Pixels, scale s: Double, offsetY: Int, needOffsetY: Int,
                                 diag: inout [String]) -> Reading? {
        func scaleY(_ v: Double) -> Int { Int(v * s) }
        func scaleX(_ v: Double) -> Int { Int(v * s) }
        let label = offsetY == 0 ? "固定帯域" : "帯域Δ\(offsetY >= 0 ? "+" : "")\(offsetY)"

        // 現在のレーティング (白のみ、右詰め run)。低信頼度の桁があれば読み取り全体を破棄
        let cyTop = scaleY(curYTop + Double(offsetY)), cyBot = scaleY(curYBot + Double(offsetY))
        let curBoxes = numberBoxes(px, yTop: cyTop, yBot: cyBot, xMin: scaleX(curXMin), xMax: scaleX(curXMax), allowColor: false)
        guard !curBoxes.isEmpty else { diag.append("\(label): 箱なし"); return nil }
        guard let curStr = readDigits(px, boxes: curBoxes, yTop: cyTop, yBot: cyBot, scale: s, dotMaxWidth: dotMaxWidthCur),
              let current = Double(curStr) else {
            diag.append("\(label): 桁一致率不足 (箱\(curBoxes.count))"); return nil
        }

        // 必要レーティング (白、桁幅で「あと」かなを除外、妥当性ゲート)。
        // 低信頼度の桁があれば「必要値なし」として現在値のみ記録する
        let nyTop = scaleY(needYTop + Double(needOffsetY)), nyBot = scaleY(needYBot + Double(needOffsetY))
        let needRun = neededBoxes(px, yTop: nyTop, yBot: nyBot, xMin: scaleX(needXMin), xMax: scaleX(needXMax), scale: s)
        let needStr = readDigits(px, boxes: needRun, yTop: nyTop, yBot: nyBot, scale: s, dotMaxWidth: dotMaxWidthNeed) ?? ""
        let needed: Double? = (needStr.contains(where: { $0.isNumber }) ? Double(needStr) : nil)

        return Reading(currentRating: current, neededRating: needed, rowOffsetY: offsetY)
    }

    /// 箱列を文字列化。細い箱は小数点、それ以外は一致率ゲート付きで分類。1 桁でも不合格なら nil
    private static func readDigits(_ px: Pixels, boxes: [(x0: Int, x1: Int)], yTop: Int, yBot: Int,
                                   scale s: Double, dotMaxWidth: Double) -> String? {
        var out = ""
        for box in boxes {
            let wCanon = Double(box.x1 - box.x0 + 1) / s
            if wCanon <= dotMaxWidth { out.append("."); continue }
            let g = classify(bitmap(px, box: box, yTop: yTop, yBot: yBot, allowColor: false))
            guard g.agreement >= minAgreement else { return nil }
            out.append(g.ch)
        }
        return out
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

    private static func classify(_ bm: [Bool]) -> (ch: Character, agreement: Double) {
        var best: Character = "?"; var bestScore = -1
        for (ch, glyph) in glyphTable {
            var agree = 0
            for i in 0..<bm.count where bm[i] == glyph[i] { agree += 1 }
            if agree > bestScore { bestScore = agree; best = ch }
        }
        return (best, bm.isEmpty ? 0 : Double(bestScore) / Double(bm.count))
    }

    // MARK: - アンカー探索 (二値ビットマップ + ダイス係数)

    private struct Bin { var bits: [Bool]; var w: Int; var h: Int }

    /// 画像の一部を 1080 基準の canonical 座標で二値化 (白インク、nearest neighbor)。
    /// 画像外は false。
    private static func canonicalBand(_ px: Pixels, scale s: Double, x0: Int, y0: Int, w: Int, h: Int) -> Bin {
        var bits = [Bool](repeating: false, count: max(0, w * h))
        for cy in 0..<h {
            let sy = Int(Double(y0 + cy) * s)
            for cx in 0..<w {
                bits[cy * w + cx] = px.isInk(Int(Double(x0 + cx) * s), sy, allowColor: false)
            }
        }
        return Bin(bits: bits, w: w, h: h)
    }

    /// 粗 (stepY/stepX) → 密 (±refine, 1px) の 2 段スライド照合。
    /// 各窓のインク量を行プレフィックス和で見積もり、テンプレのインク量とかけ離れた窓
    /// (背景のみ / 真っ白) はダイス計算をスキップする (処理時間を約 1/2〜1/3 に短縮)。
    private static func slideMatch(_ tpl: Bin, in band: Bin, stepX: Int, stepY: Int, refine: Int)
        -> (score: Double, x: Int, y: Int) {
        var best = (score: 0.0, x: 0, y: 0)
        let tplInk = tpl.bits.lazy.filter { $0 }.count
        guard tplInk > 0, band.w >= tpl.w, band.h >= tpl.h else { return best }
        let stride = band.w + 1
        var pre = [Int](repeating: 0, count: band.h * stride)
        for y in 0..<band.h {
            var acc = 0; let base = y * stride
            for x in 0..<band.w { if band.bits[y * band.w + x] { acc += 1 }; pre[base + x + 1] = acc }
        }
        let lo = tplInk * 2 / 5, hi = tplInk * 5 / 2
        func eval(_ ox: Int, _ oy: Int) {
            var wink = 0
            for ty in 0..<tpl.h { let base = (oy + ty) * stride; wink += pre[base + ox + tpl.w] - pre[base + ox] }
            guard wink >= lo && wink <= hi else { return }
            var inter = 0, bandInk = 0
            for ty in 0..<tpl.h {
                let brow = (oy + ty) * band.w + ox, trow = ty * tpl.w
                for tx in 0..<tpl.w where band.bits[brow + tx] {
                    bandInk += 1
                    if tpl.bits[trow + tx] { inter += 1 }
                }
            }
            let dice = 2.0 * Double(inter) / Double(tplInk + bandInk)
            if dice > best.score { best = (dice, ox, oy) }
        }
        for oy in Swift.stride(from: 0, through: band.h - tpl.h, by: max(1, stepY)) {
            for ox in Swift.stride(from: 0, through: band.w - tpl.w, by: max(1, stepX)) { eval(ox, oy) }
        }
        if refine > 0 {
            let c = best
            for oy in max(0, c.y - refine)...min(band.h - tpl.h, c.y + refine) {
                for ox in max(0, c.x - refine)...min(band.w - tpl.w, c.x + refine) { eval(ox, oy) }
            }
        }
        return best
    }

    // MARK: - グリフ辞書 / アンカー (Resource からロード)

    private static let glyphTable: [Character: [Bool]] = {
        guard let url = Bundle.module.url(forResource: "grandprix_glyphs", withExtension: "json"),
              let data = try? Data(contentsOf: url),
              let raw = try? JSONDecoder().decode([String: [String]].self, from: data) else {
            return [:]
        }
        var table: [Character: [Bool]] = [:]
        for (key, rows) in raw {
            let ch: Character = (key == "dot") ? "." : (key.first ?? "?")
            var bits = [Bool](); bits.reserveCapacity(glyphW * glyphH)
            for row in rows { for c in row { bits.append(c == "#") } }
            if bits.count == glyphW * glyphH { table[ch] = bits }
        }
        return table
    }()

    /// ラベルアンカー (「現在の」= current 156×52 /「必要」= need 54×31)。ゲームフォントの字形のみ。
    private static let anchors: [String: Bin] = {
        guard let url = Bundle.module.url(forResource: "grandprix_label_anchors", withExtension: "json"),
              let data = try? Data(contentsOf: url),
              let raw = try? JSONDecoder().decode([String: [String]].self, from: data) else {
            return [:]
        }
        var table: [String: Bin] = [:]
        for (key, rows) in raw {
            guard let firstRow = rows.first, !firstRow.isEmpty else { continue }
            let w = firstRow.count, h = rows.count
            var bits = [Bool](); bits.reserveCapacity(w * h)
            for row in rows { for c in row { bits.append(c == "#") } }
            if bits.count == w * h { table[key] = Bin(bits: bits, w: w, h: h) }
        }
        return table
    }()
}
