package com.tananaev.passportreader.utils.logging

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.cardview.widget.CardView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner

/**
 * Attaches a premium light-themed floating developer log overlay button (with the Android icon) to any Activity.
 * Opens a highly polished, developer-friendly log dashboard with colored severity blocks.
 */
object LogOverlayHelper {
    private const val TAG = "LogOverlayHelper"

    @SuppressLint("ClickableViewAccessibility")
    fun attach(activity: Activity) {
        val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return

        // Prevent attaching multiple overlays to the same activity
        if (root.findViewWithTag<View>("android_log_overlay_btn") != null) {
            return
        }

        val context = activity
        val density = context.resources.displayMetrics.density
        val size = (48 * density).toInt()

        // Create elegant White CardView circle with Emerald Green border
        val card = CardView(context).apply {
            tag = "android_log_overlay_btn"
            radius = 24 * density
            cardElevation = 12 * density
            setCardBackgroundColor(Color.WHITE)

            // Add thin emerald stroke
            val borderDrawable = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
                setStroke((1.5 * density).toInt(), Color.parseColor("#10B981"))
            }
            background = borderDrawable

            // Position bottom-left by default so it doesn't overlap the scanner FAB
            val params = FrameLayout.LayoutParams(size, size).apply {
                gravity = Gravity.BOTTOM or Gravity.START
                leftMargin = (16 * density).toInt()
                bottomMargin = (80 * density).toInt() // raise slightly above system navigation bar
            }
            layoutParams = params
        }

        // Inner Android Mascot Icon ImageView
        val imageView = ImageView(context).apply {
            setImageResource(android.R.drawable.sym_def_app_icon)
            scaleType = ImageView.ScaleType.FIT_CENTER
            val padding = (6 * density).toInt()
            setPadding(padding, padding, padding, padding)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        card.addView(imageView)

        // Error notification badge (deep orange red)
        val badge = View(context).apply {
            val badgeSize = (10 * density).toInt()
            layoutParams = FrameLayout.LayoutParams(badgeSize, badgeSize).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = (2 * density).toInt()
                rightMargin = (2 * density).toInt()
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#EF4444")) // Deep Orange/Rose Warning color
            }
            visibility = View.GONE
        }
        card.addView(badge)

        val updateBadge = {
            activity.runOnUiThread {
                val hasErrors = AppLog.getLogs().any { it.contains(" E/") || it.contains(" W/") }
                badge.visibility = if (hasErrors) View.VISIBLE else View.GONE
            }
        }

        val logListener = {
            updateBadge()
        }

        // Attach listener and check immediately
        AppLog.addListener(logListener)
        updateBadge()

