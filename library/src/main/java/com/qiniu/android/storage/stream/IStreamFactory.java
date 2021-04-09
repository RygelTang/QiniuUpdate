package com.qiniu.android.storage.stream;

import java.io.IOException;
import java.io.InputStream;

public interface IStreamFactory {

    long sizeOfStream();

    long lastModifyTime();

    InputStream newStreamWithOffset(long offset) throws IOException;

    String identity();

}
