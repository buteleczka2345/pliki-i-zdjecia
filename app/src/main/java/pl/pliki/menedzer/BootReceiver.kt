package pl.pliki.menedzer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Po włączeniu telefonu (i po aktualizacji aplikacji) wraca śledzenie pobrań. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) = DownloadWatchService.start(ctx)
}
