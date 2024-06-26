package de.michelinside.glucodatahandler

import android.app.Notification
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.Icon
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import de.michelinside.glucodatahandler.common.Constants
import de.michelinside.glucodatahandler.common.GlucoDataService
import de.michelinside.glucodatahandler.common.ReceiveData
import de.michelinside.glucodatahandler.common.utils.Utils
import de.michelinside.glucodatahandler.common.utils.BitmapUtils
import de.michelinside.glucodatahandler.common.notifier.InternalNotifier
import de.michelinside.glucodatahandler.common.notifier.NotifierInterface
import de.michelinside.glucodatahandler.common.notifier.NotifySource
import de.michelinside.glucodatahandler.common.notification.ChannelType
import de.michelinside.glucodatahandler.common.notification.Channels


object PermanentNotification: NotifierInterface, SharedPreferences.OnSharedPreferenceChangeListener {
    private const val LOG_ID = "GDH.PermanentNotification"
    private const val SECOND_NOTIFICATION_ID = 124
    private lateinit var notificationCompat: Notification.Builder
    private lateinit var foregroundNotificationCompat: Notification.Builder
    private lateinit var sharedPref: SharedPreferences

    enum class StatusBarIcon(val pref: String) {
        APP("app"),
        GLUCOSE("glucose"),
        TREND("trend"),
        DELTA("delta")
    }

    fun create(context: Context) {
        try {
            Log.v(LOG_ID, "create called")
            createNofitication(context)
            sharedPref = context.getSharedPreferences(Constants.SHARED_PREF_TAG, Context.MODE_PRIVATE)
            sharedPref.registerOnSharedPreferenceChangeListener(this)
            updatePreferences()
        } catch (exc: Exception) {
            Log.e(LOG_ID, "create exception: " + exc.toString() )
        }
    }

    fun destroy() {
        try {
            Log.v(LOG_ID, "destroy called")
            InternalNotifier.remNotifier(GlucoDataService.context!!, this)
            sharedPref.unregisterOnSharedPreferenceChangeListener(this)
            removeNotifications()
        } catch (exc: Exception) {
            Log.e(LOG_ID, "destroy exception: " + exc.toString() )
        }
    }

    override fun OnNotifyData(context: Context, dataSource: NotifySource, extras: Bundle?) {
        try {
            Log.v(LOG_ID, "OnNotifyData called")
            showNotifications()
        } catch (exc: Exception) {
            Log.e(LOG_ID, "OnNotifyData exception: " + exc.toString() )
        }
    }

    private fun createNotificationChannel(context: Context) {
        Channels.createNotificationChannel(context, ChannelType.MOBILE_FOREGROUND)
        Channels.createNotificationChannel(context, ChannelType.MOBILE_SECOND)
    }

