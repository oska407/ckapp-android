package com.ckapp.checkin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 方案B 到点接收（AlarmManager setAndAllowWhileIdle 唤醒，息屏/Doze 下也能到点）。
 * 只做转交：写回执 + 起 RingPlayService 播放（RingGuard.onAlarmFired）。
 * 进程若已被系统回收，本 Receiver 会先重建进程，RingGuard.init 幂等可保平安。
 */
class RingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != RingGuard.ACTION_FIRE) return
        RingGuard.init(context)   // 进程被闹钟拉起时 init 尚未执行，这里补（幂等）
        val at = intent.getLongExtra(RingGuard.EXTRA_AT, 0L)
        val bk = intent.getStringExtra(RingGuard.EXTRA_BK) ?: ""
        val sound = intent.getStringExtra(RingGuard.EXTRA_SOUND) ?: ""
        RingGuard.onAlarmFired(at, bk, sound)
    }
}
