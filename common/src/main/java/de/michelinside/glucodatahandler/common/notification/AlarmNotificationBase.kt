package de.michelinside.glucodatahandler.common.notification

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import de.michelinside.glucodatahandler.common.Command
import de.michelinside.glucodatahandler.common.Constants
import de.michelinside.glucodatahandler.common.GlucoDataService
import de.michelinside.glucodatahandler.common.ReceiveData
import de.michelinside.glucodatahandler.common.notifier.InternalNotifier
import de.michelinside.glucodatahandler.common.notifier.NotifierInterface
import de.michelinside.glucodatahandler.common.notifier.NotifySource
import de.michelinside.glucodatahandler.common.utils.Utils
import java.math.RoundingMode
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit
import de.michelinside.glucodatahandler.common.R as CR


abstract class AlarmNotificationBase: NotifierInterface, SharedPreferences.OnSharedPreferenceChangeListener {
    protected val LOG_ID = "GDH.AlarmNotification"
    private var enabled: Boolean = false
    private var addSnooze: Boolean = false
    private val VERY_LOW_NOTIFICATION_ID = 801
    private val LOW_NOTIFICATION_ID = 802
    private val HIGH_NOTIFICATION_ID = 803
    private val VERY_HIGH_NOTIFICATION_ID = 804
    private val OBSOLETE_NOTIFICATION_ID = 805
    lateinit var audioManager:AudioManager
    protected var curNotification = 0
    private var curAlarmTime = 0L
    private var forceSound = false
    private var forceVibration = false
    private var lastRingerMode = -1
    private var lastDndMode = NotificationManager.INTERRUPTION_FILTER_UNKNOWN
    private var retriggerTime = 0
    private var retriggerCount = 0
    private var curTestAlarmType = AlarmType.NONE
    private var retriggerOnDestroy = false
    private var ringtone: Ringtone? = null
    private var vibratorInstance: Vibrator? = null
    private var alarmManager: AlarmManager? = null
    private var alarmPendingIntent: PendingIntent? = null
    private var alarmNotificationActive: Boolean = false
    private var currentAlarmState: AlarmState = AlarmState.DISABLED

    enum class TriggerAction {
        TEST_ALARM,
        START_ALARM_SOUND,
        STOP_VIBRATION,
        RETRIGGER_SOUND,
    }

    //private var soundLevel = -1
    //private var lastSoundLevel = -1

    protected val vibrator: Vibrator get() {
        if(vibratorInstance == null) {
            vibratorInstance = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val manager = GlucoDataService.context!!.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                manager.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                GlucoDataService.context!!.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
        }
        return vibratorInstance!!
    }


    companion object {
        private var classInstance: AlarmNotificationBase? = null
        val instance: AlarmNotificationBase? get() = classInstance

    }

    abstract val active: Boolean

    fun getAlarmState(context: Context): AlarmState {
        var state = AlarmState.currentState(context)
        if(state == AlarmState.ACTIVE && (!active || !alarmNotificationActive)) {
            state = AlarmState.INACTIVE
        }
        if(currentAlarmState != state) {
            Log.i(LOG_ID, "Current alarm state: $state - last state: $currentAlarmState")
            currentAlarmState = state
        }
        return state
    }

    private fun isAlarmActive(context: Context): Boolean {
        return AlarmState.currentState(context) == AlarmState.ACTIVE
    }

    fun initNotifications(context: Context) {
        try {
            Log.v(LOG_ID, "initNotifications called")
            classInstance = this
            audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            createNotificationChannel(context)
            val sharedPref = context.getSharedPreferences(Constants.SHARED_PREF_TAG, Context.MODE_PRIVATE)
            sharedPref.registerOnSharedPreferenceChangeListener(this)
            onSharedPreferenceChanged(sharedPref, null)
            initNotifier()
        } catch (exc: Exception) {
            Log.e(LOG_ID, "initNotifications exception: " + exc.toString() )
        }
    }

    fun getEnabled(): Boolean = enabled

    private fun setEnabled(newEnabled: Boolean) {
        try {
            Log.v(LOG_ID, "setEnabled called: current=$enabled - new=$newEnabled")
            if (enabled != newEnabled) {
                enabled = newEnabled
                Log.i(LOG_ID, "enable alarm notifications: $newEnabled")
                initNotifier()
                if(!enabled) {
                    stopCurrentNotification()
                }
            }
        } catch (exc: Exception) {
            Log.e(LOG_ID, "setEnabled exception: " + exc.toString() )
        }
    }

