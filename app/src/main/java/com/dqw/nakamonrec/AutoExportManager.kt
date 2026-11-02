package com.dqw.nakamonrec

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import androidx.core.content.edit
import androidx.core.net.toUri
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 自動エクスポート (2026-11-02 ビーフ設計)。
 *
 * ON のあいだ、戦績ファイルが保存されるたびに「利用者が選んだフォルダ」へ
 * 現在のファイル名 (〇〇.json の 〇〇) で CSV / HTML を**上書き**する。
 * 利用者がそのフォルダに Google ドライブ等の同期フォルダを選べば、同期はそのアプリが行う。
 * なかモンREC 自身はネットワーク通信を一切行わない (プライバシーポリシー 3 章の「通信はアップデート確認のみ」を維持)。
 *
 * - 格納先は SAF (ACTION_OPEN_DOCUMENT_TREE) の永続権限で保持する。権限は利用者がいつでも取り消せるため
 *   書き込み失敗を前提にし、失敗したら REC を止めずに OFF へ倒して理由を残す (次回起動時に通知)。
 * - 連続する保存 (レコード追加 → 解析完了の更新 → GP レーティング取得) は 1 秒でまとめて 1 回だけ書く。
 * - 同名ファイルが格納先にあればそれを開いて書き換える (Google ドライブは同名ファイルを複数持てるため、
 *   名前で作り直すと増殖する。既存のドキュメント ID を見つけて上書きする)。
 */
object AutoExportManager {
    private const val PREFS = "auto_export_prefs"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_FOLDER = "folder_uri"
    private const val KEY_FORMAT = "format"
    private const val KEY_LAST_EXPORT = "last_export_at"
    private const val KEY_FAILURE = "failure_message"

    /** 連続保存をまとめる待ち時間 */
    private const val DEBOUNCE_MS = 1000L

    enum class Format(val key: String, val label: String) {
        CSV("csv", "CSV"),
        HTML("html", "HTML"),
        BOTH("both", "CSV と HTML");

