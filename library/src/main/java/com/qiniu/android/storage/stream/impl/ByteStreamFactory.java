package com.qiniu.android.storage.stream.impl;

import com.qiniu.android.storage.stream.IStreamFactory;
import com.qiniu.android.utils.Crc32;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

public class ByteStreamFactory implements IStreamFactory {

    private final byte[] data;

    public ByteStreamFactory(byte[] data) {
        this.data = data;
    }

    @Override
    public long lastModifyTime() {
        return 0;
    }

    @Override
    public long sizeOfStream() {
        return data.length;
    }

    @Override
    public InputStream newStreamWithOffset(long offset) throws IOException {
        if (offset >= data.length) {
            throw new IOException("offset is bigger than data.length");
        }
        return new ByteArrayInputStream(data, 0, (int) (data.length - offset));
    }

    @Override
    public String identity() {
        return String.valueOf(Crc32.bytes(data));
    }
}
