import Foundation
import CoreGraphics

/// グランプリの勝敗画面 (レーティングパネル表示中) のエンブレム文字帯から
/// ランク帯 (マスター3/2/1・グランドマスター) を識別する。
/// 純 Swift / CoreGraphics 実装 (OpenCV 非依存)。
///
/// 手法 (プロトタイプで実スクショ 9 枚 9/9 + ネット素材 Ⅰ/Ⅱ/Ⅲ 検証済み):
/// - エンブレムは「現在のランク」を表す (ランクアップ戦では戦闘後の新ランク)。
/// - M帯とGMで文字サイズが異なる (高さ ~42px vs ~30px @1080) ため、
///   アンカー語 2 種 (「マスター」=M帯スケール /「グランド」=GMスケール) を
///   Resource (grandprix_rank_anchors.json、二値ビットマップ=ゲームフォント字形) として
///   同梱し、探索帯にスライド照合 (dice 係数) して高い方を採用する。
/// - M帯はアンカー右側のローマ数字の柱数 (Ⅰ/Ⅱ/Ⅲ = 1/2/3 本) で分類。
/// - エンブレムの金縁・月桂樹 (金色: r-b 大) はインクから除外し、柱の誤カウントを防ぐ。
/// - 位置は 1080×2364 基準の探索帯を画面幅でスケール (通常パネルとランクアップ画面で
///   ~20px ずれるため固定点ではなく帯走査)。識別できなければ nil (誤記録より無記録)。
public enum RankTierReader {

    // MARK: - 1080×2364 基準ジオメトリ
    private static let baseWidth = 1080.0
    private static let bandX = 140, bandY = 1440, bandW = 380, bandH = 80
    private static let masterThr = 140, grandThr = 150
    private static let scoreThreshold = 0.72   // 実測: 正解 ≥0.99 / 他帯誤マッチ ≤0.64
    // ローマ数字柱カウント (アンカー右側窓、1080 基準)
    private static let barWinGap = 6, barWinW = 74, barWinH = 46
    private static let barMinInkH = 21          // インク高 >= 50% of 文字高42px
    private static let barMinW = 4, barMaxW = 24
    private static let barClusterGap = 14       // 柱同士の最大ギャップ
    private static let barFirstMaxX = 40        // 先頭柱はアンカー近傍のみ

    /// ランク帯を識別する。識別できなければ nil。
    /// 戻り値は `GrandPrixRecord.rankTiers` のいずれか。
    public static func read(_ image: CGImage) -> String? {
        guard let px = Pixels(image) else { return nil }
        let s = Double(image.width) / baseWidth

        // 探索帯を 1080 基準の canonical 座標で二値化 (nearest neighbor サンプリング)
        let bandM = canonicalBand(px, scale: s, thr: masterThr)
        let bandG = canonicalBand(px, scale: s, thr: grandThr)
        guard let master = anchors["master"], let grand = anchors["grand"] else { return nil }

        let m = slideMatch(master, in: bandM)
        let g = slideMatch(grand, in: bandG)

        if m.score >= scoreThreshold && m.score >= g.score {
            // アンカー右端の柱カウント窓 (canonical 座標)
            let winX = m.x + master.w + barWinGap
            let winY = max(0, m.y - 2)
            switch countBars(bandM, x: winX, y: winY, w: barWinW, h: min(barWinH, bandH - winY)) {
            case 1: return "マスター1"
            case 2: return "マスター2"
            case 3: return "マスター3"
            default: return nil
            }
        }
        if g.score >= scoreThreshold { return "グランドマスター" }
        return nil
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
        /// 文字インク: 明るく、かつ金色 (r-b 大: 縁・月桂樹) でない画素。
        /// 文字は白/薄紫 (M帯)・青白 (GM) なので残る。
        func isInk(_ x: Int, _ y: Int, thr: Int) -> Bool {
            guard x >= 0, x < w, y >= 0, y < h else { return false }
            let i = (y * w + x) * 4
            let r = Int(p[i]), g = Int(p[i + 1]), b = Int(p[i + 2])
            if (r * 299 + g * 587 + b * 114) / 1000 < thr { return false }
            return r - b <= 60
        }
    }

