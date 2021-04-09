package com.qiniu.android.storage.stream.impl;

import com.qiniu.android.storage.stream.IStreamFactory;
import com.qiniu.android.storage.stream.utils.StreamUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

public class FileStreamFactory implements IStreamFactory {

    private final File file;

    public FileStreamFactory(File file) {
        this.file = file;
    }

    @Override
    public long lastModifyTime() {
        return file.lastModified();
    }

    @Override
    public long sizeOfStream() {
        return file.length();
    }

    @Override
    public InputStream newStreamWithOffset(long offset) throws IOException {
        FileInputStream fis = new FileInputStream(file);
        StreamUtils.safeSkip(fis, offset);
        return fis;
    }

    @Override
    public String identity() {
        return file.getAbsolutePath();
    }
}
