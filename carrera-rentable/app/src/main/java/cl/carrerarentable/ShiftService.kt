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
import cl.carrerarentable.core.ScreenFilter
import cl.carrerarentable.core.ScreenLine
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/**
 * "Turno": mientras dura, se toma una captura de la pantalla cada 1-2 segundos y se reconoce el texto
 * con ML Kit (sin internet). El texto pasa a OfferController, que decide si hay una solicitud
 * de Uber / Cabify / DiDi y muestra el panel.
 *
 * Para no hacer lento el teléfono (Waze, Uber...):
 *  - la pantalla virtual queda conectada de forma fija: pausarla y reanudarla hace que Android avise
 *    "pantalla cambiada" a todas las apps y a la barra del sistema (en Xiaomi eso la pegaba),
 *  - modo liviano: cada 3 s en modo tranquilo y cada 1,5 s mientras en pantalla hay km o montos,
 *  - si la pantalla no cambió, no se vuelve a reconocer el texto,
 *  - todo el trabajo corre con prioridad baja ("de fondo") y reutiliza la memoria.
 */
class ShiftService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    /** Las capturas se convierten en un hilo aparte para no congelar la app. */
    private val worker = android.os.HandlerThread("kmrentable-captura", android.os.Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
    /** El reconocimiento de texto también corre con prioridad baja. */
    private val ocrExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread({ android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); r.run() }, "kmrentable-ocr")
    }
    private val workerHandler = Handler(worker.looper)
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var recognizer: TextRecognizer? = null
    @Volatile private var busy = false
    private var scale = 1f
    private var lastDiag = 0L
    /** Hasta cuándo leer rápido (cada 1 s): mientras en pantalla haya km o montos. */
    @Volatile private var fastUntil = 0L
    /** Imagen reutilizada entre capturas (evita crear memoria nueva cada vez). */
    private var buffer: Bitmap? = null
    /** Muestra de la captura anterior para saber si la pantalla cambió. */
    private var lastSample: IntArray? = null
    private var lastTouch = 0L
    /** Modo liviano (ajuste): menos lecturas y menor resolución. Se fija al empezar el turno. */
    private var light = true
    /** Freno automático: si leer la pantalla le cuesta al teléfono (está ocupado), se espera más (hasta ×3). */
    @Volatile private var backoff = 1.0
    private val fastMs get() = ((if (light) 1500L else 1000L) * backoff).toLong()
    private val slowMs get() = ((if (light) 3000L else 2000L) * backoff).toLong()

    /** Turno en la bitácora (para el $/hora real). Su fin se actualiza cada minuto. */
    private var shiftId = -1L
    private var ticks = 0

    private val tick = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            if (shiftId > 0 && now - lastTouch > 60_000) { lastTouch = now; TripLog.get(this@ShiftService).touchShift(shiftId, now) }
            captureOnce()
            val fast = HistoryImporter.active || android.os.SystemClock.elapsedRealtime() < fastUntil
            workerHandler.postDelayed(this, if (fast) fastMs else slowMs)
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
        currentApp = intent?.getStringExtra(EXTRA_APP)

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
        // Si solo se está importando el historial, no cuenta como turno de trabajo.
        if (!HistoryImporter.active) workerHandler.post { shiftId = TripLog.get(this).startShift(System.currentTimeMillis(), currentApp) }
        workerHandler.postDelayed(tick, 800)
        return START_NOT_STICKY
    }

    private fun startCapture(mp: MediaProjection) {
        val (w, h, dpi) = screenSize()
        // Se captura a menor resolución (640 px de ancho, 540 en modo liviano): suficiente para leer y gasta menos.
        light = SettingsStore.lightMode(this)
        scale = minOf(1f, (if (light) 540f else 640f) / w)
        val cw = (w * scale).toInt()
        val ch = (h * scale).toInt()
        val r = ImageReader.newInstance(cw, ch, PixelFormat.RGBA_8888, 2)
        reader = r
        display = mp.createVirtualDisplay(
            "KMRentable", cw, ch, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, workerHandler,
        )
        recognizer = TextRecognition.getClient(TextRecognizerOptions.Builder().setExecutor(ocrExecutor).build())
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

    /** Corre en el hilo de captura: toma la imagen más reciente (las anteriores se descartan). */
    private fun captureOnce() {
        if (busy) return
        val power = getSystemService(PowerManager::class.java)
        if (!power.isInteractive) return // pantalla apagada
        if (!OfferController.canDraw(this)) {
            diag("Falta el permiso \"Mostrar sobre otras apps\": el panel no se puede mostrar")
            return
        }
        val image = runCatching { reader?.acquireLatestImage() }.getOrNull() ?: return
        busy = true
        process(image)
    }

    private fun process(image: android.media.Image) {
        val bitmap: Bitmap
        try {
            val plane = image.planes[0]
            val width = image.width + (plane.rowStride - plane.pixelStride * image.width) / plane.pixelStride
            val b = buffer?.takeIf { it.width == width && it.height == image.height }
                ?: Bitmap.createBitmap(width, image.height, Bitmap.Config.ARGB_8888).also { buffer = it }
            b.copyPixelsFromBuffer(plane.buffer)
            bitmap = b
        } catch (e: Exception) {
            busy = false
            return
        } finally {
            image.close()
        }

        // Tapar nuestro propio panel para no leer sus números.
        OfferController.overlayBounds()?.let { r ->
            val p = Paint().apply { color = Color.BLACK }
            Canvas(bitmap).drawRect(r.left * scale, r.top * scale, r.right * scale, r.bottom * scale, p)
        }

        // Si la pantalla casi no cambió desde la última lectura, no se vuelve a reconocer el texto.
        val sample = sampleOf(bitmap)
        val prev = lastSample
        lastSample = sample
        if (prev != null && !HistoryImporter.active && changedFraction(prev, sample) < 0.02) {
            busy = false
            return
        }

        val started = android.os.SystemClock.elapsedRealtime()
        recognizer?.process(InputImage.fromBitmap(bitmap, 0))
            ?.addOnSuccessListener { result ->
                val raw = result.textBlocks
                    .flatMap { it.lines }
                    .sortedWith(compareBy({ it.boundingBox?.top ?: 0 }, { it.boundingBox?.left ?: 0 }))
                    .map { ScreenLine(it.text, it.boundingBox?.top ?: 0, it.boundingBox?.bottom ?: 0) }
                // Lectura rápida solo mientras en pantalla haya km o montos.
                if (raw.any { INTERESTING.containsMatchIn(it.text) }) {
                    fastUntil = android.os.SystemClock.elapsedRealtime() + FAST_WINDOW_MS
                }
                // Quitar nuestro propio panel si quedó en la captura; si no se puede, descartarla.
                val cleaned = ScreenFilter.removeOwnPanel(raw)
                if (cleaned == null) {
                    diag("captura descartada: se veía nuestro panel", raw.map { it.text })
                } else {
                    val lines = cleaned.map { it.text }
                    val found = OfferController.onScreenText(this, lines)
                    diag("líneas: ${lines.size} · oferta: ${if (found) "SÍ" else "no"}", lines, found)
                }
            }
            ?.addOnFailureListener { e -> diag("error al reconocer texto: ${e.message}") }
            ?.addOnCompleteListener {
                // Una lectura lenta (>700 ms) significa que el teléfono está ocupado: frenar.
                val took = android.os.SystemClock.elapsedRealtime() - started
                backoff = if (took > 700) minOf(backoff * 1.5, 3.0) else maxOf(backoff * 0.8, 1.0)
                busy = false
            }
            ?: run { busy = false }
    }

    /** Muestra de 24 × 48 píxeles de la captura (para comparar sin recorrer toda la imagen). */
    private fun sampleOf(b: Bitmap): IntArray {
        val cols = 24; val rows = 48
        val out = IntArray(cols * rows)
        for (y in 0 until rows) for (x in 0 until cols) {
            // Centro de cada celda de la grilla.
            out[y * cols + x] = b.getPixel((x * 2 + 1) * b.width / (cols * 2), (y * 2 + 1) * b.height / (rows * 2))
        }
        return out
    }

    private fun changedFraction(a: IntArray, b: IntArray): Double {
        if (a.size != b.size) return 1.0
        var n = 0
        for (i in a.indices) if (a[i] != b[i]) n++
        return n.toDouble() / a.size
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
            .setContentText(currentApp?.let { "Turno en $it · leyendo las solicitudes" } ?: "Leyendo las solicitudes de la pantalla")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Terminar turno", stop).build())
            .build()
    }

    override fun onDestroy() {
        running = false
        currentApp = null
        HistoryImporter.onShiftEnded()
        if (shiftId > 0) runCatching { TripLog.get(this).touchShift(shiftId, System.currentTimeMillis()) }
        OfferController.hide()
        handler.removeCallbacksAndMessages(null)
        workerHandler.removeCallbacksAndMessages(null)
        worker.quitSafely()
        ocrExecutor.shutdown()
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
        /** Lectura rápida mientras en pantalla haya km o montos; después vuelve al modo tranquilo. */
        private const val FAST_WINDOW_MS = 20_000L
        private val INTERESTING = Regex("""\d\s*km\b|\$\s?\d|CLP\s?\d|\d\s?CLP""", RegexOption.IGNORE_CASE)
        private const val ACTION_STOP = "cl.carrerarentable.STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"
        const val EXTRA_APP = "app"

        /** Empresa del turno activo (Uber, Cabify, DiDi) o null. */
        @Volatile var currentApp: String? = null
            private set

        @Volatile var running = false
            private set

        fun start(c: Context, resultCode: Int, data: Intent, app: String? = null) {
            c.startForegroundService(
                Intent(c, ShiftService::class.java)
                    .putExtra(EXTRA_RESULT_CODE, resultCode)
                    .putExtra(EXTRA_DATA, data)
                    .putExtra(EXTRA_APP, app)
            )
        }

        fun stop(c: Context) {
            c.startService(Intent(c, ShiftService::class.java).setAction(ACTION_STOP))
        }
    }
}