    fun getAddSnooze(): Boolean = addSnooze

    private fun setAddSnooze(snooze: Boolean) {
        try {
            Log.v(LOG_ID, "setAddSnooze called: current=$addSnooze - new=$snooze")
            addSnooze = snooze
        } catch (exc: Exception) {
            Log.e(LOG_ID, "setAddSnooze exception: " + exc.toString() )
        }
    }

    fun destroy(context: Context) {
        try {
            Log.v(LOG_ID, "destroy called")
            stopCurrentNotification(context)
            val sharedPref = context.getSharedPreferences(Constants.SHARED_PREF_TAG, Context.MODE_PRIVATE)
            sharedPref.unregisterOnSharedPreferenceChangeListener(this)
        } catch (exc: Exception) {
            Log.e(LOG_ID, "destroy exception: " + exc.toString() )
        }
    }

    fun stopCurrentNotification(context: Context? = null, fromClient: Boolean = false) {
        Log.v(LOG_ID, "stopCurrentNotification fromClient=$fromClient - curNotification=$curNotification")
        if (curNotification > 0) {
            stopNotification(curNotification, context, fromClient = fromClient)
        } else {
            onNotificationStopped(0, context)
        }
    }

    open fun onNotificationStopped(noticationId: Int, context: Context? = null) {

    }

    fun stopNotification(noticationId: Int, context: Context? = null, fromClient: Boolean = false) {
        try {
            Log.v(LOG_ID, "stopNotification called for $noticationId - current=$curNotification")
            stopTrigger()
            if(noticationId == curNotification) {
                checkRecreateSound()
                if (noticationId > 0) {
                    Channels.getNotificationManager(context).cancel(noticationId)
                    onNotificationStopped(noticationId, context)
                    curNotification = 0
                    curAlarmTime = 0
                    curTestAlarmType = AlarmType.NONE
                    if(!fromClient)
                        GlucoDataService.sendCommand(Command.STOP_ALARM)
                    InternalNotifier.notify(GlucoDataService.context!!, NotifySource.NOTIFICATION_STOPPED, null)
                }
            }
            stopVibrationAndSound()
        } catch (exc: Exception) {
            Log.e(LOG_ID, "stopNotification exception: " + exc.toString() )
        }
    }


    fun stopVibrationAndSound() {
        try {
            Log.d(LOG_ID, "stopVibrationAndSound called")
            vibrator.cancel()
            if(ringtone!=null) {
                ringtone!!.stop()
                ringtone = null
            }
        } catch (ex: Exception) {
            Log.e(LOG_ID, "stopVibrationAndSound exception: " + ex)
        }
    }

    fun triggerNotification(alarmType: AlarmType, context: Context, forTest: Boolean = false) {
        try {
            Log.v(LOG_ID, "triggerNotification called for $alarmType - active=$active - forTest=$forTest")
            if (active || forTest) {
                stopCurrentNotification(context)
                curNotification = getNotificationId(alarmType)
                retriggerCount = 0
                retriggerOnDestroy = false
                retriggerTime = getTriggerTime(alarmType, context)
                curAlarmTime = System.currentTimeMillis()
                curTestAlarmType = if(forTest)
                    alarmType
                else
                    AlarmType.NONE
                Log.d(LOG_ID, "Create notification for $alarmType with ID=$curNotification - triggerTime=$retriggerTime")
                checkCreateSound(alarmType, context)
                showNotification(alarmType, context)
            }
        } catch (exc: Exception) {
            Log.e(LOG_ID, "showNotification exception: " + exc.toString() )
        }
    }

    open fun executeTest(alarmType: AlarmType, context: Context) {
        Log.v(LOG_ID, "executeTest called for $alarmType")
        triggerNotification(alarmType, context, true)
    }

