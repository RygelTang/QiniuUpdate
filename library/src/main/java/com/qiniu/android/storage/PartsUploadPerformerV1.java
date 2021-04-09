package com.qiniu.android.storage;

import com.qiniu.android.http.ResponseInfo;
import com.qiniu.android.http.metrics.UploadRegionRequestMetrics;
import com.qiniu.android.http.request.RequestTransaction;
import com.qiniu.android.http.request.handler.RequestProgressHandler;
import com.qiniu.android.storage.stream.IStreamFactory;
import com.qiniu.android.storage.stream.utils.StreamUtils;
import com.qiniu.android.utils.LogUtil;
import com.qiniu.android.utils.StringUtils;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;

class PartsUploadPerformerV1 extends PartsUploadPerformer {

    private static long BlockSize = 4 * 1024 * 1024;

    PartsUploadPerformerV1(IStreamFactory factory,
                           String fileName,
                           String key,
                           UpToken token,
                           UploadOptions options,
                           Configuration config,
                           String recorderKey) {
        super(factory, fileName, key, token, options, config, recorderKey);
    }

    @Override
    UploadFileInfo getFileFromJson(JSONObject jsonObject) {
        if (jsonObject == null) {
            return null;
        }
        return UploadFileInfoPartV1.fileFromJson(jsonObject);
    }

    @Override
    UploadFileInfo getDefaultUploadFileInfo() {
        return new UploadFileInfoPartV1(factory.sizeOfStream(), BlockSize, getUploadChunkSize(), factory.lastModifyTime());
    }

    @Override
    void serverInit(PartsUploadPerformerCompleteHandler completeHandler) {
        ResponseInfo responseInfo = ResponseInfo.successResponse();
        completeHandler.complete(responseInfo, null, null);
    }

    @Override
    void uploadNextData(final PartsUploadPerformerDataCompleteHandler completeHandler) {
        UploadFileInfoPartV1 uploadFileInfo = (UploadFileInfoPartV1) fileInfo;
        UploadBlock block = null;
        UploadData chunk = null;

        synchronized (this) {
            block = uploadFileInfo.nextUploadBlock();
            if (block != null) {
                chunk = block.nextUploadData();
                if (chunk != null) {
                    chunk.isUploading = true;
                    chunk.isCompleted = false;
                }
            }
        }
        if (block == null || chunk == null) {
            LogUtil.i("key:" + StringUtils.toNonnullString(key) + " no chunk left");

            ResponseInfo responseInfo = ResponseInfo.sdkInteriorError("no chunk left");
            completeHandler.complete(true, responseInfo, null, null);
            return;
        }

        chunk.data = getDataWithChunk(chunk, block);
        if (chunk.data == null) {
            LogUtil.i("key:" + StringUtils.toNonnullString(key) + " no chunk left");

            chunk.isUploading = false;
            chunk.isCompleted = false;
            ResponseInfo responseInfo = ResponseInfo.localIOError("get data error");
            completeHandler.complete(true, responseInfo, null, null);
            return;
        }

        final UploadBlock uploadBlock = block;
        final UploadData uploadChunk = chunk;
        RequestProgressHandler progressHandler = new RequestProgressHandler() {
            @Override
            public void progress(long totalBytesWritten, long totalBytesExpectedToWrite) {
                uploadChunk.progress = (double) totalBytesWritten / (double) totalBytesExpectedToWrite;
                notifyProgress();
            }
        };
        PartsUploadPerformerCompleteHandler completeHandlerP = new PartsUploadPerformerCompleteHandler() {
            @Override
            public void complete(ResponseInfo responseInfo, UploadRegionRequestMetrics requestMetrics, JSONObject response) {

                uploadChunk.data = null;

                String blockContext = null;
                if (response != null) {
                    try {
                        blockContext = response.getString("ctx");
                    } catch (JSONException e) {
                    }
                }
                if (responseInfo.isOK() && blockContext != null) {
                    uploadBlock.context = blockContext;
                    uploadChunk.progress = 1;
                    uploadChunk.isUploading = false;
                    uploadChunk.isCompleted = true;
                    recordUploadInfo();
                    notifyProgress();
                } else {
                    uploadChunk.isUploading = false;
                    uploadChunk.isCompleted = false;

                }
                completeHandler.complete(false, responseInfo, requestMetrics, response);
            }
        };

        if (uploadChunk.isFirstData()) {
            LogUtil.i("key:" + StringUtils.toNonnullString(key) + " makeBlock");
            makeBlock(uploadBlock, uploadChunk, progressHandler, completeHandlerP);
        } else {
            LogUtil.i("key:" + StringUtils.toNonnullString(key) + " makeBlock");
            uploadChunk(uploadBlock, uploadChunk, progressHandler, completeHandlerP);
        }
    }

    @Override
    void completeUpload(final PartsUploadPerformerCompleteHandler completeHandler) {
        UploadFileInfoPartV1 uploadFileInfo = (UploadFileInfoPartV1) fileInfo;

        String[] contexts = null;
        ArrayList<String> contextsList = uploadFileInfo.allBlocksContexts();

        if (contextsList != null && contextsList.size() > 0) {
            contexts = contextsList.toArray(new String[contextsList.size()]);
        }

        final RequestTransaction transaction = createUploadRequestTransaction();
        transaction.makeFile(uploadFileInfo.size, fileName, contexts, true, new RequestTransaction.RequestCompleteHandler() {

            @Override
            public void complete(ResponseInfo responseInfo, UploadRegionRequestMetrics requestMetrics, JSONObject response) {
                destroyUploadRequestTransaction(transaction);
                completeHandler.complete(responseInfo, requestMetrics, response);
            }
        });
    }

    private void makeBlock(final UploadBlock block,
                           final UploadData chunk,
                           final RequestProgressHandler progressHandler,
                           final PartsUploadPerformerCompleteHandler completeHandler) {

        final RequestTransaction transaction = createUploadRequestTransaction();
        transaction.makeBlock(block.offset, block.size, chunk.data, true, progressHandler, new RequestTransaction.RequestCompleteHandler() {
            @Override
            public void complete(ResponseInfo responseInfo, UploadRegionRequestMetrics requestMetrics, JSONObject response) {

                destroyUploadRequestTransaction(transaction);
                completeHandler.complete(responseInfo, requestMetrics, response);
            }
        });
    }

    private void uploadChunk(final UploadBlock block,
                             final UploadData chunk,
                             final RequestProgressHandler progressHandler,
                             final PartsUploadPerformerCompleteHandler completeHandler) {

        final RequestTransaction transaction = createUploadRequestTransaction();
        transaction.uploadChunk(block.context, block.offset, chunk.data, chunk.offset, true, progressHandler, new RequestTransaction.RequestCompleteHandler() {
            @Override
            public void complete(ResponseInfo responseInfo, UploadRegionRequestMetrics requestMetrics, JSONObject response) {

                destroyUploadRequestTransaction(transaction);
                completeHandler.complete(responseInfo, requestMetrics, response);
            }
        });

    }

    private byte[] getDataWithChunk(UploadData chunk,
                                    UploadBlock block) {
        if (factory == null || chunk == null || block == null) {
            return null;
        }
        try {
            byte[] data;
            synchronized (factory) {
                final long offset = chunk.offset + block.offset;
                InputStream stream = factory.newStreamWithOffset(offset);
                data = StreamUtils.read(stream, (int) chunk.size);
            }
            return data;
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    private long getUploadChunkSize() {
        if (config.useConcurrentResumeUpload) {
            return BlockSize;
        } else {
            return config.chunkSize;
        }
    }
}
