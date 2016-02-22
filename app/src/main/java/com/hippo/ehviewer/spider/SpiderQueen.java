/*
 * Copyright 2016 Hippo Seven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hippo.ehviewer.spider;

import android.content.Context;
import android.os.Process;
import android.support.annotation.IntDef;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.UiThread;
import android.util.Log;
import android.util.SparseArray;
import android.webkit.MimeTypeMap;

import com.hippo.ehviewer.AppConfig;
import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.GetText;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.EhConfig;
import com.hippo.ehviewer.client.EhRequestBuilder;
import com.hippo.ehviewer.client.EhUrl;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.client.data.NormalPreviewSet;
import com.hippo.ehviewer.client.exception.Image509Exception;
import com.hippo.ehviewer.client.exception.ParseException;
import com.hippo.ehviewer.client.parser.GalleryDetailParser;
import com.hippo.ehviewer.client.parser.GalleryPageParser;
import com.hippo.ehviewer.client.parser.GalleryPageUrlParser;
import com.hippo.ehviewer.gallery.GalleryProvider;
import com.hippo.image.Image;
import com.hippo.unifile.UniFile;
import com.hippo.yorozuya.IOUtils;
import com.hippo.yorozuya.OSUtils;
import com.hippo.yorozuya.PriorityThread;
import com.hippo.yorozuya.PriorityThreadFactory;
import com.hippo.yorozuya.StringUtils;
import com.hippo.yorozuya.io.InputStreamPipe;
import com.hippo.yorozuya.io.OutputStreamPipe;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.Stack;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class SpiderQueen implements Runnable {

    private static final String TAG = SpiderQueen.class.getSimpleName();
    private static final AtomicInteger sIdGenerator = new AtomicInteger();

    @IntDef({MODE_READ, MODE_DOWNLOAD})
    @Retention(RetentionPolicy.SOURCE)
    public @interface Mode {}

    @IntDef({STATE_NONE, STATE_DOWNLOADING, STATE_FINISHED, STATE_FAILED})
    @Retention(RetentionPolicy.SOURCE)
    public @interface State {}

    public static final int MODE_READ = 0;
    public static final int MODE_DOWNLOAD = 1;

    public static final int STATE_NONE = 0;
    public static final int STATE_DOWNLOADING = 1;
    public static final int STATE_FINISHED = 2;
    public static final int STATE_FAILED = 3;

    private static final int NUMBER_SPIDER_WORKER = 3;
    private static final int NUMBER_PRELOAD = 5;

    private static final String SPIDER_INFO_FILENAME = ".ehviewer";

    private static final String[] URL_509_SUFFIX_ARRAY = {
            "/509.gif",
            "/509s.gif"
    };

    private static SparseArray<SpiderQueen> sQueenMap = new SparseArray<>();

    private final OkHttpClient mHttpClient;
    private final GalleryInfo mGalleryInfo;
    private volatile SpiderDen mSpiderDen;

    private int mReadReference = 0;
    private int mDownloadReference = 0;
    @Mode
    private int mMode;

    // It mQueenThread is null, failed or stopped
    @Nullable
    private volatile Thread mQueenThread;
    private final Object mQueenLock = new Object();
    private ThreadFactory mThreadFactory = new PriorityThreadFactory(
            SpiderWorker.class.getSimpleName(), Process.THREAD_PRIORITY_BACKGROUND);

    @Nullable
    private Thread mDecoderThread;
    private final Stack<Integer> mDecodeRequestStack = new Stack<>();

    private volatile AtomicReferenceArray<Thread> mWorkers;
    private final Object mWorkerLock = new Object();

    private final Object mPTokenLock = new Object();
    private volatile SpiderInfo mSpiderInfo;
    private Queue<Integer> mRequestPTokenQueue = new LinkedList<>();

    private final Object mPageStateLock = new Object();
    private volatile int[] mPageStateArray;

    // Store request page. The index may be invalid
    private final Queue<Integer> mRequestPageQueue = new LinkedList<>();
    // Store preload page. The index may be invalid
    private final Queue<Integer> mRequestPageQueue2 = new LinkedList<>();
    // Store force request page. The index may be invalid
    private final Queue<Integer> mForceRequestPageQueue = new LinkedList<>();
    // For download, when it go to mPageStateArray.size(), done
    private volatile int mDownloadPage = -1;

    private final Set<Integer> mRequestPreviewPageSet = new HashSet<>();

    private AtomicInteger mDownloadedPages = new AtomicInteger(0);
    private AtomicInteger mFinishedPages = new AtomicInteger(0);

    // Store page error
    private ConcurrentHashMap<Integer, String> mPageErrorMap = new ConcurrentHashMap<>();
    // Store page download percent
    private ConcurrentHashMap<Integer, Float> mPagePercentMap = new ConcurrentHashMap<>();

    private final List<OnSpiderListener> mSpiderListeners = new ArrayList<>();

    private SpiderQueen(EhApplication application, GalleryInfo galleryInfo) {
        mHttpClient = EhApplication.getOkHttpClient(application);
        mGalleryInfo = galleryInfo;
    }

    public void addOnSpiderListener(OnSpiderListener listener) {
        synchronized (mSpiderListeners) {
            mSpiderListeners.add(listener);
        }
    }

    public void removeOnSpiderListener(OnSpiderListener listener) {
        synchronized (mSpiderListeners) {
            mSpiderListeners.remove(listener);
        }
    }

    private void notifyGetPages(int pages) {
        synchronized (mSpiderListeners) {
            for (OnSpiderListener listener : mSpiderListeners) {
                listener.onGetPages(pages);
            }
        }
    }

    private void notifyGet509(int index) {
        synchronized (mSpiderListeners) {
            for (OnSpiderListener listener : mSpiderListeners) {
                listener.onGet509(index);
            }
        }
    }

    private void notifyDownload(int index, long contentLength, long receivedSize, int bytesRead) {
        synchronized (mSpiderListeners) {
            for (OnSpiderListener listener : mSpiderListeners) {
                listener.onDownload(index, contentLength, receivedSize, bytesRead);
            }
        }
    }

    private void notifySuccess(int index) {
        synchronized (mSpiderListeners) {
            for (OnSpiderListener listener : mSpiderListeners) {
                listener.onSuccess(index);
            }
        }
    }

    private void notifyFailure(int index, String error) {
        synchronized (mSpiderListeners) {
            for (OnSpiderListener listener : mSpiderListeners) {
                listener.onFailure(index, error);
            }
        }
    }

    private void notifyGetImageSuccess(int index, Image image) {
        synchronized (mSpiderListeners) {
            for (OnSpiderListener listener : mSpiderListeners) {
                listener.onGetImageSuccess(index, image);
            }
        }
    }

    private void notifyGetImageFailure(int index, String error) {
        synchronized (mSpiderListeners) {
            for (OnSpiderListener listener : mSpiderListeners) {
                listener.onGetImageFailure(index, error);
            }
        }
    }

    @UiThread
    public static SpiderQueen obtainSpiderQueen(@NonNull Context context,
            @NonNull GalleryInfo galleryInfo, @Mode int mode) {
        OSUtils.checkMainLoop();

        SpiderQueen queen = sQueenMap.get(galleryInfo.gid);
        if (queen == null) {
            EhApplication application = (EhApplication) context.getApplicationContext();
            queen = new SpiderQueen(application, galleryInfo);
            sQueenMap.put(galleryInfo.gid, queen);
            // Set mode
            queen.setMode(mode);
            queen.start();
        } else {
            // Set mode
            queen.setMode(mode);
        }
        return queen;
    }

    @UiThread
    public static void releaseSpiderQueen(@NonNull SpiderQueen queen, @Mode int mode) {
        OSUtils.checkMainLoop();

        // Clear mode
        queen.clearMode(mode);

        if (queen.mReadReference == 0 && queen.mDownloadReference == 0) {
            // Stop and remove if there is no reference
            queen.stop();
            sQueenMap.remove(queen.mGalleryInfo.gid);
        }
    }

    private void updateMode() {
        if (mDownloadReference > 0) {
            mMode = MODE_DOWNLOAD;
        } else {
            mMode = MODE_READ;
        }

        SpiderDen spiderDen = mSpiderDen;
        if (spiderDen != null) {
            spiderDen.setMode(mMode);
        }

        // Update download page
        if (mMode == MODE_DOWNLOAD) {
            mDownloadPage = 0;
        } else {
            mDownloadPage = -1;
        }
    }

    private void setMode(@Mode int mode) {
        switch (mode) {
            case MODE_READ:
                mReadReference++;
                break;
            case MODE_DOWNLOAD:
                mDownloadReference++;
                break;
        }

        if (mDownloadReference > 1) {
            throw new IllegalStateException("mDownloadReference can't more than 0");
        }

        updateMode();
    }

    private void clearMode(@Mode int mode) {
        switch (mode) {
            case MODE_READ:
                mReadReference--;
                break;
            case MODE_DOWNLOAD:
                mDownloadReference--;
                break;
        }

        if (mReadReference < 0 || mDownloadReference < 0) {
            throw new IllegalStateException("Mode reference < 0");
        }

        updateMode();
    }

    private void start() {
        Thread queenThread = new PriorityThread(this, TAG + '-' + sIdGenerator.incrementAndGet(),
                Process.THREAD_PRIORITY_BACKGROUND);
        mQueenThread = queenThread;
        queenThread.start();
    }

    private void stop() {
        Thread queenThread = mQueenThread;
        if (queenThread != null) {
            queenThread.interrupt();
            mQueenThread = null;
        }
    }

    public int size() {
        if (mQueenThread == null) {
            return GalleryProvider.STATE_ERROR;
        } else if (mPageStateArray == null) {
            return GalleryProvider.STATE_WAIT;
        } else {
            return mPageStateArray.length;
        }
    }

    public String getError() {
        // TODO
        if (mQueenThread == null) {
            return "Error";
        } else {
            return null;
        }
    }

    public Object forceRequest(int index) {
        return request(index, true);
    }

    public Object request(int index) {
        return request(index, false);
    }

    /**
     * @return
     * String for error<br>
     * Float for download percent<br>
     * null for wait
     */
    private Object request(int index, boolean force) {
        if (mQueenThread == null) {
            return null;
        }

        // Get page state
        int state = STATE_NONE;
        synchronized (mPageStateLock) {
            if (mPageStateArray != null && index >= 0 && index < mPageStateArray.length) {
                state = mPageStateArray[index];
            }
        }

        // Fix state for force
        if (force && (state == STATE_FINISHED || state == STATE_FAILED)) {
            state = STATE_NONE;
        }

        switch (state) {
            default:
            case STATE_NONE:
                // Add to request queue
                synchronized (mRequestPageQueue) {
                    if (force) {
                        mForceRequestPageQueue.add(index);
                    } else {
                        mRequestPageQueue.add(index);
                        mRequestPageQueue2.clear();
                        int[] pageStateArray = mPageStateArray;
                        int size;
                        if (pageStateArray != null) {
                            size = pageStateArray.length;
                        } else {
                            size = Integer.MAX_VALUE;
                        }
                        for (int i = index + 1, n = index + i + NUMBER_PRELOAD; i < n && i < size; i++) {
                            mRequestPageQueue2.add(i);
                        }
                    }
                }
                // Only ensure workers when get pages
                if (mPageStateArray != null) {
                    ensureWorkers();
                }
                return null;
            case STATE_DOWNLOADING:
                return mPagePercentMap.get(index);
            case STATE_FAILED:
                String error = mPageErrorMap.get(index);
                if (error == null) {
                    error = GetText.getString(R.string.error_unknown);
                }
                return error;
            case STATE_FINISHED:
                synchronized (mDecodeRequestStack) {
                    mDecodeRequestStack.add(index);
                    mDecodeRequestStack.notify();
                }
                return null;
        }
    }

    private void ensureWorkers() {
        synchronized (mWorkerLock) {
            if (mWorkers == null) {
                mWorkers = new AtomicReferenceArray<>(NUMBER_SPIDER_WORKER);
            }

            for (int i = 0, n = mWorkers.length(); i < n; i++) {
                if (mWorkers.get(i) == null) {
                    SpiderWorker worker = new SpiderWorker(i);
                    Thread thread = mThreadFactory.newThread(worker);
                    mWorkers.lazySet(i, thread);
                    thread.start();
                }
            }
        }
    }

    private SpiderInfo readSpiderInfoFromLocal() {
        // Read from download dir
        UniFile downloadDir = mSpiderDen.getDownloadDir();
        if (downloadDir != null) {
            UniFile file = downloadDir.findFile(SPIDER_INFO_FILENAME);
            SpiderInfo spiderInfo = SpiderInfo.readFromUniFile(file);
            if (spiderInfo != null && spiderInfo.gid == mGalleryInfo.gid &&
                    spiderInfo.token.equals(mGalleryInfo.token)) {
                return spiderInfo;
            }
        }

        // Read from cache
        File dir = AppConfig.getSpiderInfoCacheDir();
        if (dir != null) {
            UniFile file = UniFile.fromFile(new File(dir, Integer.toString(mGalleryInfo.gid)));
            SpiderInfo spiderInfo = SpiderInfo.readFromUniFile(file);
            if (spiderInfo != null && spiderInfo.gid == mGalleryInfo.gid &&
                    spiderInfo.token.equals(mGalleryInfo.token)) {
                return spiderInfo;
            }
        }

        return null;
    }

    private SpiderInfo readSpiderInfoFromInternet(EhConfig config) {
        try {
            SpiderInfo spiderInfo = new SpiderInfo();
            spiderInfo.gid = mGalleryInfo.gid;
            spiderInfo.token = mGalleryInfo.token;

            Request request = new EhRequestBuilder(EhUrl.getGalleryDetailUrl(
                    mGalleryInfo.gid, mGalleryInfo.token, 0, false), config).build();
            Response response = mHttpClient.newCall(request).execute();
            String body = response.body().string();

            spiderInfo.pages = GalleryDetailParser.parsePages(body);
            spiderInfo.previewPages = GalleryDetailParser.parsePreviewPages(body);
            NormalPreviewSet previewSet = GalleryDetailParser.parseNormalPreviewSet(body);
            spiderInfo.previewPerPage = previewSet.size();
            spiderInfo.pTokenMap = new SparseArray<>(spiderInfo.pages);
            for (int i = 0, n = previewSet.size(); i < n; i++) {
                GalleryPageUrlParser.Result result = GalleryPageUrlParser.parse(previewSet.getPageUrlAt(i));
                if (result != null) {
                    spiderInfo.pTokenMap.put(result.page, result.pToken);
                }
            }

            return spiderInfo;
        } catch (Exception e) {
            return null;
        }
    }

    private String getPTokenFromInternet(int index, EhConfig config) {
        // Check previewIndex
        int previewIndex = index / mSpiderInfo.previewPerPage;
        synchronized (mRequestPreviewPageSet) {
            if (mRequestPreviewPageSet.contains(previewIndex)) {
                return SpiderInfo.TOKEN_WAIT;
            }
            mRequestPreviewPageSet.add(previewIndex);
        }

        try {
            String url = EhUrl.getGalleryDetailUrl(
                    mGalleryInfo.gid, mGalleryInfo.token, previewIndex, false);
            Log.d(TAG, url);
            Request request = new EhRequestBuilder(url, config).build();
            Response response = mHttpClient.newCall(request).execute();
            String body = response.body().string();
            NormalPreviewSet previewSet = GalleryDetailParser.parseNormalPreviewSet(body);
            synchronized (mPTokenLock) {
                for (int i = 0, n = previewSet.size(); i < n; i++) {
                    GalleryPageUrlParser.Result result = GalleryPageUrlParser.parse(previewSet.getPageUrlAt(i));
                    if (result != null) {
                        mSpiderInfo.pTokenMap.put(result.page, result.pToken);
                    }
                }
                writeSpiderInfoToLocal(mSpiderInfo);
            }
            return mSpiderInfo.pTokenMap.get(index);
        } catch (Exception e) {
            return null;
        } finally {
            synchronized (mRequestPreviewPageSet) {
                mRequestPreviewPageSet.remove(previewIndex);
            }
        }
    }

    private void writeSpiderInfoToLocal(@NonNull SpiderInfo spiderInfo) {
        // Write to download dir
        UniFile downloadDir = mSpiderDen.getDownloadDir();
        if (downloadDir != null) {
            UniFile file = downloadDir.createFile(SPIDER_INFO_FILENAME);
            try {
                spiderInfo.write(file.openOutputStream());
            } catch (Exception e) {
                // Ignore
            }
        }

        // Read from cache
        File dir = AppConfig.getSpiderInfoCacheDir();
        if (dir != null) {
            try {
                spiderInfo.write(new FileOutputStream(new File(dir, Integer.toString(mGalleryInfo.gid))));
            } catch (Exception e) {
                // Ignore
            }
        }
    }

    private void runInternal() {
        mSpiderDen = new SpiderDen(mGalleryInfo);
        mSpiderDen.setMode(mMode);

        // Get EhConfig
        EhConfig config = Settings.getEhConfig().clone();
        config.previewSize = EhConfig.PREVIEW_SIZE_NORMAL;
        config.setDirty();

        // Read spider info
        SpiderInfo spiderInfo = readSpiderInfoFromLocal();

        // Check interrupted
        if (Thread.currentThread().isInterrupted()) {
            return;
        }

        // Spider info from internet
        if (spiderInfo == null) {
            spiderInfo = readSpiderInfoFromInternet(config);
        }

        // Error! Can't get spiderInfo
        if (spiderInfo == null) {
            return;
        }
        mSpiderInfo = spiderInfo;

        // Check interrupted
        if (Thread.currentThread().isInterrupted()) {
            return;
        }

        // Write spider info to file
        writeSpiderInfoToLocal(spiderInfo);

        // Check interrupted
        if (Thread.currentThread().isInterrupted()) {
            return;
        }

        // Setup page state
        synchronized (mPageStateLock) {
            mPageStateArray = new int[spiderInfo.pages];
        }

        // Notify get pages
        notifyGetPages(spiderInfo.pages);

        // Ensure worker
        boolean startWorkers = false;
        synchronized (mRequestPageQueue) {
            if (!mForceRequestPageQueue.isEmpty() || !mRequestPageQueue.isEmpty() || !mRequestPageQueue2.isEmpty() ||
                    mDownloadPage >= 0 && mDownloadPage < mPageStateArray.length) {
                startWorkers = true;
            }
        }
        if (startWorkers) {
            ensureWorkers();
        }

        // Start spider decoder
        Thread decoderThread = new PriorityThread(new SpiderDecoder(), "SpiderDecoder-" + sIdGenerator.incrementAndGet(),
                Process.THREAD_PRIORITY_BACKGROUND);
        mDecoderThread = decoderThread;
        decoderThread.start();

        // handle pToken request
        while (!Thread.currentThread().isInterrupted()) {
            Integer index;
            synchronized (mPTokenLock) {
                index = mRequestPTokenQueue.poll();
            }

            if (index == null) {
                // No request index, wait here
                synchronized (mQueenLock) {
                    try {
                        mQueenLock.wait();
                    } catch (InterruptedException e) {
                        break;
                    }
                }
                continue;
            }

            // Check it in spider info
            String pToken;
            synchronized (mPTokenLock) {
                pToken = spiderInfo.pTokenMap.get(index);
            }
            if (pToken != null) {
                // Get pToken from spider info, notify worker
                synchronized (mWorkerLock) {
                    mWorkerLock.notifyAll();
                }
                continue;
            }

            // Get pToken from internet
            pToken = getPTokenFromInternet(index, config);

            if (null == pToken || SpiderInfo.TOKEN_WAIT.equals(pToken)) {
                // If failed, set the pToken "failed"
                synchronized (mPTokenLock) {
                    mSpiderInfo.pTokenMap.put(index, SpiderInfo.TOKEN_FAILED);
                }
            }

            // Notify worker
            if (!SpiderInfo.TOKEN_WAIT.equals(pToken)) {
                synchronized (mWorkerLock) {
                    mWorkerLock.notifyAll();
                }
            }
        }
    }

    @Override
    public void run() {
        Log.i(TAG, Thread.currentThread().getName() + ": start");

        runInternal();

        // Set mQueenThread null
        mQueenThread = null;

        // Interrupt decoder
        Thread decoderThread = mDecoderThread;
        if (decoderThread != null) {
            decoderThread.interrupt();
        }

        // Interrupt all workers
        synchronized (mWorkerLock) {
            if (mWorkers != null) {
                for (int i = 0, n = mWorkers.length(); i < n; i++) {
                    Thread thread = mWorkers.get(i);
                    if (thread != null) {
                        thread.interrupt();
                        mWorkers.set(i, null);
                    }
                }
                mWorkers = null;
            }
        }

        Log.i(TAG, Thread.currentThread().getName() + ": end");
    }

    private class SpiderWorker implements Runnable {

        private int mWorkerIndex;
        private int mGid;

        public SpiderWorker(int workerIndex) {
            mWorkerIndex = workerIndex;
            mGid = mGalleryInfo.gid;
        }

        private void updatePageState(int index, @State int state) {
            updatePageState(index, state, null);
        }

        private void updatePageState(int index, @State int state, String error) {
            int oldState;
            synchronized (mPageStateLock) {
                oldState = mPageStateArray[index];
                mPageStateArray[index] = state;
            }

            if (oldState == STATE_NONE && state != STATE_NONE) {
                mDownloadedPages.incrementAndGet();
            } else if (oldState != STATE_NONE && state == STATE_NONE) {
                mDownloadedPages.decrementAndGet();
            }
            if (oldState != STATE_FINISHED && state == STATE_FINISHED) {
                mFinishedPages.incrementAndGet();
            } else if (oldState == STATE_FINISHED && state != STATE_FINISHED) {
                mFinishedPages.decrementAndGet();
            }

            // Clear
            if (state == STATE_DOWNLOADING) {
                mPageErrorMap.remove(index);
            } else if (state == STATE_FINISHED || state == STATE_FAILED) {
                mPagePercentMap.remove(index);
            }

            // Notify listeners
            if (state == STATE_FAILED) {
                if (error == null) {
                    error = GetText.getString(R.string.error_unknown);
                }
                mPageErrorMap.put(index, error);
                notifyFailure(index, error);
            } else if (state == STATE_FINISHED) {
                notifySuccess(index);
            }
        }

        private GalleryPageParser.Result getImageUrl(int gid, int index, String pToken,
                String skipHathKey) throws IOException, ParseException, Image509Exception {
            String url = EhUrl.getPageUrl(gid, index, pToken);
            if (skipHathKey != null) {
                url = url + "?nl=" + skipHathKey;
            }
            Log.d(TAG, url);
            Response response = mHttpClient.newCall(new EhRequestBuilder(url).build()).execute();
            String body = response.body().string();
            GalleryPageParser.Result result = GalleryPageParser.parse(body);
            if (StringUtils.endsWith(result.imageUrl, URL_509_SUFFIX_ARRAY)) {
                // Get 509
                // Notify listeners
                notifyGet509(index);
                throw new Image509Exception();
            }

            return result;
        }

        // false for stop
        private boolean downloadImage(int gid, int index, String pToken) {
            String skipHathKey = null;
            String imageUrl;
            String error = null;
            boolean interrupt = false;

            // Try twice
            for (int i = 0; i < 2; i++) {
                GalleryPageParser.Result result = null;
                try {
                    result = getImageUrl(gid, index, pToken, skipHathKey);
                } catch (MalformedURLException e) {
                    error = GetText.getString(R.string.error_invalid_url);
                } catch (IOException e) {
                    error = GetText.getString(R.string.error_socket);
                } catch (ParseException e) {
                    error = GetText.getString(R.string.error_parse_error);
                } catch (Image509Exception e) {
                    error = GetText.getString(R.string.error_509);
                }
                if (result == null) {
                    // Get image url failed
                    break;
                }
                // Check interrupted
                if (Thread.currentThread().isInterrupted()) {
                    interrupt = true;
                    break;
                }

                imageUrl = result.imageUrl;
                skipHathKey = result.skipHathKey;

                Log.d(TAG, imageUrl);

                // Start download image
                OutputStreamPipe pipe = mSpiderDen.openOutputStreamPipe(
                        index, MimeTypeMap.getFileExtensionFromUrl(imageUrl));
                if (pipe == null) {
                    // Can't get pipe
                    error = GetText.getString(R.string.error_write_failed);
                    break;
                }

                // Download image
                InputStream is = null;
                try {
                    Log.d(TAG, "Start download image " + index);

                    Response response = mHttpClient.newCall(new EhRequestBuilder(imageUrl).build()).execute();
                    ResponseBody responseBody = response.body();
                    long contentLength = responseBody.contentLength();
                    is = responseBody.byteStream();
                    pipe.obtain();
                    OutputStream os = pipe.open();

                    final byte data[] = new byte[1024 * 4];
                    long receivedSize = 0;

                    while (!Thread.currentThread().isInterrupted()) {
                        int bytesRead = is.read(data);
                        if (bytesRead == -1) {
                            break;
                        }
                        os.write(data, 0, bytesRead);
                        receivedSize += bytesRead;
                        // Update page percent
                        if (contentLength > 0) {
                            mPagePercentMap.put(index, (float) receivedSize / contentLength);
                        }
                        // Notify listener
                        notifyDownload(index, contentLength, receivedSize, bytesRead);
                    }
                    os.flush();

                    // Check interrupted
                    if (Thread.currentThread().isInterrupted()) {
                        interrupt = true;
                        break;
                    }

                    Log.d(TAG, "Download image succeed " + index);

                    // Download finished
                    updatePageState(index, STATE_FINISHED);
                    return true;
                } catch (IOException e) {
                    error = GetText.getString(R.string.error_socket);
                } finally {
                    IOUtils.closeQuietly(is);
                    pipe.close();
                    pipe.release();

                    Log.d(TAG, "End download image " + index);
                }
            }

            // Remove download failed image
            mSpiderDen.remove(index);

            updatePageState(index, STATE_FAILED, error);
            return !interrupt;
        }

        // false for stop
        private boolean runInternal() {
            int size = mPageStateArray.length;

            // Get request index
            int index;
            // From force request
            boolean force = false;
            synchronized (mRequestPageQueue) {
                if (!mForceRequestPageQueue.isEmpty()) {
                    index = mForceRequestPageQueue.remove();
                    force = true;
                } else if (!mRequestPageQueue.isEmpty()) {
                    index = mRequestPageQueue.remove();
                } else if (!mRequestPageQueue2.isEmpty()) {
                    index = mRequestPageQueue2.remove();
                } else if (mDownloadPage >= 0 && mDownloadPage < size) {
                    index = mDownloadPage;
                    mDownloadPage++;
                } else {
                    // No index any more, stop
                    return false;
                }

                // Check out of range
                if (index < 0 || index >= size) {
                    // Invalid index
                    return true;
                }
            }

            synchronized (mPageStateLock) {
                // Check the page state
                int state = mPageStateArray[index];
                if (state == STATE_DOWNLOADING || (!force && (state == STATE_FINISHED || state == STATE_FAILED))) {
                    return true;
                }

                // Set state downloading
                updatePageState(index, STATE_DOWNLOADING);
            }

            // Check exist for not force request
            if (!force && mSpiderDen.contain(index)) {
                updatePageState(index , STATE_FINISHED);
                return true;
            }

            // Clear TOKEN_FAILED for force request
            if (force) {
                synchronized (mPTokenLock) {
                    int i = mSpiderInfo.pTokenMap.indexOfKey(index);
                    String pToken = mSpiderInfo.pTokenMap.valueAt(i);
                    if (SpiderInfo.TOKEN_FAILED.equals(pToken)) {
                        mSpiderInfo.pTokenMap.setValueAt(i, null);
                    }
                }
            }

            String pToken = null;
            // Get token
            while (!Thread.currentThread().isInterrupted()) {
                synchronized (mPTokenLock) {
                    pToken = mSpiderInfo.pTokenMap.get(index);
                }
                if (pToken == null) {
                    mRequestPTokenQueue.add(index);
                    // Notify Queen
                    synchronized (mQueenLock) {
                        mQueenLock.notify();
                    }
                    // Wait
                    synchronized (mWorkerLock) {
                        try {
                            mWorkerLock.wait();
                        } catch (InterruptedException e) {
                            // Interrupted
                            Log.d(TAG, Thread.currentThread().getName() + " Interrupted");
                            break;
                        }
                    }
                } else {
                    break;
                }
            }

            if (pToken == null) {
                // Interrupted
                // Get token failed
                updatePageState(index, STATE_FAILED, null);
                return false;
            }

            if (SpiderInfo.TOKEN_FAILED.equals(pToken)) {
                // Get token failed
                updatePageState(index, STATE_FAILED, GetText.getString(R.string.error_get_ptoken_error));
                return true;
            }

            // Get image url
            return downloadImage(mGid, index, pToken);
        }

        @Override
        @SuppressWarnings("StatementWithEmptyBody")
        public void run() {
            Log.i(TAG, Thread.currentThread().getName() + ": start");

            while (!Thread.currentThread().isInterrupted() && runInternal());

            // Clear in spider worker array
            synchronized (mWorkerLock) {
                if (mWorkers != null && mWorkerIndex < mWorkers.length() &&
                        mWorkers.get(mWorkerIndex) == Thread.currentThread()) {
                    mWorkers.lazySet(mWorkerIndex, null);
                }
            }

            Log.i(TAG, Thread.currentThread().getName() + ": end");
        }
    }

    private class SpiderDecoder implements Runnable {

        @Override
        public void run() {
            Log.i(TAG, Thread.currentThread().getName() + ": start");

            while (!Thread.currentThread().isInterrupted()) {
                int index;
                synchronized (mDecodeRequestStack) {
                    if (mDecodeRequestStack.isEmpty()) {
                        try {
                            mDecodeRequestStack.wait();
                        } catch (InterruptedException e) {
                            // Interrupted
                            break;
                        }
                        continue;
                    }
                    index = mDecodeRequestStack.pop();
                }

                // Check index valid
                if (index < 0 || index >= mPageStateArray.length) {
                    notifyGetImageFailure(index, GetText.getString(R.string.error_out_of_range));
                    continue;
                }

                InputStreamPipe pipe = mSpiderDen.openInputStreamPipe(index);
                if (pipe == null) {
                    notifyGetImageFailure(index, GetText.getString(R.string.error_not_found));
                    continue;
                }

                try {
                    pipe.obtain();
                    Image image = Image.decode(pipe.open(), false);
                    if (image != null) {
                        notifyGetImageSuccess(index, image);
                    } else {
                        notifyGetImageFailure(index, GetText.getString(R.string.error_decoding_failed));
                    }
                } catch (IOException e) {
                    notifyGetImageFailure(index, GetText.getString(R.string.error_reading_failed));
                } finally {
                    pipe.close();
                    pipe.release();
                }
            }

            Log.i(TAG, Thread.currentThread().getName() + ": end");
        }
    }

    public interface OnSpiderListener {

        void onGetPages(int pages);

        void onGet509(int index);

        /**
         * @param contentLength -1 for unknown
         */
        void onDownload(int index, long contentLength, long receivedSize, int bytesRead);

        void onSuccess(int index);

        void onFailure(int index, String error);

        void onGetImageSuccess(int index, Image image);

        void onGetImageFailure(int index, String error);
    }
}