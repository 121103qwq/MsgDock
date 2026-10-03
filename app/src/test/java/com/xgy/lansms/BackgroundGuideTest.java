package com.xgy.lansms;

import org.junit.Test;
import static org.junit.Assert.*;

public class BackgroundGuideTest {
    private String selected(String manufacturer, String brand) {
        return BackgroundGuideContent.GUIDES[BackgroundGuideContent.indexFor(manufacturer, brand)].id;
    }

    @Test public void subBrandWinsOverSharedManufacturer() {
        assertEquals("honor", selected("HUAWEI", "HONOR"));
        assertEquals("realme", selected("OPPO", "realme"));
        assertEquals("xiaomi", selected("Xiaomi", "POCO"));
        assertEquals("vivo", selected("vivo", "iQOO"));
        assertEquals("oppo", selected("OPPO", "OnePlus"));
    }

    @Test public void manufacturerFallbackIsCaseAndLocaleIndependent() {
        assertEquals("xiaomi", selected(" Xiaomi ", "unknown"));
        assertEquals("samsung", selected("SAMSUNG", null));
        assertEquals("asus", selected("asus", "unknown"));
        assertEquals("android", selected("Google", "google"));
    }

    @Test public void unknownDeviceUsesHonestGenericGuide() {
        assertEquals("other", selected(null, null));
        assertEquals("other", selected("nubia", "REDMAGIC"));
        assertEquals("other", selected("Meizu", "meizu"));
        // Do not infer a vendor from arbitrary custom-ROM identifiers.
        assertEquals("other", selected("not-samsung", "custom-xiaomi-rom"));
    }
}
