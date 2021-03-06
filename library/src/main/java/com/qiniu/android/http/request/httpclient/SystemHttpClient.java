package com.qiniu.android.http.request.httpclient;

import com.qiniu.android.common.Constants;
import com.qiniu.android.http.CancellationHandler;
import com.qiniu.android.http.ProgressHandler;
import com.qiniu.android.http.ProxyConfiguration;
import com.qiniu.android.http.ResponseInfo;
import com.qiniu.android.http.dns.SystemDns;
import com.qiniu.android.http.request.Request;
import com.qiniu.android.http.request.IRequestClient;
import com.qiniu.android.http.metrics.UploadSingleRequestMetrics;
import com.qiniu.android.utils.AndroidNetwork;
import com.qiniu.android.utils.AsyncRun;
import com.qiniu.android.utils.StringUtils;


import org.json.JSONObject;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ProtocolException;
import java.net.Proxy;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLException;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Connection;
import okhttp3.ConnectionPool;
import okhttp3.Dns;
import okhttp3.EventListener;
import okhttp3.Handshake;
import okhttp3.Headers;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.internal.Version;

import static com.qiniu.android.http.ResponseInfo.NetworkError;

public class SystemHttpClient implements IRequestClient {

    public static final String ContentTypeHeader = "Content-Type";
    public static final String DefaultMime = "application/octet-stream";
    public static final String JsonMime = "application/json";
    public static final String FormMime = "application/x-www-form-urlencoded";

    private static ConnectionPool pool;
    private Request currentRequest;
    private OkHttpClient httpClient;
    private Call call;
    private UploadSingleRequestMetrics metrics;
    private RequestClientProgress requestProgress;
    private RequestClientCompleteHandler completeHandler;

