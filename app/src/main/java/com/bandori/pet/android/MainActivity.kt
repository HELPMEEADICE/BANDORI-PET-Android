package com.bandori.pet.android

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.Menu
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.navigation.NavigationBarView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.io.File

class MainActivity : Activity() {
    private lateinit var live2DView: Live2DGLView
    private lateinit var placeholder: LinearLayout
    private lateinit var input: EditText
    private var runtimeRoot: File? = null
    private var outfits: List<OutfitModel> = emptyList()
    private var selectedOutfit: OutfitModel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(92, 53, 34)
        window.navigationBarColor = Color.rgb(76, 43, 29)
        setContentView(createContent())
        installRuntimeAndLoadDefaultModel()
    }

    override fun onPause() {
        live2DView.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        live2DView.onResume()
    }

    override fun onDestroy() {
        NativeLive2D.dispose()
        super.onDestroy()
    }

    private fun createContent(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(10))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.rgb(160, 111, 77), Color.rgb(85, 48, 33))
            )
        }

        val liveCard = MaterialCardView(this).apply {
            radius = dp(36).toFloat()
            strokeWidth = dp(2)
            strokeColor = Color.rgb(248, 221, 196)
            setCardBackgroundColor(Color.rgb(255, 248, 239))
            cardElevation = dp(8).toFloat()
            preventCornerOverlap = true
            useCompatPadding = true
            setContentPadding(dp(4), dp(4), dp(4), dp(4))
        }
        root.addView(liveCard, LinearLayout.LayoutParams(-1, 0, 1f).apply { bottomMargin = dp(14) })

        val renderHost = FrameLayout(this)
        liveCard.addView(renderHost, FrameLayout.LayoutParams(-1, -1))

        live2DView = Live2DGLView(this)
        renderHost.addView(live2DView, FrameLayout.LayoutParams(-1, -1))

        placeholder = createLivePlaceholder()
        renderHost.addView(placeholder, FrameLayout.LayoutParams(-1, -1))

        root.addView(createChatBox(), LinearLayout.LayoutParams(-1, dp(116)).apply { bottomMargin = dp(14) })
        root.addView(createBottomNav(), LinearLayout.LayoutParams(-1, dp(104)))
        return root
    }

    private fun createLivePlaceholder(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
            addView(TextView(context).apply {
                text = "◎"
                textSize = 72f
                setTextColor(Color.rgb(192, 151, 111))
                gravity = Gravity.CENTER
            })
            addView(TextView(context).apply {
                text = "Live2D显示区"
                textSize = 30f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.rgb(68, 43, 28))
                gravity = Gravity.CENTER
            })
            addView(TextView(context).apply {
                text = "角色将在此处展示"
                textSize = 18f
                setTextColor(Color.rgb(121, 96, 76))
                gravity = Gravity.CENTER
                setPadding(0, dp(10), 0, 0)
            })
        }
    }

    private fun createChatBox(): View {
        val chat = MaterialCardView(this).apply {
            radius = dp(30).toFloat()
            strokeWidth = dp(1)
            strokeColor = Color.rgb(246, 224, 204)
            setCardBackgroundColor(Color.rgb(255, 248, 241))
            cardElevation = dp(7).toFloat()
            useCompatPadding = true
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        chat.addView(row, FrameLayout.LayoutParams(-1, -1))

        row.addView(FloatingActionButton(this).apply {
            setSize(FloatingActionButton.SIZE_MINI)
            setImageResource(R.drawable.ic_chat_24)
            backgroundTintList = ColorStateList.valueOf(Color.rgb(255, 236, 219))
            imageTintList = ColorStateList.valueOf(Color.rgb(100, 66, 43))
            setCompatElevation(dp(1).toFloat())
            isClickable = false
        }, LinearLayout.LayoutParams(dp(50), dp(50)).apply { rightMargin = dp(12) })

        val textInput = TextInputLayout(this).apply {
            hint = "LLM对接打字框"
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_FILLED
            boxBackgroundColor = Color.rgb(255, 248, 241)
            setBoxCornerRadii(dp(18).toFloat(), dp(18).toFloat(), dp(18).toFloat(), dp(18).toFloat())
            setBoxStrokeColorStateList(ColorStateList.valueOf(Color.rgb(142, 95, 57)))
            setHintTextColor(ColorStateList.valueOf(Color.rgb(105, 76, 57)))
        }
        input = TextInputEditText(textInput.context).apply {
            hint = "输入消息与角色对话..."
            textSize = 16f
            setSingleLine(true)
            setTextColor(Color.rgb(48, 32, 22))
            setHintTextColor(Color.rgb(159, 130, 108))
            includeFontPadding = false
        }
        textInput.addView(input, LinearLayout.LayoutParams(-1, -1))
        row.addView(textInput, LinearLayout.LayoutParams(0, -1, 1f))

        row.addView(FloatingActionButton(this).apply {
            setImageResource(R.drawable.ic_send_24)
            backgroundTintList = ColorStateList.valueOf(Color.rgb(142, 95, 57))
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            setCompatElevation(dp(4).toFloat())
            setOnClickListener { sendMessage() }
        }, LinearLayout.LayoutParams(dp(62), dp(62)).apply { leftMargin = dp(12) })

        return chat
    }

    private fun createBottomNav(): View {
        val navCard = MaterialCardView(this).apply {
            radius = dp(30).toFloat()
            setCardBackgroundColor(Color.rgb(82, 48, 32))
            cardElevation = dp(4).toFloat()
            useCompatPadding = false
        }

        val nav = BottomNavigationView(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            itemIconTintList = navColors()
            itemTextColor = navColors()
            setItemActiveIndicatorColor(ColorStateList.valueOf(Color.rgb(151, 104, 65)))
            labelVisibilityMode = NavigationBarView.LABEL_VISIBILITY_LABELED
            menu.add(Menu.NONE, NAV_HOME, Menu.NONE, "主页").setIcon(R.drawable.ic_home_24)
            menu.add(Menu.NONE, NAV_MODEL, Menu.NONE, "模型").setIcon(R.drawable.ic_cube_24)
            menu.add(Menu.NONE, NAV_SETTINGS, Menu.NONE, "设置").setIcon(R.drawable.ic_settings_24)
            selectedItemId = NAV_HOME
            setOnItemSelectedListener { item ->
                when (item.itemId) {
                    NAV_HOME -> showHome()
                    NAV_MODEL -> showModelChooser()
                    NAV_SETTINGS -> showSettings()
                }
                true
            }
        }

        navCard.addView(nav, FrameLayout.LayoutParams(-1, -1))
        return navCard
    }

    private fun navColors(): ColorStateList {
        return ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(Color.WHITE, Color.rgb(226, 209, 195))
        )
    }

    private fun installRuntimeAndLoadDefaultModel() {
        Thread {
            val installed = runCatching { AssetInstaller.install(this) }
            runOnUiThread {
                installed.onSuccess { root ->
                    runtimeRoot = root
                    outfits = OutfitRepository.load(root)
                    val defaultModel = chooseDefaultModel(outfits)
                    selectedOutfit = defaultModel
                    live2DView.configure(root.absolutePath, defaultModel?.modelConfig?.absolutePath)
                    placeholder.visibility = if (defaultModel == null) View.VISIBLE else View.GONE
                    if (defaultModel == null) {
                        showMessage("未找到可加载模型", "请确认 assets/models 中存在 model.json 或 .model3.json。")
                    }
                }.onFailure { error ->
                    showMessage("资源初始化失败", error.message ?: error.toString())
                }
            }
        }.start()
    }

    private fun chooseDefaultModel(models: List<OutfitModel>): OutfitModel? {
        return models.firstOrNull { it.characterId == "tomorin" && it.costumeId == "casual_spring_01" }
            ?: models.firstOrNull { it.modelConfig.name.endsWith(".model3.json", ignoreCase = true) }
            ?: models.firstOrNull()
    }

    private fun sendMessage() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        input.setText("")
        hideKeyboard()
        val motion = selectMotion(text)
        live2DView.setLipSync(0.8f)
        live2DView.triggerMotion(motion)
        Thread {
            Thread.sleep(450)
            runOnUiThread {
                live2DView.setLipSync(0f)
                showMessage("LLM Mock 回复", "收到：“$text”。后续可在 sendMessage() 中替换为真实异步 LLM 请求。")
            }
        }.start()
    }

    private fun selectMotion(text: String): String = when {
        text.contains("哭") || text.contains("难过") -> "cry01"
        text.contains("生气") || text.contains("怒") -> "angry01"
        text.contains("再见") || text.contains("拜") -> "bye01"
        text.contains("?") || text.contains("？") -> "question01"
        else -> "smile01"
    }

    private fun showHome() {
        val outfit = selectedOutfit?.title ?: "尚未加载模型"
        showMessage("主页", "当前模型：$outfit")
    }

    private fun showModelChooser() {
        if (outfits.isEmpty()) {
            showMessage("模型", "未从 outfit.json 与 models/ 中解析到可用模型。")
            return
        }
        val labels = outfits.map { it.title }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("选择模型")
            .setItems(labels) { _, which ->
                val model = outfits[which]
                selectedOutfit = model
                placeholder.visibility = View.GONE
                live2DView.loadModel(model.modelConfig.absolutePath)
            }
            .show()
    }

    private fun showSettings() {
        val nativeStatus = if (NativeLive2D.available) "JNI 已加载" else "JNI 未加载：${NativeLive2D.loadError()}"
        showMessage("设置", "$nativeStatus\nRuntime: ${runtimeRoot?.absolutePath ?: "未初始化"}")
    }

    private fun showMessage(title: String, message: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("确定", null)
            .show()
    }

    private fun hideKeyboard() {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(input.windowToken, 0)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private companion object {
        const val NAV_HOME = 1
        const val NAV_MODEL = 2
        const val NAV_SETTINGS = 3
    }
}
