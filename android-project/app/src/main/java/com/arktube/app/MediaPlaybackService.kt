package com.arktube.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media.session.MediaButtonReceiver
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat

/**
 * Hosts the app's one [MediaSessionCompat] and the media-style
 * notification/lock-screen transport controls that go with it, so
 * play/pause/seek reach the video playing inside MainActivity's
 * WebView from *outside* the app entirely: the notification shade,
 * the lock screen, a wired headset's inline remote, a Bluetooth
 * earbud/car-stereo's AVRCP buttons, a paired watch's media
 * complication -- anything the platform considers "a device that can
 * control the active media session", which is exactly what
 * MediaSessionCompat exists to broadcast to. None of those surfaces
 * talk to the WebView directly; they all go through this session.
 *
 * This deliberately does not attempt real background/PiP-style
 * playback survival, a media queue/playlist, or Android Auto
 * browsing (all explicitly out of scope per MainActivity's class doc)
 * -- just correct transport control for whatever's playing in the
 * foreground WebView, for as long as this process is alive.
 *
 * Runs as a Service (rather than living directly in the Activity) for
 * two reasons: it's what lets Android show a MediaStyle notification
 * at all per the platform's own guidelines, and it keeps the session
 * -- and the audio-focus/becoming-noisy handling below -- alive
 * across brief Activity recreation instead of tearing down and
 * rebuilding transport control on every config change. It's bound
 * (see [LocalBinder]) as soon as MainActivity starts, but only
 * promoted into the *foreground* -- which is what actually posts the
 * notification -- the first time real playback is reported; see
 * MainActivity's MediaPlaybackBridge.onPlaybackState().
 *
 * MainActivity binds to this service and:
 *  - implements [CommandListener] to translate session callbacks
 *    (onPlay/onPause/onSeekTo/etc., however they arrived -- a tapped
 *    notification action, a Bluetooth AVRCP command, a wired headset
 *    button) into JS calls on the page's actual `<video>` element
 *  - calls [updatePlaybackState]/[updateMetadata] whenever
 *    MEDIA_SESSION_JS reports that same `<video>` element's own
 *    play/pause/seek/title changes, so the session -- and therefore
 *    the notification/lock screen/etc. -- stays truthful about what's
 *    actually happening on the page, not just a mirror of the last
 *    command sent to it.
 */
class MediaPlaybackService : Service() {

    /** Implemented by MainActivity to actually carry out a transport command. */
    interface CommandListener {
        fun onPlayCommand()
        fun onPauseCommand()
        fun onSeekToCommand(positionMs: Long)
        fun onFastForwardCommand()
        fun onRewindCommand()
    }

    inner class LocalBinder : Binder() {
        val service: MediaPlaybackService get() = this@MediaPlaybackService
    }

    private val binder = LocalBinder()
    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var audioManager: AudioManager
    private var commandListener: CommandListener? = null
    private var isPlaying = false
    private var audioFocusRequest: AudioFocusRequest? = null

