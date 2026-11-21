package com.example.bluetoothkanta;

/** Obfuscation deters casual scanning only; it does not hide secrets from a determined attacker. */
final class Obfuscated {
    private Obfuscated() {}
    private static String decode(int seed, int[] data) {
        char[] chars = new char[data.length];
        for (int i = 0; i < data.length; i++) chars[i] = (char) (data[i] ^ ((seed + i * 13 + 7) & 255));
        return new String(chars);
    }
    static String baseUrl() { return decode(41, new int[]{88, 73, 62, 39, 23, 75, 81, 164, 249, 213, 217, 146, 160, 176, 133, 150, 110, 126, 127, 10, 80, 36, 40, 58, 29, 25, 246, 162, 238, 221, 210, 161, 254, 188, 153, 158, 101, 60, 109, 68, 77, 49, 58, 58, 13, 10, 242, 162, 142, 203, 211, 181, 177, 131, 143, 136, 109, 113, 67, 91, 93, 43, 55, 16, 21, 83, 235, 231, 212, 158}); }
    static String endpoint() { return decode(64, new int[]{44, 49, 24, 29, 84}); }
    static String status() { return decode(87, new int[]{45, 31, 25, 241, 231, 236}); }
    static String hold() { return decode(110, new int[]{61, 205, 195, 216}); }
    static String active() { return decode(133, new int[]{205, 218, 242, 250, 150, 136}); }
    static String used() { return decode(156, new int[]{246, 227, 248, 142}); }
    static String type() { return decode(179, new int[]{206, 190, 164, 132}); }
    static String oneTime() { return decode(202, new int[]{158, 144, 174, 167, 81, 91, 82, 105}); }
    static String deviceLock() { return decode(225, new int[]{172, 176, 84, 70, 95, 108, 105, 15, 31, 30, 33}); }
    static String deviceId() { return decode(248, new int[]{155, 105, 111, 79, 80, 37, 4, 62}); }
    static String expiry() { return decode(15, new int[]{115, 91, 64, 84, 56, 46}); }
    static String customer() { return decode(38, new int[]{78, 79, 52, 32, 14, 3, 30, 250}); }
}
