package com.brightcove.rentallicensesample;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v7.app.AppCompatActivity;
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

import java.io.IOException;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;

public class MainActivity extends AppCompatActivity {

    private static final String LICENSE_KEY_PREF = "license_key";
    private static final String VIDEO_URL = "http://solutions.brightcove.com/jwhisenant/dash/40961/manifest.mpd";
    private static final String LICENSE_URL = "https://manifest.prod.boltdns.net/license/v1/cenc/widevine/5420904993001/669faeb9-d71d-438f-9427-267f2791fd97/18414a8c-91c7-40fc-a8e8-7653a6793db7?fastly_token=NWIwNTdlMmJfYmMyNmNlYmE1MDNmNDNlNWEyZjFjODI2YWU2NzQ3NjcxNzZiMmYzNjcyNWU2NGU2NzE5ZTMxZTg1YTEwMmZkNg%3D%3D";
    private static final String customerRightsTokenJsonString = "{\"profile\":{\"rental\":{\"absoluteExpiration\":\"2018-05-25T04:00:00.619Z\",\"playDuration\":124300}},\"storeLicense\":true}";

    WidevineMediaDrmCallback widevineMediaDrmCallback;
    OfflineLicenseHelper offlineLicenseHelper;

    TextView expirationDate;
    Button rentButton;
    Button removeButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        widevineMediaDrmCallback = WidevineMediaDrmCallback.create(LICENSE_URL);
        try {
            offlineLicenseHelper = createLicenseHelperBC(widevineMediaDrmCallback);
        } catch (UnsupportedDrmException e) {
            e.printStackTrace();
        }

        expirationDate = findViewById(R.id.expiration_text);
        rentButton = findViewById(R.id.rent_license);
        removeButton = findViewById(R.id.release_license);

        byte[] licenseId = getSavedLicenseID();
        if (licenseId != null) {
            Date date = getExpirationDate(licenseId);
            updateExpirationText(date);
        }
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

        byte[] licenseKeySetId = null;
        if (drmInitData == null) {
            licenseKeySetId = null;
        } else {

            HashMap<String, String> requestHeaders = new HashMap<>();
            String crTokenValue = Base64.encodeToString(
                    customerRightsTokenJsonString.getBytes(), Base64.NO_WRAP);
            requestHeaders.put("X-BC-CRT-CONFIG", crTokenValue);

            widevineMediaDrmCallback.setOptionalHeaders(requestHeaders);
            try {
                licenseKeySetId = offlineLicenseHelper.downloadLicense(drmInitData);
            } catch (com.google.android.exoplayer2.drm.DrmSession.DrmSessionException e) {
                Log.e("DRM", "Failed to download license", e);
            }
        }

        Date expiryDate = null;
        if (licenseKeySetId != null) {
            saveLicenseID(licenseKeySetId);
            expiryDate = getExpirationDate(licenseKeySetId);
        }
        return expiryDate;
    }

    private Date getExpirationDate(@NonNull byte[] licenseKeySetId) {
        Pair<Long, Long> duration;
        Date expiryDate = null;
        try {
            //noinspection unchecked
            duration = offlineLicenseHelper.getLicenseDurationRemainingSec(licenseKeySetId);
            expiryDate = new Date(System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(duration.first));
        } catch (DrmSession.DrmSessionException e) {
            e.printStackTrace();
        }
        return expiryDate;
    }

    private void releaseLicense() {
        byte[] licenseId = getSavedLicenseID();
        if (licenseId != null) {
            try {
                offlineLicenseHelper.releaseLicense(licenseId);
            } catch (DrmSession.DrmSessionException e) {
                e.printStackTrace();
            }
        }
    }

    private void clearLicenseId() {
        SharedPreferences.Editor editor = getPreferences(Context.MODE_PRIVATE).edit();
        editor.clear();
        editor.apply();
    }

    private void saveLicenseID(byte[] licenseKey) {
        SharedPreferences preferences = getPreferences(Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putString(LICENSE_KEY_PREF, Arrays.toString(licenseKey));
        editor.apply();
    }

    private byte[] getSavedLicenseID () {
        SharedPreferences preferences = getPreferences(Context.MODE_PRIVATE);
        String stringArray = preferences.getString(LICENSE_KEY_PREF, null);

        byte[] licenseKeyArray = null;
        if (stringArray != null) {
            String[] split = stringArray.substring(1, stringArray.length()-1).split(", ");
            licenseKeyArray = new byte[split.length];
            for (int i = 0; i < split.length; i++) {
                licenseKeyArray[i] = Byte.parseByte(split[i]);
            }
        }
        return licenseKeyArray;
    }

    public void rent(View view) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final Date date = downloadLicense();
                    expirationDate.post(new Runnable() {
                        @Override
                        public void run() {
                            updateExpirationText(date);
                        }
                    });
                } catch (IOException | InterruptedException | UnsupportedDrmException | DrmSession.DrmSessionException e) {
                    e.printStackTrace();
                }
            }
        }).start();
    }

    public void remove(View view) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                releaseLicense();
                clearLicenseId();
                expirationDate.post(new Runnable() {
                    @Override
                    public void run() {
                        updateExpirationText(null);
                    }
                });
            }
        }).start();
    }

    private void updateButtons(boolean isLicenseAvailable) {
        if (isLicenseAvailable) {
            removeButton.setEnabled(true);
            rentButton.setEnabled(false);
        } else {
            removeButton.setEnabled(false);
            rentButton.setEnabled(true);
        }
    }

    @SuppressLint("SetTextI18n")
    private void updateExpirationText(@Nullable Date date) {
        if (date != null) {
            expirationDate.setVisibility(View.VISIBLE);
            expirationDate.setText("Expiration: " + date);
        } else {
            expirationDate.setVisibility(View.INVISIBLE);
            expirationDate.setText("");
        }
        updateButtons(date != null);
    }
}
