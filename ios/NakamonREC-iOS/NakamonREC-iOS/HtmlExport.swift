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
        // グランプリ記録 (時系列昇順)。1 件以上あるときだけ「グランプリ集計」タブを出す (2026-11-03)
        let gp = (history.grandPrixRecords ?? []).sorted { $0.timestamp < $1.timestamp }
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
        // 自動再読込は末尾のスクリプト (location.reload) で行う。meta refresh だと URL 末尾の #gp (タブ指定) が
        // 落ちる環境があるため
        sb += "<meta name=\"viewport\" content=\"width=\(pageW)\">"
        sb += "<title>なかモンREC 戦績 - \(esc(baseName))</title>"
        // 表示するタブは本文を描く前に決める (<html> の class で CSS が出し分ける)。末尾のスクリプトで切り替えると
        // 再読込のたびに「戦績 → グランプリ集計」の切替が一瞬見えてちらつく (ビーフ指摘 2026-11-03)
        sb += headScript()
        sb += "<style>\(css())\(gpCss())</style></head><body>"
        sb += "<div class=\"screen\">"
        // タブ (グランプリ記録があるときだけ)。上端 48dp の空き (アプリではステータスバー領域) に重ねる
        if !gp.isEmpty {
            sb += "<div class=\"tabs\"><div class=\"tab\" data-t=\"history\">戦績</div>"
            sb += "<div class=\"tab\" data-t=\"gp\">グランプリ集計</div></div>"
        }
        sb += "<div class=\"pane\" id=\"pane-history\">"

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
        sb += "</div>"   // list
        sb += "</div>"   // pane-history

        // ===== グランプリ集計タブ =====
        if !gp.isEmpty {
            sb += "<div class=\"pane gp\" id=\"pane-gp\">\(gpPane(gp))</div>"
        }

        sb += "</div>"   // screen

        // サムネイルの CSS (使った体だけ)
        sb += "<style>"
        for id in iconOrder {
            if let c = iconClass[id], let data = iconDataUri(id: id) {
                sb += ".\(c){background-image:url(\(data))}\n"
            }
        }
        sb += "</style>"
        sb += script()
        sb += "</body></html>"
        return sb
    }

    /// ページ末尾のスクリプト (両 OS 同一)。
    /// - TOTAL WIN RATE ラベルは表示環境のフォントで幅が変わる (Mac/iPhone の Hiragino 太字は Windows より約 1 割広く
    ///   14sp 相当だとカード幅に収まらない)。基準の 14sp のまま、収まらない環境でだけ 1px ずつ縮めて 1 行に収める
    /// - タブ: URL 末尾の #history / #gp → 前回選んだタブ (localStorage、ファイル名ごと) → 戦績 の順で決める。
    ///   OBS では URL に #gp を付けたブラウザソースを置けばグランプリ集計だけを映せる
    /// - 自動再読込: location.reload は URL 末尾の # を保つので、再読込後も同じタブが開く
    private static func script() -> String {
        """
        <script>(function(){
        var root=document.documentElement;var key='nakamonrec.tab.'+document.title;
        function fit(){var l=document.querySelector('.tlabel');if(!l)return;var s=parseFloat(getComputedStyle(l).fontSize);while(l.scrollWidth>l.clientWidth&&s>20){s-=1;l.style.fontSize=s+'px';}}
        function show(t){if(t==='gp'&&!document.getElementById('pane-gp'))t='history';root.className='tab-'+t;if(t==='history')fit();try{localStorage.setItem(key,t);}catch(e){}}
        show(root.className==='tab-gp'?'gp':'history');
        var tabs=document.querySelectorAll('.tab');for(var i=0;i<tabs.length;i++){tabs[i].onclick=function(){var v=this.getAttribute('data-t');location.hash=v;show(v);};}
        setTimeout(function(){location.reload();},\(refreshSec * 1000));
        })();</script>
        """
    }

    /// head 内で <html> に tab-history / tab-gp を付ける (本文描画前に決めるのでタブ切替のちらつきが出ない)
    private static func headScript() -> String {
        "<script>(function(){var t=location.hash.replace('#','');if(!t){try{t=localStorage.getItem('nakamonrec.tab.'+document.title)||'';}catch(e){}}"
            + "document.documentElement.className=(t==='gp')?'tab-gp':'tab-history';})();</script>"
    }

    // MARK: - グランプリ集計タブ (2026-11-03)
    // Android HistoryActivity.showGrandPrixDialog (サマリ 2 カード) / buildGrandPrixGraph (情報枠 + GrandPrixGraphView + 凡例) /
    // buildGrandPrixList (日時・ランク・戦・レーティング・変動・ボーダー) の写し (iOS の GrandPrixStatsView と同じ構成)。
    // アプリではグラフとテキスト一覧を切り替えるが、HTML は操作できないのでグラフの下に一覧を並べる (Android 版と同一出力)。
    // ズーム (直近 N 戦) と横軸切替の操作も載せない (既定 = 全体表示・戦闘数軸)。

    /// 埋め込むエンブレムの高さ (28dp)
    private static let emblemH = 84
    nonisolated(unsafe) private static var emblemCache: [String: String] = [:]

    private struct GpPoint {
        let rating: Double
        let border: Double?
        let label: String
        let tier: String?
        let dateLabel: String
    }

    private static func gpPane(_ gp: [GrandPrixRecord]) -> String {
        var sb = ""
        sb.reserveCapacity(16 * 1024)
        func emblemImg(_ tier: String?, _ cls: String) -> String {
            guard let uri = emblemDataUri(tier: tier) else { return "" }
            return "<img class=\"\(cls)\" src=\"\(uri)\" alt=\"\">"
        }
        func r1(_ v: Double) -> String { String(format: "%.1f", v) }
        // サマリはレーティングが読めているレコードだけ (未取得レコードは対象外)
        let readable = gp.filter { !$0.isUnread }
        let maxRec = readable.max { $0.currentRating < $1.currentRating }
        let curRec = readable.last

        // 現在レーティング | 最高レーティング
        sb += "<div class=\"gpsum\">"
        sb += "<div class=\"gpcard\"><div class=\"gplabel\">現在レーティング</div><div class=\"gpval\">"
        sb += emblemImg(curRec?.rankTier, "gpemblem")
        sb += "<span class=\"gpcur\">\(curRec.map { r1($0.currentRating) } ?? "—")</span></div></div>"
        sb += "<div class=\"gpcard\"><div class=\"gplabel\">最高レーティング</div><div class=\"gpval\">"
        sb += emblemImg(maxRec?.rankTier, "gpemblem")
        sb += "<span class=\"gpmax\">\(maxRec.map { r1($0.currentRating) } ?? "—")</span></div></div>"
        sb += "</div>"

        // グラフカード: 情報枠 (最新点 = アプリの選択なし既定) + グラフ + 凡例
        let parser = DateFormatter()
        parser.locale = Locale(identifier: "en_US_POSIX")
        parser.dateFormat = "yyyy-MM-dd HH:mm:ss"
        func fmt(_ pattern: String) -> DateFormatter {
            let f = DateFormatter()
            f.locale = Locale(identifier: "en_US_POSIX")
            f.dateFormat = pattern
            return f
        }
        let selFmt = fmt("M/d HH:mm")
        var points: [GpPoint] = []
        for (idx, r) in gp.enumerated() where !r.isUnread {
            let d = parser.date(from: r.timestamp)
            points.append(GpPoint(rating: r.currentRating, border: r.borderRating, label: "\(idx + 1)", tier: r.rankTier,
                                  dateLabel: d.map { selFmt.string(from: $0) } ?? String(r.timestamp.suffix(5))))
        }
        sb += "<div class=\"gpgraph\"><div class=\"gpsel\">"
        if let sel = points.last {
            sb += emblemImg(sel.tier, "gpselemblem")
            sb += "<span class=\"win\">R:\(r1(sel.rating))</span>&nbsp;&nbsp;"
            sb += "<span class=\"lose\">\(sel.border.map { "B:" + r1($0) } ?? "B:—")</span>&nbsp;&nbsp;"
            sb += "<span class=\"gpdim\">\(esc(sel.dateLabel))</span>"
        }
        sb += "</div>\(gpGraphSvg(points))"
        sb += "<div class=\"gplegend\"><span class=\"win\">● 自分</span><span class=\"lose\">&nbsp;&nbsp;&nbsp;● ボーダー</span></div></div>"

        // テキスト一覧 (最新が上)。変動 = 直前の読めているレコードとの差 (未取得は飛ばす)
        let dateFmt = fmt("yyyy.MM.dd")
        let timeFmt = fmt("HH:mm")
        var deltas: [String: Double] = [:]
        var prev: GrandPrixRecord? = nil
        for r in gp where !r.isUnread {
            if let p = prev { deltas[r.timestamp] = r.currentRating - p.currentRating }
            prev = r
        }
        sb += "<div class=\"gphead\"><span class=\"c1\">日時</span><span class=\"c2\">ランク</span><span class=\"c3\">戦</span>"
        sb += "<span class=\"c4\">レーティング</span><span class=\"c5\">変動</span><span class=\"c6\">ボーダー</span></div>"
        sb += "<div class=\"gplist\">"
        for (revIdx, r) in gp.reversed().enumerated() {
            let battleNo = gp.count - revIdx
            let parsed = parser.date(from: r.timestamp)
            let dateStr = parsed.map { dateFmt.string(from: $0) } ?? String(r.timestamp.prefix(10))
            let timeStr = parsed.map { timeFmt.string(from: $0) } ?? String(r.timestamp.suffix(8).prefix(5))
            let delta = deltas[r.timestamp]
            let deltaStr = delta.map { String(format: "%+.1f", $0) } ?? "—"
            let deltaCls = delta == nil ? "gpdim" : (delta! >= 0 ? "win" : "lose")
            sb += "<div class=\"gprow\"><span class=\"c1\">\(esc(dateStr))<br>\(esc(timeStr))</span>"
            sb += "<span class=\"c2\">\(emblemImg(r.rankTier, "gprowemblem"))</span>"
            sb += "<span class=\"c3 gpdim\">\(battleNo)</span>"
            if r.isUnread {
                sb += "<span class=\"c4 gpunread\">未取得</span>"
            } else {
                sb += "<span class=\"c4 gprating\">\(r1(r.currentRating))</span>"
            }
            sb += "<span class=\"c5 \(deltaCls)\">\(deltaStr)</span>"
            sb += "<span class=\"c6 lose\">\(r.borderRating.map { r1($0) } ?? "—")</span></div>"
        }
        sb += "</div>"
        return sb
    }

    /// GrandPrixGraphView.onDraw (Android) の写し (全体表示・戦闘数軸・選択 = 最新点)。
    /// 余白 16/88/40/40 px、Y 軸 4 分割の目盛りを右側に、線 4px、点が 100 以下ならリング、最新点に白い縦線と強調点
    private static func gpGraphSvg(_ points: [GpPoint]) -> String {
        let w = Double(pageW - 2 * 20 * s - 2 * 12 * s)   // ペイン余白 20dp + カード余白 12dp
        let h = Double(265 * s)
        if points.isEmpty {
            return "<svg class=\"gpsvg\" viewBox=\"0 0 \(Int(w)) \(Int(h))\" xmlns=\"http://www.w3.org/2000/svg\"></svg>"
        }
        let padL = 16.0, padR = 88.0, padT = 40.0, padB = 40.0
        let innerW = w - padL - padR
        let innerH = h - padT - padB
        let n = points.count
        let stepX = n > 1 ? innerW / Double(n - 1) : innerW
        var vals: [Double] = []
        for p in points { vals.append(p.rating); if let b = p.border { vals.append(b) } }
        let minV = vals.min() ?? 0
        let maxV = vals.max() ?? 1
        let range = max(maxV - minV, 1.0)
        let yMin = minV - range * 0.1
        let yMax = maxV + range * 0.1
        let yspan = max(yMax - yMin, 1.0)
        func yOf(_ v: Double) -> Double { padT + innerH - ((v - yMin) / yspan * innerH) }
        func xOf(_ i: Int) -> Double { padL + Double(i) * stepX }

        var sb = "<svg class=\"gpsvg\" viewBox=\"0 0 \(Int(w)) \(Int(h))\" xmlns=\"http://www.w3.org/2000/svg\">"
        for i in 0...4 {
            let v = yMin + (yMax - yMin) * Double(i) / 4.0
            let y = yOf(v)
            sb += "<line x1=\"\(f(padL))\" y1=\"\(f(y))\" x2=\"\(f(w - padR))\" y2=\"\(f(y))\" stroke=\"#444444\" stroke-width=\"1\"/>"
            sb += "<text x=\"\(f(w - padR + 8))\" y=\"\(f(y + 6))\" fill=\"#888888\" font-size=\"18\">\(String(format: "%.0f", v))</text>"
        }
        // X 軸ラベル (戦闘番号)。前のラベルと重なるものは描かない
        var lastRight = -Double.greatestFiniteMagnitude
        for (i, p) in points.enumerated() {
            let x = xOf(i)
            let tw = Double(p.label.count) * 10.0
            let left = x - tw / 2
            if left > lastRight + 16 && x + tw / 2 <= w - padR {
                sb += "<text x=\"\(f(left))\" y=\"\(f(h - 10))\" fill=\"#888888\" font-size=\"18\">\(esc(p.label))</text>"
                lastRight = x + tw / 2
            }
        }
        var pr = ""
        var pb = ""
        for (i, p) in points.enumerated() {
            let x = xOf(i)
            pr += (pr.isEmpty ? "M" : " L") + "\(f(x)) \(f(yOf(p.rating)))"
            if let b = p.border { pb += (pb.isEmpty ? "M" : " L") + "\(f(x)) \(f(yOf(b)))" }
        }
        sb += "<path d=\"\(pb)\" fill=\"none\" stroke=\"#90D7EC\" stroke-width=\"4\" stroke-linecap=\"round\" stroke-linejoin=\"round\"/>"
        sb += "<path d=\"\(pr)\" fill=\"none\" stroke=\"#F09199\" stroke-width=\"4\" stroke-linecap=\"round\" stroke-linejoin=\"round\"/>"
        if n <= 100 {
            for (i, p) in points.enumerated() {
                let x = xOf(i)
                if let b = p.border {
                    sb += "<circle cx=\"\(f(x))\" cy=\"\(f(yOf(b)))\" r=\"4\" fill=\"#333333\" stroke=\"#90D7EC\" stroke-width=\"2\"/>"
                }
                sb += "<circle cx=\"\(f(x))\" cy=\"\(f(yOf(p.rating)))\" r=\"4\" fill=\"#333333\" stroke=\"#F09199\" stroke-width=\"2\"/>"
            }
        }
        // 最新点のインジケーター (白い縦線 + 強調点)
        let lp = points[n - 1]
        let lx = xOf(n - 1)
        sb += "<line x1=\"\(f(lx))\" y1=\"\(f(padT))\" x2=\"\(f(lx))\" y2=\"\(f(padT + innerH))\" stroke=\"#FFFFFF\" stroke-width=\"2\"/>"
        sb += "<circle cx=\"\(f(lx))\" cy=\"\(f(yOf(lp.rating)))\" r=\"6\" fill=\"#FFFFFF\"/>"
        sb += "<circle cx=\"\(f(lx))\" cy=\"\(f(yOf(lp.rating)))\" r=\"4\" fill=\"#F09199\"/>"
        if let b = lp.border {
            sb += "<circle cx=\"\(f(lx))\" cy=\"\(f(yOf(b)))\" r=\"6\" fill=\"#FFFFFF\"/>"
            sb += "<circle cx=\"\(f(lx))\" cy=\"\(f(yOf(b)))\" r=\"4\" fill=\"#90D7EC\"/>"
        }
        sb += "</svg>"
        return sb
    }

    /// ランク帯エンブレム (Asset Catalog の PNG) を高さ emblemH に縮小して data URI に。未設定/無しなら nil
    private static func emblemDataUri(tier: String?) -> String? {
        guard let asset = GrandPrixRecord.rankEmblemAsset(tier) else { return nil }
        iconCacheLock.lock()
        if let cached = emblemCache[asset] { iconCacheLock.unlock(); return cached }
        iconCacheLock.unlock()
        guard let src = UIImage(named: asset), src.size.height > 0 else { return nil }
        let h = CGFloat(emblemH)
        let w = max(1, (src.size.width / src.size.height * h).rounded())
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        let size = CGSize(width: w, height: h)
        let scaled = UIGraphicsImageRenderer(size: size, format: format).image { _ in
            src.draw(in: CGRect(origin: .zero, size: size))
        }
        guard let png = scaled.pngData() else { return nil }
        let uri = "data:image/png;base64," + png.base64EncodedString()
        iconCacheLock.lock()
        emblemCache[asset] = uri
        iconCacheLock.unlock()
        return uri
    }

    /// タブ + グランプリ集計ペインの CSS (dp×3 / sp×3 の px 直書き。両 OS 同一)
    private static func gpCss() -> String {
        """
        .tabs{position:absolute;top:0;left:0;right:0;height:132px;display:flex;align-items:flex-end;padding:0 60px;gap:48px;z-index:2}
        .tab{font-size:39px;font-weight:700;color:#888888;padding:0 6px 10px;border-bottom:6px solid transparent;cursor:pointer}
        html:not(.tab-gp) .tab[data-t=history],html.tab-gp .tab[data-t=gp]{color:#F09199;border-bottom-color:#F09199}
        .pane{display:none;flex:1;min-height:0;flex-direction:column}
        html:not(.tab-gp) #pane-history,html.tab-gp #pane-gp{display:flex}
        .gp{padding:192px 60px 24px}
        .gpsum{display:flex}
        .gpcard{flex:1;margin:0 12px;background:#333333;border-radius:30px;padding:30px 24px;display:flex;flex-direction:column;align-items:center}
        .gplabel{color:#888888;font-size:33px;line-height:1.3}
        .gpval{display:flex;align-items:center}
        .gpemblem{height:84px;margin-right:18px}
        .gpcur{color:#FFFFFF;font-size:78px;font-weight:700;line-height:1.2}
        .gpmax{color:#F09199;font-size:78px;font-weight:700;line-height:1.2}
        .gpgraph{margin-top:24px;background:#333333;border-radius:36px;padding:24px 36px;flex:none}
        .gpsel{display:flex;align-items:center;padding:12px 0;font-size:39px;min-height:60px}
        .gpselemblem{height:60px;margin-right:18px}
        .gpdim{color:#888888}
        .gpsvg{display:block;width:100%;height:795px}
        .gplegend{padding-top:12px;font-size:36px}
        .gphead{display:flex;align-items:center;padding:18px 24px;margin-top:24px}
        .gphead span{font-size:33px;color:#888888}
        .gplist{flex:1;min-height:0;overflow-y:auto}
        .gplist::-webkit-scrollbar{display:none}
        .gprow{display:flex;align-items:center;background:#333333;border-radius:24px;padding:24px;margin:6px 0}
        .c1{flex:1.6;font-size:30px;color:#FFFFFF;line-height:1.3}
        .c2{flex:1;display:flex;align-items:center}
        .c3{flex:1;text-align:right;font-size:36px}
        .c4{flex:1.8;text-align:right}
        .c5{flex:1.1;text-align:right;font-size:36px}
        .c6{flex:1.3;text-align:right;font-size:36px}
        .gprowemblem{height:72px}
        .gprating{color:#FFFFFF;font-size:42px;font-weight:700}
        .gpunread{color:#FFB74D;font-size:33px;font-weight:700}
        """
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
        .top .card{margin:\(dp(4));border-radius:\(dp(16));padding:\(dp(8));box-sizing:border-box;display:flex;flex-direction:column;min-width:0}
        .total{flex:1;align-items:center;justify-content:center;text-align:center}
        .graph{flex:1.2}
        .tlabel{color:#AAAAAA;font-weight:700;letter-spacing:.1em;font-size:\(sp(14));line-height:1.2;width:100%;white-space:nowrap}
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
