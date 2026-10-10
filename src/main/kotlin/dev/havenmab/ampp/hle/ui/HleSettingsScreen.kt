package dev.havenmab.ampp.hle.ui

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import dev.amenhancer.plugin.api.PluginSettingsSession
import dev.havenmab.ampp.hle.feature.region.AppleInternalCatalogResolver
import dev.havenmab.ampp.hle.feature.region.RegionSelection

/**
 * 设置页。SDK v1 不允许 XML 布局/资源，全部用标准 Android View 现搭。
 *
 * 页面内容（本轮）：
 * - 地区替换：Spinner（[RegionSelection] 的 HLE 文案）
 * - 歌曲信息替换至设定地区语言：Switch
 * - 替换中日韩歌曲信息为原地区原名：Switch
 * - 创建检索库以提升替换体验（持久缓存）：Switch
 * - 详细诊断（`[debug]` 通道）：Switch，立即生效
 * - 清空检索库：按钮，调用解析器的维护缝
 *
 * 地区/开关的改动写入插件 data 目录并提示重启 Apple Music 生效（与 AM++ 的启停语义一致：
 * Hook 在 onLoad 阶段登记）。
 */
internal class HleSettingsScreen(
    private val context: Context,
    private val settings: HleSettings,
    private val resolver: () -> AppleInternalCatalogResolver?,
    private val hostVersionLabel: String,
    private val onDiagnosticsChanged: (Boolean) -> Unit,
) {
    fun createSession(): PluginSettingsSession {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }

        root.addView(title("HLE 移植（地区/歌名/歌词）"))
        root.addView(body("支持宿主：Apple Music $hostVersionLabel（目标表 applemusic-1606.json）"))
        root.addView(body("本轮已实现：地区替换、歌曲名修正。歌词翻译/音译补全为下一批，本页只保留说明。"))

        root.addView(section("地区替换"))
        val regionSpinner = Spinner(context).apply {
            adapter = ArrayAdapter(
                context,
                android.R.layout.simple_spinner_dropdown_item,
                RegionSelection.entries.map { it.displayName },
            )
            setSelection(RegionSelection.entries.indexOf(settings.regionSelection).coerceAtLeast(0))
        }
        root.addView(regionSpinner)
        root.addView(
            body(
                "「不开启」保持账号自身的地区与语言；其余选项会改写 Apple Music 的目录请求 " +
                    "storefront/language（content-http、amp-api、MediaApi 本地化参数、目录直连执行器）。" +
                    "广播/歌词等账号权益相关的请求始终回退到账号 storefront。"
            )
        )

        root.addView(section("歌曲信息"))
        val overrideSwitch = optionSwitch(
            "歌曲信息替换至设定地区语言",
            settings.overrideAccountLanguage,
        )
        val restoreSwitch = optionSwitch(
            "替换中日韩歌曲信息为原地区原名",
            settings.restoreCjkOriginalMetadata,
        )
        val cacheSwitch = optionSwitch(
            "创建检索库以提升替换体验",
            settings.localizedMetadataCache,
        )
        root.addView(overrideSwitch)
        root.addView(restoreSwitch)
        root.addView(cacheSwitch)
        root.addView(
            body(
                "原名修正目前落在 MediaApi 元数据缝（实体/属性的名称 getter 与 MediaApi→model.Song " +
                    "转换）；Listen Now、资料库、data-binding、艺人、合集、队列、操作表等 in-app 表面" +
                    "尚未接入（第 2 批，清单见 feature/title/README.md）。"
            )
        )

        root.addView(section("歌词（第 3 批预留）"))
        root.addView(
            body(
                "Apple 官方音译首播不上屏：需要把 App 切到后台再切回来才显示。这是已知问题，" +
                    "本轮不修复。插件保留 native-write / presentation-refresh / pronunciation-words / " +
                    "render-probe 诊断词汇，供下一批接入歌词翻译/音译补全时使用。"
            )
        )

        root.addView(section("诊断与维护"))
        val diagnosticSwitch = optionSwitch("详细诊断（[debug] 日志通道）", settings.verboseDiagnostics)
        diagnosticSwitch.setOnCheckedChangeListener { _, checked ->
            settings.verboseDiagnostics = checked
            settings.save()
            onDiagnosticsChanged(checked)
        }
        root.addView(diagnosticSwitch)
        val clear = Button(context).apply {
            text = "清空检索库（原地区别名缓存）"
            setOnClickListener {
                val dropped = resolver()?.dropMetadataCachesForMaintenance() ?: 0
                (this as Button).text = "清空检索库（已清理 $dropped 条）"
            }
        }
        root.addView(clear)

        regionSpinner.onItemSelectedListener = object :
            android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long,
            ) {
                val selected = RegionSelection.entries.getOrNull(position) ?: return
                if (selected == settings.regionSelection) return
                settings.regionSelection = selected
                settings.save()
                noteRestart()
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
        overrideSwitch.setOnCheckedChangeListener { _, checked ->
            settings.overrideAccountLanguage = checked
            settings.save()
            noteRestart()
        }
        restoreSwitch.setOnCheckedChangeListener { _, checked ->
            settings.restoreCjkOriginalMetadata = checked
            settings.save()
            noteRestart()
        }
        cacheSwitch.setOnCheckedChangeListener { _, checked ->
            settings.localizedMetadataCache = checked
            settings.save()
            noteRestart()
        }

        val scroll = ScrollView(context).apply { addView(root) }
        return object : PluginSettingsSession {
            override fun getView(): View = scroll

            override fun close() {
                regionSpinner.onItemSelectedListener = null
                overrideSwitch.setOnCheckedChangeListener(null)
                restoreSwitch.setOnCheckedChangeListener(null)
                cacheSwitch.setOnCheckedChangeListener(null)
                diagnosticSwitch.setOnCheckedChangeListener(null)
                clear.setOnClickListener(null)
            }
        }
    }

    private fun noteRestart() {
        android.widget.Toast.makeText(
            context,
            "已保存；地区/歌曲信息开关需要完全退出并重开 Apple Music 后生效",
            android.widget.Toast.LENGTH_SHORT,
        ).show()
    }

    private fun title(text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 18f
        setPadding(0, 0, 0, dp(8))
    }

    private fun section(text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 15f
        gravity = Gravity.START
        setPadding(0, dp(16), 0, dp(6))
    }

    private fun body(text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 12f
        alpha = 0.8f
        setPadding(0, dp(2), 0, dp(6))
    }

    private fun optionSwitch(text: String, checked: Boolean): Switch = Switch(context).apply {
        this.text = text
        isChecked = checked
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
