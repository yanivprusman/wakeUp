package com.automatelinux.wakeUp.alarm

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * What you must do to make it stop. The whole point of this app: the stock alarm ends on a
 * swipe, which a half-asleep hand performs perfectly well. A challenge costs consciousness.
 */
enum class Challenge {
    /** Any tap stops it. Here so the app can be honest about what it is giving up. */
    NONE,

    /** Two-digit sum. Offline, instant, and impossible to do with your eyes shut. */
    MATH,

    /** Say the phrase out loud. Nothing wakes a person like having to form words. */
    SPEAK,

    /** Shake the phone hard, repeatedly. For mornings when speaking would wake someone else. */
    SHAKE,
}

/**
 * One alarm.
 *
 * [days] holds [Calendar.DAY_OF_WEEK] values; empty means "once, at the next occurrence".
 * [maxGainMb] is the LoudnessEnhancer target in millibels — gain ON TOP of a maxed alarm
 * stream, which is the only way to get louder than the volume slider allows.
 */
data class Alarm(
    val id: Int,
    val hour: Int,
    val minute: Int,
    val days: Set<Int> = emptySet(),
    val enabled: Boolean = true,
    val label: String = "",
    val challenge: Challenge = Challenge.MATH,
    /**
     * How many correct answers in a row before Stop unlocks. One is a coin flip you can win
     * asleep; two means the first was not luck.
     */
    val requiredCorrect: Int = 2,
    val vibrate: Boolean = true,
    val flash: Boolean = true,
    /** Play Claude's cached briefing over the tone. See [VoiceCache]. */
    val voice: Boolean = true,
) {
    val repeats: Boolean get() = days.isNotEmpty()

    /** Next epoch-millis this alarm should fire, strictly in the future. */
    fun nextTrigger(now: Long = System.currentTimeMillis()): Long {
        val c = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (days.isEmpty()) {
            if (c.timeInMillis <= now) c.add(Calendar.DAY_OF_YEAR, 1)
            return c.timeInMillis
        }
        // Walk forward at most a full week; today counts only if it hasn't passed.
        for (i in 0..7) {
            if (c.timeInMillis > now && c.get(Calendar.DAY_OF_WEEK) in days) return c.timeInMillis
            c.add(Calendar.DAY_OF_YEAR, 1)
        }
        return c.timeInMillis
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("hour", hour); put("minute", minute)
        put("days", JSONArray().also { a -> days.forEach { a.put(it) } })
        put("enabled", enabled); put("label", label)
        put("challenge", challenge.name); put("requiredCorrect", requiredCorrect)
        put("vibrate", vibrate); put("flash", flash); put("voice", voice)
    }

    companion object {
        fun fromJson(o: JSONObject): Alarm {
            val daysArr = o.optJSONArray("days") ?: JSONArray()
            val days = buildSet { for (i in 0 until daysArr.length()) add(daysArr.getInt(i)) }
            return Alarm(
                id = o.getInt("id"),
                hour = o.getInt("hour"),
                minute = o.getInt("minute"),
                days = days,
                enabled = o.optBoolean("enabled", true),
                label = o.optString("label", ""),
                challenge = runCatching { Challenge.valueOf(o.optString("challenge", "MATH")) }
                    .getOrDefault(Challenge.MATH),
                requiredCorrect = o.optInt("requiredCorrect", 2),
                vibrate = o.optBoolean("vibrate", true),
                flash = o.optBoolean("flash", true),
                voice = o.optBoolean("voice", true),
            )
        }
    }
}

/**
 * Alarms on disk. SharedPreferences + JSON: an alarm list is a handful of rows read at boot
 * and written when you touch a switch — a database would be ceremony with no payoff, and this
 * file is read from a BroadcastReceiver where a migration that fails is a morning you sleep
 * through.
 */
object AlarmStore {
    private const val PREFS = "wakeup.alarms"
    private const val KEY = "alarms"

    fun all(context: Context): List<Alarm> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                runCatching { Alarm.fromJson(arr.getJSONObject(i)) }.getOrNull()?.let { add(it) }
            }
        }
    }

    fun get(context: Context, id: Int): Alarm? = all(context).firstOrNull { it.id == id }

    fun save(context: Context, alarms: List<Alarm>) {
        val arr = JSONArray().also { a -> alarms.forEach { a.put(it.toJson()) } }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }

    fun upsert(context: Context, alarm: Alarm) {
        val list = all(context).filterNot { it.id == alarm.id } + alarm
        save(context, list.sortedWith(compareBy({ it.hour }, { it.minute })))
    }

    fun delete(context: Context, id: Int) = save(context, all(context).filterNot { it.id == id })

    fun nextId(context: Context): Int = (all(context).maxOfOrNull { it.id } ?: 0) + 1
}
