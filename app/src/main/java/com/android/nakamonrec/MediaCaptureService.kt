package com.android.nakamonrec

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import java.util.Locale
import androidx.core.graphics.createBitmap

private const val ANALYSIS_INTERVAL_MS = 500L
class MediaCaptureService : Service() {
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private var analysisThread: HandlerThread? = null
    private var analysisHandler: Handler? = null
    
    private lateinit var dataManager: BattleDataManager
    private lateinit var analyzer: BattleAnalyzer
    private enum class State { IDLE, IN_BATTLE }
    private var currentState = State.IDLE
    private var lastAnalysisTime = 0L
    private var lastBitmapUpdateTime = 0L
    private var latestBitmap: Bitmap? = null

    // 戦闘開始時にキャプチャされた情報を一時保持し、
    // モンスター解析と勝敗検知のどちらが先に来ても良いように管理する
    private var sessionPartyIndex = -1
    private var sessionPartyScores: List<Double> = emptyList()
    private var sessionVsScore: Double = 0.0
    
    private var lastDetectedPartyIndex = -1
    private var lastDetectedPartyScores: List<Double> = emptyList()
    
    private var currentSessionId = 0L
    private var lastActiveRecord: BattleRecord? = null
    private val burstImages = mutableListOf<Bitmap>()
    private var isCapturingBurst = false

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "capture_channel"
        const val ACTION_SERVICE_STOPPED = "com.android.nakamonrec.ACTION_SERVICE_STOPPED"
        const val ACTION_RELOAD_SETTINGS = "com.android.nakamonrec.ACTION_RELOAD_SETTINGS"
        const val ACTION_RELOAD_HISTORY = "com.android.nakamonrec.ACTION_RELOAD_HISTORY"
        var isRunning = false
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        dataManager = BattleDataManager(this)
        dataManager.appendFlightLog("Service起動")
        
        analyzer = BattleAnalyzer(dataManager.monsterMaster)
        analyzer.dataManager = dataManager
        analyzer.loadTemplates(this)
        
        val modeLabel = if (dataManager.analysisMode == "light") "軽負荷" else "通常"
        dataManager.appendFlightLog("テンプレート読み込み完了 (${modeLabel}モード, モンスター${dataManager.monsterMaster.size}体)")

        captureThread = HandlerThread("CaptureThread").apply { start() }
        captureHandler = Handler(captureThread!!.looper)
        
