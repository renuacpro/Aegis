package me.impy.aegis;

import org.junit.Test;

import me.impy.aegis.crypto.otp.HOTP;

import static org.junit.Assert.*;

public class HOTPTest {
    // https://tools.ietf.org/html/rfc4226#page-32
    private final String[] _vectors = {
        "755224", "287082",
        "359152", "969429",
        "338314", "254676",
        "287922", "162583",
        "399871", "520489"
    };

    private final byte[] _secret = new byte[] {
        0x31, 0x32, 0x33, 0x34, 0x35, 0x36, 0x37, 0x38, 0x39, 0x30,
        0x31, 0x32, 0x33, 0x34, 0x35, 0x36, 0x37, 0x38, 0x39, 0x30
    };

    @Test
    public void vectorsMatch() throws Exception {
        for (int i = 0; i < _vectors.length; i++) {
            String otp = HOTP.generateOTP(_secret, i, 6, false, -1);
            assertEquals(_vectors[i], otp);
        }
    }
}
