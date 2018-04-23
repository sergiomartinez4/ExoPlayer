package com.brightcove.rentallicensesample;

import android.net.Uri;
import android.os.Build;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import com.google.android.exoplayer2.ExoPlayerLibraryInfo;
import com.google.android.exoplayer2.upstream.DataSourceInputStream;
import com.google.android.exoplayer2.upstream.DataSpec;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource;
import com.google.android.exoplayer2.upstream.HttpDataSource;
import com.google.android.exoplayer2.util.Util;

import java.io.IOException;
import java.util.Locale;
import java.util.Map;

/**
 * Created by smartinez on 4/23/18.
 */

public final class DrmUtil {

    public static byte[] executePost(String url, @Nullable byte[] data, @Nullable Map<String, String> requestProperties)
            throws IOException {
        HttpDataSource dataSource = createHttpDataSource(requestProperties);

        if (data == null) {
            data = new byte[0];
        }
        DataSpec dataSpec = new DataSpec(Uri.parse(url), data, 0, 0, -1, null,
                DataSpec.FLAG_ALLOW_GZIP);
        DataSourceInputStream inputStream = new DataSourceInputStream(dataSource, dataSpec);
        try {
            return Util.toByteArray(inputStream);
        } finally {
            Util.closeQuietly(inputStream);
        }
    }

    public static HttpDataSource createHttpDataSource(@Nullable Map<String, String> requestProperties) {
        HttpDataSource dataSource = new DefaultHttpDataSource(makeHttpUserAgent("BrightcoveExoPlayer", "ExoPlayerLib/" + ExoPlayerLibraryInfo.VERSION),
                null);
        if (requestProperties != null) {
            for (Map.Entry<String, String> requestProperty : requestProperties.entrySet()) {
                dataSource.setRequestProperty(requestProperty.getKey(), requestProperty.getValue());
            }
        }
        return dataSource;
    }

    public static String makeHttpUserAgent(@NonNull String playerName, @Nullable String suffix) {
        return String.format(Locale.getDefault(), "%s/%s (Linux;Android %s)%s",
                playerName, "2.7.3", Build.VERSION.RELEASE,
                suffix == null ? "" : " " + suffix
        );
    }
}
