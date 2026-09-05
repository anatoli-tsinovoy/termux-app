package com.termux.view;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Typeface;
import android.util.Base64;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalOutput;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Device regressions for the Android BitmapFactory/Canvas Kitty image path. */
@RunWith(AndroidJUnit4.class)
public final class KittyRenderingTest {
    private static final int CELL_WIDTH = 8;
    private static final int CELL_HEIGHT = 16;
    private static final int IMAGE_COLUMNS = 8;
    private static final int IMAGE_ROWS = 3;

    private static final int RED = 0xffe53935;
    private static final int GREEN = 0xff43a047;
    private static final int BLUE = 0xff1e88e5;
    private static final int YELLOW = 0xffffb300;

    private static final int[] OSC_FRAGMENT_SIZES = {1, 2, 3, 5, 8, 13, 21, 34, 55, 89, 144};

    @Test
    public void directPlacementCrossingBottomScrollsImageAndCursorOnce() {
        Fixture fixture = new Fixture(IMAGE_COLUMNS, IMAGE_ROWS);
        byte[] png = makeCornerPng();

        append(fixture.emulator, "\033[3;1H");
        append(fixture.emulator, kittyDisplay(png, 101, ",c=2,r=2"));

        Rendered rendered = render(fixture);
        assertFourCornerPixels(rendered.bitmap);
        export("direct-placement-scroll.png", rendered.bitmap);

        assertEquals("image scroll leaves the cursor on the final image row", 2,
            fixture.emulator.getCursorRow());
        assertEquals("image width advances the cursor exactly once", 2,
            fixture.emulator.getCursorCol());
    }

    @Test
    public void c1PlacementClipsAtBottomWithoutScrollingOrMovingCursor() {
        Fixture fixture = new Fixture(IMAGE_COLUMNS, IMAGE_ROWS);
        byte[] png = makeCornerPng();

        append(fixture.emulator, "\033[3;1H");
        append(fixture.emulator, kittyDisplay(png, 102, ",C=1,c=2,r=2"));

        Rendered rendered = render(fixture);
        assertTrue("the visible top image row must be drawn", countColor(rendered.bitmap, RED) > 4);
        assertTrue("the visible top image row must be drawn", countColor(rendered.bitmap, GREEN) > 4);
        assertEquals("the clipped lower image row must not be drawn", 0,
            countColor(rendered.bitmap, BLUE));
        assertEquals("the clipped lower image row must not be drawn", 0,
            countColor(rendered.bitmap, YELLOW));
        export("c1-placement-clipped.png", rendered.bitmap);

        assertEquals("C=1 leaves the cursor row stationary", 2, fixture.emulator.getCursorRow());
        assertEquals("C=1 leaves the cursor column stationary", 0, fixture.emulator.getCursorCol());
    }

    @Test
    public void threeMarkVirtualPlaceholderMatchesTwoMarkAndKeepsAsciiAtRight() {
        byte[] png = makeCornerPng();
        Rendered threeMark = renderVirtualPlaceholder(png, "\u0305\u0305\u030d", 0x01020304L,
            "virtual-three-mark.png");
        Rendered twoMark = renderVirtualPlaceholder(png, "\u0305\u0305", 0x00020304L,
            "virtual-two-mark.png");
        assertFourCornerPixels(threeMark.bitmap);
        assertFourCornerPixels(twoMark.bitmap);
        assertBitmapsEqual("a three-mark PUA cluster must consume all marks", twoMark.bitmap,
            threeMark.bitmap);
        Rendered plainX = renderPlainX();
        assertCellPixelsEqual("three-mark neighboring X must match plain X", plainX.bitmap,
            threeMark.bitmap, threeMark.renderer, 1, 0);
        assertCellPixelsEqual("two-mark neighboring X must match plain X", plainX.bitmap,
            twoMark.bitmap, twoMark.renderer, 1, 0);
    }

