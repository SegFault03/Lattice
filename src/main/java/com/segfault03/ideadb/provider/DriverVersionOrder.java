package com.segfault03.ideadb.provider;

import java.math.BigInteger;

/** Numeric release order with classified builds ahead of the corresponding plain release. */
public final class DriverVersionOrder {
    private DriverVersionOrder() {}
    public static int descending(String left, String right) {
        String[] first = left.split("\\."), second = right.split("\\.");
        for (int index = 0; index < Math.max(first.length, second.length); index++) {
            int difference = numberAt(second, index).compareTo(numberAt(first, index));
            if (difference != 0) return difference;
        }
        return right.compareTo(left);
    }
    private static BigInteger numberAt(String[] parts, int index) {
        if (index >= parts.length) return BigInteger.ZERO;
        String digits = parts[index].replaceFirst("[^0-9].*$", "");
        return digits.isEmpty() ? BigInteger.ZERO : new BigInteger(digits);
    }
}
