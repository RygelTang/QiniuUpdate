package com.qiniu.android.storage;

import com.qiniu.android.common.Zone;
import com.qiniu.android.common.ZoneInfo;
import com.qiniu.android.common.ZonesInfo;
import com.qiniu.android.http.ResponseInfo;
import com.qiniu.android.http.metrics.UploadRegionRequestMetrics;
import com.qiniu.android.http.metrics.UploadTaskMetrics;
import com.qiniu.android.http.request.IUploadRegion;
import com.qiniu.android.http.serverRegion.UploadDomainRegion;
import com.qiniu.android.storage.stream.IStreamFactory;
import com.qiniu.android.storage.stream.impl.ByteStreamFactory;
import com.qiniu.android.storage.stream.impl.FileStreamFactory;

import org.json.JSONObject;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.ArrayList;

abstract class BaseUpload implements Runnable {
    protected final IStreamFactory factory;
    protected final String key;
    protected final String fileName;
    protected final UpToken token;
    protected final UploadOptions option;
    protected final Configuration config;
    protected final Recorder recorder;
    protected final String recorderKey;
    protected final UpTaskCompletionHandler completionHandler;

    private UploadRegionRequestMetrics currentRegionRequestMetrics;
    private UploadTaskMetrics metrics = new UploadTaskMetrics(null);

    private int currentRegionIndex;
    private ArrayList<IUploadRegion> regions;

    protected BaseUpload(IStreamFactory factory,
                         String fileName,
                         String key,
                         UpToken token,
                         UploadOptions option,
                         Configuration config,
                         Recorder recorder,
                         String recorderKey,
                         UpTaskCompletionHandler completionHandler) {
        this.factory = factory;
        this.fileName = fileName != null ? fileName : "?";
        this.key = key;
        this.token = token;
        this.option = option != null ? option : UploadOptions.defaultOptions();
        this.config = config;
        this.recorder = recorder;
        this.recorderKey = recorderKey;
        this.completionHandler = completionHandler;

        this.initData();
    }

    protected BaseUpload(File file,
                         String key,
                         UpToken token,
                         UploadOptions option,
                         Configuration config,
                         Recorder recorder,
                         String recorderKey,
                         UpTaskCompletionHandler completionHandler) throws FileNotFoundException {
        this(new FileStreamFactory(file), file.getName(), key, token, option, config, recorder, recorderKey, completionHandler);
    }

    protected BaseUpload(byte[] data,
                         String key,
                         String fileName,
                         UpToken token,
                         UploadOptions option,
                         Configuration config,
                         UpTaskCompletionHandler completionHandler) {
        this(new ByteStreamFactory(data), fileName, key, token, option, config, null, null, completionHandler);
    }

    protected void initData() {
        currentRegionIndex = 0;
    }


    @Override
    public void run() {
        config.zone.preQuery(token, new Zone.QueryHandler() {
            @Override
            public void complete(int code, ResponseInfo responseInfo, UploadRegionRequestMetrics requestMetrics) {
                metrics.addMetrics(requestMetrics);

                if (code == 0) {
                    int prepareCode = prepareToUpload();
                    if (prepareCode == 0) {
                        startToUpload();
                    } else {
                        ResponseInfo responseInfoP = ResponseInfo.errorInfo(prepareCode, null);
                        completeAction(responseInfoP, null);
                    }
                } else {
                    completeAction(responseInfo, responseInfo.response);
                }
            }
        });
    }

    protected int prepareToUpload() {
        int ret = 0;
        if (!setupRegions()) {
            ret = -1;
        }
        return ret;
    }

    protected abstract void startToUpload();

    protected boolean switchRegionAndUpload() {
        if (currentRegionRequestMetrics != null) {
            metrics.addMetrics(currentRegionRequestMetrics);
            currentRegionRequestMetrics = null;
        }
        boolean isSwitched = switchRegion();
        if (isSwitched) {
            startToUpload();
        }
        return isSwitched;
    }

    protected void completeAction(ResponseInfo responseInfo,
                                  JSONObject response) {
        if (currentRegionRequestMetrics != null && metrics != null) {
            metrics.addMetrics(currentRegionRequestMetrics);
        }
        if (completionHandler != null) {
            completionHandler.complete(responseInfo, key, metrics, response);
        }

        metrics = null;
        currentRegionRequestMetrics = null;
    }

    private boolean setupRegions() {
        if (config == null || config.zone == null) {
            return false;
        }
        ZonesInfo zonesInfo = config.zone.getZonesInfo(token);
        if (zonesInfo == null || zonesInfo.zonesInfo == null || zonesInfo.zonesInfo.size() == 0) {
            return false;
        }
        ArrayList<ZoneInfo> zoneInfos = zonesInfo.zonesInfo;

        ArrayList<IUploadRegion> defaultRegions = new ArrayList<>();
        for (ZoneInfo zoneInfo : zoneInfos) {
            UploadDomainRegion region = new UploadDomainRegion();
            region.setupRegionData(zoneInfo);
            if (region.isValid()) {
                defaultRegions.add(region);
            }
        }
        regions = defaultRegions;
        metrics.regions = defaultRegions;
        return defaultRegions.size() > 0;
    }

    protected void insertRegionAtFirst(IUploadRegion region) {
        if (region == null) {
            return;
        }

        boolean hasRegion = false;
        for (IUploadRegion regionP : regions) {
            if (region.isEqual(regionP)) {
                hasRegion = true;
                break;
            }
        }
        if (!hasRegion) {
            regions.add(0, region);
        }
    }

    protected boolean switchRegion() {
        if (regions == null) {
            return false;
        }
        boolean ret = false;
        synchronized (this) {
            int regionIndex = currentRegionIndex + 1;
            if (regionIndex < regions.size()) {
                currentRegionIndex = regionIndex;
                ret = true;
            }
        }
        return ret;
    }

    protected boolean switchRegionAndUploadIfNeededWithErrorResponse(ResponseInfo errorResponseInfo) {
        if (errorResponseInfo == null || errorResponseInfo.isOK() || // ????????? || ??????error ??????
                !errorResponseInfo.couldRetry() || !config.allowBackupHost || // ??????????????????
                !switchRegionAndUpload()) { // ????????????
            return false;
        } else {
            return true;
        }
    }

    protected IUploadRegion getTargetRegion() {
        if (regions == null || regions.size() == 0) {
            return null;
        } else {
            return regions.get(0);
        }
    }

    protected IUploadRegion getCurrentRegion() {
        if (regions == null) {
            return null;
        }
        IUploadRegion region = null;
        synchronized (this) {
            if (currentRegionIndex < regions.size()) {
                region = regions.get(currentRegionIndex);
            }
        }
        return region;
    }


    protected UploadRegionRequestMetrics getCurrentRegionRequestMetrics() {
        return currentRegionRequestMetrics;
    }

    // ?????????????????????????????????????????????????????????????????????????????????????????????????????????????????????Region???host???????????????
    protected void addRegionRequestMetricsOfOneFlow(UploadRegionRequestMetrics metrics) {
        if (metrics == null) {
            return;
        }
        if (this.currentRegionRequestMetrics == null) {
            this.currentRegionRequestMetrics = metrics;
        } else {
            this.currentRegionRequestMetrics.addMetrics(metrics);
        }
    }

    protected interface UpTaskCompletionHandler {
        void complete(ResponseInfo responseInfo,
                      String key,
                      UploadTaskMetrics requestMetrics,
                      JSONObject response);
    }
}
