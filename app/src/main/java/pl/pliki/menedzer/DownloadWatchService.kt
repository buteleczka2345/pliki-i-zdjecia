package pl.pliki.menedzer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import java.io.File

/**
 * Działa w tle i patrzy na systemową bazę plików. Każdy nowy plik w folderach pobrań = powiadomienie
 * z przyciskami Otwórz/Zainstaluj i Przenieś na kartę SD. Nie zużywa procesora, dopóki nic się nie zmienia.
 */
class DownloadWatchService : Service() {
    companion object {
        private const val MOVE = "move"
        fun start(ctx: Context) {
            if (Files.hasAccess()) ctx.startForegroundService(Intent(ctx, DownloadWatchService::class.java))
        }
    }

    private val thread = HandlerThread("pobrania").apply { start() }
    private val handler = Handler(thread.looper)
    private var seen: MutableSet<Long>? = null
    private val nm by lazy { getSystemService(NotificationManager::class.java) }
    private val check = Runnable { check() }
    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            handler.removeCallbacks(check)
            handler.postDelayed(check, 1500) // przeglądarka zmienia wpis kilka razy pod rząd
        }
    }

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        nm.createNotificationChannel(NotificationChannel("tlo", "Śledzenie pobrań", NotificationManager.IMPORTANCE_MIN))
        nm.createNotificationChannel(NotificationChannel("nowe", "Nowe pobrania", NotificationManager.IMPORTANCE_DEFAULT))
        val n = Notification.Builder(this, "tlo")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Pliki czekają na nowe pobrania")
            .setContentIntent(appIntent())
            .setOngoing(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(1, n)
        } catch (e: Exception) {
            stopSelf() // system nie pozwolił (np. start w tle) — wystartuje przy następnym otwarciu aplikacji
            return
        }
        contentResolver.registerContentObserver(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), true, observer)
        handler.post { seen = Files.recentDownloads(this, 7, 1000).map { it.id }.toMutableSet() }
    }

    override fun onDestroy() {
        contentResolver.unregisterContentObserver(observer)
        thread.quitSafely()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == MOVE) {
            val file = File(intent.getStringExtra("path")!!)
            val id = intent.getIntExtra("id", 0)
            handler.post { moveToSd(file, id) }
        }
        return START_STICKY
    }

    private fun check() {
        val seen = seen ?: return
        if (!Files.hasAccess()) return
        for (item in Files.recentDownloads(this, 1, 100).reversed()) {
            if (!seen.add(item.id)) continue
            val p = item.file.path
            if (!item.file.isFile || synchronized(Files.ours) { Files.ours.any { p == it || p.startsWith("$it/") } }) continue
            notifyNew(item)
        }
    }

    private fun notifyNew(item: Item) {
        val f = item.file
        val id = item.id.toInt()
        val apk = kindOf(f.name) == Kind.APK
        val b = Notification.Builder(this, "nowe")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Pobrano: ${f.name}")
            .setContentText("${formatSize(item.size)} · ${f.parentFile?.name}")
            .setContentIntent(appIntent())
            .setAutoCancel(true)
            .addAction(Notification.Action.Builder(null, if (apk) "Zainstaluj" else "Otwórz", openIntent(f, id)).build())
        if (Files.sdRoot(this) != null && !f.path.startsWith(Files.sdRoot(this)!!.path)) {
            val move = Intent(this, DownloadWatchService::class.java).setAction(MOVE).putExtra("path", f.path).putExtra("id", id)
            b.addAction(Notification.Action.Builder(null, "Przenieś na kartę SD",
                PendingIntent.getService(this, id * 2 + 1, move, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build())
        }
        nm.notify(id, b.build())
    }

    private fun moveToSd(file: File, id: Int) {
        val text = try {
            val dst = Files.transfer(this, file, File(Files.sdRoot(this) ?: error("Brak karty SD"), "Download"), move = true)
            "Przeniesiono na kartę SD: ${dst.name}"
        } catch (e: Exception) {
            "Nie przeniesiono ${file.name}: ${e.message}"
        }
        nm.notify(id, Notification.Builder(this, "nowe")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(text)
            .setContentIntent(appIntent())
            .setAutoCancel(true)
            .build())
    }

    private fun openIntent(f: File, id: Int): PendingIntent {
        // APK otwieramy przez aplikację: ona sprawdzi zgodę na instalację, zanim wywoła instalator
        val i = if (kindOf(f.name) == Kind.APK) Intent(this, MainActivity::class.java).putExtra(MainActivity.OPEN, f.path)
        else Files.viewIntent(f)
        return PendingIntent.getActivity(this, id * 2, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun appIntent() = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
}
