package com.termux.app;

import android.app.Instrumentation;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;
import android.util.Base64;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalSession;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** End-to-end OSC 52 clipboard regressions through the foreground Termux activity. */
@RunWith(AndroidJUnit4.class)
public final class NativeClipboardTest {
    private static final int KIB = 1024;
    private static final int ASCII_PAYLOAD_LENGTH = 256 * KIB;
    private static final int BMP_PAYLOAD_LENGTH = 256 * KIB;
    private static final int LARGER_BMP_PAYLOAD_LENGTH = 393217;
    private static final int LARGE_ASCII_PAYLOAD_LENGTH = 600 * KIB;
    private static final long WAIT_TIMEOUT_MILLIS = 30_000L;

    private static final int[] OSC_FRAGMENT_SIZES = {1, 2, 3, 5, 8, 13, 21, 34, 55, 89, 144, 1024};
    private static final char[] BMP_PATTERN = {'\u4e00', '\u4e01', '\u4e02', '\u4e03'};

    private Instrumentation instrumentation;
    private Context targetContext;
    private ClipboardManager clipboardManager;
    private ClipData savedClipboard;
    private TermuxActivity activity;
    private TerminalSession session;

    @Before
    public void launchFocusedActivity() {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        targetContext = instrumentation.getTargetContext();
        clipboardManager = (ClipboardManager) targetContext.getSystemService(Context.CLIPBOARD_SERVICE);
        assertNotNull("target clipboard manager", clipboardManager);

        Intent intent = new Intent(targetContext, TermuxActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        activity = (TermuxActivity) instrumentation.startActivitySync(intent);
        waitForFocusedSession();
        savedClipboard = readClipboardClipData();
    }

    @After
    public void restoreClipboardAndFinishActivity() {
        try {
            if (clipboardManager != null) restoreClipboard();
        } finally {
            if (activity != null && !activity.isFinishing()) {
                instrumentation.runOnMainSync(() -> activity.finish());
                instrumentation.waitForIdleSync();
            }
        }
    }

    @Test
    public void osc52Ascii256KiBBelCopiesExactly() throws Exception {
        String expected = makeAsciiPayload(ASCII_PAYLOAD_LENGTH);

        appendOsc52(expected, "\007");
        assertClipboardEquals(expected);
    }

    @Test
    public void osc52BmpUnicode256KiBUtf16UnitsCopiesExactly() throws Exception {
        String expected = makeBmpPayload(BMP_PAYLOAD_LENGTH);

        appendOsc52(expected, "\033\\");
        assertClipboardEquals(expected);
    }
    @Test
    public void osc52LargePayloadMatchesClipboardBaseline() throws Exception {
        assertLargePayloadMatchesClipboardBaseline(
            makeAsciiPayload(LARGE_ASCII_PAYLOAD_LENGTH), "\007");
        assertLargePayloadMatchesClipboardBaseline(
            makeBmpPayload(LARGER_BMP_PAYLOAD_LENGTH), "\033\\");
    }


    private void waitForFocusedSession() {
        long deadline = SystemClock.uptimeMillis() + WAIT_TIMEOUT_MILLIS;
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync();
            if (!activity.isFinishing() && activity.isVisible() && activity.hasWindowFocus()) {
                TerminalSession candidate = activity.getCurrentSession();
                if (candidate != null && candidate.getEmulator() != null) {
                    session = candidate;
                    return;
                }
            }
            SystemClock.sleep(100L);
        }

        fail("TermuxActivity did not become focused with an initialized terminal session");
    }

