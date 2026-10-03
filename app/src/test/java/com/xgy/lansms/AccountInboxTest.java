package com.xgy.lansms;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import static org.junit.Assert.*;

public class AccountInboxTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    private JSONObject row(long seq) throws Exception {
        return new JSONObject().put("seq", seq).put("client_message_id", "uuid-" + seq)
            .put("sender", "10086").put("body", "验证码 583921").put("received_at", 123L)
            .put("source_device", new JSONObject().put("id", "phone-a").put("name", "K70"));
    }

    @Test public void conversionKeepsProtocolIdAndAccountScope() throws Exception {
        JSONObject sms = AccountApi.accountMessage("user-a", "phone-b", row(7));
        assertEquals("account:user-a:7", sms.getString("id"));
        assertEquals("uuid-7", CloudInboxStore.deliveryId(sms));
        assertEquals("验证码 583921", sms.getString("text"));
        assertEquals("K70", sms.getString("device"));
        assertFalse(sms.getBoolean("silent"));
        assertTrue(AccountApi.accountMessage("user-a", "phone-a", row(7)).getBoolean("silent"));
    }

    @Test public void revokedSourceStillHasUsableMessage() throws Exception {
        JSONObject source = row(1).put("source_device", JSONObject.NULL);
        assertEquals("已移除设备", AccountApi.accountMessage("u", "d", source).getString("device"));
    }

    @Test public void emptyPageAndSparseIncreasingSequencesAreValid() throws Exception {
        AccountApi.validatePage(new JSONArray(), 20);
        AccountApi.validatePage(new JSONArray().put(row(21)).put(row(99)), 20);
    }

    @Test(expected = IllegalArgumentException.class) public void duplicateCursorCannotBeCommitted() throws Exception {
        AccountApi.validatePage(new JSONArray().put(row(5)).put(row(5)), 0);
    }

    @Test(expected = IllegalArgumentException.class) public void oldCursorCannotBeCommitted() throws Exception {
        AccountApi.validatePage(new JSONArray().put(row(4)), 5);
    }

    @Test public void accountSwitchAndOptOutHidePendingButKeepLegacy() throws Exception {
        JSONObject sms = AccountApi.accountMessage("a", "receiver", row(1));
        assertTrue(CloudInboxStore.accountVisible(sms, "a", true));
        assertFalse(CloudInboxStore.accountVisible(sms, "b", true));
        assertFalse(CloudInboxStore.accountVisible(sms, "", true));
        assertFalse(CloudInboxStore.accountVisible(sms, "a", false));
        assertTrue(CloudInboxStore.accountVisible(new JSONObject().put("id", "lan"), "", false));
    }

    @Test public void replayIsIdempotentAndHistoryIsAccountScoped() throws Exception {
        File dir = temp.newFolder();
        JSONObject a = AccountApi.accountMessage("a", "r", row(1));
        assertTrue(CloudInboxStore.acceptAccount(dir, a));
        assertTrue(CloudInboxStore.acceptAccount(dir, a));
        assertTrue(CloudInboxStore.acceptAccount(dir, AccountApi.accountMessage("b", "r", row(1))));
        assertEquals(1, CloudInboxStore.localHistory(dir, "a", 200).size());
        assertEquals(1, CloudInboxStore.localHistory(dir, "b", 200).size());
        assertTrue(CloudInboxStore.localHistory(dir, "", 200).isEmpty());
    }

    @Test public void historySurvivesMultiplePagesAndReopensNewestFirst() throws Exception {
        File dir = temp.newFolder();
        for (int i = 1; i <= 205; i++)
            assertTrue(CloudInboxStore.acceptAccount(dir, AccountApi.accountMessage("a", "r", row(i))));
        List<JSONObject> recent = CloudInboxStore.localHistory(new File(dir.getPath()), "a", 200);
        assertEquals(200, recent.size());
        assertEquals(205, recent.get(0).getLong("seq"));
        assertEquals(6, recent.get(199).getLong("seq"));
        assertEquals(205, CloudInboxStore.localHistory(dir, "a", 300).size());
    }

    @Test public void crashTruncatedTailDoesNotSwallowNextMessage() throws Exception {
        File dir = temp.newFolder();
        Files.write(new File(dir, "cloud-inbox.jsonl").toPath(), "{\"id\":\"partial".getBytes(StandardCharsets.UTF_8));
        assertTrue(CloudInboxStore.acceptAccount(dir, AccountApi.accountMessage("a", "r", row(1))));
        assertEquals(1, CloudInboxStore.localHistory(dir, "a", 200).size());
    }

    @Test public void localHistoryIncludesLanAndPairedCloudWithoutAccountLeakage() throws Exception {
        File dir = temp.newFolder();
        File inbox = new File(dir, "cloud-inbox.jsonl");
        JSONObject lan = new JSONObject().put("id", "uuid-1").put("source", "lan").put("text", "LAN copy");
        JSONObject paired = new JSONObject().put("id", "paired").put("source", "cloud").put("text", "paired copy");
        JSONObject a = AccountApi.accountMessage("a", "r", row(1).put("body", "private A"));
        JSONObject b = AccountApi.accountMessage("b", "r", row(1).put("body", "private B"));
        String original = lan + "\n" + paired + "\n" + a + "\n" + b + "\n";
        Files.writeString(inbox.toPath(), original, StandardCharsets.UTF_8);

        List<JSONObject> loggedOut = CloudInboxStore.localHistory(dir, "", 200);
        assertEquals(2, loggedOut.size());
        assertEquals("paired copy", loggedOut.get(0).getString("text"));
        assertEquals("LAN copy", loggedOut.get(1).getString("text"));
        List<JSONObject> forA = CloudInboxStore.localHistory(dir, "a", 200);
        assertEquals(2, forA.size()); // LAN/account copies of the same delivery appear once.
        assertEquals("private A", forA.get(0).getString("text"));
        assertEquals("private B", CloudInboxStore.localHistory(dir, "b", 200).get(0).getString("text"));
        assertEquals(original, Files.readString(inbox.toPath(), StandardCharsets.UTF_8));
    }

    @Test public void localHistoryLimitCountsUniqueVisibleDeliveries() throws Exception {
        File dir = temp.newFolder();
        String rows = "{\"id\":\"one\",\"text\":\"old\"}\n"
            + "{\"id\":\"two\"}\n{\"id\":\"one\",\"text\":\"latest\"}\n"
            + "{\"id\":\"three\"}\n{\"id\":\"hidden\",\"accountUserId\":\"other\"}\n";
        Files.writeString(new File(dir, "cloud-inbox.jsonl").toPath(), rows, StandardCharsets.UTF_8);
        List<JSONObject> recent = CloudInboxStore.localHistory(dir, "", 2);
        assertEquals(2, recent.size());
        assertEquals("three", recent.get(0).getString("id"));
        assertEquals("latest", recent.get(1).getString("text"));
        assertTrue(CloudInboxStore.localHistory(dir, "", 0).isEmpty());
    }

    @Test public void localHistorySkipsDamagedAndUnidentifiedRows() throws Exception {
        File dir = temp.newFolder();
        Files.writeString(new File(dir, "cloud-inbox.jsonl").toPath(),
            "not-json\n{}\n{\"id\":\"valid\"}\n{\"id\":", StandardCharsets.UTF_8);
        assertEquals("valid", CloudInboxStore.localHistory(dir, "", 200).get(0).getString("id"));
        assertEquals(1, CloudInboxStore.localHistory(dir, "", 200).size());
    }

    @Test public void persistenceFailureDoesNotAcknowledgeMessage() throws Exception {
        File nonDirectory = temp.newFile();
        assertFalse(CloudInboxStore.acceptAccount(nonDirectory, AccountApi.accountMessage("a", "r", row(1))));
    }
}
