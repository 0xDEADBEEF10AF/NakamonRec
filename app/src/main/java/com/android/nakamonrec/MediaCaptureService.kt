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
    private var selectedPartyIndex = -1
    private var currentPartyScores: List<Double> = emptyList()
    private var currentVsScore: Double = 0.0
    private var currentSessionId = 0L
    private var debugImageSavedInSession = false
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
        analyzer = BattleAnalyzer(dataManager.monsterMaster)
        analyzer.loadTemplates(this)

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

    private fun repeatScan(sessionId: Long, count: Int, delayMs: Long) {
        // セッション切れ、全確定済み、または回数終了なら即終了
        if (sessionId != currentSessionId || analyzer.isAllIdentified() || count <= 0) {
            // 最終的に未確定で終わった場合のみバースト画像5枚を保存
            if (count <= 0 && sessionId == currentSessionId && !analyzer.isAllIdentified() && !debugImageSavedInSession) {
                synchronized(this) {
                    val timestamp = System.currentTimeMillis()
                    burstImages.forEachIndexed { index, bmp ->
                        if (!bmp.isRecycled) {
                            analyzer.saveDebugBitmap(bmp, "incomplete_burst_${index}_$timestamp")
                        }
                    }
                }
                debugImageSavedInSession = true
            }
            // バースト画像の解放
            if (sessionId != currentSessionId || analyzer.isAllIdentified() || count <= 0) {
                clearBurstImages()
            }
            return
        }

        analysisHandler?.post {
            try {
                if (sessionId == currentSessionId) {
                    val snapshots = synchronized(this) { burstImages.toList() }
                    val foundNew = if (snapshots.isNotEmpty()) {
                        analyzer.identifyNextSlot(snapshots)
                    } else {
                        synchronized(this) {
                            val bmp = latestBitmap
                            if (bmp != null && !bmp.isRecycled) {
                                analyzer.identifyStepByStep(bmp)
                                true 
                            } else false
                        }
                    }

                    // 識別が進んだ場合、もし既にバトルが終了してレコードが作成済みなら更新する
                    if (foundNew && currentState == State.IDLE) {
                        val recordToUpdate = lastActiveRecord
                        if (recordToUpdate != null) {
                            val (my, enemy, scores) = analyzer.getCurrentResults()
                            recordToUpdate.myParty = my
                            recordToUpdate.enemyParty = enemy
                            recordToUpdate.myPartyScores = scores.first
                            recordToUpdate.enemyPartyScores = scores.second
                            dataManager.updateRecord(recordToUpdate)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("Battle", "Analysis error: ${e.message}")
            } finally {
                // 解析後に再度条件チェックして次を予約（シーケンシャル）
                if (sessionId == currentSessionId && !analyzer.isAllIdentified() && count > 1) {
                    analysisHandler?.postDelayed({
                        repeatScan(sessionId, count - 1, delayMs)
                    }, delayMs)
                } else if (analyzer.isAllIdentified()) {
                    Log.i("Battle", "All monsters identified. Ending scan loop early.")
                    clearBurstImages()
                }
            }
        }
    }

    private fun startBurstCapture(sessionId: Long) {
        if (sessionId != currentSessionId) return
        isCapturingBurst = true
        
        var captureCount = 0
        val captureRunnable = object : Runnable {
            override fun run() {
                if (sessionId != currentSessionId || captureCount >= 5) {
                    isCapturingBurst = false
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
                
                if (captureCount < 5) {
                    captureHandler?.postDelayed(this, 200L)
                } else {
                    isCapturingBurst = false
                }
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
            selectedPartyIndex = detected
            currentPartyScores = scores
        }
        
        if (analyzer.isVsDetected(bitmap)) {
            val vsScore = analyzer.detectVsScore(bitmap, analyzer.calibrationData.vsBox)
            currentVsScore = vsScore

            analysisHandler?.removeCallbacksAndMessages(null)

            currentSessionId = System.currentTimeMillis()
            debugImageSavedInSession = false
            currentState = State.IN_BATTLE
            analyzer.resetIdentification()
            
            clearBurstImages()
            startBurstCapture(currentSessionId) // バースト撮影開始

            val partyName = if (selectedPartyIndex != -1) "P${selectedPartyIndex + 1}" else "?"
            updateNotification(dataManager.history.totalWins, dataManager.history.totalLosses, "戦闘開始 ($partyName)")
            
            repeatScan(currentSessionId, 40, 50L)
        }
    }

    private fun handleBattleState(bitmap: Bitmap) {
        val result = analyzer.checkBattleResult(bitmap)
        if (result != null) {
            val resultScore = if (result == "WIN") analyzer.detectWinScore(bitmap, analyzer.calibrationData.winBox)
                              else analyzer.detectLoseScore(bitmap, analyzer.calibrationData.loseBox)

            // 戦闘終了時の不完全なデバッグ画像保存は、中途半端なロゴが映るだけで有用でないため削除

            // currentSessionId = 0 はここでは行わない（バックグラウンド識別を継続させるため）
            finalizeBattle(result, currentVsScore, resultScore)
        }
    }

    private fun finalizeBattle(result: String, vsScore: Double, resultScore: Double) {
        val (myParty, enemyParty, scores) = analyzer.getCurrentResults()
        lastActiveRecord = dataManager.addRecord(
            result, myParty, enemyParty, selectedPartyIndex,
            vsScore, scores.first, scores.second, resultScore,
            currentPartyScores
        )
        currentState = State.IDLE
        selectedPartyIndex = -1 // 次回識別のためにリセット
        currentPartyScores = emptyList() // リセット
        currentVsScore = 0.0
        updateNotification(dataManager.history.totalWins, dataManager.history.totalLosses, "戦闘終了")
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
