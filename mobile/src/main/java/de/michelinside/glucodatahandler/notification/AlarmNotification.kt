package de.michelinside.glucodatahandler.notification

import android.app.Notification
import android.app.NotificationChannelGroup
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Paint
import android.media.AudioAttributes
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.util.Log
import android.widget.RemoteViews
import android.widget.Toast
import de.michelinside.glucodatahandler.MainActivity
import de.michelinside.glucodatahandler.common.Constants
import de.michelinside.glucodatahandler.common.GlucoDataService
import de.michelinside.glucodatahandler.common.R as CR
import de.michelinside.glucodatahandler.R
import de.michelinside.glucodatahandler.common.ReceiveData
import de.michelinside.glucodatahandler.common.notification.AlarmType
import de.michelinside.glucodatahandler.common.notification.ChannelType
import de.michelinside.glucodatahandler.common.notification.Channels
import de.michelinside.glucodatahandler.common.notifier.InternalNotifier
import de.michelinside.glucodatahandler.common.notifier.NotifierInterface
import de.michelinside.glucodatahandler.common.notifier.NotifySource
import de.michelinside.glucodatahandler.common.utils.BitmapUtils
import de.michelinside.glucodatahandler.common.utils.Utils
import java.io.FileOutputStream

object AlarmNotification: NotifierInterface, SharedPreferences.OnSharedPreferenceChangeListener {
    private const val LOG_ID = "GDH.AlarmNotification"
    private const val ALARM_GROUP_ID = "alarm_group"
    private const val VERY_LOW_NOTIFICATION_ID = 801
    private var enabled: Boolean = false
    fun initNotifications(context: Context) {
        try {
            Log.v(LOG_ID, "initNotifications called")
            createNotificationChannels(context)
            val sharedPref = context.getSharedPreferences(Constants.SHARED_PREF_TAG, Context.MODE_PRIVATE)
            sharedPref.registerOnSharedPreferenceChangeListener(this)
            onSharedPreferenceChanged(sharedPref, null)
        } catch (exc: Exception) {
            Log.e(LOG_ID, "initNotifications exception: " + exc.toString() )
        }
    }

    fun setEnabled(newEnabled: Boolean) {
        try {
            Log.v(LOG_ID, "setEnabled called: current=$enabled - new=$newEnabled")
            if (enabled != newEnabled) {
                enabled = newEnabled
                if(enabled) {
                    InternalNotifier.addNotifier(GlucoDataService.context!!, this, mutableSetOf(NotifySource.ALARM_TRIGGER) )
                } else {
                    stopNotifications()
                    InternalNotifier.remNotifier(GlucoDataService.context!!, this)
                }
            }
        } catch (exc: Exception) {
            Log.e(LOG_ID, "setEnabled exception: " + exc.toString() )
        }
    }

    fun destroy(context: Context) {
        try {
            Log.v(LOG_ID, "destroy called")
            stopNotifications(context)
            val sharedPref = context.getSharedPreferences(Constants.SHARED_PREF_TAG, Context.MODE_PRIVATE)
            sharedPref.unregisterOnSharedPreferenceChangeListener(this)
        } catch (exc: Exception) {
            Log.e(LOG_ID, "destroy exception: " + exc.toString() )
        }
    }

    private fun stopNotifications(context: Context? = null) {
        Log.v(LOG_ID, "stopNotifications called")
        stopNotification(AlarmType.VERY_LOW, context)
    }

    private fun stopNotification(alarmType: AlarmType, context: Context? = null) {
        try {
            Log.v(LOG_ID, "stopNotification called for $alarmType")
            stopNotification(getNotificationId(alarmType))
        } catch (exc: Exception) {
            Log.e(LOG_ID, "stopNotifications exception: " + exc.toString() )
        }
    }
    fun stopNotification(noticationId: Int, context: Context? = null) {
        try {
            Log.v(LOG_ID, "stopNotification called for $noticationId")
            if (noticationId > 0)
                Channels.getNotificationManager(context).cancel(noticationId)
        } catch (exc: Exception) {
            Log.e(LOG_ID, "stopNotifications exception: " + exc.toString() )
        }
    }

    fun triggerNotification(alarmType: AlarmType, context: Context) {
        try {
            Log.v(LOG_ID, "triggerNotification called for $alarmType - enabled=$enabled")
            if (enabled) {
                Channels.getNotificationManager(context).notify(
                    getNotificationId(alarmType),
                    createNotification(context, alarmType)
                )
            }
        } catch (exc: Exception) {
            Log.e(LOG_ID, "showNotification exception: " + exc.toString() )
        }
    }