        companion object {
            fun from(key: String?): Format = entries.firstOrNull { it.key == key } ?: CSV
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private var pendingRunnable: Runnable? = null

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---- 設定 ----

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_ENABLED, enabled) }
    }

    fun folderUri(context: Context): Uri? = prefs(context).getString(KEY_FOLDER, null)?.toUri()

    /** フォルダ選択の結果を保存し、永続権限を取る。以前のフォルダの権限は返す */
    fun setFolderUri(context: Context, uri: Uri) {
        val resolver = context.contentResolver
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        folderUri(context)?.let { old ->
            if (old != uri) try { resolver.releasePersistableUriPermission(old, flags) } catch (_: Exception) {}
        }
        try { resolver.takePersistableUriPermission(uri, flags) } catch (_: Exception) {}
        prefs(context).edit { putString(KEY_FOLDER, uri.toString()) }
    }

    fun format(context: Context): Format = Format.from(prefs(context).getString(KEY_FORMAT, null))

    fun setFormat(context: Context, format: Format) {
        prefs(context).edit { putString(KEY_FORMAT, format.key) }
    }

    /** 直近の書き出し時刻 (表示用)。未実行なら null */
    fun lastExportAt(context: Context): String? = prefs(context).getString(KEY_LAST_EXPORT, null)

    /** 失敗で OFF にしたときのメッセージを 1 回だけ取り出す (MainActivity の onResume で通知に使う) */
    fun consumeFailureMessage(context: Context): String? {
        val p = prefs(context)
        val msg = p.getString(KEY_FAILURE, null) ?: return null
        p.edit { remove(KEY_FAILURE) }
        return msg
    }

    /** 格納先フォルダの表示名。取れなければドキュメント ID の末尾、それも無ければ null */
    fun folderDisplayName(context: Context): String? {
        val tree = folderUri(context) ?: return null
        return try {
            val docId = DocumentsContract.getTreeDocumentId(tree)
            val docUri = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
            context.contentResolver.query(
                docUri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                ?: docId.substringAfterLast(':').substringAfterLast('/').ifEmpty { docId }
        } catch (_: Exception) {
            null
        }
    }

    // ---- 書き出し ----

    /**
     * BattleDataManager.saveHistory から呼ばれる。有効なら DEBOUNCE_MS 後に 1 回だけ書き出す。
     * 呼び出しスレッドは問わない (解析スレッド / メインスレッド)
     */
    @Synchronized
    fun onHistorySaved(context: Context, dataManager: BattleDataManager) {
        if (!isEnabled(context)) return
        val appContext = context.applicationContext
        pendingRunnable?.let { mainHandler.removeCallbacks(it) }
        val r = Runnable {
            synchronized(this) { pendingRunnable = null }
            executor.execute { exportNow(appContext, dataManager) }
        }
        pendingRunnable = r
        mainHandler.postDelayed(r, DEBOUNCE_MS)
    }

    /**
     * 今すぐ書き出す (呼び出しスレッドで IO を行う。UI スレッドから呼ばないこと)。
     * 成功なら true。失敗したら自動エクスポートを OFF にし、理由を飛行記録と設定に残して false
     */
    fun exportNow(context: Context, dataManager: BattleDataManager): Boolean {
        val folder = folderUri(context)
        if (folder == null) {
            fail(context, dataManager, "格納先フォルダが未設定です")
            return false
        }
        val history = dataManager.snapshotHistory()
        val baseName = dataManager.currentFileName
        val format = format(context)
        return try {
            if (format != Format.HTML) {
                writeDocument(context, folder, "$baseName.csv", "text/csv", csvBytes(history))
            }
            if (format != Format.CSV) {
                val html = HtmlExport.render(context, history, baseName, dataManager.monsterMaster)
                writeDocument(context, folder, "$baseName.html", "text/html", html.toByteArray(Charsets.UTF_8))
            }
            val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            prefs(context).edit { putString(KEY_LAST_EXPORT, now) }
            dataManager.appendFlightLog("自動エクスポート: $baseName (${format.label})")
            true
        } catch (e: Exception) {
            fail(context, dataManager, e.message ?: e.javaClass.simpleName)
            false
        }
    }

    private fun fail(context: Context, dataManager: BattleDataManager, message: String) {
        prefs(context).edit {
            putBoolean(KEY_ENABLED, false)
            putString(KEY_FAILURE, message)
        }
        dataManager.appendFlightLog("⚠️ 自動エクスポート失敗 → OFF にしました: $message")
    }

    /** 既存の CSV エクスポートと同じ内容 (BOM 付き UTF-8、CsvSupport.encode) */
    private fun csvBytes(history: BattleHistory): ByteArray {
        val body = CsvSupport.encode(history).toByteArray(Charsets.UTF_8)
        return byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + body
    }

    /**
     * 格納先フォルダの中の同名ファイルを探して上書きする。無ければ作る。
     * "wt" (切り詰めて書く) に対応しないプロバイダでは、削除して作り直す
     */
    private fun writeDocument(context: Context, treeUri: Uri, name: String, mime: String, bytes: ByteArray) {
        val resolver = context.contentResolver
        val parentDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentDocId)

        val existing = findChild(context, treeUri, parentDocId, name)
        if (existing != null) {
            val out = try { resolver.openOutputStream(existing, "wt") } catch (_: Exception) { null }
            if (out != null) {
                out.use { it.write(bytes) }
                return
            }
            // 切り詰め非対応: 残骸が残らないよう作り直す
            try { DocumentsContract.deleteDocument(resolver, existing) } catch (_: Exception) {}
        }
        val created = DocumentsContract.createDocument(resolver, parentUri, mime, name)
            ?: throw IllegalStateException("ファイルを作成できません: $name")
        resolver.openOutputStream(created, "w")?.use { it.write(bytes) }
            ?: throw IllegalStateException("書き込み用に開けません: $name")
    }

    private fun findChild(context: Context, treeUri: Uri, parentDocId: String, name: String): Uri? {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME
        )
        context.contentResolver.query(childrenUri, projection, null, null, null)?.use { c ->
            val idIdx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIdx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            while (c.moveToNext()) {
                if (c.getString(nameIdx) == name) {
                    return DocumentsContract.buildDocumentUriUsingTree(treeUri, c.getString(idIdx))
                }
            }
        }
        return null
    }
}