    // Pauses on an outright focus loss (a phone call starting, another
    // player taking over) -- the same etiquette every other media app
    // follows, and without it the video would carry on playing under/
    // over whatever else now also wants the speaker.
    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> commandListener?.onPauseCommand()
        }
    }

    // Pauses when the active audio route disappears (headphones
    // unplugged, Bluetooth device disconnected) instead of carrying
    // on out loud to whatever's left -- again, standard media-app
    // etiquette, and the specific thing ACTION_AUDIO_BECOMING_NOISY
    // exists for.
    private val becomingNoisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                commandListener?.onPauseCommand()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        createNotificationChannel()

        mediaSession = MediaSessionCompat(this, "ArkTubeMediaSession").apply {
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    commandListener?.onPlayCommand()
                }

                override fun onPause() {
                    commandListener?.onPauseCommand()
                }

                override fun onStop() {
                    // No real native "stop" for a page video beyond
                    // pausing it -- there's nothing to release/tear
                    // down the way a local media player would.
                    commandListener?.onPauseCommand()
                }

                override fun onSeekTo(pos: Long) {
                    commandListener?.onSeekToCommand(pos)
                }

                override fun onFastForward() {
                    commandListener?.onFastForwardCommand()
                }

                override fun onRewind() {
                    commandListener?.onRewindCommand()
                }
            })
            setPlaybackState(idlePlaybackState())
            isActive = true
        }

        registerReceiver(becomingNoisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Whenever this service is (re)started -- MainActivity's own
        // startForegroundService() call the first time real playback
        // begins, or the system relaunching it to deliver a tapped
        // notification action/media-button PendingIntent -- Android
        // requires startForeground() to be called shortly after
        // onStartCommand() returns. Post a notification immediately;
        // updatePlaybackState()/updateMetadata() replace it with
        // fresher content moments later once MainActivity/JS report
        // the actual state.
        startForegroundWithNotification()
        intent?.let { MediaButtonReceiver.handleIntent(mediaSession, it) }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(audioFocusListener)
        }
        runCatching { unregisterReceiver(becomingNoisyReceiver) }
        mediaSession.isActive = false
        mediaSession.release()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    fun setCommandListener(listener: CommandListener?) {
        commandListener = listener
    }

    /**
     * Mirrors the page's own `<video>` play/pause/position into the
     * session -- called from MainActivity whenever MEDIA_SESSION_JS
     * reports a play/pause/seeked/timeupdate event, so this reflects
     * what's *actually* happening on the page, not just the last
     * command a native control sent it.
     *
     * IMPORTANT: MEDIA_SESSION_JS reports playback state on every
     * throttled `timeupdate` tick (roughly once a second) for as long
     * as the video keeps playing, not just once when it actually
     * starts -- so this runs with `playing == true` continuously
     * during normal playback, not just on the false->true edge.
     * requestAudioFocus() below must only fire on that edge (tracked
     * via `wasPlaying`): re-requesting AUDIOFOCUS_GAIN on every one
     * of those repeated "still playing" reports competes with
     * WebView/Chromium's own internal audio focus handling for the
     * same <video> element it's actively playing, knocking Chromium's
     * focus loose and making it pause the real HTML5 video -- which
     * is what was showing up as playback pausing instantly and
     * repeatedly right after starting. Same failure shape as the
     * SurfaceView z-order bug elsewhere in this app: an operation
     * that's only safe once per state transition was instead being
     * re-run on every repeated report.
     */
    fun updatePlaybackState(playing: Boolean, positionMs: Long, playbackSpeed: Float) {
        val wasPlaying = isPlaying
        isPlaying = playing
        val actions = PlaybackStateCompat.ACTION_PLAY or
            PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_PLAY_PAUSE or
            PlaybackStateCompat.ACTION_SEEK_TO or
            PlaybackStateCompat.ACTION_FAST_FORWARD or
            PlaybackStateCompat.ACTION_REWIND or
            PlaybackStateCompat.ACTION_STOP
        val state = if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(state, positionMs, if (playbackSpeed > 0f) playbackSpeed else 1f)
                .build()
        )
        if (playing) {
            if (!wasPlaying) {
                requestAudioFocus()
            }
            startForegroundWithNotification()
        } else {
            updateNotification()
            // Demotes out of the foreground state but leaves the
            // notification up (now showing a "paused" transport
            // control) so the user can dismiss it manually, matching
            // how music apps behave once actually paused -- and lets
            // Android reclaim the process more readily than it would
            // while a foreground service is still active.
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
        }
    }

    /**
     * Updates title/duration/artwork -- called from MainActivity once
     * MEDIA_SESSION_JS reports the page's title (and MainActivity has
     * finished decoding `artwork`, if any og:image URL was found; see
     * MainActivity.loadArtworkAndApplyMetadata()).
     */
    fun updateMetadata(title: String?, durationMs: Long, artwork: Bitmap?) {
        val builder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title ?: getString(R.string.app_name))
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "YouTube")
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, durationMs)
        if (artwork != null) {
            builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, artwork)
        }
        mediaSession.setMetadata(builder.build())
        updateNotification()
    }

    private fun idlePlaybackState(): PlaybackStateCompat = PlaybackStateCompat.Builder()
        .setActions(PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PLAY_PAUSE)
        .setState(PlaybackStateCompat.STATE_PAUSED, 0, 1f)
        .build()

    private fun requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = audioFocusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setOnAudioFocusChangeListener(audioFocusListener)
                .build()
                .also { audioFocusRequest = it }
            audioManager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                audioFocusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN
            )
        }
    }

    private fun buildNotification(): android.app.Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val playPauseAction = if (isPlaying) {
            NotificationCompat.Action(
                android.R.drawable.ic_media_pause, "Pause",
                MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_PAUSE)
            )
        } else {
            NotificationCompat.Action(
                android.R.drawable.ic_media_play, "Play",
                MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_PLAY)
            )
        }
        val rewindAction = NotificationCompat.Action(
            android.R.drawable.ic_media_rew, "Rewind 10s",
            MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_REWIND)
        )
        val forwardAction = NotificationCompat.Action(
            android.R.drawable.ic_media_ff, "Forward 10s",
            MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_FAST_FORWARD)
        )

        val metadata = mediaSession.controller?.metadata
        val title = metadata?.getString(MediaMetadataCompat.METADATA_KEY_TITLE) ?: getString(R.string.app_name)
        val artwork = metadata?.getBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART)

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(title)
            .setContentText("YouTube")
            .setLargeIcon(artwork)
            .setContentIntent(contentIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(isPlaying)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(rewindAction)
            .addAction(playPauseAction)
            .addAction(forwardAction)
            .setStyle(
                MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .setDeleteIntent(
                MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_STOP)
            )
            .build()
    }

    private fun updateNotification() {
        if (!::mediaSession.isInitialized) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            // No permission to actually show it -- the session itself
            // (and therefore lock-screen/Bluetooth/wired transport
            // control) still works without a visible notification.
            return
        }
        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification())
    }

    private fun startForegroundWithNotification() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID, "Playback controls", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Playback controls for the video currently open in ARKtube"
                setShowBadge(false)
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "arktube_playback"
        private const val NOTIFICATION_ID = 1001
    }
}
