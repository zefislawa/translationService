package com.example.config;

import org.springframework.util.StringUtils;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;

final class LogBodyTextDecoder {

    private static final List<Charset> POSSIBLE_WRONG_CHARSETS = List.of(
            StandardCharsets.ISO_8859_1,
            Charset.forName("windows-1252"),
            Charset.forName("windows-1251"),
            Charset.forName("IBM850"),
            Charset.forName("IBM852"),
            Charset.forName("IBM855"),
            Charset.forName("IBM866")
    );

    private LogBodyTextDecoder() {
    }

    static String decode(byte[] body, Charset declaredCharset) {
        Charset charset = declaredCharset != null ? declaredCharset : StandardCharsets.UTF_8;
        return repairLikelyMojibake(new String(body, charset));
    }

    static String repairLikelyMojibake(String text) {
        if (!StringUtils.hasText(text) || !looksLikeMojibake(text)) {
            return text;
        }

        String best = text;
        int bestScore = readabilityScore(text);
        int originalPenalty = mojibakePenalty(text);

        for (Charset wrongCharset : POSSIBLE_WRONG_CHARSETS) {
            String repaired = reinterpretAsUtf8(text, wrongCharset);
            if (repaired == null || repaired.equals(text)) {
                continue;
            }

            int repairedPenalty = mojibakePenalty(repaired);
            int repairedScore = readabilityScore(repaired);
            if (repairedPenalty < originalPenalty && repairedScore > bestScore) {
                best = repaired;
                bestScore = repairedScore;
            }
        }

        return best;
    }

    private static String reinterpretAsUtf8(String text, Charset wrongCharset) {
        try {
            CharsetEncoder encoder = wrongCharset.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            ByteBuffer bytes = encoder.encode(CharBuffer.wrap(text));

            CharsetDecoder utf8Decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            return utf8Decoder.decode(bytes).toString();
        } catch (CharacterCodingException ex) {
            return null;
        }
    }

    private static boolean looksLikeMojibake(String text) {
        return mojibakePenalty(text) >= 8;
    }

    private static int readabilityScore(String text) {
        int score = 0;
        for (int offset = 0; offset < text.length(); ) {
            int codePoint = text.codePointAt(offset);
            offset += Character.charCount(codePoint);

            if (Character.isLetter(codePoint)) {
                score += Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.LATIN ? 2 : 4;
            } else if (Character.isDigit(codePoint) || Character.isWhitespace(codePoint) || isCommonPunctuation(codePoint)) {
                score += 1;
            } else if (isBadLogCharacter(codePoint)) {
                score -= 12;
            }
        }
        return score - (mojibakePenalty(text) * 4);
    }

    private static boolean isCommonPunctuation(int codePoint) {
        return ".,:;!?()[]{}<>/\\'\"-_+=*@#%&|`~\n\r\t".indexOf(codePoint) >= 0
                || codePoint == 0x2013
                || codePoint == 0x2014
                || codePoint == 0x2018
                || codePoint == 0x2019
                || codePoint == 0x201C
                || codePoint == 0x201D;
    }

    private static boolean isBadLogCharacter(int codePoint) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(codePoint);
        return codePoint == 0xFFFD
                || Character.isISOControl(codePoint) && !Character.isWhitespace(codePoint)
                || block == Character.UnicodeBlock.BOX_DRAWING
                || block == Character.UnicodeBlock.BLOCK_ELEMENTS;
    }

    private static int mojibakePenalty(String text) {
        int penalty = 0;
        for (int offset = 0; offset < text.length(); ) {
            int codePoint = text.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (isBadLogCharacter(codePoint)) {
                penalty += 6;
            } else if (codePoint == '\u00C3' || codePoint == '\u00C2'
                    || codePoint == '\u00D0' || codePoint == '\u00D1') {
                penalty += 4;
            } else if (codePoint == '\u00F0' || codePoint == '\u00FE' || codePoint == '\u00DE') {
                penalty += 2;
            }
        }

        penalty += countOccurrences(text, "\u00E2\u20AC") * 8;
        penalty += countOccurrences(text, "\u00D4\u00C7") * 8;
        penalty += countWindows1251CyrillicPairs(text) * 8;

        return penalty;
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int index = text.indexOf(needle);
        while (index >= 0) {
            count++;
            index = text.indexOf(needle, index + needle.length());
        }
        return count;
    }

    private static int countWindows1251CyrillicPairs(String text) {
        int count = 0;
        int previous = -1;
        for (int offset = 0; offset < text.length(); ) {
            int codePoint = text.codePointAt(offset);
            offset += Character.charCount(codePoint);

            if ((previous == '\u0420' || previous == '\u0421')
                    && codePoint >= '\u0400'
                    && codePoint <= '\u045F') {
                count++;
            }
            previous = codePoint;
        }
        return count;
    }
}
