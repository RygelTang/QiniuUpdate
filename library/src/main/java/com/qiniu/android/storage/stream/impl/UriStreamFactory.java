package com.qiniu.android.storage.stream.impl;

import android.content.Context;
import android.net.Uri;

import com.qiniu.android.storage.stream.IStreamFactory;
import com.qiniu.android.storage.stream.utils.StreamUtils;

import java.io.IOException;
import java.io.InputStream;

public class UriStreamFactory implements IStreamFactory {

    private final Uri uri;
    private final long len;
    private final long lastModifyTime;
    private final Context context;

    public UriStreamFactory(Context context, Uri uri, long len, long lastModifyTime) {
        this.context = context.getApplicationContext();
        this.uri = uri;
        this.len = len;
        this.lastModifyTime = lastModifyTime;
    }

    @Override
    public long lastModifyTime() {

        return lastModifyTime;
    }

    @Override
    public long sizeOfStream() {
        return len;
    }

    @Override
    public InputStream newStreamWithOffset(long offset) throws IOException {
        InputStream is = context.getContentResolver().openInputStream(uri);
        StreamUtils.safeSkip(is, offset);
        return is;
    }

    @Override
    public String identity() {
        return uri.toString();
    }
}
