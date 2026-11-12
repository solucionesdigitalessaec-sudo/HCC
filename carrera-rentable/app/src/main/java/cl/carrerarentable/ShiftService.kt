package cl.carrerarentable

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.view.WindowManager
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/**
 * "Turno": lectura por imagen. Uber y DiDi esconden su texto a los servicios de accesibilidad,
 * así que mientras dura el turno se toma una captura de la pantalla cada segundo y se reconoce
 * el texto con ML Kit (sin internet). El texto pasa al mismo lector de solicitudes de siempre.
 */
class ShiftService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var recognizer: TextRecognizer? = null
    private var busy = false
    private var scale = 1f
    private var lastDiag = 0L

    private val tick = object : Runnable {
        override fun run() {
            captureOnce()
            handler.postDelayed(this, INTERVAL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        else @Suppress("DEPRECATION") intent?.getParcelableExtra(EXTRA_DATA)
        if (data == null || projection != null) return START_NOT_STICKY

        // En Android 14+ el servicio debe estar en primer plano ANTES de pedir la captura.
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notification)
        }

        val mpm = getSystemService(MediaProjectionManager::class.java)
        val mp = runCatching { mpm.getMediaProjection(resultCode, data) }.getOrNull()
        if (mp == null) {
            Diag.ocr(this, "No se pudo iniciar la captura de pantalla")
            stopSelf()
            return START_NOT_STICKY
        }
        projection = mp
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                handler.post { stopSelf() }
            }
        }, handler)

        startCapture(mp)
        running = true
        Diag.ocr(this, "Turno iniciado")
        handler.postDelayed(tick, 800)
        return START_NOT_STICKY
    }

    private fun startCapture(mp: MediaProjection) {
        val (w, h, dpi) = screenSize()
        // Se captura a menor resolución (máx. 720 px de ancho): suficiente para leer y gasta menos.
        scale = minOf(1f, 720f / w)
        val cw = (w * scale).toInt()
        val ch = (h * scale).toInt()
        val r = ImageReader.newInstance(cw, ch, PixelFormat.RGBA_8888, 2)
        reader = r
        display = mp.createVirtualDisplay(
            "KMRentable", cw, ch, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, handler,
        )
        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    private fun screenSize(): Triple<Int, Int, Int> {
        val wm = getSystemService(WindowManager::class.java)
        val dpi = resources.displayMetrics.densityDpi
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            Triple(b.width(), b.height(), dpi)
        } else {
            val m = android.util.DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(m)
            Triple(m.widthPixels, m.heightPixels, dpi)
        }
    }

    private fun captureOnce() {
        if (busy) return
        val power = getSystemService(PowerManager::class.java)
        if (!power.isInteractive) return // pantalla apagada
        val svc = RideAccessibilityService.instance
        if (svc == null) {
            diag("El lector de accesibilidad no está activo: el panel no se puede mostrar")
            return
        }
        val image = runCatching { reader?.acquireLatestImage() }.getOrNull() ?: return
        val bitmap: Bitmap
        try {
            val plane = image.planes[0]
            val rowPadding = plane.rowStride - plane.pixelStride * image.width
            val full = Bitmap.createBitmap(image.width + rowPadding / plane.pixelStride, image.height, Bitmap.Config.ARGB_8888)
            full.copyPixelsFromBuffer(plane.buffer)
            bitmap = if (rowPadding == 0) full else Bitmap.createBitmap(full, 0, 0, image.width, image.height)
        } catch (e: Exception) {
            return
        } finally {
            image.close()
        }

        // Tapar nuestro propio panel para no leer sus números.
        svc.overlayBounds()?.let { r ->
            val p = Paint().apply { color = Color.BLACK }
            Canvas(bitmap).drawRect(r.left * scale, r.top * scale, r.right * scale, r.bottom * scale, p)
        }

        busy = true
        recognizer?.process(InputImage.fromBitmap(bitmap, 0))
            ?.addOnSuccessListener { result ->
                val lines = result.textBlocks
                    .flatMap { it.lines }
                    .sortedWith(compareBy({ it.boundingBox?.top ?: 0 }, { it.boundingBox?.left ?: 0 }))
                    .map { it.text }
                val found = RideAccessibilityService.instance?.onScreenText(lines) ?: false
                diag("líneas: ${lines.size} · oferta: ${if (found) "SÍ" else "no"}", lines, found)
            }
            ?.addOnFailureListener { e -> diag("error al reconocer texto: ${e.message}") }
            ?.addOnCompleteListener { busy = false }
            ?: run { busy = false }
    }

    private fun diag(status: String, lines: List<String> = emptyList(), force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastDiag < 3000) return
        lastDiag = now
        Diag.ocr(this, status, lines)
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Turno", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ShiftService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("KMRentable · turno activo")
            .setContentText("Leyendo las solicitudes de la pantalla")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Terminar turno", stop).build())
            .build()
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        runCatching { display?.release() }
        runCatching { reader?.close() }
        runCatching { recognizer?.close() }
        runCatching { projection?.stop() }
        projection = null
        Diag.ocr(this, "Turno terminado")
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "turno"
        private const val NOTIF_ID = 7
        private const val INTERVAL_MS = 1000L
        private const val ACTION_STOP = "cl.carrerarentable.STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"

        @Volatile var running = false
            private set

        fun start(c: Context, resultCode: Int, data: Intent) {
            c.startForegroundService(
                Intent(c, ShiftService::class.java)
                    .putExtra(EXTRA_RESULT_CODE, resultCode)
                    .putExtra(EXTRA_DATA, data)
            )
        }

        fun stop(c: Context) {
            c.startService(Intent(c, ShiftService::class.java).setAction(ACTION_STOP))
        }
    }
}
