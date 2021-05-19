/*
    Copyright (C) 2019 Pixel Experience

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.
 */
package com.android.updater;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.android.updater.misc.Constants;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;

import io.noties.markwon.AbstractMarkwonPlugin;
import io.noties.markwon.Markwon;
import io.noties.markwon.MarkwonPlugin;
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin;
import io.noties.markwon.html.HtmlPlugin;

public class LocalChangelogActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_local_changelog);
        new getChangelogDialog().execute(Constants.historychglog);
    }

    private class getChangelogDialog extends AsyncTask<String, Void, String> {

        @SuppressLint("WrongThread")
        protected String doInBackground( String... strings) {
            String outputString = "";
            String inputString;
            int i = 0;

            TextView textView = findViewById(R.id.fetchfailed);
            ProgressBar loading = findViewById(R.id.loading);

            try {
                textView.setVisibility(View.VISIBLE);
                loading.setVisibility(View.VISIBLE);
                textView.setText(R.string.changelog_loading);
                URL changelog = new URL(strings[0]);
                BufferedReader in = new BufferedReader(new InputStreamReader(changelog.openStream(),"UTF-8"));

                while((inputString = in.readLine()) != null) {

                    if (i >= 0) {
                        outputString += inputString + "\n";
                    }
                    i++;
                }

                in.close();
                textView.setVisibility(View.INVISIBLE);
                loading.setVisibility(View.INVISIBLE);
            } catch(IOException e) {
                Log.e("ChangeLogAct:", "Could not fetch changelog from " + strings[0]);
                loading.setVisibility(View.INVISIBLE);
                textView.setVisibility(View.VISIBLE);
                textView.setText(R.string.changelog_fetch_failed);
            }
            return outputString;

        }

        protected void onPostExecute(String result) {
            TextView textView = findViewById(R.id.changelog_text);
            final Markwon markwoncontent;
            markwoncontent = Markwon.builder(textView.getContext()).usePlugin(HtmlPlugin.create())
                    .usePlugin(StrikethroughPlugin.create())
                    .usePlugin(new AbstractMarkwonPlugin() {
                        @Override
                        public void configure(@NonNull MarkwonPlugin.Registry registry) {
                            registry.require(HtmlPlugin.class, htmlPlugin -> htmlPlugin
                                    .addHandler(new UpdatesListAdapter.ColorTagHandler()));
                        }
                    })
                    .build();
            markwoncontent.setMarkdown(textView, result);
        }
    }

}
