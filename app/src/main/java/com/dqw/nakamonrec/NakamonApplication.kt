package com.dqw.nakamonrec

import android.app.Application
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import org.opencv.android.OpenCVLoader

/**
 * OpenCV のネイティブライブラリをプロセス起動時に 1 回だけ読み込む。
 *
 * 26.11.1 まで初期化は MainActivity.onCreate だけで行っていた。プロセスが終了した後に
 * 「最近使ったアプリ」から校正画面が直接復元されると MainActivity を経由せず、
 * BattleAnalyzer.loadTemplates が UnsatisfiedLinkError で落ちた (Vitals 1 件)。
 * Application で読み込めば、どの Activity / Service が最初に起動しても OpenCV は使える状態になる。
 */
class NakamonApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // 端末のライト/ダーク設定に関わらず常にダークで表示する (iOS の Info.plist UIUserInterfaceStyle = Dark と同じ)。
        // テーマ (Theme.Material3.DayNight) がライトになると、白固定の文字を載せた画面・ダイアログや、
        // 文字色を指定していない入力欄・選択欄が「白地に白」「暗地に暗」になる漏れが繰り返し見つかったため
        // (26.11.3、ビーフ判断 2026-11-16)。どの Activity より先に適用されるよう Application で設定する
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        if (OpenCVLoader.initLocal()) {
            Log.i("OpenCV", "OpenCV loaded successfully")
        } else {
            Log.e("OpenCV", "OpenCV initialization failed")
        }
    }
}
