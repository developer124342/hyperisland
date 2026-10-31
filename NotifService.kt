package pl.pixel.island

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.graphics.drawable.BitmapDrawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class NotifService : NotificationListenerService() {
    companion object { @Volatile var instance: NotifService? = null }

    private var msm: MediaSessionManager? = null
    private var all: List<MediaController> = emptyList()
    private val keys = HashMap<String, String>()
    private val me get() = ComponentName(this, NotifService::class.java)

    private val cb = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) { pushMedia() }
        override fun onMetadataChanged(metadata: MediaMetadata?) { pushMedia() }
        override fun onSessionDestroyed() { try { pick(msm?.getActiveSessions(me)) } catch (_: Exception) {} }
    }
    private val sessL = MediaSessionManager.OnActiveSessionsChangedListener { pick(it) }

    override fun onListenerConnected() {
        instance = this
        msm = getSystemService(MediaSessionManager::class.java)
        try { msm?.addOnActiveSessionsChangedListener(sessL, me) } catch (_: Exception) {}
        resync()
    }

    override fun onListenerDisconnected() {
        instance = null
        all.forEach { try { it.unregisterCallback(cb) } catch (_: Exception) {} }
        all = emptyList()
        try { msm?.removeOnActiveSessionsChangedListener(sessL) } catch (_: Exception) {}
    }

    fun resync() {
        try { pick(msm?.getActiveSessions(me)) } catch (_: Exception) {}
        try { activeNotifications?.forEach { handle(it, true) } } catch (_: Exception) {}
    }

    // ---------- muzyka ----------
    private fun pick(list: List<MediaController>?) {
        all.forEach { try { it.unregisterCallback(cb) } catch (_: Exception) {} }
        all = list ?: emptyList()
        all.forEach { it.registerCallback(cb) }
        pushMedia()
    }

    private fun pushMedia() {
        val svc = IslandService.instance ?: return
        val c = all.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: all.firstOrNull()
        val st = c?.playbackState
        val md = c?.metadata
        if (c == null || st == null || md == null ||
            st.state == PlaybackState.STATE_STOPPED || st.state == PlaybackState.STATE_NONE) {
            svc.remove("media"); return
        }
        val playing = st.state == PlaybackState.STATE_PLAYING
        val art = md.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val icon = art?.let { BitmapDrawable(resources, it) }
            ?: try { packageManager.getApplicationIcon(c.packageName) } catch (_: Exception) { null }
        val title = md.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty().ifBlank { "Odtwarzanie" }
        val artist = (md.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)).orEmpty()
        val tc = c.transportControls
        svc.post(Item(
            key = "media", type = Type.MEDIA, title = title, sub = artist, icon = icon,
            glyph = R.drawable.ic_music,
            ind = if (playing) Ind.EQ else Ind.NONE,
            acts = listOf(
                Act(icon = R.drawable.ic_prev) { tc.skipToPrevious() },
                Act(icon = if (playing) R.drawable.ic_pause else R.drawable.ic_play, bg = android.graphics.Color.WHITE) {
                    if (playing) tc.pause() else tc.play()
                },
                Act(icon = R.drawable.ic_next) { tc.skipToNext() }
            ),
            onTap = {
                val pi = c.sessionActivity
                if (pi != null) svc.fire(pi)
                else packageManager.getLaunchIntentForPackage(c.packageName)?.let {
                    it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK); svc.startActivity(it)
                }
            },
            autoHideMs = if (playing) 0L else 6000L,
            media = c
        ))
    }

    // ---------- powiadomienia ----------
    override fun onNotificationPosted(sbn: StatusBarNotification) = handle(sbn, false)

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        keys.remove(sbn.key)?.let { IslandService.instance?.remove(it) }
    }

    private fun negative(l: String) =
        listOf("odrzu", "rozł", "zakoń", "decline", "reject", "hang", "end", "dismiss", "ignor").any { l.contains(it, true) }

    private fun lowImportance(sbn: StatusBarNotification): Boolean {
        val r = Ranking()
        return currentRanking.getRanking(sbn.key, r) && r.importance < NotificationManager.IMPORTANCE_DEFAULT
    }

    private fun handle(sbn: StatusBarNotification, initial: Boolean) {
        val svc = IslandService.instance ?: return
        val pkg = sbn.packageName
        if (pkg == packageName) return
        val n = sbn.notification ?: return
        val ex = n.extras
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (ex.getCharSequence(Notification.EXTRA_TEXT)
            ?: ex.getCharSequence(Notification.EXTRA_BIG_TEXT))?.toString().orEmpty()
        val ongoing = n.flags and Notification.FLAG_ONGOING_EVENT != 0
        val icon = try { packageManager.getApplicationIcon(pkg) } catch (_: Exception) { null }
        val acts = (n.actions ?: emptyArray()).mapNotNull { a ->
            a.actionIntent?.let { pi -> Act(a.title?.toString().orEmpty()) { svc.fire(pi) } }
        }
        val open: (() -> Unit)? = n.contentIntent?.let { pi -> { svc.fire(pi) } }
        val tmpl = ex.getString(Notification.EXTRA_TEMPLATE).orEmpty()

        val item: Item? = when {
            // nagrywanie ekranu (systemowe powiadomienie SystemUI)
            pkg == "com.android.systemui" && ongoing &&
                (n.channelId ?: "").contains("screen_record", true) ->
                Item(key = "rec", type = Type.REC, title = "Nagrywanie ekranu", sub = "Dotknij, aby zatrzymać",
                    glyph = R.drawable.ic_rec, glyphTint = 0xFFFF3B30.toInt(), ind = Ind.REC, since = sbn.postTime,
                    acts = acts.take(1).map { Act(it.label, 0, 0xFFFF3B30.toInt(), it.run) })
            n.category == Notification.CATEGORY_CALL ->
                Item(key = "call", type = Type.CALL, title = title.ifBlank { "Połączenie" },
                    sub = text.ifBlank { if (ongoing) "Rozmowa trwa" else "Połączenie przychodzące" },
                    icon = null, glyph = R.drawable.ic_call, glyphTint = 0xFF34C759.toInt(), ind = Ind.CALL,
                    since = if (ongoing) sbn.postTime else 0L,
                    acts = acts.take(3).map {
                        Act(it.label, 0, if (negative(it.label)) 0xFFFF3B30.toInt() else 0xFF34C759.toInt(), it.run)
                    }, onTap = open, autoExpand = !ongoing)
            n.category == Notification.CATEGORY_ALARM || n.category == Notification.CATEGORY_STOPWATCH ||
                (pkg.contains("deskclock") && ongoing) ->
                Item(key = "timer", type = Type.TIMER, title = title, sub = text, icon = icon,
                    glyph = R.drawable.ic_timer, glyphTint = 0xFFFF9F0A.toInt(), ind = Ind.TIMER,
                    since = if (ex.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER)) n.`when` else 0L,
                    indText = if (ex.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN)) "down" else "",
                    acts = acts.take(2), onTap = open)
            n.category == Notification.CATEGORY_NAVIGATION && ongoing ->
                Item(key = "nav", type = Type.NAV, title = title, sub = text, icon = icon,
                    glyph = R.drawable.ic_nav, glyphTint = 0xFF4DA3FF.toInt(), onTap = open)
            tmpl.contains("MediaStyle") -> null
            ongoing || initial -> null
            n.flags and Notification.FLAG_GROUP_SUMMARY != 0 -> null
            title.isBlank() && text.isBlank() -> null
            lowImportance(sbn) -> null
            else ->
                Item(key = "n:" + sbn.key, type = Type.NOTIF, title = title, sub = text, icon = icon,
                    glyph = R.drawable.ic_bell, ind = Ind.DOT, acts = acts.take(2), onTap = open,
                    autoHideMs = 4500L, autoExpand = false)
        }
        if (item == null) return
        keys[sbn.key] = item.key
        svc.post(item)
    }
}
