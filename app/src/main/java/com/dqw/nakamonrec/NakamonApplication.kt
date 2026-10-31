package com.dqw.nakamonrec

import android.app.Application
import android.util.Log
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
        if (OpenCVLoader.initLocal()) {
            Log.i("OpenCV", "OpenCV loaded successfully")
        } else {
            Log.e("OpenCV", "OpenCV initialization failed")
        }
    }
}