    private fun createNofitication(context: Context) {
        createNotificationChannel(context)

        Channels.getNotificationManager().cancel(GlucoDataService.NOTIFICATION_ID)
        Channels.getNotificationManager().cancel(SECOND_NOTIFICATION_ID)

        notificationCompat = Notification.Builder(context, ChannelType.MOBILE_SECOND.channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(Utils.getAppIntent(context, MainActivity::class.java, 5, false))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
            .setShowWhen(true)
            .setColorized(true)
            .setGroup(ChannelType.MOBILE_SECOND.channelId)
            .setCategory(Notification.CATEGORY_STATUS)
            .setVisibility(Notification.VISIBILITY_PUBLIC)

        foregroundNotificationCompat = Notification.Builder(context, ChannelType.MOBILE_FOREGROUND.channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(Utils.getAppIntent(context, MainActivity::class.java, 4, false))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setAutoCancel(false)
            .setShowWhen(true)
            .setColorized(true)
            .setGroup(ChannelType.MOBILE_FOREGROUND.channelId)
            .setCategory(Notification.CATEGORY_STATUS)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
    }


    private fun removeNotifications() {
        //notificationMgr.cancel(NOTIFICATION_ID)  // remove notification
        showPrimaryNotification(false)
        Channels.getNotificationManager().cancel(SECOND_NOTIFICATION_ID)
    }

    private fun getStatusBarIcon(iconKey: String): Icon {
        val bigIcon = sharedPref.getBoolean(Constants.SHARED_PREF_PERMANENT_NOTIFICATION_USE_BIG_ICON, false)
        val coloredIcon = sharedPref.getBoolean(Constants.SHARED_PREF_PERMANENT_NOTIFICATION_COLORED_ICON, true)
        return when(sharedPref.getString(iconKey, StatusBarIcon.APP.pref)) {
            StatusBarIcon.GLUCOSE.pref -> BitmapUtils.getGlucoseAsIcon(roundTarget=!bigIcon, color = if(coloredIcon) ReceiveData.getClucoseColor() else Color.WHITE)
            StatusBarIcon.TREND.pref -> BitmapUtils.getRateAsIcon(roundTarget=true, color = if(coloredIcon) ReceiveData.getClucoseColor() else Color.WHITE, resizeFactor = if (bigIcon) 1.5F else 1F)
            StatusBarIcon.DELTA.pref -> BitmapUtils.getDeltaAsIcon(roundTarget=!bigIcon, color = if(coloredIcon) ReceiveData.getClucoseColor(true) else Color.WHITE)
            else -> Icon.createWithResource(GlucoDataService.context, R.mipmap.ic_launcher)
        }
    }

    fun getNotification(withContent: Boolean, iconKey: String, foreground: Boolean) : Notification {
        var remoteViews: RemoteViews? = null
        if (withContent) {
            remoteViews = RemoteViews(GlucoDataService.context!!.packageName, R.layout.notification)
            remoteViews.setTextViewText(R.id.glucose, ReceiveData.getClucoseAsString())
            remoteViews.setTextColor(R.id.glucose, ReceiveData.getClucoseColor())
            remoteViews.setImageViewBitmap(R.id.trendImage, BitmapUtils.getRateAsBitmap())
            remoteViews.setTextViewText(R.id.deltaText, "Δ " + ReceiveData.getDeltaAsString())
            if (ReceiveData.isObsolete(Constants.VALUE_OBSOLETE_SHORT_SEC)) {
                if (!ReceiveData.isObsolete())
                    remoteViews.setInt(R.id.glucose, "setPaintFlags", Paint.STRIKE_THRU_TEXT_FLAG or Paint.ANTI_ALIAS_FLAG)
                remoteViews.setTextColor(R.id.deltaText, Color.GRAY )
            }
            if(!ReceiveData.isIobCob()) {
                remoteViews.setViewVisibility(R.id.iobText, View.GONE)
                remoteViews.setViewVisibility(R.id.cobText, View.GONE)
            } else {
                remoteViews.setTextViewText(R.id.iobText, GlucoDataService.context!!.getString(R.string.iob_label) + ": " + ReceiveData.getIobAsString() )
                remoteViews.setTextViewText(R.id.cobText, GlucoDataService.context!!.getString(R.string.cob_label) + ": " + ReceiveData.getCobAsString())
                remoteViews.setViewVisibility(R.id.iobText, View.VISIBLE)
                if (ReceiveData.cob.isNaN())
                    remoteViews.setViewVisibility(R.id.cobText, View.GONE)
                else
                    remoteViews.setViewVisibility(R.id.cobText, View.VISIBLE)
            }
        }

        val notificationBuilder = if(foreground) foregroundNotificationCompat else notificationCompat
        notificationBuilder
            .setSmallIcon(getStatusBarIcon(iconKey))
            .setWhen(ReceiveData.time)
            .setCustomContentView(remoteViews)
            .setCustomBigContentView(null)
            .setColorized(false)
            .setStyle(Notification.DecoratedCustomViewStyle())
            .setContentTitle(if (withContent) ReceiveData.getClucoseAsString() else "")
            .setContentText(if (withContent) "Delta: " + ReceiveData.getDeltaAsString() else "")

        when(sharedPref.getString(iconKey, StatusBarIcon.APP.pref)) {
            StatusBarIcon.GLUCOSE.pref,
            StatusBarIcon.TREND.pref -> {
                notificationBuilder.setColor(Color.TRANSPARENT)
            }
        }

        val notification = notificationBuilder.build()

        notification.visibility = Notification.VISIBILITY_PUBLIC
        notification.flags = notification.flags or Notification.FLAG_NO_CLEAR
        return notification
    }

    private fun showNotification(id: Int, withContent: Boolean, iconKey: String, foreground: Boolean) {
        try {
            Log.v(LOG_ID, "showNotification called for id " + id)
            Channels.getNotificationManager().notify(
                id,
                getNotification(withContent, iconKey, foreground)
            )
        } catch (exc: Exception) {
            Log.e(LOG_ID, "showNotification exception: " + exc.toString() )
        }
    }

    private fun showNotifications() {
        showPrimaryNotification(true)
        if (sharedPref.getBoolean(Constants.SHARED_PREF_SECOND_PERMANENT_NOTIFICATION, false)) {
            Log.d(LOG_ID, "show second notification")
            showNotification(SECOND_NOTIFICATION_ID, false, Constants.SHARED_PREF_SECOND_PERMANENT_NOTIFICATION_ICON, false)
        } else {
            Channels.getNotificationManager().cancel(SECOND_NOTIFICATION_ID)
        }
    }

    private fun showPrimaryNotification(show: Boolean) {
        Log.d(LOG_ID, "showPrimaryNotification " + show)
        //if (show)
            showNotification(GlucoDataService.NOTIFICATION_ID, !sharedPref.getBoolean(Constants.SHARED_PREF_PERMANENT_NOTIFICATION_EMPTY, false), Constants.SHARED_PREF_PERMANENT_NOTIFICATION_ICON, true)
        /*if (show != GlucoDataService.foreground) {
            Log.d(LOG_ID, "change foreground notification mode")
            with(sharedPref.edit()) {
                putBoolean(Constants.SHARED_PREF_FOREGROUND_SERVICE, show)
                apply()
            }
            val serviceIntent =
                Intent(GlucoDataService.context!!, GlucoDataServiceMobile::class.java)
            if (show)
                serviceIntent.putExtra(Constants.SHARED_PREF_FOREGROUND_SERVICE, true)
            else
                serviceIntent.putExtra(Constants.ACTION_STOP_FOREGROUND, true)
            GlucoDataService.context!!.startService(serviceIntent)
        }*/
    }

    private fun hasContent(): Boolean {
        if (!sharedPref.getBoolean(Constants.SHARED_PREF_PERMANENT_NOTIFICATION_EMPTY, false))
            return true

        if (sharedPref.getString(Constants.SHARED_PREF_PERMANENT_NOTIFICATION_ICON, StatusBarIcon.APP.pref) != StatusBarIcon.APP.pref) {
            return true
        }

        if (sharedPref.getBoolean(Constants.SHARED_PREF_SECOND_PERMANENT_NOTIFICATION, false)) {
            if (sharedPref.getString(Constants.SHARED_PREF_SECOND_PERMANENT_NOTIFICATION_ICON, StatusBarIcon.APP.pref) != StatusBarIcon.APP.pref) {
                return true
            }
        }
        return false
    }

    private fun updatePreferences() {
        try {
            //if (sharedPref.getBoolean(Constants.SHARED_PREF_PERMANENT_NOTIFICATION, true)) {
                val content = hasContent()
                Log.i(LOG_ID, "update permanent notifications having content: " + content)
                if (content) {
                    val filter = mutableSetOf(
                        NotifySource.BROADCAST,
                        NotifySource.MESSAGECLIENT,
                        NotifySource.SETTINGS,
                        NotifySource.OBSOLETE_VALUE
                    )   // to trigger re-start for the case of stopped by the system
                    if (!sharedPref.getBoolean(Constants.SHARED_PREF_PERMANENT_NOTIFICATION_EMPTY, false))
                        filter.add(NotifySource.IOB_COB_CHANGE)
                    InternalNotifier.addNotifier(GlucoDataService.context!!, this, filter)
                } else {
                    InternalNotifier.remNotifier(GlucoDataService.context!!, this)
                }
                showNotifications()
            /*}
            else {
                Log.i(LOG_ID, "deactivate permanent notification")
                InternalNotifier.remNotifier(GlucoDataService.context!!, this)
                removeNotifications()
            }*/
        } catch (exc: Exception) {
            Log.e(LOG_ID, "updatePreferences exception: " + exc.toString() )
        }
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        try {
            Log.d(LOG_ID, "onSharedPreferenceChanged called for key " + key)
            when(key) {
                //Constants.SHARED_PREF_PERMANENT_NOTIFICATION,
                Constants.SHARED_PREF_PERMANENT_NOTIFICATION_ICON,
                Constants.SHARED_PREF_SECOND_PERMANENT_NOTIFICATION,
                Constants.SHARED_PREF_SECOND_PERMANENT_NOTIFICATION_ICON,
                Constants.SHARED_PREF_PERMANENT_NOTIFICATION_USE_BIG_ICON,
                Constants.SHARED_PREF_PERMANENT_NOTIFICATION_COLORED_ICON,
                Constants.SHARED_PREF_PERMANENT_NOTIFICATION_EMPTY -> {
                    updatePreferences()
                }
            }
        } catch (exc: Exception) {
            Log.e(LOG_ID, "onSharedPreferenceChanged exception: " + exc.toString() )
        }
    }

}