    fun triggerDelay(action: TriggerAction, alarmType: AlarmType, context: Context, delaySeconds: Float) {
        stopTrigger()
        Log.d(LOG_ID, "Trigger action $action for $alarmType in $delaySeconds seconds")
        alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        var hasExactAlarmPermission = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if(!alarmManager!!.canScheduleExactAlarms()) {
                Log.d(LOG_ID, "Need permission to set exact alarm!")
                hasExactAlarmPermission = false
            }
        }
        val intent = Intent(context, AlarmIntentReceiver::class.java)
        intent.action = action.toString()
        intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
        intent.putExtra(Constants.ALARM_NOTIFICATION_EXTRA_ALARM_TYPE, alarmType.ordinal)
        alarmPendingIntent = PendingIntent.getBroadcast(
            context,
            800,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT
        )
        val alarmTime = System.currentTimeMillis() + (delaySeconds*1000).toInt()
        if (hasExactAlarmPermission) {
            alarmManager!!.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                alarmTime,
                alarmPendingIntent!!
            )
        } else {
            alarmManager!!.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                alarmTime,
                alarmPendingIntent!!
            )
        }
    }

    private fun stopTrigger() {
        if(alarmManager != null && alarmPendingIntent != null) {
            Log.d(LOG_ID, "Stop trigger")
            alarmManager!!.cancel(alarmPendingIntent!!)
            alarmManager = null
            alarmPendingIntent = null
        }
    }

    fun triggerTest(alarmType: AlarmType, context: Context) {
        triggerDelay(TriggerAction.TEST_ALARM, alarmType, context, 3F)
    }

    private fun showNotification(alarmType: AlarmType, context: Context) {
        Channels.getNotificationManager(context).notify(
            curNotification,
            createNotification(context, alarmType)
        )
    }

    private fun createNotificationChannel(context: Context) {
        Log.v(LOG_ID, "createNotificationChannel called")

        val channel = Channels.getNotificationChannel(context, ChannelType.ALARM, false)

        val audioAttributes = AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .setUsage(AudioAttributes.USAGE_ALARM)
            .build()

        channel.setSound(getUri(CR.raw.silence, context), audioAttributes)
        channel.enableVibration(false)

        Channels.getNotificationManager(context).createNotificationChannel(channel)

        //TODO: delete
        Channels.deleteNotificationChannel(context, ChannelType.LOW_ALARM)
        Channels.deleteNotificationChannel(context, ChannelType.VERY_LOW_ALARM)
        Channels.deleteNotificationChannel(context, ChannelType.HIGH_ALARM)
        Channels.deleteNotificationChannel(context, ChannelType.VERY_HIGH_ALARM)
        Channels.deleteNotificationChannel(context, ChannelType.OBSOLETE_ALARM)
        Channels.getNotificationManager(context).deleteNotificationChannel("gdh_alarm_notification_01")

    }

    protected fun createSnoozeIntent(context: Context, snoozeTime: Long, noticationId: Int): PendingIntent {
        val intent = Intent(Constants.ALARM_SNOOZE_ACTION)
        intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
        intent.putExtra(Constants.ALARM_SNOOZE_EXTRA_TIME, snoozeTime)
        intent.putExtra(Constants.ALARM_SNOOZE_EXTRA_NOTIFY_ID, noticationId)
        intent.setPackage(context.packageName)
        return PendingIntent.getBroadcast(context, snoozeTime.toInt(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    protected fun createStopIntent(context: Context, noticationId: Int): PendingIntent {
        val intent = Intent(Constants.ALARM_STOP_NOTIFICATION_ACTION)
        intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
        intent.putExtra(Constants.ALARM_SNOOZE_EXTRA_NOTIFY_ID, noticationId)
        intent.setPackage(context.packageName)
        return PendingIntent.getBroadcast(context, 888, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    protected fun createSnoozeAction(context: Context, title: String, snoozeTime: Long, notificationId: Int): Notification.Action {
        return Notification.Action.Builder(null, title, createSnoozeIntent(context, snoozeTime, notificationId)).build()
    }

    protected fun createStopAction(context: Context, title: String, notificationId: Int): Notification.Action {
        return Notification.Action.Builder(null, title, createStopIntent(context, notificationId)).build()
    }

    abstract fun buildNotification(notificationBuilder: Notification.Builder, context: Context, alarmType: AlarmType)

    private fun createNotification(context: Context, alarmType: AlarmType): Notification? {
        Log.v(LOG_ID, "createNotification called for $alarmType")
        val channelId = getChannelId()
        val resId = getAlarmTextRes(alarmType)
        if (resId == null)
            return null

        val notificationBuilder = Notification.Builder(context, channelId)
            .setSmallIcon(CR.mipmap.ic_launcher)
            .setDeleteIntent(createStopIntent(context, getNotificationId(alarmType)))
            .setOnlyAlertOnce(false)
            .setAutoCancel(true)
            .setShowWhen(true)
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setWhen(ReceiveData.time)
            .setColorized(false)
            .setGroup("alarm")
            .setLocalOnly(false)
            .setStyle(Notification.DecoratedCustomViewStyle())
            .setContentTitle(context.getString(resId))
            .setContentText(ReceiveData.getClucoseAsString()  + " (Δ " + ReceiveData.getDeltaAsString() + ")")

            /*.setLargeIcon(BitmapUtils.getRateAsIcon())
            .addAction(createAction(context, context.getString(CR.string.snooze) + ": 60", 60L, getNotificationId(alarmType)))
            .addAction(createAction(context, "90", 90L, getNotificationId(alarmType)))
            .addAction(createAction(context, "120", 120L, getNotificationId(alarmType)))*/

        buildNotification(notificationBuilder, context, alarmType)
        startVibrationAndSound(alarmType, context)
        return notificationBuilder.build()
    }

    private fun startVibrationAndSound(alarmType: AlarmType, context: Context, reTrigger: Boolean = false) {
        if(!reTrigger) {
            val soundDelay = getSoundDelay(alarmType, context)
            Log.v(LOG_ID, "Start vibration and sound with $soundDelay seconds delay")
            if(soundDelay > 0) {
                vibrate(alarmType, context, true)
                if(getSound(alarmType, context) != null) {
                    triggerDelay(TriggerAction.START_ALARM_SOUND, alarmType, context, soundDelay.toFloat())
                    return
                }
                triggerDelay(TriggerAction.STOP_VIBRATION, alarmType, context, soundDelay.toFloat())
                return
            }
        }
        // else
        Thread {
            Thread.sleep(1500)
            if(curNotification > 0) {
                vibrate(alarmType, context, false)
                startSound(alarmType, context, false)
                checkRetrigger(context)
            }
        }.start()
    }


    open fun vibrate(alarmType: AlarmType, context: Context, repeat: Boolean = false, vibrateOnly: Boolean = false) {
        try {
            if (getRingerMode() >= AudioManager.RINGER_MODE_VIBRATE && curNotification > 0) {
                val vibratePattern = getVibrationPattern(alarmType) ?: return
                Log.d(LOG_ID, "start vibration for $alarmType - repeat: $repeat")
                vibrator.cancel()
                vibrator.vibrate(VibrationEffect.createWaveform(vibratePattern, if(repeat) 1 else -1))
            }
        } catch (ex: Exception) {
            Log.e(LOG_ID, "vibrate exception: " + ex)
        }
    }

    fun startSound(alarmType: AlarmType, context: Context, restartVibration: Boolean) {
        if (getRingerMode() >= AudioManager.RINGER_MODE_NORMAL && curNotification > 0) {
            val soundUri = getSound(alarmType, context)
            if (soundUri != null) {
                Log.d(LOG_ID, "Play ringtone $soundUri")
                ringtone = RingtoneManager.getRingtone(context, soundUri)
                val aa = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                ringtone!!.setAudioAttributes(aa)
                ringtone!!.play()
            }
        }
        if(restartVibration) {
            vibrate(alarmType, context, false)
        }
    }

    fun forceDnd(): Boolean {
        if(!Channels.getNotificationManager().isNotificationPolicyAccessGranted &&
            ( Channels.getNotificationManager().currentInterruptionFilter > NotificationManager.INTERRUPTION_FILTER_ALL ||
                    audioManager.ringerMode == AudioManager.RINGER_MODE_SILENT) ) {
            return true
        }
        return false
    }

    protected fun getRingerMode(): Int {
        if(forceDnd())
            return AudioManager.RINGER_MODE_NORMAL  // DnD is on and can not be changed, so do not change ringer mode
        if(Channels.getNotificationManager().currentInterruptionFilter > NotificationManager.INTERRUPTION_FILTER_ALL) {
            lastDndMode = Channels.getNotificationManager().currentInterruptionFilter
            Log.d(LOG_ID, "Disable DnD in level $lastDndMode")
            Channels.getNotificationManager()
                .setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
            Thread.sleep(100)
        }
        Log.d(LOG_ID, "Current ringer mode ${audioManager.ringerMode}")
        return audioManager.ringerMode
    }

    protected fun checkCreateSound(alarmType: AlarmType, context: Context) {
        try {
            Log.v(LOG_ID, "checkCreateSound called for force sound=$forceSound - vibration=$forceVibration - DnD=${Channels.getNotificationManager().currentInterruptionFilter} - ringmode=${audioManager.ringerMode}")
            lastRingerMode = -1
            lastDndMode = NotificationManager.INTERRUPTION_FILTER_UNKNOWN
            if (forceSound || forceVibration) {
                if (getRingerMode() < AudioManager.RINGER_MODE_NORMAL) {
                    val channelId = getChannelId()
                    val channel = Channels.getNotificationManager().getNotificationChannel(channelId)
                    Log.d(LOG_ID, "Channel prio=${channel.importance}")
                    if(channel.importance >= NotificationManager.IMPORTANCE_DEFAULT) { // notification supports sound
                        val soundMode = getSoundMode(alarmType, context)
                        var targetRingerMode = AudioManager.RINGER_MODE_SILENT
                        if(soundMode>SoundMode.SILENT) {
                            if (!forceSound && forceVibration) {
                                targetRingerMode = AudioManager.RINGER_MODE_VIBRATE
                            } else if (forceSound) {
                                targetRingerMode = soundMode.ringerMode
                            }
                        }
                        Log.d(LOG_ID, "Check force sound for soundMode=$soundMode - targetRinger=$targetRingerMode - currentRinger=${audioManager.ringerMode}")
                        if (targetRingerMode > audioManager.ringerMode ) {
                            lastRingerMode = audioManager.ringerMode
                            Log.d(LOG_ID, "Set cur ringer mode $lastRingerMode to $targetRingerMode")
                            audioManager.ringerMode = targetRingerMode
                        }
                    }
                }
            }
            /*
            if (soundLevel >= 0) {
                lastSoundLevel = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
                val level = minOf(soundLevel, getMaxSoundLevel())
                Log.d(LOG_ID, "Set cur sound level $lastSoundLevel to $level")
                audioManager.setStreamVolume(
                    AudioManager.STREAM_ALARM,
                    level,
                    0
                )
            }*/
        } catch (exc: Exception) {
            Log.e(LOG_ID, "checkCreateSound exception: " + exc.message.toString() )
        }
    }

    open fun getSoundMode(alarmType: AlarmType, context: Context): SoundMode {val channelId = getChannelId()
        val channel = Channels.getNotificationManager().getNotificationChannel(channelId)
        Log.d(LOG_ID, "Channel: prio=${channel.importance}")
        if(channel.importance >= NotificationManager.IMPORTANCE_DEFAULT) {
            if(getSound(alarmType, context) != null)
                return SoundMode.NORMAL
            return SoundMode.VIBRATE
        } else if(channel.importance == NotificationManager.IMPORTANCE_NONE)
            return SoundMode.OFF
        return SoundMode.SILENT
    }

    protected fun checkRecreateSound() {
        try {
            if(lastRingerMode >= 0 ) {
                Log.d(LOG_ID, "Reset ringer mode to $lastRingerMode")
                audioManager.ringerMode = lastRingerMode
                lastRingerMode = -1
            }
            if(lastDndMode != NotificationManager.INTERRUPTION_FILTER_UNKNOWN) {
                Log.d(LOG_ID, "Reset DnD mode to $lastDndMode")
                Channels.getNotificationManager().setInterruptionFilter(lastDndMode)
                lastDndMode = NotificationManager.INTERRUPTION_FILTER_UNKNOWN
            }
            /*
            if(lastSoundLevel >= 0) {
                Log.d(LOG_ID, "Reset sound level to $lastSoundLevel")
                audioManager.setStreamVolume(
                    AudioManager.STREAM_ALARM,
                    lastSoundLevel,
                    0
                )
                lastSoundLevel = -1
            }*/
        } catch (exc: Exception) {
            Log.e(LOG_ID, "checkCreateSound exception: " + exc.message.toString() )
        }
    }

    protected fun getNotificationId(alarmType: AlarmType): Int {
        return when(alarmType) {
            AlarmType.VERY_LOW -> VERY_LOW_NOTIFICATION_ID
            AlarmType.LOW -> LOW_NOTIFICATION_ID
            AlarmType.HIGH -> HIGH_NOTIFICATION_ID
            AlarmType.VERY_HIGH -> VERY_HIGH_NOTIFICATION_ID
            AlarmType.OBSOLETE -> OBSOLETE_NOTIFICATION_ID
            else -> -1
        }
    }

    fun getChannelId(): String {
        return ChannelType.ALARM.channelId
    }

    fun getAlarmSoundRes(alarmType: AlarmType): Int? {
        return when(alarmType) {
            AlarmType.VERY_LOW -> CR.raw.gdh_very_low_alarm
            AlarmType.LOW -> CR.raw.gdh_low_alarm
            AlarmType.HIGH -> CR.raw.gdh_high_alarm
            AlarmType.VERY_HIGH -> CR.raw.gdh_very_high_alarm
            AlarmType.OBSOLETE -> CR.raw.gdh_obsolete_alarm
            else -> null
        }
    }

    private fun getSound(alarmType: AlarmType, context: Context): Uri? {
        val sharedPref = context.getSharedPreferences(Constants.SHARED_PREF_TAG, Context.MODE_PRIVATE)
        val prefix = when(alarmType) {
            AlarmType.VERY_LOW -> Constants.SHARED_PREF_ALARM_VERY_LOW
            AlarmType.LOW -> Constants.SHARED_PREF_ALARM_LOW
            AlarmType.HIGH -> Constants.SHARED_PREF_ALARM_HIGH
            AlarmType.VERY_HIGH -> Constants.SHARED_PREF_ALARM_VERY_HIGH
            AlarmType.OBSOLETE -> Constants.SHARED_PREF_ALARM_OBSOLETE
            else -> ""
        }
        if(prefix.isNotEmpty() && sharedPref.getBoolean(prefix + "_use_custom_sound", false)) {
            val path = sharedPref.getString(prefix + "_custom_sound", "")
            if(path.isNullOrEmpty())
                return null
            return Uri.parse(path)
        }

        return getDefaultAlarm(alarmType, context)
    }


    fun getAlarmTextRes(alarmType: AlarmType): Int? {
        return when(alarmType) {
            AlarmType.VERY_LOW -> CR.string.very_low_alarm_text
            AlarmType.LOW -> CR.string.very_low_text
            AlarmType.HIGH -> CR.string.very_high_text
            AlarmType.VERY_HIGH -> CR.string.very_high_alarm_text
            AlarmType.OBSOLETE -> CR.string.obsolete_alarm_text
            else -> null
        }
    }

    protected fun getDefaultAlarm(alarmType: AlarmType, context: Context): Uri? {
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

    fun getVibrationPattern(alarmType: AlarmType): LongArray? {
        return when(alarmType) {
            AlarmType.VERY_LOW -> longArrayOf(0, 1000, 500, 1000, 500, 1000, 500, 1000, 500, 1000, 500, 1000)
            AlarmType.LOW -> longArrayOf(0, 700, 500, 700, 500, 700, 500, 700)
            AlarmType.HIGH -> longArrayOf(0, 500, 500, 500, 500, 500, 500, 500)
            AlarmType.VERY_HIGH -> longArrayOf(0, 800, 500, 800, 800, 600, 800, 800, 500, 800, 800, 600, 800)
            AlarmType.OBSOLETE -> longArrayOf(0, 600, 500, 500, 500, 600, 500, 500)
            else -> null
        }
    }

    fun getTriggerTime(alarmType: AlarmType, context: Context): Int {
        val sharedPref = context.getSharedPreferences(Constants.SHARED_PREF_TAG, Context.MODE_PRIVATE)
        return when(alarmType) {
            AlarmType.VERY_LOW -> sharedPref.getInt(Constants.SHARED_PREF_ALARM_VERY_LOW_RETRIGGER, 0)
            AlarmType.LOW -> sharedPref.getInt(Constants.SHARED_PREF_ALARM_LOW_RETRIGGER, 0)
            AlarmType.HIGH -> sharedPref.getInt(Constants.SHARED_PREF_ALARM_HIGH_RETRIGGER, 0)
            AlarmType.VERY_HIGH -> sharedPref.getInt(Constants.SHARED_PREF_ALARM_VERY_HIGH_RETRIGGER, 0)
            AlarmType.OBSOLETE -> sharedPref.getInt(Constants.SHARED_PREF_ALARM_OBSOLETE_RETRIGGER, 0)
            else -> 0
        }
    }

    fun getSoundDelay(alarmType: AlarmType, context: Context): Int {
        val sharedPref = context.getSharedPreferences(Constants.SHARED_PREF_TAG, Context.MODE_PRIVATE)
        return when(alarmType) {
            AlarmType.VERY_LOW -> sharedPref.getInt(Constants.SHARED_PREF_ALARM_VERY_LOW_SOUND_DELAY, 0)
            AlarmType.LOW -> sharedPref.getInt(Constants.SHARED_PREF_ALARM_LOW_SOUND_DELAY, 0)
            AlarmType.HIGH -> sharedPref.getInt(Constants.SHARED_PREF_ALARM_HIGH_SOUND_DELAY, 0)
            AlarmType.VERY_HIGH -> sharedPref.getInt(Constants.SHARED_PREF_ALARM_VERY_HIGH_SOUND_DELAY, 0)
            AlarmType.OBSOLETE -> sharedPref.getInt(Constants.SHARED_PREF_ALARM_OBSOLETE_SOUND_DELAY, 0)
            else -> 0
        }
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?) {
        try {
            Log.d(LOG_ID, "onSharedPreferenceChanged called for " + key)
            if (key == null) {
                onSharedPreferenceChanged(sharedPreferences, Constants.SHARED_PREF_ALARM_NOTIFICATION_ENABLED)
                onSharedPreferenceChanged(sharedPreferences, Constants.SHARED_PREF_ALARM_SNOOZE_ON_NOTIFICATION)
                onSharedPreferenceChanged(sharedPreferences, Constants.SHARED_PREF_ALARM_FORCE_SOUND)
                onSharedPreferenceChanged(sharedPreferences, Constants.SHARED_PREF_ALARM_FORCE_VIBRATION)
            } else {
                when(key) {
                    Constants.SHARED_PREF_ALARM_NOTIFICATION_ENABLED -> setEnabled(sharedPreferences.getBoolean(Constants.SHARED_PREF_ALARM_NOTIFICATION_ENABLED, enabled))
                    Constants.SHARED_PREF_ALARM_SNOOZE_ON_NOTIFICATION -> setAddSnooze(sharedPreferences.getBoolean(Constants.SHARED_PREF_ALARM_SNOOZE_ON_NOTIFICATION, addSnooze))
                    Constants.SHARED_PREF_ALARM_FORCE_SOUND -> forceSound = sharedPreferences.getBoolean(Constants.SHARED_PREF_ALARM_FORCE_SOUND, forceSound)
                    Constants.SHARED_PREF_ALARM_FORCE_VIBRATION -> forceVibration = sharedPreferences.getBoolean(Constants.SHARED_PREF_ALARM_FORCE_VIBRATION, forceVibration)
                }
            }
        } catch (exc: Exception) {
            Log.e(LOG_ID, "onSharedPreferenceChanged exception: " + exc.toString())
        }
    }

    open fun stopNotificationForRetrigger(): Boolean = false

    override fun OnNotifyData(context: Context, dataSource: NotifySource, extras: Bundle?) {
        try {
            Log.d(LOG_ID, "OnNotifyData called for $dataSource")
            when(dataSource) {
                NotifySource.ALARM_TRIGGER -> {
                    if (ReceiveData.forceAlarm)
                        triggerNotification(ReceiveData.getAlarmType(), context)
                }
                NotifySource.OBSOLETE_ALARM_TRIGGER -> {
                    triggerNotification(AlarmType.OBSOLETE, context)
                }
                NotifySource.ALARM_STATE_CHANGED -> {
                    initNotifier(context)
                }
                else -> Log.w(LOG_ID, "Unsupported source $dataSource")
            }
        } catch (exc: Exception) {
            Log.e(LOG_ID, "OnNotifyData exception: " + exc.toString())
        }
    }

    private fun isTriggerActive(): Boolean {
        return retriggerCount < 3 && retriggerTime > 0 && curAlarmTime > 0L && curNotification == getNotificationId(getAlarmType())
    }

    private fun getAlarmType(): AlarmType {
        if(curTestAlarmType != AlarmType.NONE)
            return curTestAlarmType
        return ReceiveData.getAlarmType()
    }

    private fun checkRetrigger(context: Context) {
        if(isTriggerActive()) {
            val elapsedTimeMin = Utils.round((System.currentTimeMillis() - curAlarmTime).toFloat()/60000, 0, RoundingMode.DOWN).toLong()
            val nextTriggerMin = (elapsedTimeMin/retriggerTime)*retriggerTime + retriggerTime
            val nextAlarmTime = curAlarmTime + TimeUnit.MINUTES.toMillis(nextTriggerMin)
            val timeInMillis = nextAlarmTime - System.currentTimeMillis()
            Log.d(LOG_ID,
                "elapsed: $elapsedTimeMin nextTrigger: $nextTriggerMin - retrigger-time: $retriggerTime - in $timeInMillis ms"
            )
            Log.i(LOG_ID, "Retrigger sound after $nextTriggerMin minute(s) at ${DateFormat.getTimeInstance(
                DateFormat.DEFAULT).format(nextAlarmTime)} (alarm from ${DateFormat.getTimeInstance(DateFormat.DEFAULT).format(Date(curAlarmTime))})")
            retriggerCount++
            triggerDelay(TriggerAction.RETRIGGER_SOUND, getAlarmType(), context, (timeInMillis.toFloat()/1000))
        }
    }

    fun initNotifier(context: Context? = null) {
        val requireConext = context ?: GlucoDataService.context!!
        val newActive = isAlarmActive(requireConext)
        if(alarmNotificationActive != newActive) {
            Log.i(LOG_ID, "Change alarm notification active to ${newActive}")
            alarmNotificationActive = newActive
            val filter = mutableSetOf(NotifySource.ALARM_STATE_CHANGED)
            if(alarmNotificationActive) {
                filter.add(NotifySource.ALARM_TRIGGER)
                filter.add(NotifySource.OBSOLETE_ALARM_TRIGGER)
            }
            InternalNotifier.addNotifier(requireConext, this, filter )
        }
    }

    fun hasFullscreenPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
            return Channels.getNotificationManager().canUseFullScreenIntent()
        return true
    }

    open fun canReshowNotification(): Boolean = true

    fun handleTimerAction(context: Context, action: String, extras: Bundle?) {
        Log.d(LOG_ID, "handleTimerAction called for ${action} with extras: ${Utils.dumpBundle(extras)}")
        if(extras?.containsKey(Constants.ALARM_NOTIFICATION_EXTRA_ALARM_TYPE) == true && instance != null) {
            val alarmType = AlarmType.fromIndex(extras.getInt(Constants.ALARM_NOTIFICATION_EXTRA_ALARM_TYPE, ReceiveData.getAlarmType().ordinal))
            when(TriggerAction.valueOf(action)) {
                TriggerAction.TEST_ALARM -> {
                    executeTest(alarmType, context)
                    GlucoDataService.sendCommand(Command.TEST_ALARM, extras)
                }
                TriggerAction.START_ALARM_SOUND -> {
                    startSound(alarmType, context, true)
                    checkRetrigger(context)
                }
                TriggerAction.STOP_VIBRATION -> {
                    stopVibrationAndSound()
                    checkRetrigger(context)
                }
                TriggerAction.RETRIGGER_SOUND -> {
                    if(canReshowNotification())
                        showNotification(alarmType, context)
                    startVibrationAndSound(alarmType, context, true)
                }
            }
        }
    }

}

class AlarmIntentReceiver: BroadcastReceiver() {
    private val LOG_ID = "GDH.AlarmIntentReceiver"
    override fun onReceive(context: Context, intent: Intent) {
        try {
            Log.v(LOG_ID, "onReceive called for ${intent.action} with extras: ${Utils.dumpBundle(intent.extras)}")
            AlarmNotificationBase.instance!!.handleTimerAction(context, intent.action!!, intent.extras)
        } catch (exc: Exception) {
            Log.e(LOG_ID, "onReceive exception: " + exc.toString())
        }
    }
}