    @Test
    public void osc52LargePayloadSurvivesAndroidBase64AndFragmentedBelAndSt() {
        Fixture fixture = new Fixture(IMAGE_COLUMNS, IMAGE_ROWS);
        char[] payloadChars = new char[51 * 1024];
        for (int i = 0; i < payloadChars.length; i++) {
            payloadChars[i] = (char) ('A' + (i % 26));
        }
        String expected = new String(payloadChars);
        String encoded = Base64.encodeToString(expected.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        assertTrue("the regression must exercise a roughly 68KiB encoded clipboard", encoded.length() > 65 * 1024);
        assertTrue("the payload must remain below the intended encoded cap", encoded.length() < 70 * 1024);

        appendFragmented(fixture.emulator,
            ("\033]52;c;" + encoded + "\007").getBytes(StandardCharsets.UTF_8));
        appendFragmented(fixture.emulator,
            ("\033]52;c;" + encoded + "\033\\").getBytes(StandardCharsets.UTF_8));

        assertEquals(2, fixture.output.clipboardValues.size());
        assertEquals(expected, fixture.output.clipboardValues.get(0));
        assertEquals(expected, fixture.output.clipboardValues.get(1));
    }

    @Test
    public void capturedDirectStreamRendersImageAndLeavesBlankRegionClean() throws IOException {
        assertCapturedStream("app-direct.raw", "captured-app-direct.png", 0, 0, 6, 3,
            8, 0, false);
    }

    @Test
    public void capturedUtf8RemoteStreamRendersVirtualImageWithoutLeakedGlyphs() throws IOException {
        assertCapturedStream("remote-client-utf8.raw", "captured-remote-utf8.png", 0, 0, 5, 3,
            5, 0, true);
    }

    private static Rendered renderVirtualPlaceholder(byte[] png, String marks, long imageId,
                                                      String artifactName) {
        Fixture fixture = new Fixture(IMAGE_COLUMNS, IMAGE_ROWS);
        append(fixture.emulator, kittyDisplay(png, imageId, ",p=7,U=1,c=1,r=1"));
        append(fixture.emulator, "\033[1;1H\033[38;2;2;3;4m"
            + new String(Character.toChars(0x10eeee)) + marks + "\033[39mX");
        Rendered rendered = render(fixture);
        export(artifactName, rendered.bitmap);
        return rendered;
    }

    private static void assertCapturedStream(String assetName, String artifactName,
                                             int imageColumn, int imageRow, int imageColumns,
                                             int imageRows, int adjacentColumn, int adjacentRow,
                                             boolean snapshotBeforeTmuxTeardown)
        throws IOException {
        Fixture fixture = new Fixture(80, 24);
        byte[] capture = readAsset(assetName);
        byte[] png = extractFirstKittyPng(capture);
        Bitmap source = BitmapFactory.decodeByteArray(png, 0, png.length);
        assertNotNull("capture must contain a real decodable PNG", source);
        assertTrue("the source PNG must contain visible pixels", countNonBlack(source) > 20);

        if (snapshotBeforeTmuxTeardown) {
            byte[] marginReset = "\033[1;0r".getBytes(StandardCharsets.US_ASCII);
            int snapshotLength = indexOf(capture, marginReset, 0);
            assertTrue("capture must contain the pre-teardown ESC[1;0r boundary", snapshotLength >= 0);
            appendFragmented(fixture.emulator, Arrays.copyOf(capture, snapshotLength));
        } else {
            appendFragmented(fixture.emulator, capture);
        }
        Rendered rendered = render(fixture);

        int imageLeft = Math.max(0, (int) Math.floor(imageColumn * rendered.renderer.getFontWidth()));
        int imageRight = Math.min(rendered.bitmap.getWidth(),
            (int) Math.ceil((imageColumn + imageColumns) * rendered.renderer.getFontWidth()));
        int imageTop = Math.max(0, imageRow * rendered.renderer.getFontLineSpacing() - 2);
        int imageBottom = Math.min(rendered.bitmap.getHeight(),
            (imageRow + imageRows + 1) * rendered.renderer.getFontLineSpacing());
        assertTrue("captured Kitty image must paint observable pixels",
            countNonBlack(rendered.bitmap, imageLeft, imageTop, imageRight, imageBottom) > 20);

        assertBlackRegion(rendered, adjacentColumn, adjacentRow, adjacentColumn + 2, adjacentRow + 3);
        assertBlackRegion(rendered, 10, 8, 16, 11);
        export(artifactName, rendered.bitmap);
    }

    private static byte[] makeCornerPng() {
        int[] pixels = new int[8 * 8];
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                if (y < 4) {
                    pixels[y * 8 + x] = x < 4 ? RED : GREEN;
                } else {
                    pixels[y * 8 + x] = x < 4 ? BLUE : YELLOW;
                }
            }
        }
        Bitmap bitmap = Bitmap.createBitmap(pixels, 8, 8, Bitmap.Config.ARGB_8888);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
            throw new AssertionError("Bitmap PNG compression failed");
        }
        byte[] png = output.toByteArray();
        assertNotNull("BitmapFactory must decode the generated PNG", BitmapFactory.decodeByteArray(png, 0, png.length));
        return png;
    }

    private static String kittyDisplay(byte[] png, long imageId, String options) {
        return "\033_Ga=T,i=" + imageId + ",q=2,f=100,m=0" + options + ";"
            + Base64.encodeToString(png, Base64.NO_WRAP) + "\033\\";
    }

    private static Rendered render(Fixture fixture) {
        TerminalRenderer renderer = new TerminalRenderer(16, Typeface.MONOSPACE);
        int width = Math.max(1, (int) Math.ceil(renderer.getFontWidth() * fixture.columns) + 4);
        int height = Math.max(1, renderer.getFontLineSpacing() * fixture.rows + 8);
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.BLACK);
        renderer.render(fixture.emulator, canvas, 0, -1, -1, -1, -1);
        return new Rendered(bitmap, renderer);
    }

    private static Rendered renderPlainX() {
        Fixture fixture = new Fixture(IMAGE_COLUMNS, IMAGE_ROWS);
        append(fixture.emulator, "\033[1;2H\033[39mX");
        return render(fixture);
    }

    private static void assertFourCornerPixels(Bitmap bitmap) {
        assertTrue("upper-left source corner must survive as red pixels", countColor(bitmap, RED) > 4);
        assertTrue("upper-right source corner must survive as green pixels", countColor(bitmap, GREEN) > 4);
        assertTrue("lower-left source corner must survive as blue pixels", countColor(bitmap, BLUE) > 4);
        assertTrue("lower-right source corner must survive as yellow pixels", countColor(bitmap, YELLOW) > 4);
        Bounds red = bounds(bitmap, RED);
        Bounds green = bounds(bitmap, GREEN);
        Bounds blue = bounds(bitmap, BLUE);
        Bounds yellow = bounds(bitmap, YELLOW);
        assertTrue("red must be left of green", red.minX < green.minX);
        assertTrue("blue must be left of yellow", blue.minX < yellow.minX);
        assertTrue("red must be above blue", red.minY < blue.minY);
        assertTrue("green must be above yellow", green.minY < yellow.minY);
    }

    private static void assertBitmapsEqual(String message, Bitmap expected, Bitmap actual) {
        assertEquals(message + " (width)", expected.getWidth(), actual.getWidth());
        assertEquals(message + " (height)", expected.getHeight(), actual.getHeight());
        for (int y = 0; y < expected.getHeight(); y++) {
            for (int x = 0; x < expected.getWidth(); x++) {
                assertEquals(message + " at " + x + "," + y, expected.getPixel(x, y), actual.getPixel(x, y));
            }
        }
    }

    private static void assertCellPixelsEqual(String message, Bitmap expected, Bitmap actual,
                                               TerminalRenderer renderer, int column, int row) {
        assertEquals(message + " (width)", expected.getWidth(), actual.getWidth());
        assertEquals(message + " (height)", expected.getHeight(), actual.getHeight());
        int left = Math.max(0, (int) Math.floor(column * renderer.getFontWidth()));
        int right = Math.min(actual.getWidth(),
            (int) Math.ceil((column + 1) * renderer.getFontWidth()));
        int top = Math.max(0, row * renderer.getFontLineSpacing() - 2);
        int bottom = Math.min(actual.getHeight(),
            (row + 1) * renderer.getFontLineSpacing() + 2);
        assertTrue(message + " must contain visible glyph pixels",
            countNonBlack(expected, left, top, right, bottom) > 4);
        for (int y = top; y < bottom; y++) {
            for (int x = left; x < right; x++) {
                assertEquals(message + " at " + x + "," + y,
                    expected.getPixel(x, y), actual.getPixel(x, y));
            }
        }
    }

    private static void assertBlackRegion(Rendered rendered, int columnStart, int rowStart,
                                          int columnEnd, int rowEnd) {
        int left = Math.max(0, (int) Math.floor(columnStart * rendered.renderer.getFontWidth()));
        int right = Math.min(rendered.bitmap.getWidth(),
            (int) Math.ceil(columnEnd * rendered.renderer.getFontWidth()));
        int top = Math.max(0, rowStart * rendered.renderer.getFontLineSpacing() - 2);
        int bottom = Math.min(rendered.bitmap.getHeight(),
            rowEnd * rendered.renderer.getFontLineSpacing());
        for (int y = top; y < bottom; y++) {
            for (int x = left; x < right; x++) {
                assertEquals("unexpected glyph or image pixel in blank region at " + x + "," + y,
                    Color.BLACK, rendered.bitmap.getPixel(x, y));
            }
        }
    }

    private static int countColor(Bitmap bitmap, int color) {
        return countColor(bitmap, color, 0, 0, bitmap.getWidth(), bitmap.getHeight());
    }

    private static int countColor(Bitmap bitmap, int color, int left, int top, int right, int bottom) {
        int count = 0;
        for (int y = Math.max(0, top); y < Math.min(bitmap.getHeight(), bottom); y++) {
            for (int x = Math.max(0, left); x < Math.min(bitmap.getWidth(), right); x++) {
                if (bitmap.getPixel(x, y) == color) count++;
            }
        }
        return count;
    }

    private static int countNonBlack(Bitmap bitmap) {
        return countNonBlack(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight());
    }

    private static int countNonBlack(Bitmap bitmap, int left, int top, int right, int bottom) {
        int count = 0;
        for (int y = Math.max(0, top); y < Math.min(bitmap.getHeight(), bottom); y++) {
            for (int x = Math.max(0, left); x < Math.min(bitmap.getWidth(), right); x++) {
                if (bitmap.getPixel(x, y) != Color.BLACK) count++;
            }
        }
        return count;
    }

    private static Bounds bounds(Bitmap bitmap, int color) {
        Bounds result = new Bounds();
        for (int y = 0; y < bitmap.getHeight(); y++) {
            for (int x = 0; x < bitmap.getWidth(); x++) {
                if (bitmap.getPixel(x, y) == color) result.include(x, y);
            }
        }
        return result;
    }

    private static byte[] readAsset(String name) throws IOException {
        InputStream input = InstrumentationRegistry.getInstrumentation().getContext().getAssets().open(name);
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (count > 0) output.write(buffer, 0, count);
            }
            return output.toByteArray();
        } finally {
            input.close();
        }
    }

    private static byte[] extractFirstKittyPng(byte[] capture) {
        byte[] apc = {0x1b, '_', 'G'};
        byte[] st = {0x1b, '\\'};
        int start = indexOf(capture, apc, 0);
        assertTrue("capture must contain a Kitty APC", start >= 0);
        int separator = indexOf(capture, new byte[] {';'}, start + apc.length);
        int end = indexOf(capture, st, separator + 1);
        assertTrue("Kitty APC must have a string terminator", separator >= 0 && end > separator);
        String base64 = new String(capture, separator + 1, end - separator - 1, StandardCharsets.US_ASCII);
        byte[] png = Base64.decode(base64, Base64.DEFAULT);
        assertTrue("Kitty APC payload must decode", png.length > 0);
        return png;
    }

    private static int indexOf(byte[] haystack, byte[] needle, int from) {
        outer:
        for (int i = Math.max(0, from); i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    private static void append(TerminalEmulator emulator, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        emulator.append(bytes, bytes.length);
    }

    private static void appendFragmented(TerminalEmulator emulator, byte[] bytes) {
        int offset = 0;
        int fragment = 0;
        while (offset < bytes.length) {
            int count = Math.min(OSC_FRAGMENT_SIZES[fragment % OSC_FRAGMENT_SIZES.length], bytes.length - offset);
            byte[] part = Arrays.copyOfRange(bytes, offset, offset + count);
            emulator.append(part, part.length);
            offset += count;
            fragment++;
        }
    }

    private static void export(String name, Bitmap bitmap) {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File directory = context.getExternalFilesDir("kitty-render-regressions");
        assertNotNull("instrumentation external files directory", directory);
        assertTrue("create instrumentation artifact directory", directory.exists() || directory.mkdirs());
        File output = new File(directory, name);
        try {
            FileOutputStream stream = new FileOutputStream(output);
            try {
                assertTrue("export rendered PNG " + output, bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream));
            } finally {
                stream.close();
            }
        } catch (IOException e) {
            throw new AssertionError("export rendered PNG " + output, e);
        }
    }

    private static final class Fixture {
        final int columns;
        final int rows;
        final RecordingOutput output;
        final TerminalEmulator emulator;

        Fixture(int columns, int rows) {
            this.columns = columns;
            this.rows = rows;
            output = new RecordingOutput();
            emulator = new TerminalEmulator(output, columns, rows, CELL_WIDTH, CELL_HEIGHT,
                Math.max(100, rows * 2), null);
        }
    }

    private static final class Rendered {
        final Bitmap bitmap;
        final TerminalRenderer renderer;

        Rendered(Bitmap bitmap, TerminalRenderer renderer) {
            this.bitmap = bitmap;
            this.renderer = renderer;
        }
    }

    private static final class Bounds {
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;

        void include(int x, int y) {
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
        }
    }

    private static final class RecordingOutput extends TerminalOutput {
        final ByteArrayOutputStream responses = new ByteArrayOutputStream();
        final List<String> clipboardValues = new ArrayList<>();

        @Override
        public void write(byte[] data, int offset, int count) {
            responses.write(data, offset, count);
        }

        @Override
        public void titleChanged(String oldTitle, String newTitle) {
        }

        @Override
        public void onCopyTextToClipboard(String text) {
            clipboardValues.add(text);
        }

        @Override
        public void onPasteTextFromClipboard() {
        }

        @Override
        public void onBell() {
        }

        @Override
        public void onColorsChanged() {
        }
    }
}
