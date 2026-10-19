package app.kelimo.learn

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.app.Notification
import app.kelimo.KelimoApp
import app.kelimo.MainActivity
import app.kelimo.R
import app.kelimo.ui.I18n
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** Local daily reminder. Inexact alarm, no exact-alarm permission, nothing leaves the phone. */
object Reminders {
    private const val CHANNEL = "daily"

    fun createChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Kelimo", NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun pending(ctx: Context) = PendingIntent.getBroadcast(
        ctx, 1, Intent(ctx, ReminderReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun schedule(ctx: Context, hour: Int) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        am.cancel(pending(ctx))
        if (hour !in 0..23) return
        var at = LocalDateTime.now().withHour(hour).withMinute(0).withSecond(0)
        if (at.isBefore(LocalDateTime.now())) at = at.plusDays(1)
        val millis = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        am.setInexactRepeating(AlarmManager.RTC_WAKEUP, millis, AlarmManager.INTERVAL_DAY, pending(ctx))
    }

    fun notifyNow(ctx: Context) {
        if (ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val s = KelimoApp.instance.store.value
        if (s.stats.xpOn(LocalDate.now()) >= s.profile.dailyGoalXp) return
        val t = I18n.table(s.profile.ui)
        val streak = s.stats.streak()
        val body = if (streak > 0) t("remind.streak").replace("%d", streak.toString()) else t("remind.body")
        val open = PendingIntent.getActivity(ctx, 2, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_fg)
            .setContentTitle(t("remind.title"))
            .setContentText(body)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(1, n)
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Reminders.schedule(context, KelimoApp.instance.store.value.settings.reminderHour)
        } else {
            Reminders.notifyNow(context)
        }
    }
}
