package com.music.bitchord.playback

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.setPadding
import com.music.bitchord.MainActivity
import com.music.bitchord.R
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.artworkAt
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A small, draggable player shown only for audio opened from another app. */
internal class ExternalAudioPopupWindow(
    context: Context,
    private val scope: CoroutineScope,
    private val onPlayPause: () -> Unit,
    private val onClose: () -> Unit,
) {
    private val appContext = context.applicationContext
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val density = context.resources.displayMetrics.density
    private val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12))
        background = GradientDrawable().apply {
            setColor(Color.rgb(27, 29, 35))
            cornerRadius = dp(20).toFloat()
            setStroke(dp(1), Color.argb(45, 255, 255, 255))
        }
        elevation = dp(12).toFloat()
        isClickable = true
        isFocusable = false
    }
    private val artwork = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        clipToOutline = true
        background = GradientDrawable().apply {
            setColor(Color.rgb(55, 58, 66))
            cornerRadius = dp(12).toFloat()
        }
        contentDescription = null
    }
    private val title = TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = 15f
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        setTypeface(typeface, android.graphics.Typeface.BOLD)
    }
    private val artist = TextView(context).apply {
        setTextColor(Color.rgb(190, 194, 204))
        textSize = 13f
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
    }
    private val playPause = ImageButton(context).apply {
        setBackground(GradientDrawable().apply {
            setColor(Color.rgb(55, 59, 68))
            shape = GradientDrawable.OVAL
        })
        setPadding(dp(10))
        scaleType = ImageView.ScaleType.FIT_CENTER
        setColorFilter(Color.WHITE)
        contentDescription = context.getString(R.string.play)
        setOnClickListener { onPlayPause() }
    }
    private val close = TextView(context).apply {
        text = "×"
        textSize = 28f
        gravity = Gravity.CENTER
        setTextColor(Color.rgb(220, 222, 228))
        contentDescription = context.getString(R.string.close)
        isClickable = true
        isFocusable = true
        setOnClickListener { onClose() }
    }
    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = dp(16)
        y = dp(100)
    }

    private var attached = false
    private var artJob: Job? = null
    private var artworkRequestKey: String? = null
    private var currentSong: Song? = null

    init {
        val dragHandle = View(context).apply {
            background = GradientDrawable().apply {
                setColor(Color.rgb(119, 123, 134))
                cornerRadius = dp(3).toFloat()
            }
            contentDescription = context.getString(R.string.popup_player_drag_handle)
            setOnTouchListener(::dragWindow)
        }
        root.addView(
            dragHandle,
            LinearLayout.LayoutParams(dp(34), dp(5)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(10)
            },
        )

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(artwork, LinearLayout.LayoutParams(dp(58), dp(58)))

        val labels = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, dp(6), 0)
            setOnClickListener { openPlayer() }
        }
        labels.addView(title, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        labels.addView(artist, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        row.addView(labels, LinearLayout.LayoutParams(0, dp(58), 1f))
        row.addView(playPause, LinearLayout.LayoutParams(dp(42), dp(42)))
        row.addView(close, LinearLayout.LayoutParams(dp(34), dp(46)))
        root.addView(row)
        artwork.setOnClickListener { openPlayer() }
    }

    fun show(song: Song, isPlaying: Boolean) {
        if (windowManager == null) return
        currentSong = song
        title.text = song.title.ifBlank { song.videoId }
        artist.text = song.artist
        artwork.contentDescription = song.title
        updatePlaybackState(isPlaying)
        if (!attached) {
            runCatching { windowManager.addView(root, params) }
                .onSuccess { attached = true }
                .onFailure { return }
        } else {
            runCatching { windowManager.updateViewLayout(root, params) }
        }
        loadArtwork(song)
    }

    fun updatePlaybackState(isPlaying: Boolean) {
        playPause.setImageResource(if (isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play)
        playPause.contentDescription = appContext.getString(if (isPlaying) R.string.pause else R.string.play)
    }

    fun dismiss() {
        artJob?.cancel()
        artJob = null
        artworkRequestKey = null
        currentSong = null
        if (attached) runCatching { windowManager?.removeView(root) }
        attached = false
    }

    private fun loadArtwork(song: Song) {
        val source = song.artworkAt((dp(256)))
        if (source == artworkRequestKey) return
        artworkRequestKey = source
        artJob?.cancel()
        if (source == null) {
            artwork.setImageDrawable(null)
            return
        }
        artJob = scope.launch(Dispatchers.IO) {
            val bitmap: Bitmap? = runCatching {
                val request = ImageRequest.Builder(appContext)
                    .data(source)
                    .size(dp(256), dp(256))
                    .allowHardware(false)
                    .build()
                val result = SingletonImageLoader.get(appContext).execute(request)
                (result as? SuccessResult)?.image?.toBitmap()
            }.getOrNull()
            withContext(Dispatchers.Main) {
                if (attached && currentSong?.localUri == song.localUri && artworkRequestKey == source) {
                    artwork.setImageBitmap(bitmap)
                }
            }
        }
    }

    private fun dragWindow(view: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                view.tag = floatArrayOf(event.rawX, event.rawY, params.x.toFloat(), params.y.toFloat())
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val origin = view.tag as? FloatArray ?: return true
                val screen = appContext.resources.displayMetrics
                val maxX = (screen.widthPixels - root.width).coerceAtLeast(0)
                val maxY = (screen.heightPixels - root.height).coerceAtLeast(0)
                params.x = (origin[2] + event.rawX - origin[0]).toInt().coerceIn(0, maxX)
                params.y = (origin[3] + event.rawY - origin[1]).toInt().coerceIn(0, maxY)
                if (attached) runCatching { windowManager?.updateViewLayout(root, params) }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                view.tag = null
                return true
            }
        }
        return false
    }

    private fun openPlayer() {
        runCatching {
            appContext.startActivity(
                android.content.Intent(appContext, MainActivity::class.java)
                    .putExtra(PlayerDeepLink.EXTRA_OPEN_PLAYER, true)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
        }
    }

    private fun dp(value: Int): Int = (value * density).toInt().coerceAtLeast(1)
}
