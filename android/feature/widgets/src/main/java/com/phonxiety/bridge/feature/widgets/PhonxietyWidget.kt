package com.phonxiety.bridge.feature.widgets

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.annotation.ColorRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.Button
import androidx.glance.ButtonDefaults
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.phonxiety.bridge.BridgeConfigLoader
import com.phonxiety.bridge.BridgeRuntime

/** The 2×1 glance: link state, path and RTT, the live call with its timer, and the last outcome. */
class PhonxietyWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Single

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val content = WidgetContentMapper.map(
            bridge = BridgeRuntime.state.value,
            paired = paired(context),
            lastCall = WidgetStateStore.lastCall(),
            nowEpochMs = System.currentTimeMillis()
        )
        provideContent { PhonxietyWidgetContent(content) }
    }

    private fun paired(context: Context): Boolean = runCatching {
        BridgeConfigLoader.loadOrNull(context) != null
    }.getOrDefault(false)
}

/** The launcher's entry point; it starts the state collector and lets Glance render. */
class PhonxietyWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = PhonxietyWidget()

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        WidgetUpdates.start(context)
        super.onUpdate(context, appWidgetManager, appWidgetIds)
    }

    override fun onEnabled(context: Context) {
        WidgetUpdates.start(context)
        super.onEnabled(context)
    }
}

/** Ends the call from the widget's button; the widget re-renders when the state reports the end. */
class HangupAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        CallEnd.hangUp(context)
    }
}

@Composable
private fun PhonxietyWidgetContent(content: WidgetContent) {
    val background = resourceColor(R.color.phonxiety_widget_background)
    val text = resourceColor(R.color.phonxiety_widget_text)
    val muted = resourceColor(R.color.phonxiety_widget_muted)
    val dot = resourceColor(
        when (content.tone) {
            WidgetTone.GOOD -> R.color.phonxiety_widget_good
            WidgetTone.WARN -> R.color.phonxiety_widget_warn
            WidgetTone.BAD -> R.color.phonxiety_widget_bad
            WidgetTone.IDLE -> R.color.phonxiety_widget_muted
        }
    )
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .background(background)
            .cornerRadius(20.dp)
            .padding(12.dp)
            .clickable(actionStartActivity(openAppIntent())),
        verticalAlignment = Alignment.Top
    ) {
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = GlanceModifier
                    .size(8.dp)
                    .background(dot)
                    .cornerRadius(4.dp)
            ) {}
            Spacer(GlanceModifier.width(6.dp))
            Text(
                text = content.linkLabel,
                style = TextStyle(
                    color = text,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
            )
            Spacer(GlanceModifier.defaultWeight())
            Text(
                text = content.pathLabel,
                style = TextStyle(color = muted, fontSize = 12.sp),
                maxLines = 1
            )
        }
        Spacer(GlanceModifier.size(6.dp))
        val call = content.call
        if (call != null) {
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = GlanceModifier.defaultWeight()) {
                    Text(
                        text = call.title,
                        style = TextStyle(color = text, fontSize = 13.sp, fontWeight = FontWeight.Medium),
                        maxLines = 1
                    )
                    Text(
                        text = call.elapsed,
                        style = TextStyle(color = muted, fontSize = 12.sp),
                        maxLines = 1
                    )
                }
                Button(
                    text = "Hang up",
                    onClick = actionRunCallback<HangupAction>(),
                    colors = ButtonDefaults.buttonColors(
                        backgroundColor = resourceColor(R.color.phonxiety_widget_bad),
                        contentColor = resourceColor(R.color.phonxiety_widget_on_bad)
                    )
                )
            }
        } else {
            Text(
                text = content.footer,
                style = TextStyle(color = muted, fontSize = 12.sp),
                maxLines = 1
            )
        }
    }
}

/**
 * The public `ColorProvider` overload takes a `Color`; the resource overload is library-private, so
 * the resource is resolved against the widget's configuration, which carries night mode.
 */
@Composable
private fun resourceColor(@ColorRes id: Int): ColorProvider {
    val context = LocalContext.current
    return ColorProvider(Color(context.getColor(id)))
}
