package com.qiniu.android;

import android.test.InstrumentationTestCase;
import android.test.suitebuilder.annotation.LargeTest;
import android.test.suitebuilder.annotation.MediumTest;
import android.util.Log;

import com.qiniu.android.common.FixedZone;
import com.qiniu.android.common.Zone;
import com.qiniu.android.http.ResponseInfo;
import com.qiniu.android.storage.Configuration;
import com.qiniu.android.storage.UpCompletionHandler;
import com.qiniu.android.storage.UpProgressHandler;
import com.qiniu.android.storage.UploadManager;
import com.qiniu.android.storage.UploadOptions;
import com.qiniu.android.utils.AsyncRun;
import com.qiniu.android.utils.Etag;

import junit.framework.Assert;

import org.json.JSONObject;

import java.io.File;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class ResumeUploadTest extends BaseTest {

    String TAG = this.getClass().getSimpleName();
    private UploadManager uploadManager;
    private volatile String key;
    private volatile ResponseInfo info = null;
    private volatile JSONObject resp;
    private Queue<Double> queue = new LinkedList<Double>() {{
        offer(0.00000001);
        offer(0.00000001);
        offer(0.00000001);
        offer(0.00000001);
        offer(0.00000001);
    }};

    private void putProgress(double size) {
        if (size > 0.94) {
            return;
        }
        queue.offer(size);
        if (queue.size() > 4) {
            queue.poll();
        }
    }

    private boolean isProgressAllSame() {
        double size = 0.00000001;
        double _lit = 0.0000000001;
        boolean same = true;
        Iterator<Double> it = queue.iterator();
        while (it.hasNext()) {
            double temp = it.next();
            same = same && (Math.abs(size - temp) < _lit);
            size = temp;
        }
        return same;
    }

    private String getProgress() {
        StringBuilder ss = new StringBuilder("");
        Iterator<Double> it = queue.iterator();
        while (it.hasNext()) {
            ss.append(it.next()).append(", ");
        }
        return ss.toString();
    }

    private UploadOptions getUploadOptions() {
        return new UploadOptions(null, null, false, new UpProgressHandler() {
            public void progress(String key, double percent) {
                Log.d(TAG, "== percent:" + percent);
                putProgress(percent);
            }
        }, null);
    }

    public void setUp() throws Exception {
        Configuration config = new Configuration.Builder()
                .useConcurrentResumeUpload(false)
                .build();
        uploadManager = new UploadManager(config);
    }

    private void template(int size) throws Throwable {

        final String expectKey = "android-resume-test1-" + size + "k";
        final File f = TempFile.createFile(size);
        final UploadOptions options = getUploadOptions();
        AsyncRun.runInMain(new Runnable() { // THIS IS THE KEY TO SUCCESS
            public void run() {
                uploadManager.put(f, expectKey, TestConfig.token_z0, new UpCompletionHandler() {
                    public void complete(String k, ResponseInfo rinfo, JSONObject response) {
                        Log.i("qiniutest", k + rinfo);
                        key = k;
                        info = rinfo;
                        resp = response;
                    }
                }, options);
            }
        });

        wait(new WaitConditional() {
            @Override
            public boolean shouldWait() {
                if (resp == null){
                    return true;
                } else {
                    return false;
                }
            }
        }, 1200);

        assertTrue(info.toString(), info.isOK());
        assertNotNull(info.reqId);
        assertEquals(info.toString(), expectKey, key);
        String hash = resp.getString("hash");
        assertEquals(hash, Etag.file(f));
        TempFile.remove(f);
    }

    private void template2(int size) throws Throwable {

        final String expectKey = "android-resume-test2-" + size + "k";
        final File f = TempFile.createFile(size);
        String[] s = new String[]{"up.qbox.me"};
        Zone z = new FixedZone(s);
        Configuration c = new Configuration.Builder()
                .zone(z).useHttps(true).useConcurrentResumeUpload(false)
                .build();
        UploadManager uploadManager2 = new UploadManager(c);
        final UploadOptions options = getUploadOptions();
        uploadManager2.put(f, expectKey, TestConfig.token_z0, new UpCompletionHandler() {
            public void complete(String k, ResponseInfo rinfo, JSONObject response) {
                Log.i("qiniutest", k + rinfo);
                key = k;
                info = rinfo;
                resp = response;
            }
        }, options);

        wait(new WaitConditional() {
            @Override
            public boolean shouldWait() {
                if (resp == null){
                    return true;
                } else {
                    return false;
                }
            }
        }, 1200);

        assertTrue(info.toString(), info.isOK());
        assertNotNull(info.reqId);
        assertEquals(info.toString(), expectKey, key);
        String hash = resp.getString("hash");
        assertEquals(hash, Etag.file(f));
        TempFile.remove(f);
    }

    @LargeTest
    public void test4M1K() throws Throwable {
        template(1024 * 4 + 1);
    }

    @LargeTest
    public void test8M1L() throws Throwable {
        template(1024 * 8 + 1);
    }

    @LargeTest
    public void test20M1K() throws Throwable {
        template(1024 * 20 + 1);
    }

    @LargeTest
    public void test4M1k2() throws Throwable {
        template2(1024 * 4 + 1);
    }

    @LargeTest
    public void test8M1k2() throws Throwable {
        template2(1024 * 8 + 1);
    }

    @LargeTest
    public void test20M1k2() throws Throwable {
        template2(1024 * 20 + 1);
    }

}