    // MARK: - 二値ビットマップ

    private struct Bin {
        var bits: [Bool]; var w: Int; var h: Int
        func at(_ x: Int, _ y: Int) -> Bool { bits[y * w + x] }
    }

    /// 探索帯を 1080 基準サイズで二値化 (非1080幅は nearest neighbor で吸収)
    private static func canonicalBand(_ px: Pixels, scale: Double, thr: Int) -> Bin {
        var bits = [Bool](repeating: false, count: bandW * bandH)
        for cy in 0..<bandH {
            let sy = Int(Double(bandY + cy) * scale)
            for cx in 0..<bandW {
                let sx = Int(Double(bandX + cx) * scale)
                bits[cy * bandW + cx] = px.isInk(sx, sy, thr: thr)
            }
        }
        return Bin(bits: bits, w: bandW, h: bandH)
    }

    // MARK: - スライド照合 (dice 係数)

    private static func slideMatch(_ tpl: Bin, in band: Bin) -> (score: Double, x: Int, y: Int) {
        var best = (score: 0.0, x: 0, y: 0)
        let tplInk = tpl.bits.lazy.filter { $0 }.count
        guard tplInk > 0, band.w >= tpl.w, band.h >= tpl.h else { return best }
        for oy in 0...(band.h - tpl.h) {
            for ox in 0...(band.w - tpl.w) {
                var inter = 0, bandInk = 0
                for ty in 0..<tpl.h {
                    let brow = (oy + ty) * band.w + ox
                    let trow = ty * tpl.w
                    for tx in 0..<tpl.w {
                        if band.bits[brow + tx] {
                            bandInk += 1
                            if tpl.bits[trow + tx] { inter += 1 }
                        }
                    }
                }
                let dice = 2.0 * Double(inter) / Double(tplInk + bandInk)
                if dice > best.score { best = (dice, ox, oy) }
            }
        }
        return best
    }

    // MARK: - ローマ数字の柱カウント (Ⅰ/Ⅱ/Ⅲ = 1/2/3)

    private static func countBars(_ band: Bin, x: Int, y: Int, w: Int, h: Int) -> Int {
        guard x >= 0, y >= 0, w > 0, h > 0, x < band.w, y < band.h else { return 0 }
        let x1 = min(x + w, band.w), y1 = min(y + h, band.h)
        var colInk = [Int](repeating: 0, count: x1 - x)
        for cx in x..<x1 {
            for cy in y..<y1 where band.at(cx, cy) { colInk[cx - x] += 1 }
        }
        // インク高しきい値以上の列をグループ化 (1列の欠けは許容)
        var groups: [(start: Int, end: Int)] = []
        var cx = 0
        while cx < colInk.count {
            if colInk[cx] >= barMinInkH {
                var end = cx, gap = 0, cur = cx + 1
                while cur < colInk.count && gap <= 1 {
                    if colInk[cur] >= barMinInkH { end = cur; gap = 0 } else { gap += 1 }
                    cur += 1
                }
                groups.append((cx, end))
                cx = end + 1
            } else { cx += 1 }
        }
        // 柱幅のグループのみ採用し、先頭柱から近接クラスタを数える
        let bars = groups.filter { ($0.end - $0.start + 1) >= barMinW && ($0.end - $0.start + 1) <= barMaxW }
        guard let first = bars.first, first.start <= barFirstMaxX else { return 0 }
        var count = 1
        var prevEnd = first.end
        for b in bars.dropFirst() {
            if b.start - prevEnd <= barClusterGap { count += 1; prevEnd = b.end } else { break }
        }
        return count
    }

    // MARK: - アンカーテンプレート (Resource からロード)

    private static let anchors: [String: Bin] = {
        guard let url = Bundle.module.url(forResource: "grandprix_rank_anchors", withExtension: "json"),
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
