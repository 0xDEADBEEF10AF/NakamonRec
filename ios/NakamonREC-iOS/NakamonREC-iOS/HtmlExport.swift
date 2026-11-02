//
//  HtmlExport.swift
//  NakamonREC-iOS
//
//  自動エクスポートの HTML 形式。Android `HtmlExport.kt` と同じ出力 (戦績画面のビューア、スマホ実寸 1080×2364)。
//  両 OS で同じ HTML になるよう、レイアウトの数値・色は Android の戦績画面 (activity_history.xml /
//  item_battle_record.xml / dimens.xml) を dp×3・sp×3 で写したものをそのまま使う。
//
//  - 上段: TOTAL WIN RATE カード + RECENT TREND (直近 20 戦の移動勝率グラフを SVG で描画)
//  - パーティ別 3 カード (最新の構成 4 体、勝率、Use:、W-L)
//  - 戦績リスト (最新が一番上。配信画面で新しい結果がすぐ目に入るように)
//  - 外部参照なし: モンスターのサムネイルは縮小 PNG を base64 で CSS クラスに 1 回だけ埋め込む
//  - 数秒ごとに自動再読込 (meta refresh)
//  集計画面 (パーティ/モンスター/グランプリ) とフィルタは載せない (ビーフ決定 2026-11-02)。
//

import Foundation
import UIKit
import NakamonREC_Shared