        analysisThread = HandlerThread("AnalysisThread").apply { start() }
        analysisHandler = Handler(analysisThread!!.looper)

        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_RELOAD_SETTINGS -> {
                reloadCalibrationData()
                return START_NOT_STICKY
            }
            ACTION_RELOAD_HISTORY -> {
                val prefs = getSharedPreferences("NakamonPrefs", MODE_PRIVATE)
                val currentFile = prefs.getString("last_file_name", "default_record") ?: "default_record"
                dataManager.loadHistory(currentFile)
                updateNotification(dataManager.history.totalWins, dataManager.history.totalLosses, "戦績データを更新しました")
                return START_NOT_STICKY
            }
        }

        val bootNotif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("起動中...")
            .build()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, bootNotif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, bootNotif)
        }

        val prefs = getSharedPreferences("NakamonPrefs", MODE_PRIVATE)
        val lastFile = prefs.getString("last_file_name", "default_record") ?: "default_record"
        dataManager.loadHistory(lastFile)
        reloadCalibrationData()

        updateNotification(dataManager.history.totalWins, dataManager.history.totalLosses, "待機中 ($lastFile)")

        val data = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra("DATA", Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra("DATA")
        }

        if (data != null) {
            val resultCode = intent?.getIntExtra("RESULT_CODE", Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
            val mpManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mpManager.getMediaProjection(resultCode, data)
            setupVirtualDisplay()
        }

        return START_NOT_STICKY
    }

    private fun reloadCalibrationData() {
        val prefs = getSharedPreferences("NakamonPrefs", MODE_PRIVATE)
        val calJson = prefs.getString("calibration_data", null)
        if (calJson != null) {
            try {
                val calData = Gson().fromJson(calJson, CalibrationData::class.java)
                analyzer.calibrationData = calData
            } catch (e: Exception) {
                Log.e("CaptureService", "校正データのロード失敗: ${e.message}")
            }
        }
    }

    private fun setupVirtualDisplay() {
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi
        
        val scale = width.toFloat() / 1080f
        dataManager.appendFlightLog(String.format(Locale.US, "校正完了 frame幅=%d scale=%.3f", width, scale))

        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, Handler(Looper.getMainLooper()))

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "NakamonCapture", width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )

        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            
            val currentTime = System.currentTimeMillis()
            val minInterval = if (currentState == State.IN_BATTLE) 50L else 500L

            if (currentTime - lastBitmapUpdateTime >= minInterval) {
                val cleanBitmap = try {
                    processImageToBitmap(image)
                } catch (_: Exception) {
                    null
                }
                
                if (cleanBitmap != null) {
                    lastBitmapUpdateTime = currentTime
                    synchronized(this) {
                        latestBitmap?.recycle()
                        latestBitmap = cleanBitmap
                    }

                    if (currentTime - lastAnalysisTime >= ANALYSIS_INTERVAL_MS) {
                        lastAnalysisTime = currentTime
                        
                        val snapshot = synchronized(this) {
                            if (latestBitmap != null && !latestBitmap!!.isRecycled) {
                                Bitmap.createBitmap(latestBitmap!!)
                            } else null
                        }
                        
                        snapshot?.let { bmp ->
                            analysisHandler?.post {
                                try {
                                    when (currentState) {
                                        State.IDLE -> handleIdleState(bmp)
                                        State.IN_BATTLE -> handleBattleState(bmp)
                                    }
                                } finally {
                                    bmp.recycle()
                                }
                            }
                        }
                    }
                }
            }
            image.close()
        }, captureHandler)
    }

    private fun processImageToBitmap(image: android.media.Image): Bitmap {
        val planes = image.planes[0]
        val buffer = planes.buffer
        val pixelStride = planes.pixelStride
        val rowStride = planes.rowStride
        val rowPadding = rowStride - pixelStride * image.width
        
        return if (rowPadding == 0) {
            val bitmap = createBitmap(image.width, image.height)
            bitmap.copyPixelsFromBuffer(buffer)
            bitmap
        } else {
            val tempBitmap = createBitmap(image.width + rowPadding / pixelStride, image.height)
            tempBitmap.copyPixelsFromBuffer(buffer)
            val cleanBitmap = Bitmap.createBitmap(tempBitmap, 0, 0, image.width, image.height)
            tempBitmap.recycle()
            cleanBitmap
        }
    }

    private fun performFullAnalysis(sessionId: Long) {
        analysisHandler?.post {
            try {
                if (sessionId != currentSessionId) return@post

                val snapshots = synchronized(this) { burstImages.toList() }
                if (snapshots.isEmpty()) return@post

                val startTime = System.currentTimeMillis()
                dataManager.appendFlightLog("モンスター解析開始 (${snapshots.size}枚 × 8スロット, Top-K 最適化)")

                // Bitmap をそのまま analyzer に渡す。Mat 変換と per-ROI normalize は analyzer 内部で実施
                // (フレーム全体 normalize はテンプレと条件が合わなくなるため行わない)
                val allowed = if (dataManager.analysisMode == "light") dataManager.lightModeMonsters else null
                analyzer.performDeepAnalysisBatch(snapshots, allowed)

                val duration = System.currentTimeMillis() - startTime
                dataManager.appendFlightLog(String.format(Locale.US, "モンスター解析完了 (%.2fs)", duration / 1000.0))

                analyzer.getIdentificationSummary().forEach { line ->
                    dataManager.appendFlightLog(line)
                }

                // 解析結果をレコードに反映
                synchronized(this) {
                    lastActiveRecord?.let { record ->
                        val (my, enemy, scores) = analyzer.getCurrentResults()
                        record.myParty = my
                        record.enemyParty = enemy
                        record.myPartyScores = scores.first
                        record.enemyPartyScores = scores.second
                        dataManager.updateRecord(record)
                        dataManager.appendFlightLog("📝 戦績レコードをモンスター解析結果で更新しました")
                    }
                }

                clearBurstImages()
                Log.i("Battle", "Deep analysis completed in ${duration}ms")

            } catch (e: Exception) {
                Log.e("Battle", "Batch analysis error: ${e.message}", e)
            }
        }
    }

    private fun startBurstCapture(sessionId: Long, onComplete: () -> Unit) {
        if (sessionId != currentSessionId) return
        isCapturingBurst = true
        
        var captureCount = 0
        val captureRunnable = object : Runnable {
            override fun run() {
                if (sessionId != currentSessionId) {
                    isCapturingBurst = false
                    return
                }

                if (captureCount >= 4) {
                    isCapturingBurst = false
                    onComplete()
                    return
                }
                
                synchronized(this@MediaCaptureService) {
                    latestBitmap?.let {
                        if (!it.isRecycled) {
                            burstImages.add(Bitmap.createBitmap(it))
                            captureCount++
                        }
                    }
                }
                captureHandler?.postDelayed(this, 150L)
            }
        }
        captureHandler?.post(captureRunnable)
    }

    private fun clearBurstImages() {
        synchronized(this) {
            burstImages.forEach { it.recycle() }
            burstImages.clear()
        }
    }

    private fun handleIdleState(bitmap: Bitmap) {
        val (detected, scores) = analyzer.detectSelectedParty(bitmap)
        
        if (detected != -1) {
            if (lastDetectedPartyIndex != detected) {
                dataManager.appendFlightLog("パーティ選択検知 P[${detected + 1}] Score ${String.format(Locale.US, "%.3f", scores[detected])}")
                // マッチングスコア詳細用に画像を保存
                analyzer.saveRoi(bitmap, analyzer.calibrationData.partySelectBoxes[detected], "party_p$detected", 
                    (BattleAnalyzer.ROI_PAD_PARTY_V * analyzer.calibrationData.uiScale).toInt(),
                    (BattleAnalyzer.ROI_PAD_PARTY_H * analyzer.calibrationData.uiScale).toInt())
            }
            lastDetectedPartyIndex = detected
            lastDetectedPartyScores = scores
        }
        
        if (analyzer.isVsDetected(bitmap)) {
            dataManager.rotateFlightLog()
            
            // 現在の検知情報をセッションに固定する
            sessionPartyIndex = lastDetectedPartyIndex
            sessionPartyScores = lastDetectedPartyScores
            
            if (sessionPartyIndex >= 0) {
                dataManager.appendFlightLog("パーティ P[${sessionPartyIndex + 1}] で戦闘開始")
            } else {
                dataManager.appendFlightLog("パーティ未検知で戦闘開始")
            }
            
            val vsScore = analyzer.detectVsScore(bitmap, analyzer.calibrationData.vsBox)
            dataManager.appendFlightLog("VS検知 Score ${String.format(Locale.US, "%.3f", vsScore)} → バースト開始")
            
            sessionVsScore = vsScore
            analysisHandler?.removeCallbacksAndMessages(null)
            currentSessionId = System.currentTimeMillis()
            currentState = State.IN_BATTLE
            analyzer.resetIdentification()
            lastActiveRecord = null // レコードの器をクリア
            
            clearBurstImages()
            synchronized(this) {
                burstImages.add(Bitmap.createBitmap(bitmap))
            }

            startBurstCapture(currentSessionId) {
                performFullAnalysis(currentSessionId)
            }

            val partyName = if (sessionPartyIndex != -1) "P${sessionPartyIndex + 1}" else "?"
            updateNotification(dataManager.history.totalWins, dataManager.history.totalLosses, "戦闘開始 ($partyName)")
        }
    }

    private fun handleBattleState(bitmap: Bitmap) {
        val result = analyzer.checkBattleResult(bitmap)
        if (result != null) {
            val resultScore = if (result == "WIN") analyzer.detectWinScore(bitmap, analyzer.calibrationData.winBox)
                              else analyzer.detectLoseScore(bitmap, analyzer.calibrationData.loseBox)

            val emoji = if (result == "WIN") "🏆" else "💀"
            val label = if (result == "WIN") "勝利" else "敗北"
            dataManager.appendFlightLog("$emoji ${label}検知 Score ${String.format(Locale.US, "%.3f", resultScore)}")

            finalizeBattle(result, sessionVsScore, resultScore)
        }
    }

    private fun finalizeBattle(result: String, vsScore: Double, resultScore: Double) {
        synchronized(this) {
            // すでにレコードが作成済みの場合は重複させない
            if (lastActiveRecord != null) return
            
            val (myParty, enemyParty, scores) = analyzer.getCurrentResults()
            lastActiveRecord = dataManager.addRecord(
                result, myParty, enemyParty, sessionPartyIndex,
                vsScore, scores.first, scores.second, resultScore,
                sessionPartyScores
            )
            
            dataManager.appendFlightLog(String.format(Locale.US, "📝 戦績記録: %s P[%d] 味方=%s vs 敵=%s",
                result, sessionPartyIndex + 1, myParty.joinToString(","), enemyParty.joinToString(",")))
                
            currentState = State.IDLE
            // 次回識別のためにセッション情報をクリア
            sessionPartyIndex = -1
            sessionPartyScores = emptyList()
            sessionVsScore = 0.0
            
            updateNotification(dataManager.history.totalWins, dataManager.history.totalLosses, "戦闘終了")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Capture Service", NotificationManager.IMPORTANCE_HIGH)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    private fun buildMyNotification(win: Int, lose: Int, status: String): Notification {
        val total = win + lose
        val winRate = if (total > 0) (win.toDouble() / total * 100) else 0.0
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(status)
            .setContentText("${win}W - ${lose}L (${String.format(Locale.US, "%.1f", winRate)}%)")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(win: Int, lose: Int, status: String) {
        val notification = buildMyNotification(win, lose, status)
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        isRunning = false
        currentSessionId = 0
        sendBroadcast(Intent(ACTION_SERVICE_STOPPED))
        clearBurstImages()
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        if (::analyzer.isInitialized) analyzer.releaseTemplates()
        captureThread?.quitSafely()
        analysisThread?.quitSafely()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
