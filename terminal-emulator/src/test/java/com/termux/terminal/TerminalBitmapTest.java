package com.termux.terminal;

import junit.framework.TestCase;

public class TerminalBitmapTest extends TestCase {

    public void testKittyPlacementRetainsRequestedAspectRatioSizeForClipping() {
        assertArrayEquals(new int[] {1000, 500}, TerminalBitmap.getKittyPlacementSize(
            2, 1, 1000, -1, true));
    }

    public void testKittyPlacementRetainsRequestedExactSizeForClipping() {
        assertArrayEquals(new int[] {1000, 2}, TerminalBitmap.getKittyPlacementSize(
            1, 1, 1000, 2, false));
    }

    public void testKittyPlacementRejectsSizeThatExceedsBitmapLimit() {
        assertNull(TerminalBitmap.getKittyPlacementSize(
            1, 1, Integer.MAX_VALUE, Integer.MAX_VALUE, false));
    }

    public void testKittyPlacementRejectsUnrepresentableAspectRatio() {
        assertNull(TerminalBitmap.getKittyPlacementSize(
            1, 2, Integer.MAX_VALUE, -1, true));
    }

    private static void assertArrayEquals(int[] expected, int[] actual) {
        if (expected == null ? actual != null : actual == null || expected.length != actual.length) {
            fail("Expected " + describe(expected) + ", got " + describe(actual));
        }
        for (int i = 0; i < expected.length; i++) {
            if (expected[i] != actual[i]) {
                fail("Expected " + describe(expected) + ", got " + describe(actual));
            }
        }
    }

    private static String describe(int[] values) {
        if (values == null) return "null";
        return "{" + values[0] + ", " + values[1] + "}";
    }
}