    private fun createNotificationChannels(context: Context) {
        Log.v(LOG_ID, "createNotificationChannels called")
        val groupName = context.resources.getString(CR.string.alarm_notification_group_name)
        Channels.getNotificationManager(context).createNotificationChannelGroup(
            NotificationChannelGroup(ALARM_GROUP_ID, groupName))
        createNotificationChannel(context, AlarmType.VERY_LOW, true)
    }

    private fun createNotificationChannel(context: Context, alarmType: AlarmType, byPassDnd: Boolean) {
        Log.v(LOG_ID, "createNotificationChannel called for $alarmType")
        val channelType = getChannelType(alarmType)
        if (channelType != null) {
            val audioAttributes = AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_ALARM)
                .build()

            val channel = Channels.getNotificationChannel(context, channelType, false)
            channel.group = ALARM_GROUP_ID
            channel.setSound(getDefaultAlarm(alarmType, context), audioAttributes)
            channel.vibrationPattern = getVibrationPattern(alarmType)
            channel.setBypassDnd(byPassDnd)
            Channels.getNotificationManager(context).createNotificationChannel(channel)
        }
    }

    private fun createSnoozeIntent(context: Context, snoozeTime: Long, noticationId: Int): PendingIntent {
        val intent = Intent(Constants.ALARM_SNOOZE_ACTION)
        intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
        intent.putExtra(Constants.ALARM_SNOOZE_EXTRA_TIME, snoozeTime)
        intent.putExtra(Constants.ALARM_SNOOZE_EXTRA_NOTIFY_ID, noticationId)
        intent.setPackage(context.packageName)
        return PendingIntent.getBroadcast(context, snoozeTime.toInt(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun createAction(context: Context, title: String, snoozeTime: Long, noticationId: Int): Notification.Action {
        return Notification.Action.Builder(null, title, createSnoozeIntent(context, snoozeTime, noticationId)).build()

    }

    private fun createNotification(context: Context, alarmType: AlarmType): Notification? {
        Log.v(LOG_ID, "createNotification called for $alarmType")
        val channelId = getChannelId(alarmType)
        val resId = getAlarmTextRes(alarmType)
        if (channelId.isNullOrEmpty() || resId == null)
            return null

        val remoteViews = RemoteViews(GlucoDataService.context!!.packageName, R.layout.alarm_notification)
        remoteViews.setTextViewText(R.id.alarm, context.getString(resId))
        remoteViews.setTextViewText(R.id.snooze, context.getString(CR.string.snooze))
        remoteViews.setTextViewText(R.id.glucose, ReceiveData.getClucoseAsString())
        remoteViews.setTextColor(R.id.glucose, ReceiveData.getClucoseColor())
        remoteViews.setImageViewBitmap(R.id.trendImage, BitmapUtils.getRateAsBitmap())
        remoteViews.setTextViewText(R.id.deltaText, "Δ " + ReceiveData.getDeltaAsString())
        remoteViews.setOnClickPendingIntent(R.id.snooze_60, createSnoozeIntent(context, 60L, getNotificationId(alarmType)))
        remoteViews.setOnClickPendingIntent(R.id.snooze_90, createSnoozeIntent(context, 90L, getNotificationId(alarmType)))
        remoteViews.setOnClickPendingIntent(R.id.snooze_120, createSnoozeIntent(context, 120L, getNotificationId(alarmType)))
        if (ReceiveData.isObsolete(Constants.VALUE_OBSOLETE_SHORT_SEC)) {
            if (!ReceiveData.isObsolete())
                remoteViews.setInt(R.id.glucose, "setPaintFlags", Paint.STRIKE_THRU_TEXT_FLAG or Paint.ANTI_ALIAS_FLAG)
            remoteViews.setTextColor(R.id.deltaText, Color.GRAY )
        }

        val notification = Notification.Builder(context, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(Utils.getAppIntent(context, MainActivity::class.java, 8, false))
            .setOnlyAlertOnce(false)
            .setAutoCancel(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setWhen(ReceiveData.time)
            .setCustomContentView(remoteViews)
            .setCustomBigContentView(null)
            .setColorized(false)
            .setStyle(Notification.DecoratedCustomViewStyle())

            /*.setLargeIcon(BitmapUtils.getRateAsIcon())
            .setContentTitle(context.getString(resId))
            .setContentText(ReceiveData.getClucoseAsString()  + " (Δ " + ReceiveData.getDeltaAsString() + ")")
            .addAction(createAction(context, context.getString(CR.string.snooze) + ": 60", 60L, getNotificationId(alarmType)))
            .addAction(createAction(context, "90", 90L, getNotificationId(alarmType)))
            .addAction(createAction(context, "120", 120L, getNotificationId(alarmType)))*/
            .build()

        return notification
    }

    private fun getNotificationId(alarmType: AlarmType): Int {
        return when(alarmType) {
            AlarmType.VERY_LOW -> VERY_LOW_NOTIFICATION_ID
            else -> -1
        }
    }

    private fun getChannelType(alarmType: AlarmType): ChannelType? {
        return when(alarmType) {
            AlarmType.VERY_LOW -> ChannelType.VERY_LOW_ALARM
            else -> null
        }
    }

    fun getChannelId(alarmType: AlarmType): String? {
        return when(alarmType) {
            AlarmType.VERY_LOW -> ChannelType.VERY_LOW_ALARM.channelId
            else -> null
        }
    }

    fun getAlarmSoundRes(alarmType: AlarmType): Int? {
        return when(alarmType) {
            AlarmType.VERY_LOW -> CR.raw.gdh_alarm_very_low
            else -> null
        }
    }

    private fun getAlarmTextRes(alarmType: AlarmType): Int? {
        return when(alarmType) {
            AlarmType.VERY_LOW -> CR.string.very_low_alarm_text
            else -> null
        }
    }

    private fun getDefaultAlarm(alarmType: AlarmType, context: Context): Uri? {
        val res = getAlarmSoundRes(alarmType)
        if (res != null) {
            return getUri(res, context)
        }
        return null
    }

    private fun getUri(resId: Int, context: Context): Uri {
        val uri = "android.resource://" + context.packageName + "/" + resId
        return Uri.parse(uri)
    }

    private fun getVibrationPattern(alarmType: AlarmType): LongArray? {
        return when(alarmType) {
            AlarmType.VERY_LOW -> longArrayOf(0, 1000, 500, 1000, 500, 1000, 500, 1000, 500, 1000, 500, 1000)
            AlarmType.LOW -> longArrayOf(0, 700, 500, 700, 500, 700, 500, 700)
            AlarmType.HIGH -> longArrayOf(0, 500, 500, 500, 500, 500, 500, 500)
            AlarmType.VERY_HIGH -> longArrayOf(0, 800, 500, 800, 800, 600, 800, 800, 500, 800, 800, 600, 800)
            else -> null
        }
    }

    fun saveAlarm(context: Context, alarmType: AlarmType, uri: Uri) {
        try {
            Log.v(LOG_ID, "saveAlarm called for $alarmType to $uri")
            val resId = getAlarmSoundRes(alarmType)
            if (resId != null) {
                Thread {
                    context.contentResolver.openFileDescriptor(uri, "w")?.use {
                        FileOutputStream(it.fileDescriptor).use { outputStream ->
                            val inputStream = context.resources.openRawResource(resId)
                            val buffer = ByteArray(4 * 1024) // or other buffer size
                            var read: Int
                            while (inputStream.read(buffer).also { rb -> read = rb } != -1) {
                                outputStream.write(buffer, 0, read)
                            }
                            Log.v(LOG_ID, "flush")
                            outputStream.flush()
                            outputStream.close()
                        }
                    }
                    val text = context.resources.getText(CR.string.alarm_saved)
                    Handler(GlucoDataService.context!!.mainLooper).post {
                        Toast.makeText(GlucoDataService.context!!, text, Toast.LENGTH_SHORT).show()
                    }
                }.start()
            }
        } catch (exc: Exception) {
            Log.e(LOG_ID, "Saving alarm to file exception: " + exc.message.toString() )
        }
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?) {
        try {
            Log.d(LOG_ID, "onSharedPreferenceChanged called for " + key)
            when(key) {
                null,
                Constants.SHARED_PREF_ALARM_NOTIFICATION_ENABLED -> setEnabled(sharedPreferences.getBoolean(Constants.SHARED_PREF_ALARM_NOTIFICATION_ENABLED, enabled))
            }
        } catch (exc: Exception) {
            Log.e(LOG_ID, "onSharedPreferenceChanged exception: " + exc.toString())
        }
    }

    override fun OnNotifyData(context: Context, dataSource: NotifySource, extras: Bundle?) {
        try {
            Log.d(LOG_ID, "OnNotifyData called for $dataSource")
            if (dataSource == NotifySource.ALARM_TRIGGER && ReceiveData.forceAlarm) {
                triggerNotification(ReceiveData.getAlarmType(), context)
            }
        } catch (exc: Exception) {
            Log.e(LOG_ID, "OnNotifyData exception: " + exc.toString())
        }
    }
}