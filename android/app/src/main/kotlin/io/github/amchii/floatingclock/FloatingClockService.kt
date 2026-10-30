package io.github.amchii.floatingclock

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.LinearLayout
import android.text.TextUtils
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.graphics.drawable.GradientDrawable
import android.graphics.Typeface
import androidx.core.app.NotificationCompat
import java.util.*
import kotlin.math.ceil

class FloatingClockService : Service() {
    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private var containerView: View? = null
    private var textView: TextView? = null
    // offset in milliseconds to apply to system time (ntpTime - systemTime)
    private var offsetMillis: Long = 0L
    private var displayPrecision = DisplayPrecision.CENTISECOND
    private var updateIntervalMs = 10L
    private var fractionColor: Int = Color.parseColor("#C62828")
    private var bgColor: Int = 0xB3141518.toInt()
    private var bgDrawable: GradientDrawable? = null
    private val handler = Handler(Looper.getMainLooper())
    private val updateRunnable = object : Runnable {
        override fun run() {
            updateTime()
            handler.postDelayed(this, updateIntervalMs)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Update offset/colors from Intent extras if provided. Calling startOverlay again
        // with different extras will update the displayed time source.
        intent?.let {
            offsetMillis = it.getLongExtra("offset", 0L)
            applyPrecision(it.getStringExtra("precision"))
            fractionColor = it.getIntExtra("fractionColor", fractionColor)
            bgColor = it.getIntExtra("bgColor", bgColor)
        } ?: applyPrecision(null)
        // 悬浮窗已在运行时，背景色实时生效
        bgDrawable?.setColor(bgColor)
        startForegroundWithNotification()
        // Avoid scheduling multiple update runnables when startOverlay is
        // invoked repeatedly to update the offset/colors; remove previous
        // callbacks first.
        handler.removeCallbacks(updateRunnable)
        handler.post(updateRunnable)
        // Force an immediate update
        updateTime()
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(updateRunnable)
        removeOverlay()
        val intent = Intent("io.github.amchii.floatingclock.OVERLAY_STOPPED")
        intent.setPackage(packageName)
        sendBroadcast(intent)
        super.onDestroy()
    }

    private fun startForegroundWithNotification() {
        val channelId = "floating_clock_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "悬浮时间", NotificationManager.IMPORTANCE_LOW)
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }

        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE else PendingIntent.FLAG_UPDATE_CURRENT
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, pendingFlags)

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("悬浮时间")
            .setContentText("悬浮时间正在运行")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        startForeground(1, notification)
    }

    private fun createOverlay() {
        if (!Settings.canDrawOverlays(this)) return

        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        layoutParams.gravity = Gravity.START or Gravity.TOP
        layoutParams.x = 0
        layoutParams.y = 200

        // 横向布局：时间（等宽锁定宽度，居中）+ 关闭按钮
        val container = LinearLayout(this)
        container.orientation = LinearLayout.HORIZONTAL
        container.gravity = Gravity.CENTER_VERTICAL

        val density = resources.displayMetrics.density
        val bg = GradientDrawable()
        bg.cornerRadius = 14 * density
        // 背景色可在 App 侧设置，默认深色半透明
        bg.setColor(bgColor)
        bg.setStroke((density).toInt().coerceAtLeast(1), 0x26FFFFFF)
        container.background = bg
        bgDrawable = bg
        val padH = (12 * density).toInt()
        val padV = (8 * density).toInt()
        container.setPadding(padH, padV, padH, padV)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) container.elevation = 6f

        val tv = TextView(this)
        tv.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        tv.setTextColor(0xFFFFFFFF.toInt())
        tv.setShadowLayer(4f, 0f, 1f, 0x66000000)
        tv.textSize = 21f
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) tv.letterSpacing = 0.02f
        tv.isSingleLine = true
        tv.ellipsize = TextUtils.TruncateAt.END
        tv.gravity = Gravity.CENTER
        tv.setIncludeFontPadding(false)
        tv.text = currentTimeText()

        // 关键：按最宽时间串一次性测量并锁定宽度，
        // 之后时间变化只是改文字，悬浮窗大小不再跟着变
        val timeWidth = ceil(tv.paint.measureText("00:00:00.00")).toInt() + (4 * density).toInt()
        val tvLp = LinearLayout.LayoutParams(timeWidth, LinearLayout.LayoutParams.WRAP_CONTENT)
        tvLp.marginEnd = (6 * density).toInt()
        container.addView(tv, tvLp)

        val closeBtn = TextView(this)
        closeBtn.text = "✕"
        closeBtn.setTextColor(0xCCFFFFFF.toInt())
        closeBtn.textSize = 11f
        val closeSize = (22 * density).toInt()
        val closeBg = GradientDrawable()
        closeBg.shape = GradientDrawable.OVAL
        closeBg.setColor(0x33FFFFFF)
        closeBtn.background = closeBg
        // Center the '✕' inside the circular background and remove extra font padding
        closeBtn.setGravity(Gravity.CENTER)
        closeBtn.setIncludeFontPadding(false)
        val closeLp = LinearLayout.LayoutParams(closeSize, closeSize)
        closeLp.gravity = Gravity.CENTER_VERTICAL
        container.addView(closeBtn, closeLp)

        closeBtn.setOnClickListener {
            stopSelf()
        }

        // Dragging handler on the whole container
        container.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f

            override fun onTouch(v: View?, event: MotionEvent?): Boolean {
                when (event?.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = layoutParams.x
                        initialY = layoutParams.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - initialTouchX).toInt()
                        val dy = (event.rawY - initialTouchY).toInt()
                        layoutParams.x = initialX + dx
                        layoutParams.y = initialY + dy
                        try {
                            windowManager.updateViewLayout(container, layoutParams)
                        } catch (e: Exception) {
                        }
                        return true
                    }
                }
                return false
            }
        })

        textView = tv
        containerView = container
        overlayView = container
        try {
            windowManager.addView(container, layoutParams)
        } catch (e: Exception) {
        }
    }

    private fun removeOverlay() {
        try {
            overlayView?.let { windowManager.removeView(it) }
        } catch (e: Exception) {
        }
        overlayView = null
        textView = null
        containerView = null
        bgDrawable = null
    }

    private fun updateTime() {
        textView?.post {
            textView?.text = currentTimeText()
        }
    }

    private fun currentTimeText(): CharSequence {
        val nowMs = System.currentTimeMillis() + offsetMillis
        val cal = Calendar.getInstance()
        cal.timeInMillis = nowMs
        val h = cal.get(Calendar.HOUR_OF_DAY)
        val m = cal.get(Calendar.MINUTE)
        val s = cal.get(Calendar.SECOND)
        val base = String.format(Locale.getDefault(), "%02d:%02d:%02d", h, m, s)
        val fraction = if (displayPrecision == DisplayPrecision.CENTISECOND) {
            String.format(Locale.getDefault(), "%02d", cal.get(Calendar.MILLISECOND) / 10)
        } else {
            (cal.get(Calendar.MILLISECOND) / 100).toString()
        }
        val display = "$base.$fraction"
        val spannable = SpannableString(display)
        val dotIndex = display.lastIndexOf('.')
        if (dotIndex in 0 until display.length - 1) {
            spannable.setSpan(
                ForegroundColorSpan(fractionColor),
                dotIndex + 1,
                display.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        // Overlay should show only the time (no alias), keep it compact.
        return spannable
    }

    private fun applyPrecision(raw: String?) {
        val newPrecision = when (raw) {
            "decisecond" -> DisplayPrecision.DECISECOND
            else -> DisplayPrecision.CENTISECOND
        }
        displayPrecision = newPrecision
        updateIntervalMs = if (newPrecision == DisplayPrecision.CENTISECOND) 10L else 100L
    }
}

private enum class DisplayPrecision {
    CENTISECOND,
    DECISECOND
}