nonisolated enum HtmlExport {
    private static let refreshSec = 5
    /// dp → px (1080px 幅 = 360dp 相当)
    private static let s = 3
    private static let pageW = 1080
    private static let pageH = 2364
    /// 埋め込むサムネの画素数 (元は 78×130。22dp=66px の正方形に cover で収めるので高さ 100 あれば足りる)
    private static let iconW = 60
    private static let iconH = 100
    private static let graphWindow = 20

    private static let iconCacheLock = NSLock()
    nonisolated(unsafe) private static var iconCache: [String: String] = [:]

    static func render(history: BattleHistory, baseName: String) -> String {
        let records = history.records
        let wins = records.filter { $0.result == "WIN" }.count
        let losses = records.filter { $0.result == "LOSE" }.count
        let total = wins + losses
        let rate = total > 0 ? Double(wins) / Double(total) * 100.0 : 0.0

        // 使うサムネイルだけ埋め込む (id → css class)
        var iconClass: [String: String] = [:]
        var iconOrder: [String] = []
        func cls(_ id: String) -> String? {
            if id.isEmpty || id == "?" { return nil }
            if let c = iconClass[id] { return c }
            let c = "m\(iconClass.count)"
            iconClass[id] = c
            iconOrder.append(id)
            return c
        }
        for r in records { for id in r.myParty + r.enemyParty { _ = cls(id) } }

        var sb = ""
        sb.reserveCapacity(96 * 1024)
        sb += "<!doctype html><html lang=\"ja\"><head><meta charset=\"utf-8\">"
        sb += "<meta http-equiv=\"refresh\" content=\"\(refreshSec)\">"
        sb += "<meta name=\"viewport\" content=\"width=\(pageW)\">"
        sb += "<title>なかモンREC 戦績 - \(esc(baseName))</title>"
        sb += "<style>\(css())</style></head><body>"
        sb += "<div class=\"screen\">"

        // ===== 上段 =====
        sb += "<div class=\"top\">"
        sb += "<div class=\"card total\">"
        sb += "<div class=\"tlabel\">TOTAL WIN RATE</div>"
        sb += "<div class=\"trate\">\(total > 0 ? RateFormat.percent(rate) : "□")</div>"
        sb += "<div class=\"tcount\">\(total > 0 ? "\(total) Matches" : "□")</div>"
        sb += "<div class=\"twl\">\(winLose(wins, losses))</div>"
        sb += "</div>"
        sb += "<div class=\"card graph\"><div class=\"glabel\">RECENT TREND</div>"
        sb += graphSvg(records)
        sb += "</div></div>"

        // ===== パーティ別カード =====
        sb += "<div class=\"parties\">"
        for i in 0..<3 {
            let p = records.filter { $0.partyIndex == i }
            let w = p.filter { $0.result == "WIN" }.count
            let l = p.filter { $0.result == "LOSE" }.count
            let t = w + l
            let pr = t > 0 ? Double(w) / Double(t) * 100.0 : 0.0
            let usage = records.isEmpty ? 0.0 : Double(p.count) / Double(records.count) * 100.0
            let latest = p.last?.myParty ?? ["", "", "", ""]
            sb += "<div class=\"card party\"><div class=\"pname\">P\(i + 1)</div><div class=\"picons\">"
            for k in 0..<4 {
                let c = (k < latest.count) ? cls(latest[k]) : nil
                sb += c.map { "<i class=\"pi \($0)\"></i>" } ?? "<i class=\"pi hidden\"></i>"
            }
            sb += "</div><div class=\"prate\">\(t > 0 ? RateFormat.percent(pr) : "□")</div>"
            sb += "<div class=\"puse\">Use: \(RateFormat.percent(usage))</div>"
            sb += "<div class=\"pwl\">\(winLose(w, l))</div></div>"
        }
        sb += "</div>"

        // ===== 戦績リスト (最新を最上部に) =====
        sb += "<div class=\"list\" id=\"list\">"
        for r in records.reversed() {
            let resCls = r.result == "WIN" ? "win" : "lose"
            let hasMemo = r.hasMemo
            sb += "<div class=\"row\""
            if hasMemo, let memo = r.memo { sb += " title=\"\(esc(memo))\"" }
            sb += ">"
            let pno = r.partyIndex >= 0 ? "P\(r.partyIndex + 1)" : "P?"
            sb += "<div class=\"left\"><div class=\"res \(resCls)\">\(esc(r.result))</div>"
            sb += "<div class=\"pno\">\(pno)\(hasMemo ? " ✎" : "")</div></div>"
            sb += "<div class=\"mid\">\(iconsHtml(r.myParty, cls))<div class=\"vs\">vs</div>\(iconsHtml(r.enemyParty, cls))</div>"
            let ts = r.timestamp.replacingOccurrences(of: "-", with: ".")
            let parts = ts.split(separator: " ", maxSplits: 1).map(String.init)
            let date = parts.first ?? ts
            let time = parts.count > 1 ? parts[1] : ""
            sb += "<div class=\"time\">\(esc(date))<br>\(esc(time))</div>"
            sb += "</div>"
        }
        sb += "</div>"
        sb += "</div>"

        // サムネイルの CSS (使った体だけ)
        sb += "<style>"
        for id in iconOrder {
            if let c = iconClass[id], let data = iconDataUri(id: id) {
                sb += ".\(c){background-image:url(\(data))}\n"
            }
        }
        sb += "</style>"
        sb += "</body></html>"
        return sb
    }

    // MARK: - 部品

    private static func css() -> String {
        func dp(_ v: Int) -> String { "\(v * s)px" }
        func sp(_ v: Int) -> String { "\(v * s)px" }
        return """
        html,body{margin:0;padding:0;height:100%;background:#222222;overflow-x:auto;overflow-y:hidden}
        body{width:\(pageW)px;font-family:Roboto,"Noto Sans JP","Hiragino Sans","Yu Gothic",sans-serif;color:#FFFFFF;-webkit-font-smoothing:antialiased}
        .screen{position:relative;width:\(pageW)px;height:min(\(pageH)px,100vh);display:flex;flex-direction:column;overflow:hidden}
        .card{background:#333333;box-shadow:0 \(dp(2)) \(dp(4)) rgba(0,0,0,.45)}
        .top{display:flex;height:\(dp(120));margin:\(dp(48)) \(dp(8)) 0 \(dp(8))}
        .top .card{margin:\(dp(4));border-radius:\(dp(16));padding:\(dp(8));box-sizing:border-box;display:flex;flex-direction:column}
        .total{flex:1;align-items:center;justify-content:center;text-align:center}
        .graph{flex:1.2}
        .tlabel{color:#AAAAAA;font-weight:700;letter-spacing:.1em;font-size:\(sp(12));line-height:1.2;width:100%;white-space:nowrap}
        .trate{color:#F09199;font-weight:700;font-size:\(sp(30));line-height:1.15;flex:3;display:flex;align-items:center}
        .tcount{color:#AAAAAA;font-size:\(sp(12));flex:1.2;display:flex;align-items:center}
        .twl{font-size:\(sp(12));flex:1.2;display:flex;align-items:center}
        .win{color:#F09199}.lose{color:#90D7EC}
        .glabel{color:#666666;font-size:\(sp(11));font-weight:700;text-align:right;margin-right:\(dp(4));line-height:1.2}
        .graph svg{display:block;width:100%;flex:1;margin-top:\(dp(2))}
        .parties{display:flex;margin:\(dp(4)) \(dp(4)) 0 \(dp(4))}
        .party{flex:1;margin:\(dp(4));border-radius:\(dp(10));padding:\(dp(4)) \(dp(4)) \(dp(12)) \(dp(4));display:flex;flex-direction:column;align-items:center}
        .pname{font-size:\(sp(14));font-weight:700;line-height:1.3}
        .picons{display:flex;justify-content:center;margin:\(dp(4)) 0}
        .pi{display:inline-block;width:\(dp(18));height:\(dp(18));margin:\(dp(1));border-radius:\(dp(6));background:#2c2c2c center/cover no-repeat}
        .pi.hidden{visibility:hidden}
        .prate{color:#F09199;font-size:\(sp(16));font-weight:700;line-height:1.3}
        .puse{color:#AAAAAA;font-size:\(sp(11));line-height:1.4}
        .pwl{font-size:\(sp(11));line-height:1.4}
        .list{flex:1;margin:\(dp(4)) 0 \(dp(8)) 0;overflow-y:auto;overflow-x:hidden}
        .list::-webkit-scrollbar{display:none}
        .row{display:flex;align-items:center;background:#333333;border-radius:\(dp(2));margin:\(dp(2)) \(dp(4));padding:\(dp(8)) \(dp(4));box-shadow:0 \(dp(1)) \(dp(2)) rgba(0,0,0,.35)}
        .left{width:\(dp(45));display:flex;flex-direction:column;align-items:center;justify-content:center;flex:none}
        .res{font-weight:700;font-size:\(sp(14));line-height:1.2}
        .pno{font-weight:700;font-size:\(sp(10));line-height:1.3;color:#FFFFFF}
        .mid{flex:1;display:flex;align-items:center;justify-content:center;min-width:0}
        .icons{display:flex}
        .mi{display:inline-block;width:\(dp(22));height:\(dp(22));margin-right:\(dp(1));border-radius:\(dp(6));background:#2c2c2c center/cover no-repeat;flex:none}
        .vs{font-weight:700;font-size:\(sp(10));margin:0 \(dp(2))}
        .time{width:\(dp(65));flex:none;text-align:right;color:#8E8E93;font-size:\(sp(10));line-height:1.25;margin-left:\(dp(2))}
        """
    }

    private static func winLose(_ w: Int, _ l: Int) -> String {
        "<span class=\"win\">\(w)W</span> - <span class=\"lose\">\(l)L</span>"
    }

    private static func iconsHtml(_ ids: [String], _ cls: (String) -> String?) -> String {
        var sb = "<div class=\"icons\">"
        for id in ids {
            let c = cls(id)
            let name = (id == "?" || id.isEmpty) ? "?" : MonsterCatalog.name(for: id)
            sb += "<i class=\"mi\(c.map { " \($0)" } ?? "")\" title=\"\(esc(name))\"></i>"
        }
        return sb + "</div>"
    }

    /// WinRateGraphView (Android) と同じ描画を SVG で再現。直近 20 戦の移動勝率、表示は最後の 20 点、
    /// ダイナミックスケール (10% 刻み・最低 20% 幅)、最新点を選択状態 (白い点線 + 丸 + ツールチップ)
    private static func graphSvg(_ records: [BattleRecord]) -> String {
        let innerTop = Double(pageW - 2 * 8 * s - 4 * 4 * s)
        let cardW = innerTop * 1.2 / 2.2
        let w = cardW - Double(2 * 8 * s)
        let h = Double(120 * s - 2 * 8 * s) - Double(11 * s) * 1.2 - Double(2 * s)
        if records.count < 2 {
            return "<svg viewBox=\"0 0 \(Int(w)) \(Int(h))\" xmlns=\"http://www.w3.org/2000/svg\"></svg>"
        }
        var rates: [Double] = []
        for i in 0..<records.count {
            let start = max(0, i - graphWindow + 1)
            let sub = records[start...i]
            let wins = sub.filter { $0.result == "WIN" }.count
            rates.append(Double(wins) / Double(sub.count) * 100.0)
        }
        let padL = 60.0, padR = 30.0, padT = 40.0, padB = 45.0
        let innerW = w - padL - padR
        let innerH = h - padT - padB
        let visible = graphWindow
        let stepX = innerW / Double(visible - 1)
        let scroll = Double(max(0, rates.count - visible)) * stepX

        var minV = Double.greatestFiniteMagnitude
        var maxV = -Double.greatestFiniteMagnitude
        for (i, r) in rates.enumerated() {
            let x = padL + Double(i) * stepX - scroll
            if x >= padL - 1 && x <= w - padR + 1 { minV = min(minV, r); maxV = max(maxV, r) }
        }
        var dMin = max(0, (minV / 10.0).rounded(.down) * 10.0)
        var dMax = min(100, (maxV / 10.0).rounded(.up) * 10.0)
        if dMax - dMin < 20 { if dMax <= 80 { dMax = dMin + 20 } else { dMin = dMax - 20 } }
        let range = dMax - dMin
        func yOf(_ r: Double) -> Double { padT + innerH - ((r - dMin) / range * innerH) }

        var sb = "<svg viewBox=\"0 0 \(Int(w)) \(Int(h))\" xmlns=\"http://www.w3.org/2000/svg\">"
        for i in 0...5 {
            let rv = dMin + range * Double(i) / 5.0
            let y = yOf(rv)
            sb += "<line x1=\"\(f(padL))\" y1=\"\(f(y))\" x2=\"\(f(w - padR))\" y2=\"\(f(y))\" stroke=\"#444444\" stroke-width=\"1\"/>"
            sb += "<text x=\"5\" y=\"\(f(y + 6))\" fill=\"#888888\" font-size=\"18\">\(Int(rv))%</text>"
        }
        sb += "<defs><clipPath id=\"gc\"><rect x=\"\(f(padL))\" y=\"0\" width=\"\(f(w - padL - padR))\" height=\"\(f(h))\"/></clipPath></defs>"
        sb += "<g clip-path=\"url(#gc)\">"
        for i in 0..<rates.count {
            let x = padL + Double(i) * stepX - scroll
            if x >= padL - 1 && x <= w - padR + 1 {
                sb += "<line x1=\"\(f(x))\" y1=\"\(f(padT))\" x2=\"\(f(x))\" y2=\"\(f(padT + innerH))\" stroke=\"#444444\" stroke-width=\"1\"/>"
            }
        }
        var line = ""
        var area = ""
        var first = true
        var lastX = padL
        for (i, r) in rates.enumerated() {
            let x = padL + Double(i) * stepX - scroll
            if x < padL - stepX || x > w + stepX { continue }
            let y = yOf(r)
            if first {
                line += "M\(f(x)) \(f(y))"
                area += "M\(f(x)) \(f(padT + innerH)) L\(f(x)) \(f(y))"
                first = false
            } else {
                line += " L\(f(x)) \(f(y))"
                area += " L\(f(x)) \(f(y))"
            }
            lastX = x
        }
        area += " L\(f(lastX)) \(f(padT + innerH)) Z"
        sb += "<path d=\"\(area)\" fill=\"#F09199\" fill-opacity=\"0.12\"/>"
        sb += "<path d=\"\(line)\" fill=\"none\" stroke=\"#F09199\" stroke-width=\"4\" stroke-linecap=\"round\" stroke-linejoin=\"round\"/>"
        sb += "</g>"
        let li = rates.count - 1
        let lx = padL + Double(li) * stepX - scroll
        let ly = yOf(rates[li])
        sb += "<line x1=\"\(f(lx))\" y1=\"\(f(padT))\" x2=\"\(f(lx))\" y2=\"\(f(padT + innerH))\" stroke=\"#FFFFFF\" stroke-width=\"2\" stroke-dasharray=\"10 10\"/>"
        sb += "<circle cx=\"\(f(lx))\" cy=\"\(f(ly))\" r=\"8\" fill=\"#FFFFFF\"/><circle cx=\"\(f(lx))\" cy=\"\(f(ly))\" r=\"6\" fill=\"#F09199\"/>"
        let tip = "\(RateFormat.percent(rates[li]))：\(rates.count)Matches"
        let tipW = Double(tip.count) * 12.0
        let tx = min(max(lx, padL + tipW / 2), w - padR - tipW / 2)
        sb += "<text x=\"\(f(tx))\" y=\"\(f(padT - 12))\" fill=\"#FFFFFF\" font-size=\"22\" font-weight=\"700\" text-anchor=\"middle\">\(esc(tip))</text>"
        if rates.count > visible {
            sb += "<text x=\"\(f(padL))\" y=\"\(f(h - 2))\" fill=\"#888888\" fill-opacity=\"0.47\" font-size=\"\(10 * s)\">SLIDE TO SEE</text>"
        }
        sb += "</svg>"
        return sb
    }

    private static func f(_ v: Double) -> String { String(format: "%.1f", v) }

    /// templates/<id>.png を縮小 PNG にして data URI に。失敗したら nil (灰色の枠だけ表示)
    private static func iconDataUri(id: String) -> String? {
        iconCacheLock.lock()
        if let cached = iconCache[id] { iconCacheLock.unlock(); return cached }
        iconCacheLock.unlock()
        guard let path = Bundle.main.path(forResource: id, ofType: "png", inDirectory: "templates"),
              let src = UIImage(contentsOfFile: path) else { return nil }
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        let size = CGSize(width: iconW, height: iconH)
        let scaled = UIGraphicsImageRenderer(size: size, format: format).image { _ in
            src.draw(in: CGRect(origin: .zero, size: size))
        }
        guard let png = scaled.pngData() else { return nil }
        let uri = "data:image/png;base64," + png.base64EncodedString()
        iconCacheLock.lock()
        iconCache[id] = uri
        iconCacheLock.unlock()
        return uri
    }

    private static func esc(_ s: String) -> String {
        var out = ""
        out.reserveCapacity(s.count)
        for c in s {
            switch c {
            case "&": out += "&amp;"
            case "<": out += "&lt;"
            case ">": out += "&gt;"
            case "\"": out += "&quot;"
            case "'": out += "&#39;"
            default: out.append(c)
            }
        }
        return out
    }
}
