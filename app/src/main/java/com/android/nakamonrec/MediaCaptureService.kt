package com.android.nakamonrec

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
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
    private var latestBitmap: Bitmap? = null
    private var selectedPartyIndex = -1
    private var currentSessionId = 0L
    private var debugImageSavedInSession = false

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
                analyzer.setCalibrationData(calData)
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
            
            val cleanBitmap = try {
                processImageToBitmap(image)
            } catch (_: Exception) {
                image.close()
                return@setOnImageAvailableListener
            }
            image.close()

            synchronized(this) {
                latestBitmap?.recycle()
                latestBitmap = cleanBitmap
            }

            val currentTime = System.currentTimeMillis()
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
        }, captureHandler)
    }

    private fun processImageToBitmap(image: android.media.Image): Bitmap {
        val planes = image.planes[0]
        val buffer = planes.buffer
        val pixelStride = planes.pixelStride
        val rowStride = planes.rowStride
        val rowPadding = rowStride - pixelStride * image.width
        val tempBitmap = createBitmap(image.width + rowPadding / pixelStride, image.height)
        tempBitmap.copyPixelsFromBuffer(buffer)
        val cleanBitmap = Bitmap.createBitmap(tempBitmap, 0, 0, image.width, image.height)
        tempBitmap.recycle()
        return cleanBitmap
    }

    private fun repeatScan(sessionId: Long, count: Int, delayMs: Long) {
        if (sessionId != currentSessionId || analyzer.isAllIdentified()) {
            if (count <= 0 && sessionId == currentSessionId && !analyzer.isAllIdentified() && !debugImageSavedInSession) {
                synchronized(this) {
                    latestBitmap?.let { analyzer.saveDebugBitmap(it, "incomplete_results_${System.currentTimeMillis()}") }
                }
                debugImageSavedInSession = true
            }
            return
        }

        analysisHandler?.post {
            val bitmapToProcess: Bitmap? = synchronized(this) {
                val current = latestBitmap
                if (current != null && !current.isRecycled) Bitmap.createBitmap(current) else null
            }
            
            bitmapToProcess?.let { bitmap ->
                try {
                    if (sessionId == currentSessionId) {
                        analyzer.identifyStepByStep(bitmap)
                    }
                } catch (e: Exception) {
                    Log.e("Battle", "Analysis error: ${e.message}")
                } finally {
                    bitmap.recycle()
                }
            }
            
            analysisHandler?.postDelayed({
                repeatScan(sessionId, count - 1, delayMs)
            }, delayMs)
        }
    }

    private fun handleIdleState(bitmap: Bitmap) {
        val detected = analyzer.detectSelectedParty(bitmap)
        if (detected != -1) selectedPartyIndex = detected
        
        if (analyzer.isVsDetected(bitmap)) {
            analysisHandler?.removeCallbacksAndMessages(null)

            currentSessionId = System.currentTimeMillis()
            debugImageSavedInSession = false
            currentState = State.IN_BATTLE
            analyzer.resetIdentification()
            val partyName = if (selectedPartyIndex != -1) "Party${selectedPartyIndex + 1}" else "?"
            updateNotification(dataManager.history.totalWins, dataManager.history.totalLosses, "戦闘開始！ ($partyName)")
            
            repeatScan(currentSessionId, 40, 50L)
        }
    }

    private fun handleBattleState(bitmap: Bitmap) {
        val result = analyzer.checkBattleResult(bitmap)
        if (result != null) {
            if (!analyzer.isAllIdentified() && !debugImageSavedInSession) {
                analyzer.saveDebugBitmap(bitmap, "battle_ended_incomplete_${System.currentTimeMillis()}")
                debugImageSavedInSession = true
            }
            currentSessionId = 0
            finalizeBattle(result)
        }
    }

    private fun finalizeBattle(result: String) {
        val (myParty, enemyParty) = analyzer.getCurrentResults()
        dataManager.addRecord(result, myParty, enemyParty, selectedPartyIndex)
        currentState = State.IDLE
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
            .setContentText("戦績: ${win}勝 ${lose}敗 (勝率 ${String.format(Locale.US, "%.1f", winRate)}%)")
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
