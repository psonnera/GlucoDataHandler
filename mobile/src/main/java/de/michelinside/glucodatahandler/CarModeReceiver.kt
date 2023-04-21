package de.michelinside.glucodatahandler

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import androidx.car.app.connection.CarConnection
import androidx.car.app.notification.CarAppExtender
import androidx.car.app.notification.CarNotificationManager
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.graphics.drawable.IconCompat
import de.michelinside.glucodatahandler.common.*
import java.util.*


object CarModeReceiver: ReceiveDataInterface {
    private val LOG_ID = "GlucoDataHandler.CarModeReceiver"
    private val CHANNEL_ID = "GlucoDataNotify_Car"
    private val CHANNEL_NAME = "Notification for Android Auto"
    private val NOTIFICATION_ID = 789
    private var init = false
    @SuppressLint("StaticFieldLeak")
    private lateinit var notificationMgr: CarNotificationManager
    private var show_notification = true
    private var car_connected = false

    var enable_notification : Boolean get() {
        return show_notification
    }
    set(value) {
        show_notification = value
        Log.d(LOG_ID, "show_notification set to " + show_notification.toString())
    }


    private fun createNotificationChannel(context: Context) {
        notificationMgr = CarNotificationManager.from(context)
        val notificationChannel = NotificationChannelCompat.Builder(
            CHANNEL_ID,
            NotificationManager.IMPORTANCE_HIGH
        )
        notificationChannel.setSound(null, null)   // silent
        notificationMgr.createNotificationChannel(notificationChannel.setName(CHANNEL_NAME).build())
    }

    fun addNotification(context: Context) {
        try {
            if(!init) {
                Log.d(LOG_ID, "addNotification called")
                val sharedPref = context.getSharedPreferences(Constants.SHARED_PREF_TAG, Context.MODE_PRIVATE)
                enable_notification = sharedPref.getBoolean(Constants.SHARED_PREF_CAR_NOTIFICATION, enable_notification)
                createNotificationChannel(context)
                CarConnection(context).type.observeForever(::onConnectionStateUpdated)
                init = true
            }
        } catch (exc: Exception) {
            Log.e(LOG_ID, "init exception: " + exc.message.toString() )
        }
    }

    fun remNotification(context: Context) {
        try {
            if (init) {
                Log.d(LOG_ID, "remNotification called")
                CarConnection(context).type.removeObserver(::onConnectionStateUpdated)
                init = false
            }
        } catch (exc: Exception) {
            Log.e(LOG_ID, "init exception: " + exc.message.toString())
        }
    }

    private fun onConnectionStateUpdated(connectionState: Int) {
        try {
            val message = when(connectionState) {
                CarConnection.CONNECTION_TYPE_NOT_CONNECTED -> "Not connected to a head unit"
                CarConnection.CONNECTION_TYPE_NATIVE -> "Connected to Android Automotive OS"
                CarConnection.CONNECTION_TYPE_PROJECTION -> "Connected to Android Auto"
                else -> "Unknown car connection type"
            }
            Log.d(LOG_ID, "onConnectionStateUpdated: " + message + " (" + connectionState.toString() + ")")
            if (connectionState == CarConnection.CONNECTION_TYPE_NOT_CONNECTED)  {
                Log.d(LOG_ID, "Exited Car Mode")
                cancelNotification()
                car_connected = false
                ReceiveData.remNotifier(this)
            } else {
                Log.d(LOG_ID, "Entered Car Mode")
                car_connected = true
                ReceiveData.addNotifier(this, mutableSetOf(
                    ReceiveDataSource.BROADCAST,
                    ReceiveDataSource.MESSAGECLIENT))
                if(!ReceiveData.isObsolete() && GlucoDataService.context != null)
                    showNotification(GlucoDataService.context!!)
            }
        } catch (exc: Exception) {
            Log.e(LOG_ID, "OnReceiveData exception: " + exc.message.toString() )
        }
    }

    override fun OnReceiveData(context: Context, dataSource: ReceiveDataSource, extras: Bundle?) {
        Log.d(LOG_ID, "OnReceiveData called")
        try {
            showNotification(context)
        } catch (exc: Exception) {
            Log.e(LOG_ID, "OnReceiveData exception: " + exc.message.toString() )
        }
    }

    fun cancelNotification() {
        notificationMgr.cancel(NOTIFICATION_ID)  // remove notification
    }

    fun showNotification(context: Context) {
        try {
            if (enable_notification && car_connected) {
                Log.d(LOG_ID, "showNotification called")
                val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setLargeIcon(getRateAsIcon())
                    .setContentTitle("Delta: " + ReceiveData.getDeltaAsString())
                    .setContentText("Delta: " + ReceiveData.getDeltaAsString())
                    .setWhen(ReceiveData.time)
                    .setStyle(createMessageStyle())
                    .addAction(createReplyAction(context))
                    .addAction(createDismissAction(context))
                    .setSilent(true)
                    .extend (
                        CarAppExtender.Builder()
                            .setImportance(NotificationManager.IMPORTANCE_HIGH)
                            .build()
                    )
                notificationMgr.notify(NOTIFICATION_ID, builder)
            }
        } catch (exc: Exception) {
            Log.e(LOG_ID, "showNotification exception: " + exc.toString() )
        }
    }
/*
    fun getGlucoseAsIcon(): Bitmap? {
        return Utils.textToBitmap(ReceiveData.getClucoseAsString(), ReceiveData.getClucoseColor(), false, ReceiveData.isObsolete(300) && !ReceiveData.isObsolete(), 300, 300)
    }
*/
    fun getRateAsIcon(): Bitmap? {
        if (ReceiveData.isObsolete(300))
            return Utils.textToBitmap("?", Color.GRAY, false)
        return Utils.rateToBitmap(ReceiveData.rate, ReceiveData.getClucoseColor(), resizeFactor = 0.75F)
    }

    private fun createMessageStyle(): NotificationCompat.MessagingStyle {
        val person = Person.Builder()
            .setIcon(IconCompat.createWithBitmap(getRateAsIcon()!!))
            .setName(ReceiveData.getClucoseAsString())
            .setImportant(true)
            .build()
        val messagingStyle = NotificationCompat.MessagingStyle(person)
        messagingStyle.conversationTitle = ReceiveData.getClucoseAsString()
        messagingStyle.isGroupConversation = false
        messagingStyle.addMessage("Delta: " + ReceiveData.getDeltaAsString(), System.currentTimeMillis(), person)
        return messagingStyle
    }

    private fun createReplyAction(context: Context): NotificationCompat.Action {
        val remoteInputWear =
            RemoteInput.Builder("extra_voice_reply").setLabel("Reply")
                .build()
        val intent = Intent("DoNothing") //new Intent(this, PopupReplyReceiver.class);
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            1,
            intent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Action.Builder(R.mipmap.ic_launcher, "Reply", pendingIntent)
            .setAllowGeneratedReplies(true)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            .addRemoteInput(remoteInputWear)
            .build()
    }

    private fun createDismissAction(context: Context): NotificationCompat.Action {
        val intent = Intent("DoNothing") //new Intent(this, DismissReceiver.class);
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            2,
            intent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Action.Builder(
            R.mipmap.ic_launcher,
            "Mark as read",
            pendingIntent
        )
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
            .setShowsUserInterface(false)
            .build()
    }
}