    private void appendOsc52(String payload, String terminator) throws Exception {
        String encoded = Base64.encodeToString(payload.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        byte[] sequence = ("\033]52;c;" + encoded + terminator).getBytes(StandardCharsets.US_ASCII);
        TerminalSession testSession = session;

        instrumentation.runOnMainSync(() -> {
            TerminalEmulator emulator = testSession.getEmulator();
            assertNotNull("current terminal emulator", emulator);

            int offset = 0;
            int fragment = 0;
            while (offset < sequence.length) {
                int count = Math.min(OSC_FRAGMENT_SIZES[fragment % OSC_FRAGMENT_SIZES.length],
                    sequence.length - offset);
                byte[] part = Arrays.copyOfRange(sequence, offset, offset + count);
                emulator.append(part, part.length);
                offset += count;
                fragment++;
            }
        });
    }

    private void assertClipboardEquals(String expected) throws Exception {
        String expectedHash = sha256Utf8(expected);
        String actual = awaitClipboard(expected.length(), expectedHash);

        assertEquals("clipboard UTF-16 length", expected.length(), actual.length());
        assertEquals("clipboard SHA-256", expectedHash, sha256Utf8(actual));
        assertTrue("clipboard content", expected.contentEquals(actual));
    }

    private String awaitClipboard(int expectedLength, String expectedHash) throws Exception {
        long deadline = SystemClock.uptimeMillis() + WAIT_TIMEOUT_MILLIS;
        String actual = null;
        while (SystemClock.uptimeMillis() < deadline) {
            assertTrue("TermuxActivity lost foreground focus", activity.hasWindowFocus());
            actual = readClipboardText();
            if (actual != null && actual.length() == expectedLength && expectedHash.equals(sha256Utf8(actual))) {
                return actual;
            }
            SystemClock.sleep(50L);
        }

        String actualHash = actual == null || actual.length() != expectedLength ? "<unavailable>" : sha256Utf8(actual);
        int actualLength = actual == null ? -1 : actual.length();
        fail("clipboard did not reach expected length/hash: expectedLength=" + expectedLength
            + ", actualLength=" + actualLength + ", expectedHash=" + expectedHash + ", actualHash=" + actualHash);
        return null;
    }
    private void assertLargePayloadMatchesClipboardBaseline(String payload, String terminator) throws Exception {
        boolean directAccepted = directClipboardSetSucceeds(payload);
        String sentinel = "termux-osc52-sentinel-" + payload.length();
        setClipboardText(sentinel);
        assertClipboardEquals(sentinel);

        appendOsc52(payload, terminator);
        if (directAccepted) {
            assertClipboardEquals(payload);
            return;
        }

        assertClipboardEquals(sentinel);
        String followUpPayload = "termux-osc52-follow-up-" + makeAsciiPayload(128);
        appendOsc52(followUpPayload, "\007");
        assertClipboardEquals(followUpPayload);
    }

    private boolean directClipboardSetSucceeds(String payload) {
        AtomicReference<RuntimeException> failure = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            try {
                clipboardManager.setPrimaryClip(ClipData.newPlainText("", payload));
            } catch (RuntimeException exception) {
                failure.set(exception);
            }
        });
        return failure.get() == null;
    }

    private void setClipboardText(String text) {
        instrumentation.runOnMainSync(
            () -> clipboardManager.setPrimaryClip(ClipData.newPlainText("", text)));
    }

    private String readClipboardText() {
        AtomicReference<String> value = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            ClipData clipData = clipboardManager.getPrimaryClip();
            if (clipData == null || clipData.getItemCount() == 0) return;
            ClipData.Item item = clipData.getItemAt(0);
            if (item == null) return;
            CharSequence text = item.coerceToText(targetContext);
            if (text != null) value.set(text.toString());
        });
        return value.get();
    }

    private ClipData readClipboardClipData() {
        AtomicReference<ClipData> value = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> value.set(clipboardManager.getPrimaryClip()));
        return value.get();
    }

    private void restoreClipboard() {
        instrumentation.runOnMainSync(() -> {
            if (savedClipboard != null) {
                clipboardManager.setPrimaryClip(savedClipboard);
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                clipboardManager.clearPrimaryClip();
            } else {
                clipboardManager.setPrimaryClip(ClipData.newPlainText(null, ""));
            }
        });
    }

    private static String makeAsciiPayload(int length) {
        char[] chars = new char[length];
        for (int i = 0; i < chars.length; i++) chars[i] = (char) ('A' + (i % 26));
        return new String(chars);
    }

    private static String makeBmpPayload(int length) {
        char[] chars = new char[length];
        for (int i = 0; i < chars.length; i++) chars[i] = BMP_PATTERN[i % BMP_PATTERN.length];
        return new String(chars);
    }

    private static String sha256Utf8(String value) throws NoSuchAlgorithmException {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder(digest.length * 2);
        for (byte valueByte : digest) result.append(String.format("%02x", valueByte & 0xff));
        return result.toString();
    }
}
