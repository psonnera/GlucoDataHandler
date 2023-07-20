package de.michelinside.glucodatahandler.widget

import android.content.Context
import android.util.Log


/**
 * Implementation of App Widget functionality.
 */
class GlucoseTrendDeltaWidget : GlucoseBaseWidget(GlucoseTrendDeltaWidget::class.java, true) {

    companion object {
        private val LOG_ID = "GlucoDataHandler.GlucoseTrendDeltaWidget"
        fun create(context: Context) {
            Log.d(LOG_ID, "create called")
            triggerUpdate(context, GlucoseTrendDeltaWidget::class.java)
        }
    }
}
