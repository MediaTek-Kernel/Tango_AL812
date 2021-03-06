/*
 * Copyright (C) 2007 The Android Open Source Project
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

package com.android.settings;

import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;

import android.widget.Toast;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.content.DialogInterface.OnDismissListener;
import android.content.res.Configuration;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.zip.GZIPInputStream;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemProperties;
import android.content.ActivityNotFoundException;
import java.io.File;

/**
 * The "dialog" that shows from "License" in the Settings app.
 */
public class SettingsHuaweiPrivacyActivity extends Activity {

    private static final String TAG = "SettingsLicenseActivity";
    private static final boolean LOGV = false || false;
	private static final String DEFAULT_LICENSE_PATH = "/system/etc/huawei_privacy_policy.html.gz";
    private static final String PROPERTY_LICENSE_PATH = "ro.config.license_path";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
		final String path = SystemProperties.get(PROPERTY_LICENSE_PATH, DEFAULT_LICENSE_PATH);
        if (TextUtils.isEmpty(path)) {
            Log.e(TAG, "The system property for the license file is empty");
            showErrorAndFinish();
            return;
        }

        final File file = new File(path);
        if (!file.exists() || file.length() == 0) {
            Log.e(TAG, "License file " + path + " does not exist");
            showErrorAndFinish();
            return;
        }

        // Kick off external viewer due to WebView security restrictions; we
        // carefully point it at HTMLViewer, since it offers to decompress
        // before viewing.
        final Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(Uri.fromFile(file), "text/html");
        intent.putExtra(Intent.EXTRA_TITLE, getString(R.string.settings_huawei_privacy_title));
        intent.addCategory(Intent.CATEGORY_DEFAULT);
        intent.setPackage("com.android.htmlviewer");

        try {
            startActivity(intent);
            finish();
        } catch (ActivityNotFoundException e) {
            Log.e(TAG, "Failed to find viewer", e);
            showErrorAndFinish();
        }
		

	}

    @Override
    protected void onResume() {
        super.onResume();
    }
    
    @Override
    protected void onStop() {
        super.onStop();             
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

    private void showPageOfText(String text) {

    }

    private void showErrorAndFinish() {
        Toast.makeText(this, R.string.settings_huawei_privacy_activity_unavailable, Toast.LENGTH_LONG)
                .show();
        finish();
    }
}
