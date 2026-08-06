package com.enrola.agent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Sizing a message the way a network would. The thresholds are not arbitrary: 160 septets fit
 * one GSM-7 message, one character outside that alphabet drops the whole message to UCS-2 at 70,
 * and concatenating costs a few characters per part for the reassembly header.
 */
class MessageSegmenterTest {

    private static String repeat(String unit, int length) {
        return unit.repeat(length / unit.length() + 1).substring(0, length);
    }

    @Nested
    @DisplayName("GSM-7")
    class Gsm7 {

        @Test
        void oneHundredAndSixtyIsASingleMessage() {
            assertThat(MessageSegmenter.segments(repeat("a", 160))).isEqualTo(1);
        }

        @Test
        @DisplayName("161 characters no longer fits, and each part drops to 153")
        void oneHundredAndSixtyOneBecomesTwo() {
            String text = repeat("a", 161);

            assertThat(MessageSegmenter.segments(text)).isEqualTo(2);
            assertThat(MessageSegmenter.split(text).get(0)).hasSize(153);
        }

        @Test
        @DisplayName("the escape-table characters cost two septets each")
        void extendedCharactersCountDouble() {
            // 80 opening braces is 160 septets: still one message.
            assertThat(MessageSegmenter.segments(repeat("{", 80))).isEqualTo(1);
            assertThat(MessageSegmenter.segments(repeat("{", 81))).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("UCS-2")
    class Ucs2 {

        @Test
        @DisplayName("one character outside GSM-7 drops the whole message to 70")
        void oneEmojiCostsTheWholeMessage() {
            String text = repeat("a", 100);
            assertThat(MessageSegmenter.segments(text)).isEqualTo(1);

            assertThat(MessageSegmenter.segments("😀" + text)).isGreaterThan(1);
        }

        @Test
        void seventyIsASingleMessage() {
            // "日" is outside GSM-7; "ü" would not be, since the alphabet includes it.
            assertThat(MessageSegmenter.segments("日" + repeat("a", 69))).isEqualTo(1);
            assertThat(MessageSegmenter.segments("日" + repeat("a", 70))).isEqualTo(2);
        }

        @Test
        @DisplayName("the accented characters GSM-7 does include stay cheap")
        void gsmAccentsDoNotForceUcs2() {
            assertThat(MessageSegmenter.segments("ü" + repeat("a", 159))).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("splitting")
    class Splitting {

        @Test
        void shortTextIsOneWholePart() {
            assertThat(MessageSegmenter.split("Hi Sam, quick question about your cover."))
                    .containsExactly("Hi Sam, quick question about your cover.");
        }

        @Test
        @DisplayName("parts break between words, never inside one")
        void breaksOnWordBoundaries() {
            String text = ("waiting periods carry over when you switch to an equivalent level of cover "
                            + "so you do not serve them twice and there is no fee to change funds at all")
                    .repeat(2);

            var parts = MessageSegmenter.split(text);

            assertThat(parts).hasSizeGreaterThan(1);
            assertThat(parts).allSatisfy(part -> assertThat(part).doesNotEndWith(" "));
            // Rejoining recovers the original: nothing lost, nothing duplicated.
            assertThat(String.join(" ", parts)).isEqualTo(text);
        }

        @Test
        @DisplayName("a word longer than a message is split rather than dropped")
        void oneEnormousWord() {
            String text = repeat("a", 400);

            var parts = MessageSegmenter.split(text);

            assertThat(parts).hasSize(3);
            assertThat(String.join("", parts)).isEqualTo(text);
        }

        @Test
        void blankTextIsOneEmptyPart() {
            assertThat(MessageSegmenter.split("")).containsExactly("");
            assertThat(MessageSegmenter.split(null)).containsExactly("");
        }
    }

    @Nested
    @DisplayName("trimming")
    class Trimming {

        @Test
        @DisplayName("cuts at the last full sentence, not mid-thought")
        void trimsToASentence() {
            String text =
                    "Pregnancy has a 12 month wait. It carries over when you switch to equivalent cover. "
                            + "Anything new or higher starts fresh. There is no fee to change funds at any time "
                            + "of year, and the old fund issues a clearance certificate for you.";

            String trimmed = MessageSegmenter.trimTo(text, 2);

            assertThat(MessageSegmenter.segments(trimmed)).isLessThanOrEqualTo(2);
            assertThat(trimmed).endsWith(".").startsWith("Pregnancy has a 12 month wait.");
        }

        @Test
        void leavesShortEnoughTextAlone() {
            assertThat(MessageSegmenter.trimTo("Short answer.", 2)).isEqualTo("Short answer.");
        }

        @Test
        @DisplayName("with no sentence to cut at, falls back to whole words")
        void trimsToWordsWhenThereIsNoSentenceEnd() {
            String text = "word ".repeat(200).strip();

            String trimmed = MessageSegmenter.trimTo(text, 2);

            assertThat(MessageSegmenter.segments(trimmed)).isEqualTo(2);
            assertThat(trimmed).endsWith("word");
        }

        @Test
        @DisplayName("the budget quoted to the model matches what it is held to")
        void budgetMatchesTheSegmenter() {
            assertThat(MessageSegmenter.segments(repeat("a", MessageSegmenter.budget(1)))).isEqualTo(1);
            assertThat(MessageSegmenter.segments(repeat("a", MessageSegmenter.budget(2)))).isEqualTo(2);
            assertThat(MessageSegmenter.segments(repeat("a", MessageSegmenter.budget(3)))).isEqualTo(3);
        }
    }
}
