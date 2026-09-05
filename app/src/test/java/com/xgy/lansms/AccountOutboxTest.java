package com.xgy.lansms;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

/** Pure tests for the account upload queue contract. */
public class AccountOutboxTest {
    @Test public void retryScheduleUsesTheRequestedDelaysAndCapsAtFiveMinutes() {
        assertEquals(0L, AccountOutboxStore.retryDelayMs(0));
        assertEquals(2_000L, AccountOutboxStore.retryDelayMs(1));
        assertEquals(5_000L, AccountOutboxStore.retryDelayMs(2));
        assertEquals(15_000L, AccountOutboxStore.retryDelayMs(3));
        assertEquals(30_000L, AccountOutboxStore.retryDelayMs(4));
        assertEquals(60_000L, AccountOutboxStore.retryDelayMs(5));
        assertEquals(300_000L, AccountOutboxStore.retryDelayMs(6));
        assertEquals(300_000L, AccountOutboxStore.retryDelayMs(99));
    }

    @Test public void payloadUsesTheSnakeCaseAccountApiFields() throws Exception {
        JSONObject payload = AccountOutboxStore.payload("m-1", "10086", "验证码 123456", 123L);
        assertEquals("m-1", payload.getString("client_message_id"));
        assertEquals("10086", payload.getString("sender"));
        assertEquals("验证码 123456", payload.getString("body"));
        assertEquals(123L, payload.getLong("received_at"));
        assertFalse(payload.has("id"));
    }

    @Test public void duplicateClientIdsAreDetectedWithoutComparingMessageBody() {
        AccountOutboxStore.Entry original = new AccountOutboxStore.Entry(
                "same-id", "10086", "first", 1L, 0, 0L, "");
        AccountOutboxStore.Entry changedBody = new AccountOutboxStore.Entry(
                "same-id", "95588", "second", 2L, 0, 0L, "");
        assertTrue(AccountOutboxStore.containsId(Collections.singletonList(original), "same-id"));
        assertTrue(AccountOutboxStore.containsId(Arrays.asList(original, changedBody), "same-id"));
        assertFalse(AccountOutboxStore.containsId(Collections.singletonList(original), "other-id"));
    }

    @Test public void accountQueueOnlyKeepsTheJobAliveForAnAvailableAccount() {
        assertTrue(CloudSyncJobService.shouldReschedule(true, 0, false, 1, true));
        assertFalse(CloudSyncJobService.shouldReschedule(true, 0, false, 1, false));
        assertTrue(CloudSyncJobService.shouldReschedule(true, -1, true, 0, false));
    }

    @Test public void retryTimerReplacesOnlyWhenTheNewDeadlineIsEarlier() {
        assertTrue(AccountApi.shouldReplaceRetryTimer(-1L, 2_000L));
        assertFalse(AccountApi.shouldReplaceRetryTimer(2_000L, 2_000L));
        assertFalse(AccountApi.shouldReplaceRetryTimer(2_000L, 5_000L));
        assertTrue(AccountApi.shouldReplaceRetryTimer(5_000L, 2_000L));
    }
}
