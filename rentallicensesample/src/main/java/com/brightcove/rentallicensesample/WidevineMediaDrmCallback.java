/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.brightcove.rentallicensesample;

import android.annotation.TargetApi;
import android.net.Uri;
import android.os.Build;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.text.TextUtils;
import android.util.Log;

import com.google.android.exoplayer2.ExoPlayerLibraryInfo;
import com.google.android.exoplayer2.drm.ExoMediaDrm;
import com.google.android.exoplayer2.drm.MediaDrmCallback;
import com.google.android.exoplayer2.upstream.DataSourceInputStream;
import com.google.android.exoplayer2.upstream.DataSpec;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource;
import com.google.android.exoplayer2.upstream.HttpDataSource;
import com.google.android.exoplayer2.util.Util;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static com.brightcove.rentallicensesample.DrmUtil.executePost;

@SuppressWarnings("WeakerAccess")
@TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR2)
public class WidevineMediaDrmCallback implements MediaDrmCallback {

    /**
     * A map of HTTP headers that should be added to the license request.
     */
    protected static final Map<String, String> REQUEST_HEADERS =
            Collections.singletonMap("Content-Type", "application/octet-stream");
    /**
     * The fully qualified URL to the DRM license service that will be used if the request does
     * include an URL.
     */
    protected final String defaultUrl;

    /**
     * Lock of synchronizing the read/write operations one {@link #optionalHeaders}.
     */
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    /**
     * Optional request headers to be passed to the license key request.
     */
    private Map<String, String> optionalHeaders;

    /**
     * Gets the optional request headers that will be passed to the license key request.
     *
     * @return null or reference to an immutable map of request headers.
     */
    @Nullable
    public Map<String, String> getOptionalHeaders() {
        lock.readLock().lock();
        try {
            return optionalHeaders;
        } finally {
            lock.readLock().unlock();
        }
    }

    public void setOptionalHeaders(@Nullable Map<String, String> optionalHeaders) {
        lock.writeLock().lock();
        try {
            this.optionalHeaders = optionalHeaders;
        } finally {
            lock.writeLock().unlock();
        }
    }
    /**
     * Constructs a new Widevine based Media DRM callback handler.
     *
     * @param defaultUrl the fully qualified URL to the DRM license service that will be used if
     *                   the request does include an URL.
     */
    public WidevineMediaDrmCallback(@Nullable String defaultUrl) {
        this.defaultUrl = defaultUrl;
    }

    public static WidevineMediaDrmCallback create(String licenseUrl) {

        return new WidevineMediaDrmCallback(licenseUrl);
    }

    @Override
    public byte[] executeProvisionRequest(UUID uuid, @NonNull ExoMediaDrm.ProvisionRequest request) throws IOException {
        return executeProvisionRequest(request.getDefaultUrl(), request.getData());
    }

    @Override
    public byte[] executeKeyRequest(UUID uuid, @NonNull ExoMediaDrm.KeyRequest request) throws IOException {
        return executeKeyRequest(request.getDefaultUrl(), request.getData());
    }

    protected byte[] executeProvisionRequest(String url, byte[] data) throws IOException {
        if (TextUtils.isEmpty(url)) {
            url = defaultUrl;
        }

        return executePost(url + "&signedRequest=" + new String(data), null, null);
    }

    protected byte[] executeKeyRequest(String url, byte[] data) throws IOException {
        if (TextUtils.isEmpty(url)) {
            url = defaultUrl;
        }

        HashMap<String, String> requestHeaders = new HashMap<>(REQUEST_HEADERS);
        Map<String, String> headerMap = getOptionalHeaders();
        if (headerMap != null) {
            requestHeaders.putAll(headerMap);
        }

        return executePost(url, data, requestHeaders);
    }

}
