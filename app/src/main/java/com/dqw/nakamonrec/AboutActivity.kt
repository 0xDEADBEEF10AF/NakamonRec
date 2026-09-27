package com.dqw.nakamonrec

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.dqw.nakamonrec.databinding.ActivityAboutBinding

/**
 * About 画面 (旧 Readme / Copyright ダイアログ)。iOS HelpView.swift と同じ構成・見え方に揃えている。
 *
 * 表示するのは バージョン / ストアでの最新版確認 / 公開ヘルプへの導線 / アプリ概要 / 注意事項 /
 * 問い合わせ先 / 著作権 のみ。使い方・主要機能・推奨環境はバージョンごとに変わるため
 * 公開ヘルプ (help_url) に一本化した (26.10.1)。
 * 「使い方はこちら」は外部ブラウザを起動する。通信の主体はブラウザであり、
 * アプリ自身はヘルプ取得のための通信を行わない (privacy.html の記載と整合)。
 */
class AboutActivity : AppCompatActivity() {

    companion object {
        /**
         * Google Play 上のアプリ ID (= release の applicationId)。
         * debug ビルドは applicationIdSuffix ".debug" が付くため packageName をそのまま使うと
         * Play に存在しない ID になりストアが開けない。ストアリンクは常にこの定数を使う。
         */
        const val PLAY_PACKAGE_ID = "com.dqw.nakamonrec"
        const val PLAY_STORE_WEB_URL = "https://play.google.com/store/apps/details?id=$PLAY_PACKAGE_ID"
    }

    private lateinit var binding: ActivityAboutBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // edge-to-edge のため、ステータスバー/ナビゲーションバー分をルートの padding で避ける
        ViewCompat.setOnApplyWindowInsetsListener(binding.rootAbout) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        binding.textVersion.text = getString(R.string.readme_version_format, currentVersionName())
        binding.btnClose.setOnClickListener { finish() }
        binding.btnStoreCheck.setOnClickListener { openStorePage() }
        binding.btnOpenHelp.setOnClickListener { openHelpPage() }
    }

    private fun currentVersionName(): String = try {
        val info = if (Build.VERSION.SDK_INT >= 33) {
            packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0)
        }
        info.versionName ?: "Unknown"
    } catch (_: Exception) {
        "Unknown"
    }

    /** Google Play のアプリページを開く (Play 未搭載端末はブラウザ URL にフォールバック)。iOS の「App Store で最新版を確認」に対応 */
    private fun openStorePage() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, "market://details?id=$PLAY_PACKAGE_ID".toUri()))
        } catch (_: ActivityNotFoundException) {
            openUrl(PLAY_STORE_WEB_URL)
        }
    }

    private fun openHelpPage() = openUrl(getString(R.string.help_url))

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.msg_no_browser, Toast.LENGTH_SHORT).show()
        }
    }
}
