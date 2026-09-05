package com.xgy.lansms;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

/** Native Android retry worker; no third-party background scheduler is required. */
public class CloudSyncJobService extends JobService {
    private static final int PERIODIC_JOB_ID = 5812301;
    private static final int IMMEDIATE_JOB_ID = 5812302;

    @Override public boolean onStartJob(JobParameters params) {
        CloudRelay.executor().execute(() -> {
            boolean needsReschedule = true;
            try {
                // Flush the account queue first so a slow legacy relay cannot
                // delay the current MsgDock history path. Keep the old relay
                // retry independent and durable as before.
                boolean accountSyncSuccess = AccountApi.flushOutbox(getApplicationContext());
                boolean legacySyncSuccess = true;
                try {
                    CloudRelay.flushOutbox(getApplicationContext());
                } catch (RuntimeException e) {
                    legacySyncSuccess = false;
                    android.util.Log.w("XgyLanSms", "旧云端 outbox 刷新失败；账号云端已独立处理", e);
                }
                boolean deviceSyncSuccess = DeviceBackupManager.retryFromJob(getApplicationContext());
                needsReschedule = !legacySyncSuccess || shouldReschedule(deviceSyncSuccess,
                        CloudOutboxStore.count(getApplicationContext()), accountSyncSuccess,
                        AccountOutboxStore.count(getApplicationContext()),
                        AccountStore.hasAccount(getApplicationContext()));
            } catch (RuntimeException ignored) {
                // A transient worker failure must use JobScheduler's configured
                // exponential backoff instead of being marked complete.
            } finally { jobFinished(params, needsReschedule); }
        });
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) { return true; }

    static boolean shouldReschedule(boolean deviceSyncSuccess, int pendingOutboxCount) {
        return !deviceSyncSuccess || pendingOutboxCount != 0;
    }

    static boolean shouldReschedule(boolean deviceSyncSuccess, int pendingOutboxCount,
                                    boolean accountSyncSuccess, int pendingAccountOutboxCount) {
        return !deviceSyncSuccess || pendingOutboxCount != 0
                || !accountSyncSuccess || pendingAccountOutboxCount != 0;
    }

    static boolean shouldReschedule(boolean deviceSyncSuccess, int pendingOutboxCount,
                                    boolean accountSyncSuccess, int pendingAccountOutboxCount,
                                    boolean accountAvailable) {
        return !deviceSyncSuccess || pendingOutboxCount != 0
                || (accountAvailable && (!accountSyncSuccess || pendingAccountOutboxCount != 0));
    }

    public static void schedule(Context context) {
        Context app = context.getApplicationContext();
        JobScheduler scheduler = (JobScheduler) app.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (scheduler == null) return;
        try {
            ComponentName component = new ComponentName(app, CloudSyncJobService.class);
            JobInfo periodic = new JobInfo.Builder(PERIODIC_JOB_ID, component)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(15L * 60L * 1000L)
                    .setPersisted(true)
                    .build();
            scheduler.schedule(periodic);
            JobInfo immediate = new JobInfo.Builder(IMMEDIATE_JOB_ID, component)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setMinimumLatency(0L)
                    .setOverrideDeadline(60_000L)
                    .setBackoffCriteria(30_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                    .setPersisted(true)
                    .build();
            scheduler.schedule(immediate);
        } catch (RuntimeException e) {
            // The in-process retry remains available when a vendor ROM rejects
            // a persisted job or temporarily limits the scheduler.
            android.util.Log.w("MsgDock", "账号 JobScheduler 安排失败", e);
        }
    }
}
