package com.spoonwork.timetracker

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.Build

/** 위젯 새로고침: 시스템이 인터넷을 보장해주는 작업으로 실행 */
class RefreshJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Thread {
            TrackerWidget.fetchNow(applicationContext)
            jobFinished(params, false)
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true

    companion object {
        fun schedule(ctx: Context) {
            val js = ctx.getSystemService(JobScheduler::class.java) ?: return
            val b = JobInfo.Builder(1001, ComponentName(ctx, RefreshJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            if (Build.VERSION.SDK_INT >= 31) b.setExpedited(true)
            try {
                js.schedule(b.build())
            } catch (e: Exception) {
                // 빠른 작업 한도 초과 등: 일반 작업으로 다시
                js.schedule(
                    JobInfo.Builder(1001, ComponentName(ctx, RefreshJob::class.java))
                        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).build()
                )
            }
        }
    }
}
