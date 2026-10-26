//
//  ScreenCaptureKitProbeView.swift
//  NakamonREC-iOS
//
//  デバッグメニュー配下の iOS 27 ScreenCaptureKit 検証画面。
//  開始 → システムピッカーで「画面全体」→ ホームへ戻って別アプリを開く → 通知とログを確認、の手順を案内する。
//

import SwiftUI
import NakamonREC_Shared

#if canImport(ScreenCaptureKit)

@available(iOS 27.0, *)
struct ScreenCaptureKitProbeView: View {
    // @Observable な共有インスタンス。参照を保持するだけで SwiftUI がアクセスしたプロパティを追跡する
    private var probe = ScreenCaptureKitProbe.shared

    var body: some View {
        List {
            Section("状態") {
                Text(probe.statusText)
                    .font(.callout)
                if !probe.frameSummary.isEmpty {
                    Text(probe.frameSummary)
                        .font(.callout.monospacedDigit())
                        .foregroundStyle(.secondary)
                }
            }

            Section {
                if probe.isRunning {
                    Button(role: .destructive) {
                        probe.stop()
                    } label: {
                        Label("キャプチャを停止", systemImage: "stop.circle")
                    }
                } else {
                    Button {
                        probe.start()
                    } label: {
                        Label("キャプチャを開始 (ピッカーを表示)", systemImage: "record.circle")
                    }
                }
            }

            Section("手順") {
                Text("1. 「キャプチャを開始」→ システムのピッカーで「画面全体」を選ぶ")
                Text("2. 開始通知が出たらホームに戻り、ドラクエウォークなど別のアプリを開く")
                Text("3. 数秒後に「バックグラウンドでキャプチャ継続中」の通知が出れば成功")
                Text("4. このアプリに戻って停止 → 「直近の解析ログ」でフレームサイズと受信数を確認")
            }
            .font(.footnote)
            .foregroundStyle(.secondary)

            Section("確認したいこと") {
                Text("フレームの px サイズが画面の native 解像度と一致するか (校正値に直結)")
                Text("バックグラウンド中もフレームが届き続けるか (bg カウント)")
                Text("アプリ本体からのローカル通知が表示されるか")
                Text("開始/停止音・赤い録画インジケータの有無")
            }
            .font(.footnote)
            .foregroundStyle(.secondary)
        }
        .navigationTitle("ScreenCaptureKit 検証")
        .navigationBarTitleDisplayMode(.inline)
    }
}

#endif
