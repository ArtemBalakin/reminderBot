package com.example.reminderbot.util;

import org.junit.jupiter.api.Test;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.net.URLEncoder;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

class TelegramInitDataValidatorTest {
    private static final String TOKEN = "123:testing";

    private String signed(long authDate) throws Exception {
        Map<String, String> fields = new TreeMap<>();
        fields.put("auth_date", Long.toString(authDate));
        fields.put("user", "{\"id\":42}");
        String check = "auth_date=" + authDate + "\\nuser={\"id\":42}";
        check = check.replace("\\n", "\n");
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("WebAppData".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] secret = mac.doFinal(TOKEN.getBytes(StandardCharsets.UTF_8));
        mac.init(new SecretKeySpec(secret, "HmacSHA256"));
        byte[] digest = mac.doFinal(check.getBytes(StandardCharsets.UTF_8));
        StringBuilder hash = new StringBuilder();
        for (byte b : digest) hash.append(String.format("%02x", b));
        return "auth_date=" + authDate + "&user=" + URLEncoder.encode(fields.get("user"), StandardCharsets.UTF_8) + "&hash=" + hash;
    }

    @Test void validSignedData() throws Exception {
        assertTrue(TelegramInitDataValidator.isValid(signed(Instant.now().getEpochSecond()), TOKEN));
    }

    @Test void expiredSignedDataIsRejected() throws Exception {
        assertFalse(TelegramInitDataValidator.isValid(signed(Instant.now().minusSeconds(48 * 3600).getEpochSecond()), TOKEN));
    }

    @Test void tamperedDataIsRejected() throws Exception {
        String signed = signed(Instant.now().getEpochSecond());
        assertFalse(TelegramInitDataValidator.isValid(signed.replace("42", "43"), TOKEN));
    }
}
