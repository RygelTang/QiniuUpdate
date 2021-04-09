package com.qiniu.android.storage.stream.utils;

import java.io.IOException;
import java.io.InputStream;

public class StreamUtils {

    public static void safeSkip(InputStream in, long offset) throws IOException {
        if (offset == 0) {
            return;
        }
        long at = offset;
        while (at > 0) {
            long amt = in.skip(at);
            if (amt < 0) {
                throw new RuntimeException(": unexpected EOF");
            }
            at -= amt;
        }
    }

    public static byte[] read(InputStream in, int len) throws IOException {
        long bytesCopied = 0L;
        byte[] buffer = new byte[len];
        long bytes = in.read(buffer);
        while (bytes >= 0 && bytesCopied < len) {
            bytesCopied += bytes;
            bytes = in.read(buffer, (int) bytesCopied, (int) (len - bytesCopied));
        }
        in.close();
        return buffer;
    }

}
