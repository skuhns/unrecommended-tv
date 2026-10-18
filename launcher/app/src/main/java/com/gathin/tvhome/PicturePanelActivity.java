package com.gathin.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/** The Picture panel opened from the home screen's Picture button. */
public class PicturePanelActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_picture);
        new PicturePanel(this, findViewById(R.id.panelRoot), new PicturePanel.Host() {
            @Override
            public void start(Intent intent) {
                startActivity(intent);
            }

            @Override
            public void beforeMenu() {
            }

            @Override
            public void afterLook(String summary) {
            }

            @Override
            public void close() {
                finish();
            }
        });
    }
}
