package com.example.config;

import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LogBodyTextDecoderTest {

    @Test
    void repairsUtf8TextDecodedAsIbm850() {
        String original = "Преглед на ефективния достъп — роли";
        String mojibake = new String(original.getBytes(StandardCharsets.UTF_8), Charset.forName("IBM850"));

        assertEquals(original, LogBodyTextDecoder.repairLikelyMojibake(mojibake));
    }

    @Test
    void repairsUtf8TextDecodedAsWindows1252() {
        String original = "Преглед на роли";
        String mojibake = new String(original.getBytes(StandardCharsets.UTF_8), Charset.forName("windows-1252"));

        assertEquals(original, LogBodyTextDecoder.repairLikelyMojibake(mojibake));
    }

    @Test
    void repairsUtf8TextDecodedAsWindows1251() {
        String original = "Преглед на роли";
        String mojibake = new String(original.getBytes(StandardCharsets.UTF_8), Charset.forName("windows-1251"));

        assertEquals(original, LogBodyTextDecoder.repairLikelyMojibake(mojibake));
    }

    @Test
    void leavesValidLanguageTextWithEthUntouched() {
        String original = "Hvað er nytt i dag?";

        assertEquals(original, LogBodyTextDecoder.repairLikelyMojibake(original));
    }
}
