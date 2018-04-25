package com.brightcove.rentallicensesample;

import android.net.Uri;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Base64;
import android.util.Log;
import android.util.Pair;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.drm.DrmInitData;
import com.google.android.exoplayer2.drm.DrmSession;
import com.google.android.exoplayer2.drm.FrameworkMediaDrm;
import com.google.android.exoplayer2.drm.OfflineLicenseHelper;
import com.google.android.exoplayer2.drm.UnsupportedDrmException;
import com.google.android.exoplayer2.source.dash.DashUtil;
import com.google.android.exoplayer2.source.dash.manifest.DashManifest;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSourceFactory;

import java.io.IOException;
import java.util.Date;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;

public class MainActivity extends AppCompatActivity {

    private static final String VIDEO_URL = "http://solutions.brightcove.com/jwhisenant/dash/40961/manifest.mpd";
    private static final String LICENSE_URL = "https://manifest.prod.boltdns.net/license/v1/cenc/widevine/5420904993001/669faeb9-d71d-438f-9427-267f2791fd97/18414a8c-91c7-40fc-a8e8-7653a6793db7?fastly_token=NWIwNTdlMmJfYmMyNmNlYmE1MDNmNDNlNWEyZjFjODI2YWU2NzQ3NjcxNzZiMmYzNjcyNWU2NGU2NzE5ZTMxZTg1YTEwMmZkNg%3D%3D";
    private static final String customerRightsTokenJsonString = "{\"profile\":{\"rental\":{\"absoluteExpiration\":\"2018-04-25T04:00:00.619Z\",\"playDuration\":124300}},\"storeLicense\":true}";
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        final TextView expirationDate = findViewById(R.id.expiration_text);
        final Button rentbutton = findViewById(R.id.rent_license);
        rentbutton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            final Date date = downloadLicense();
                            expirationDate.post(new Runnable() {
                                @Override
                                public void run() {
                                    expirationDate.setVisibility(View.VISIBLE);
                                    expirationDate.setText("Expiration: "+date);
                                }
                            });
                        } catch (IOException e) {
                            e.printStackTrace();
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        } catch (UnsupportedDrmException e) {
                            e.printStackTrace();
                        } catch (DrmSession.DrmSessionException e) {
                            e.printStackTrace();
                        }
                    }
                }).start();
            }
        });
    }

    private OfflineLicenseHelper createLicenseHelperExo() {
        //Create LicenseManager
        try {
            //FrameworkMediaDrm.newInstance(C.WIDEVINE_UUID);
            OfflineLicenseHelper offlineLicenseHelper = OfflineLicenseHelper.newWidevineInstance(LICENSE_URL, true, new DefaultHttpDataSourceFactory("ExoPlayer"));


        } catch (UnsupportedDrmException e) {
            e.printStackTrace();
        }
        return null;
    }

    private OfflineLicenseHelper createLicenseHelperBC(WidevineMediaDrmCallback widevineMediaDrmCallback) throws UnsupportedDrmException {
        FrameworkMediaDrm mediaDrm = FrameworkMediaDrm.newInstance(C.WIDEVINE_UUID);
        return new com.google.android.exoplayer2.drm.OfflineLicenseHelper<>(C.WIDEVINE_UUID,
                mediaDrm, widevineMediaDrmCallback, null);
    }

    private Date downloadLicense() throws IOException, InterruptedException, UnsupportedDrmException, DrmSession.DrmSessionException {
        DataSource dataSource = DrmUtil.createHttpDataSource(null);
        DashManifest dashManifest = DashUtil.loadManifest(dataSource, Uri.parse(VIDEO_URL));
        DrmInitData drmInitData = DashUtil.loadDrmInitData(dataSource, dashManifest.getPeriod(0));

        WidevineMediaDrmCallback callback = WidevineMediaDrmCallback.create(LICENSE_URL);
        OfflineLicenseHelper offlineLicenseHelper = createLicenseHelperBC(callback);

        byte[] licenseKeySetId = null;
        if (drmInitData == null) {
            licenseKeySetId = null;
        } else {

            HashMap<String, String> requestHeaders = new HashMap<>();
            String crTokenValue = Base64.encodeToString(
                    customerRightsTokenJsonString.getBytes(), Base64.NO_WRAP);
            requestHeaders.put("X-BC-CRT-CONFIG", crTokenValue);

            WidevineMediaDrmCallback widevineMediaDrmCallback = (WidevineMediaDrmCallback) callback;
            widevineMediaDrmCallback.setOptionalHeaders(requestHeaders);
            try {
                licenseKeySetId = offlineLicenseHelper.downloadLicense(drmInitData);
            } catch (com.google.android.exoplayer2.drm.DrmSession.DrmSessionException e) {
                Log.e("DRM", "Failed to download license", e);
            }
        }

        boolean success = licenseKeySetId != null;
        Date expiryDate = null;
        if (success) {
            Pair<Long, Long> duration = offlineLicenseHelper.getLicenseDurationRemainingSec(licenseKeySetId);
            expiryDate = new Date(System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(duration.first));

        }
        //return licenseKeySetId;
        return expiryDate;
    }
}