    @Override
    public void request(Request request,
                        boolean isAsync,
                        ProxyConfiguration connectionProxy,
                        RequestClientProgress progress,
                        RequestClientCompleteHandler complete) {

        metrics = new UploadSingleRequestMetrics();
        metrics.clientName = "okhttp";
        metrics.clientVersion = Version.userAgent.replace("okhttp/", "");
        metrics.setRequest(request);
        currentRequest = request;
        httpClient = createHttpClient(connectionProxy);
        requestProgress = progress;
        completeHandler = complete;

        okhttp3.Request.Builder requestBuilder = createRequestBuilder(requestProgress);
        if (requestBuilder == null) {
            ResponseInfo responseInfo = ResponseInfo.invalidArgument("invalid http request");
            handleError(request, responseInfo.statusCode, responseInfo.message, complete);
            return;
        }

        ResponseTag tag = new ResponseTag();
        call = httpClient.newCall(requestBuilder.tag(tag).build());

        if (isAsync) {
            call.enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    e.printStackTrace();
                    String msg = e.getMessage();
                    int status = getStatusCodeByException(e);
                    if (call.isCanceled()) {
                        status = ResponseInfo.Cancelled;
                        msg = "user cancelled";
                    }
                    handleError(currentRequest, status, msg, completeHandler);
                }

                @Override
                public void onResponse(Call call, final okhttp3.Response response) throws IOException {
                    AsyncRun.runInBack(new Runnable() {
                        @Override
                        public void run() {
                            handleResponse(currentRequest, response, completeHandler);
                        }
                    });
                }
            });

        } else {
            try {
                okhttp3.Response response = call.execute();
                handleResponse(request, response, complete);
            } catch (Exception e) {
                e.printStackTrace();
                String msg = e.getMessage();
                int status = getStatusCodeByException(e);
                if (call.isCanceled()) {
                    status = ResponseInfo.Cancelled;
                    msg = "user cancelled";
                }
                handleError(request, status, msg, complete);
            }

        }
    }

    @Override
    public synchronized void cancel() {
        if (call != null && !call.isCanceled()) {
            call.cancel();
        }
    }

    private OkHttpClient createHttpClient(ProxyConfiguration connectionProxy) {
        if (currentRequest == null) {
            return null;
        }

        OkHttpClient.Builder clientBuilder = new OkHttpClient.Builder();
        if (connectionProxy != null) {
            clientBuilder.proxy(connectionProxy.proxy());
            if (connectionProxy.user != null && connectionProxy.password != null) {
                clientBuilder.proxyAuthenticator(connectionProxy.authenticator());
            }
        }


        clientBuilder.eventListener(createEventLister());

        clientBuilder.dns(new Dns() {
            @Override
            public List<InetAddress> lookup(String s) throws UnknownHostException {
                if (currentRequest.getInetAddress() != null && s.equals(currentRequest.host)) {
                    List<InetAddress> inetAddressList = new ArrayList<>();
                    inetAddressList.add(currentRequest.getInetAddress());
                    return inetAddressList;
                } else {
                    return new SystemDns().lookupInetAddress(s);
                }
            }
        });

        clientBuilder.connectionPool(SystemHttpClient.getConnectPool());

        clientBuilder.networkInterceptors().add(new Interceptor() {
            @Override
            public okhttp3.Response intercept(Chain chain) throws IOException {
                okhttp3.Request request = chain.request();
                final long before = System.currentTimeMillis();
                okhttp3.Response response = chain.proceed(request);
                final long after = System.currentTimeMillis();

                ResponseTag tag = (ResponseTag) request.tag();
                String ip = "";
                try {
                    ip = chain.connection().socket().getRemoteSocketAddress().toString();
                } catch (Exception e) {
                    e.printStackTrace();
                }
                tag.ip = ip;
                tag.duration = after - before;
                return response;
            }
        });

        clientBuilder.connectTimeout(currentRequest.timeout, TimeUnit.SECONDS);
        clientBuilder.readTimeout(currentRequest.timeout, TimeUnit.SECONDS);
        clientBuilder.writeTimeout(60, TimeUnit.SECONDS);

        return clientBuilder.build();
    }

    private synchronized static ConnectionPool getConnectPool() {
        if (pool == null) {
            pool = new ConnectionPool(5, 10, TimeUnit.MINUTES);
        }
        return pool;
    }

    private okhttp3.Request.Builder createRequestBuilder(final RequestClientProgress progress) {
        if (currentRequest == null) {
            return null;
        }

        Headers allHeaders = Headers.of(currentRequest.allHeaders);

        okhttp3.Request.Builder requestBuilder = null;
        if (currentRequest.httpMethod.equals(Request.HttpMethodHEAD) ||
                currentRequest.httpMethod.equals(Request.HttpMethodGet)) {
            requestBuilder = new okhttp3.Request.Builder().get().url(currentRequest.urlString);
            for (String key : currentRequest.allHeaders.keySet()) {
                String value = currentRequest.allHeaders.get(key);
                requestBuilder.header(key, value);
            }
        } else if (currentRequest.httpMethod.equals(Request.HttpMethodPOST) ||
                currentRequest.httpMethod.equals(Request.HttpMethodPUT)) {
            requestBuilder = new okhttp3.Request.Builder().url(currentRequest.urlString);
            requestBuilder = requestBuilder.headers(allHeaders);

            RequestBody rbody;
            if (currentRequest.httpBody.length > 0) {
                MediaType type = MediaType.parse(DefaultMime);
                String contentType = currentRequest.allHeaders.get(ContentTypeHeader);
                if (contentType != null) {
                    type = MediaType.parse(contentType);
                }
                rbody = new ByteBody(type, currentRequest.httpBody);
            } else {
                rbody = new ByteBody(null, new byte[0]);
            }
            rbody = new CountingRequestBody(rbody, new ProgressHandler() {
                @Override
                public void onProgress(long bytesWritten, long totalSize) {
                    if (progress != null) {
                        progress.progress(bytesWritten, totalSize);
                    }
                }
            }, currentRequest.httpBody.length, null);

            if (currentRequest.httpMethod.equals(Request.HttpMethodPOST)) {
                requestBuilder = requestBuilder.post(rbody);
            } else if (currentRequest.httpMethod.equals(Request.HttpMethodPUT)) {
                requestBuilder = requestBuilder.put(rbody);
            }

        }
        return requestBuilder;
    }

    private EventListener createEventLister() {
        return new EventListener() {
            @Override
            public void callStart(Call call) {
                metrics.startDate = new Date();
            }

            @Override
            public void dnsStart(Call call,
                                 String domainName) {
                metrics.domainLookupStartDate = new Date();
            }

            @Override
            public void dnsEnd(Call call,
                               String domainName,
                               List<InetAddress> inetAddressList) {
                metrics.domainLookupEndDate = new Date();
            }

            @Override
            public void connectStart(Call call,
                                     InetSocketAddress inetSocketAddress,
                                     Proxy proxy) {
                metrics.connectStartDate = new Date();
                metrics.remoteAddress = inetSocketAddress.getAddress().getHostAddress();
                metrics.remotePort = inetSocketAddress.getPort();
                metrics.localAddress = AndroidNetwork.getHostIP();
            }

            @Override
            public void secureConnectStart(Call call) {
                metrics.connectEndDate = new Date();
            }

            @Override
            public void secureConnectEnd(Call call,
                                         Handshake handshake) {
                metrics.secureConnectionStartDate = new Date();
            }

            @Override
            public void connectEnd(Call call,
                                   InetSocketAddress inetSocketAddress,
                                   Proxy proxy,
                                   Protocol protocol) {
                metrics.secureConnectionEndDate = new Date();
            }

            @Override
            public void connectFailed(Call call,
                                      InetSocketAddress inetSocketAddress,
                                      Proxy proxy,
                                      Protocol protocol,
                                      IOException ioe) {
                metrics.connectEndDate = new Date();
            }

            @Override
            public void connectionAcquired(Call call, Connection connection) {
            }

            @Override
            public void connectionReleased(Call call, Connection connection) {
            }

            @Override
            public void requestHeadersStart(Call call) {
                metrics.requestStartDate = new Date();
            }

            @Override
            public void requestHeadersEnd(Call call, okhttp3.Request request) {
                metrics.countOfRequestHeaderBytesSent = request.headers().toString().length();
            }

            @Override
            public void requestBodyStart(Call call) {
            }

            @Override
            public void requestBodyEnd(Call call, long byteCount) {
                metrics.requestEndDate = new Date();
                metrics.countOfRequestBodyBytesSent = byteCount;
            }

            public void requestFailed(Call call, IOException ioe) {
                metrics.requestEndDate = new Date();
                metrics.countOfRequestBodyBytesSent = 0;
            }

            @Override
            public void responseHeadersStart(Call call) {
                metrics.responseStartDate = new Date();
            }

            @Override
            public void responseHeadersEnd(Call call, Response response) {

            }

            @Override
            public void responseBodyStart(Call call) {
            }

            @Override
            public void responseBodyEnd(Call call, long byteCount) {
                metrics.responseEndDate = new Date();
            }

            public void responseFailed(Call call, IOException ioe) {
                metrics.responseEndDate = new Date();
            }

            @Override
            public void callEnd(Call call) {
                metrics.endDate = new Date();
            }

            @Override
            public void callFailed(Call call, IOException ioe) {
                metrics.endDate = new Date();
            }
        };
    }

    private synchronized void handleError(Request request,
                                          int responseCode,
                                          String errorMsg,
                                          RequestClientCompleteHandler complete) {
        if (metrics == null || metrics.response != null) {
            return;
        }

        ResponseInfo info = ResponseInfo.create(request, responseCode, null, null, errorMsg);
        metrics.response = info;
        metrics.request = request;
        complete.complete(info, metrics, info.response);

        releaseResource();
    }

    private synchronized void handleResponse(Request request,
                                             okhttp3.Response response,
                                             RequestClientCompleteHandler complete) {
        if (metrics == null || metrics.response != null) {
            return;
        }

        int statusCode = response.code();

        HashMap<String, String> responseHeader = new HashMap<String, String>();
        int headerCount = response.headers().size();
        for (int i = 0; i < headerCount; i++) {
            String name = response.headers().name(i).toLowerCase();
            String value = response.headers().value(i);
            responseHeader.put(name, value);
        }

        byte[] responseBody = null;
        JSONObject responseJson = null;
        String errorMessage = null;
        try {
            responseBody = response.body().bytes();
        } catch (IOException e) {
            errorMessage = e.getMessage();
        }

        if (responseBody == null) {
            errorMessage = response.message();
        } else if (responseContentType(response) != "application/json") {
            String responseString = new String(responseBody);
            if (responseString.length() > 0) {
                try {
                    responseJson = new JSONObject(responseString);
                } catch (Exception ignored) {
                }
            }
        } else {
            try {
                responseJson = buildJsonResp(responseBody);
            } catch (Exception e) {
                statusCode = ResponseInfo.ParseError;
                errorMessage = e.getMessage();
            }
        }


        final ResponseInfo info = ResponseInfo.create(request, statusCode, responseHeader, responseJson, errorMessage);
        metrics.response = info;
        metrics.request = request;
        if (response.protocol() == Protocol.HTTP_1_0) {
            metrics.httpVersion = "1.0";
        } else if (response.protocol() == Protocol.HTTP_1_1) {
            metrics.httpVersion = "1.1";
        } else if (response.protocol() == Protocol.HTTP_2) {
            metrics.httpVersion = "2";
        }
        complete.complete(info, metrics, info.response);

        releaseResource();
    }

    private void releaseResource() {
        this.currentRequest = null;
        this.requestProgress = null;
        this.completeHandler = null;
        this.metrics = null;
        this.httpClient = null;
        this.call = null;
    }

    private static String responseContentType(okhttp3.Response response) {
        MediaType mediaType = response.body().contentType();
        if (mediaType == null) {
            return "";
        }
        return mediaType.type() + "/" + mediaType.subtype();
    }

    private int getStatusCodeByException(Exception e) {
        int statusCode = NetworkError;
        String msg = e.getMessage();
        if (msg != null && msg.contains("Canceled")) {
            statusCode = ResponseInfo.Cancelled;
        } else if (e instanceof CancellationHandler.CancellationException) {
            statusCode = ResponseInfo.Cancelled;
        } else if (e instanceof UnknownHostException) {
            statusCode = ResponseInfo.UnknownHost;
        } else if (msg != null && msg.indexOf("Broken pipe") == 0) {
            statusCode = ResponseInfo.NetworkConnectionLost;
        } else if (e instanceof SocketTimeoutException) {
            statusCode = ResponseInfo.TimedOut;
        } else if (e instanceof java.net.ConnectException) {
            statusCode = ResponseInfo.CannotConnectToHost;
        } else if (e instanceof ProtocolException) {
            statusCode = ResponseInfo.NetworkProtocolError;
        } else if (e instanceof SSLException) {
            statusCode = ResponseInfo.NetworkSSLError;
        }
        return statusCode;
    }

    private static JSONObject buildJsonResp(byte[] body) throws Exception {
        String str = new String(body, Constants.UTF_8);
        // ?????? ??? ?????????
        if (StringUtils.isNullOrEmpty(str)) {
            return new JSONObject();
        }
        return new JSONObject(str);
    }


    private static class ResponseTag {
        public String ip = "";
        public long duration = -1;
    }
}