        // Bind listener to Lifecycle to avoid memory leaks
        if (activity is LifecycleOwner) {
            activity.lifecycle.addObserver(object : LifecycleEventObserver {
                override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
                    if (event == Lifecycle.Event.ON_DESTROY) {
                        AppLog.removeListener(logListener)
                    }
                }
            })
        }

        // Draggable touch listener with smooth micro-animation
        var dX = 0f
        var dY = 0f
        var startX = 0f
        var startY = 0f
        val clickDragTolerance = 5 * density

        card.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dX = v.x - event.rawX
                    dY = v.y - event.rawY
                    startX = event.rawX
                    startY = event.rawY
                    v.animate().scaleX(0.85f).scaleY(0.85f).setDuration(80).start()
                }
                MotionEvent.ACTION_MOVE -> {
                    val newX = event.rawX + dX
                    val newY = event.rawY + dY

                    val parent = v.parent as View
                    val maxX = parent.width - v.width
                    val maxY = parent.height - v.height

                    v.x = newX.coerceIn(0f, maxX.toFloat())
                    v.y = newY.coerceIn(0f, maxY.toFloat())
                }
                MotionEvent.ACTION_UP -> {
                    v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(80).start()

                    val diffX = Math.abs(event.rawX - startX)
                    val diffY = Math.abs(event.rawY - startY)
                    if (diffX < clickDragTolerance && diffY < clickDragTolerance) {
                        showLogDialog(activity)
                    }
                }
            }
            true
        }

        root.addView(card)
    }

    private fun showLogDialog(activity: Activity) {
        val dialog = Dialog(activity, android.R.style.Theme_Light_NoTitleBar_Fullscreen)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val context = activity
        val density = context.resources.displayMetrics.density

        // Base container: Soft Slate-50 Background
        val rootLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F8FAFC")) // Clean modern Slate-50 background
            setPadding((16 * density).toInt(), (16 * density).toInt(), (16 * density).toInt(), (16 * density).toInt())
        }

        // Row 1: Title & Icon (Full Width)
        val titleLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val consoleIcon = TextView(context).apply {
            text = "⚙️"
            textSize = 18f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                rightMargin = (8 * density).toInt()
            }
        }
        titleLayout.addView(consoleIcon)

        val titleView = TextView(context).apply {
            text = "Android Console Logs"
            setTextColor(Color.parseColor("#0F172A")) // Slate-900 (deep dark blue-black)
            textSize = 20f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        titleLayout.addView(titleView)

        // Row 2: Pill Action Buttons
        val buttonsLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }

        val btnStyle = { btn: Button, textStr: String, bgHex: String, txtColor: Int ->
            btn.apply {
                text = textStr
                textSize = 12f
                isAllCaps = false
                setTextColor(txtColor)
                background = GradientDrawable().apply {
                    cornerRadius = 20 * density // Clean pill shape
                    setColor(Color.parseColor(bgHex))
                }
                val paddingH = (16 * density).toInt()
                val paddingV = (8 * density).toInt()
                setPadding(paddingH, paddingV, paddingH, paddingV)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    rightMargin = (8 * density).toInt()
                }
            }
        }

        val copyBtn = Button(context)
        btnStyle(copyBtn, "Copy All", "#10B981", Color.WHITE) // Emerald Green 500
        copyBtn.setOnClickListener {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val allLogsText = AppLog.getLogs().joinToString("\n")
            val clip = ClipData.newPlainText("Console Logs", allLogsText)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(context, "Copied to clipboard!", Toast.LENGTH_SHORT).show()
        }

        val clearBtn = Button(context)
        btnStyle(clearBtn, "Clear", "#EF4444", Color.WHITE) // Rose Red 500
        clearBtn.setOnClickListener {
            AppLog.clear()
        }

        val closeBtn = Button(context)
        btnStyle(closeBtn, "Close", "#64748B", Color.WHITE) // Slate Grey 500
        closeBtn.setOnClickListener {
            dialog.dismiss()
        }

        buttonsLayout.addView(copyBtn, copyBtn.layoutParams)
        buttonsLayout.addView(clearBtn, clearBtn.layoutParams)
        buttonsLayout.addView(closeBtn, closeBtn.layoutParams)

        // Add Row 1 and Row 2 to main container
        rootLayout.addView(titleLayout, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = (12 * density).toInt()
        })

        rootLayout.addView(buttonsLayout, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = (16 * density).toInt()
        })

        // Search text view (White background with Slate-200 border, rounded 12dp)
        val searchEdit = EditText(context).apply {
            hint = "🔍 Filter logs by text..."
            setHintTextColor(Color.parseColor("#94A3B8"))
            setTextColor(Color.parseColor("#1E293B"))
            textSize = 14f
            maxLines = 1
            background = GradientDrawable().apply {
                cornerRadius = 12 * density
                setColor(Color.WHITE)
                setStroke((1 * density).toInt(), Color.parseColor("#CBD5E1")) // Slate 300
            }
            setPadding((16 * density).toInt(), (10 * density).toInt(), (16 * density).toInt(), (10 * density).toInt())
        }
        
        rootLayout.addView(searchEdit, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = (14 * density).toInt()
        })

        // Filter chips layout
        val filterLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        var selectedFilter = "ALL"
        val filterButtons = mutableMapOf<String, TextView>()
        var triggerRender: (() -> Unit)? = null

        val createTab = { label: String ->
            val tv = TextView(context).apply {
                text = label
                textSize = 11f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                setTextColor(Color.parseColor("#64748B")) // Slate 500
                background = GradientDrawable().apply {
                    cornerRadius = 16 * density
                    setColor(Color.parseColor("#F1F5F9")) // Soft background for inactive chips
                    setStroke((1 * density).toInt(), Color.parseColor("#E2E8F0"))
                }
                setPadding((16 * density).toInt(), (6 * density).toInt(), (16 * density).toInt(), (6 * density).toInt())
                setOnClickListener {
                    selectedFilter = label
                    for ((k, btn) in filterButtons) {
                        val active = k == selectedFilter
                        btn.setTextColor(if (active) Color.WHITE else Color.parseColor("#64748B"))
                        btn.background = GradientDrawable().apply {
                            cornerRadius = 16 * density
                            setColor(if (active) Color.parseColor("#10B981") else Color.parseColor("#F1F5F9"))
                            if (!active) {
                                setStroke((1 * density).toInt(), Color.parseColor("#E2E8F0"))
                            }
                        }
                    }
                    triggerRender?.invoke()
                }
            }
            filterButtons[label] = tv
            filterLayout.addView(tv, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                rightMargin = (8 * density).toInt()
            })
        }

        createTab("ALL")
        createTab("INFO")
        createTab("WARN")
        createTab("ERROR")

        // Set ALL active initially
        filterButtons["ALL"]?.apply {
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                cornerRadius = 16 * density
                setColor(Color.parseColor("#10B981")) // Active Emerald Green
            }
        }

        rootLayout.addView(filterLayout, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = (16 * density).toInt()
        })

        // Log container CardView (White container with soft shadow and rounded 12dp)
        val logCard = CardView(context).apply {
            radius = 12 * density
            cardElevation = 4 * density
            setCardBackgroundColor(Color.WHITE)
        }

        val scrollView = ScrollView(context).apply {
            isVerticalScrollBarEnabled = true
        }

        val logContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((12 * density).toInt(), (12 * density).toInt(), (12 * density).toInt(), (12 * density).toInt())
        }
        scrollView.addView(logContainer)
        logCard.addView(scrollView)
        
        rootLayout.addView(logCard, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))

        val renderLogs = {
            val query = searchEdit.text.toString().lowercase(java.util.Locale.US)
            val rawLogs = AppLog.getLogs()

            logContainer.removeAllViews()

            val filtered = rawLogs.filter { line ->
                val levelMatch = when (selectedFilter) {
                    "INFO" -> line.contains(" I/")
                    "WARN" -> line.contains(" W/")
                    "ERROR" -> line.contains(" E/")
                    else -> true
                }
                val searchMatch = query.isEmpty() || line.lowercase(java.util.Locale.US).contains(query)
                levelMatch && searchMatch
            }

            for (line in filtered) {
                val tv = TextView(context).apply {
                    text = line
                    textSize = 10.5f
                    typeface = Typeface.MONOSPACE
                    val padH = (10 * density).toInt()
                    val padV = (4 * density).toInt()
                    setPadding(padH, padV, padH, padV)
                    
                    // Style lines by log level with premium backgrounds and text colors
                    when {
                        line.contains(" E/") -> {
                            setTextColor(Color.parseColor("#DC2626")) // Rose 600
                            background = GradientDrawable().apply {
                                cornerRadius = 4 * density
                                setColor(Color.parseColor("#FEF2F2")) // Rose 50 background
                            }
                        }
                        line.contains(" W/") -> {
                            setTextColor(Color.parseColor("#D97706")) // Amber 600
                            background = GradientDrawable().apply {
                                cornerRadius = 4 * density
                                setColor(Color.parseColor("#FFFBEB")) // Amber 50 background
                            }
                        }
                        line.contains(" D/") -> {
                            setTextColor(Color.parseColor("#059669")) // Emerald 600
                            background = GradientDrawable().apply {
                                cornerRadius = 4 * density
                                setColor(Color.parseColor("#F0FDF4")) // Emerald 50 background
                            }
                        }
                        else -> {
                            setTextColor(Color.parseColor("#334155")) // Slate 700
                            background = null
                        }
                    }

                    // Add a tiny bottom margin to separate log rows
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        bottomMargin = (2 * density).toInt()
                    }
                }
                logContainer.addView(tv, tv.layoutParams)
            }

            scrollView.post {
                scrollView.fullScroll(View.FOCUS_DOWN)
            }
            Unit
        }

        triggerRender = renderLogs
        renderLogs()

        // Sync with log additions in real time
        val logUpdateListener = {
            activity.runOnUiThread {
                renderLogs()
            }
        }
        AppLog.addListener(logUpdateListener)

        dialog.setOnDismissListener {
            AppLog.removeListener(logUpdateListener)
        }

        dialog.setContentView(rootLayout)
        dialog.show()
    }
}
