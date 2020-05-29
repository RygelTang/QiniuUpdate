package com.qiniu.android;

import android.util.Log;

import com.qiniu.android.http.ResponseInfo;
import com.qiniu.android.http.request.RequestTranscation;
import com.qiniu.android.http.request.handler.RequestProgressHandler;
import com.qiniu.android.http.metrics.UploadRegionRequestMetrics;
import com.qiniu.android.storage.Configuration;
import com.qiniu.android.storage.UpToken;
import com.qiniu.android.storage.UploadOptions;

import junit.framework.Assert;
import junit.framework.TestCase;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class RequestTranscationTest extends BaseTest {

    public void testUCQuery(){

        UpToken token = UpToken.parse(TestConfig.token_z0);

        ArrayList<String> hosts = new ArrayList<String>();
        hosts.add("uc.qbox.me");

        final WaitCondition waitCondition = new WaitCondition();

        RequestTranscation requestTranscation = new RequestTranscation(hosts, token);
        requestTranscation.quertUploadHosts(true, new RequestTranscation.RequestCompleteHandler() {
            @Override
            public void complete(ResponseInfo responseInfo, UploadRegionRequestMetrics requestMetrics, JSONObject response) {
                Assert.assertTrue("pass", responseInfo.isOK());
                waitCondition.shouldWait = false;
            }
        });

        wait(waitCondition, 60);
    }

    public void testUploadForm(){

        final WaitCondition waitCondition = new WaitCondition();

        UpToken token = UpToken.parse(TestConfig.token_z0);

        ArrayList<String> hosts = new ArrayList<String>();
        hosts.add("upload.qiniup.com");

        byte[] data = "你好".getBytes();
        RequestTranscation requestTranscation = new RequestTranscation(new Configuration.Builder().build(),
                UploadOptions.defaultOptions(),
                hosts, null,
                "android-transcation-form",
                token);
        requestTranscation.uploadFormData(data, null, true, new RequestProgressHandler() {
            @Override
            public void progress(long totalBytesWritten, long totalBytesExpectedToWrite) {
                Log.i("RequestTranscation", ("== progress: " + (totalBytesWritten*1.0/totalBytesExpectedToWrite)));
            }
        }, new RequestTranscation.RequestCompleteHandler() {
            @Override
            public void complete(ResponseInfo responseInfo, UploadRegionRequestMetrics requestMetrics, JSONObject response) {
                Assert.assertTrue("pass", responseInfo.isOK());
                waitCondition.shouldWait = false;
            }
        });

        wait(waitCondition, 60);
    }


    public void testUploadBlock(){

        final WaitCondition waitCondition = new WaitCondition();

        makeBlock(new RequestTranscation.RequestCompleteHandler() {
            @Override
            public void complete(ResponseInfo responseInfo, UploadRegionRequestMetrics requestMetrics, JSONObject response) {

                if (responseInfo.isOK()){

                    String ct = null;
                    try {
                        ct = response.getString("ctx");
                    } catch (JSONException e) {}

                    if (ct == null) {
                        Assert.assertTrue("pass", false);
                        waitCondition.shouldWait = false;
                        return;
                    }

                    uploadChunk(ct, new RequestTranscation.RequestCompleteHandler(){
                        @Override
                        public void complete(ResponseInfo responseInfo, UploadRegionRequestMetrics requestMetrics, JSONObject response) {

                            if (responseInfo.isOK()){

                                String ct_02 = null;
                                try {
                                    ct_02 = response.getString("ctx");
                                } catch (JSONException e) {}

                                if (ct_02 == null) {
                                    Assert.assertTrue("pass", false);
                                    waitCondition.shouldWait = false;
                                    return;
                                }

                                makeFile(new String[]{ct_02}, new RequestTranscation.RequestCompleteHandler() {
                                    @Override
                                    public void complete(ResponseInfo responseInfo, UploadRegionRequestMetrics requestMetrics, JSONObject response) {
                                        Assert.assertTrue("pass", responseInfo.isOK());
                                        waitCondition.shouldWait = false;
                                    }
                                });
                            } else {
                                Assert.assertTrue("pass", false);
                                waitCondition.shouldWait = false;
                            }
                        }
                    });
                } else {
                    Assert.fail("fail");
                    waitCondition.shouldWait = false;
                }
            }
        });

        wait(waitCondition, 60);
    }

    private void makeBlock(RequestTranscation.RequestCompleteHandler completeHandler){
        UpToken token = UpToken.parse(TestConfig.token_z0);

        ArrayList<String> hosts = new ArrayList<String>();
        hosts.add("upload.qiniup.com");

        byte[] data = new byte[2*1024*1024];
        RequestTranscation requestTranscation = new RequestTranscation(new Configuration.Builder().build(), UploadOptions.defaultOptions(), hosts, null, "android-transcation-block", token);
        requestTranscation.makeBlock(0, 3*1024*1024, data, true, new RequestProgressHandler() {
            @Override
            public void progress(long totalBytesWritten, long totalBytesExpectedToWrite) {
                Log.i("RequestTranscation", ("== progress: " + (totalBytesWritten*1.0/totalBytesExpectedToWrite)));
            }
        }, completeHandler);
    }

    private void uploadChunk(String ct, RequestTranscation.RequestCompleteHandler completeHandler){
        UpToken token = UpToken.parse(TestConfig.token_z0);

        ArrayList<String> hosts = new ArrayList<String>();
        hosts.add("upload.qiniup.com");

        byte[] data = new byte[1*1024*1024];
        RequestTranscation requestTranscation = new RequestTranscation(new Configuration.Builder().build(), UploadOptions.defaultOptions(), hosts, null,"android-transcation-block", token);
        requestTranscation.uploadChunk(ct,0, data, 2*1024*1024,true, new RequestProgressHandler() {
            @Override
            public void progress(long totalBytesWritten, long totalBytesExpectedToWrite) {
                Log.i("RequestTranscation", ("== progress: " + (totalBytesWritten*1.0/totalBytesExpectedToWrite)));
            }
        }, completeHandler);
    }

    private void makeFile(String[] blockContexts, RequestTranscation.RequestCompleteHandler completeHandler){
        UpToken token = UpToken.parse(TestConfig.token_z0);

        ArrayList<String> hosts = new ArrayList<String>();
        hosts.add("upload.qiniup.com");

        RequestTranscation requestTranscation = new RequestTranscation(new Configuration.Builder().build(), UploadOptions.defaultOptions(), hosts,null, "android-transcation-block", token);
        requestTranscation.makeFile(3*1024*1024, "android-transcation-block-fileName", blockContexts, true, completeHandler);
    }
}
