package com.example.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.example.model.WatermarkConfig
import com.example.model.WatermarkPreset
import com.example.model.WatermarkStyle

/**
 * Immutable undo operations tracked by the shared Watermark Studio editing engine.
 * Every operation is scoped cleanly to its target template or template-switch transition.
 */
internal sealed interface StudioEditOperation {
    data class UpdateTemplateConfig(
        val style: WatermarkStyle,
        val previousConfig: WatermarkConfig
    ) : StudioEditOperation

    data class SwitchTemplate(
        val previousStyle: WatermarkStyle
    ) : StudioEditOperation

    data class ResetTemplate(
        val style: WatermarkStyle,
        val previousConfig: WatermarkConfig
    ) : StudioEditOperation
}

/**
 * Central, single-source-of-truth editing state holder for Watermark Studio.
 *
 * Maintains an independent [WatermarkConfig] instance per [WatermarkStyle] template so switching
 * templates never mixes text, logos, placements, or styling, and switching back restores prior
 * session edits cleanly.
 */
@Stable
internal class WatermarkStudioStateHolder(
    initialConfig: WatermarkConfig
) {
    private var templateConfigs by mutableStateOf(
        WatermarkTemplateDefaults.createInitialTemplateConfigs(initialConfig)
    )

    var selectedStyle: WatermarkStyle by mutableStateOf(initialConfig.style)
        private set

    private val undoHistory = mutableStateListOf<StudioEditOperation>()

    val activeConfig: WatermarkConfig
        get() = templateConfigs[selectedStyle]
            ?: WatermarkTemplateDefaults.defaultConfig(selectedStyle)

    val activeCapabilities: WatermarkTemplateCapabilities
        get() = capabilitiesForTemplate(selectedStyle)

    val canUndo: Boolean
        get() = undoHistory.isNotEmpty()

    val isSelectedTemplateModified: Boolean
        get() = isTemplateModified(selectedStyle)

    fun configFor(style: WatermarkStyle): WatermarkConfig {
        return templateConfigs[style]
            ?: WatermarkTemplateDefaults.defaultConfig(style, isEnabled = activeConfig.isEnabled)
    }

    fun isTemplateModified(style: WatermarkStyle): Boolean {
        val current = configFor(style)
        val defaults = WatermarkTemplateDefaults.defaultConfig(style, isEnabled = current.isEnabled)
        return current != defaults
    }

    /**
     * Switches the active template without resetting or overwriting any template's configuration.
     * Preserves master watermark enablement consistently across selection.
     */
    fun selectTemplate(style: WatermarkStyle) {
        if (style == selectedStyle) return
        val currentEnabled = activeConfig.isEnabled
        val targetConfig = configFor(style)
        if (targetConfig.isEnabled != currentEnabled) {
            templateConfigs = templateConfigs + (style to targetConfig.copy(isEnabled = currentEnabled))
        }
        undoHistory.add(StudioEditOperation.SwitchTemplate(previousStyle = selectedStyle))
        selectedStyle = style
    }

    /**
     * Applies an immutable edit scoped strictly to the currently selected template.
     */
    fun updateActiveConfig(transform: (WatermarkConfig) -> WatermarkConfig) {
        val style = selectedStyle
        val previous = configFor(style)
        val updated = transform(previous).copy(style = style)
        if (updated == previous) return
        undoHistory.add(
            StudioEditOperation.UpdateTemplateConfig(
                style = style,
                previousConfig = previous
            )
        )
        templateConfigs = templateConfigs + (style to updated)
    }

    fun setWatermarkEnabled(enabled: Boolean) {
        updateActiveConfig { it.copy(isEnabled = enabled) }
    }

    fun setPrimaryText(text: String) {
        updateActiveConfig { it.copy(customText = text) }
    }

    fun addPrimaryText() {
        val defaultText = WatermarkTemplateDefaults.defaultConfig(selectedStyle).customText
            .ifBlank { "STU•D•O" }
        updateActiveConfig { it.copy(customText = defaultText) }
    }

    fun removePrimaryText() {
        updateActiveConfig { it.copy(customText = "") }
    }

    fun setSecondaryText(text: String) {
        updateActiveConfig { it.copy(secondaryText = text) }
    }

    fun addSecondaryText() {
        val defaultSub = WatermarkTemplateDefaults.defaultConfig(selectedStyle).secondaryText
            .ifBlank { "Custom Subtitle" }
        updateActiveConfig { it.copy(secondaryText = defaultSub) }
    }

    fun removeSecondaryText() {
        updateActiveConfig { it.copy(secondaryText = "") }
    }

    /**
     * Applies a saved preset directly to its own template style while leaving other templates intact.
     */
    fun applyPreset(preset: WatermarkPreset) {
        val targetStyle = preset.config.style
        val previousForTarget = configFor(targetStyle)
        if (targetStyle != selectedStyle) {
            undoHistory.add(StudioEditOperation.SwitchTemplate(previousStyle = selectedStyle))
            selectedStyle = targetStyle
        }
        val nextConfig = preset.config.copy(isEnabled = activeConfig.isEnabled)
        if (nextConfig != previousForTarget) {
            undoHistory.add(
                StudioEditOperation.UpdateTemplateConfig(
                    style = targetStyle,
                    previousConfig = previousForTarget
                )
            )
            templateConfigs = templateConfigs + (targetStyle to nextConfig)
        }
    }

    /**
     * Reverses the most recent operation, scoped accurately to the affected template.
     */
    fun undo() {
        if (undoHistory.isEmpty()) return
        when (val op = undoHistory.removeAt(undoHistory.lastIndex)) {
            is StudioEditOperation.UpdateTemplateConfig -> {
                templateConfigs = templateConfigs + (op.style to op.previousConfig)
                selectedStyle = op.style
            }
            is StudioEditOperation.SwitchTemplate -> {
                val restoredEnabled = activeConfig.isEnabled
                val targetPrev = configFor(op.previousStyle)
                if (targetPrev.isEnabled != restoredEnabled) {
                    templateConfigs = templateConfigs + (
                        op.previousStyle to targetPrev.copy(isEnabled = restoredEnabled)
                    )
                }
                selectedStyle = op.previousStyle
            }
            is StudioEditOperation.ResetTemplate -> {
                templateConfigs = templateConfigs + (op.style to op.previousConfig)
                selectedStyle = op.style
            }
        }
    }

    /**
     * Restores ONLY the currently selected template to its original defaults.
     * Other templates and their session edits remain completely untouched.
     */
    fun resetSelectedTemplate() {
        val style = selectedStyle
        val previous = configFor(style)
        val defaults = WatermarkTemplateDefaults.defaultConfig(
            style = style,
            isEnabled = previous.isEnabled
        )
        if (previous == defaults) return
        undoHistory.add(
            StudioEditOperation.ResetTemplate(
                style = style,
                previousConfig = previous
            )
        )
        templateConfigs = templateConfigs + (style to defaults)
    }
}

@Composable
internal fun rememberWatermarkStudioStateHolder(
    initialConfig: WatermarkConfig,
    key1: Any? = null,
    key2: Any? = null
): WatermarkStudioStateHolder {
    return remember(key1, key2) {
        WatermarkStudioStateHolder(initialConfig = initialConfig)
    }
}
