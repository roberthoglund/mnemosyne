package org.mnemosyne;

import com.srplab.www.starcore.StarCoreFactory;
import com.srplab.www.starcore.StarCoreFactoryPath;
import com.srplab.www.starcore.StarObjectClass;
import com.srplab.www.starcore.StarServiceClass;
import com.srplab.www.starcore.StarSrvGroupClass;

import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.support.v4.content.ContextCompat;
import android.util.Log;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public class MnemosyneThread extends Thread {

    StarCoreFactory starcore;
    StarObjectClass python;
    StarObjectClass mnemosyne;
    StarObjectClass config;
    StarObjectClass database;
    StarObjectClass controller;
    StarObjectClass reviewController;
    StarObjectClass componentManager;
    StarObjectClass activateCardsDialog;
    MnemosyneActivity UIActivity;
    Handler mnemosyneHandler;
    Handler UIHandler;
    String basedir;
    ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    public MnemosyneThread(MnemosyneActivity activity, Handler handler, String packageName) {
        UIActivity = activity;
        UIHandler = handler;
        basedir = UIActivity.getApplicationInfo().dataDir;
    }

    public Handler getHandler() {
        return mnemosyneHandler;
    }

    public void startMnemosyne() {

        UIHandler.post(new Runnable() {
            public void run() {
                progressDialog = new ProgressDialog(UIActivity);
                progressDialog.setCancelable(false);
                progressDialog.setProgressStyle(ProgressDialog.STYLE_SPINNER);
                progressDialog.setMessage("Initialising Mnemosyne...");
                progressDialog.setIndeterminate(true);
                progressDialog.show();
            }
        });

        StarCoreFactoryPath.StarCoreCoreLibraryPath = basedir + "/lib";
        StarCoreFactoryPath.StarCoreShareLibraryPath = basedir + "/lib";
        StarCoreFactoryPath.StarCoreOperationPath = basedir + "/files";

        try{
            //--load python34 core library first;
            System.load(UIActivity.getApplicationInfo().nativeLibraryDir+"/libpython3.4m.so");
            System.load(UIActivity.getApplicationInfo().nativeLibraryDir+"/libstar_python34.so");
            System.load(basedir+"/files/lib-dynload/time.cpython-34m.so");
            System.load(basedir+"/files/lib-dynload/math.cpython-34m.so");
            System.out.println("Load trial success.");
        }
        catch(UnsatisfiedLinkError ex)
        {
            System.out.println(ex.toString());
        }

        String path = basedir + "/files/lib-dynload";
        //Log.i("Mnemosyne", "Listing files in path: " + path);
        File ff = new File(path);
        if (ff.exists()) {
            File files[] = ff.listFiles();
            //Log.i("Mnemosyne", "Number of files: " + files.length);
            for (int i = 0; i < files.length; i++) {
                //cannot locate symbol "crypt" referenced by "/data/data/org.mnemosyne/files/lib-dynload/_crypt.cpython-34m.so".
                //cannot locate symbol "setgrent" referenced by "/data/data/org.mnemosyne/files/lib-dynload/grp.cpython-34m.so".
                if (    !files[i].getName().contentEquals("_crypt.cpython-34m.so") &
                        !files[i].getName().contentEquals("grp.cpython-34m.so")   )
                //if ( files[i].getName().contentEquals("_md5.cpython-34m.so") |
                //        files[i].getName().contentEquals("_sha1.cpython-34m.so") |
                //        files[i].getName().contentEquals("_sha512.cpython-34m.so") |
                //        files[i].getName().contentEquals("_struct.cpython-34m.so") |
                //        files[i].getName().contentEquals("math.cpython-34m.so") |
                //        files[i].getName().contentEquals("_random.cpython-34m.so") |
                //        files[i].getName().contentEquals("_sha256.cpython-34m.so") |
                //        files[i].getName().contentEquals("_sqlite3.cpython-34m.so") |
                //        files[i].getName().contentEquals("time.cpython-34m.so") |
                //        files[i].getName().contentEquals("select.cpython-34m.so") |
                //        files[i].getName().contentEquals("_ctypes.cpython-34m.so") |
                //        files[i].getName().contentEquals("_socket.cpython-34m.so") |
                //        files[i].getName().contentEquals("_pickle.cpython-34m.so") |
                //        files[i].getName().contentEquals("binascii.cpython-34m.so") |
                //        files[i].getName().contentEquals("unicodedata.cpython-34m.so") |
                //        files[i].getName().contentEquals("_struct.cpython-34m.so") |
                //        files[i].getName().contentEquals("")
                //        )
                {
                    //Log.i("Mnemosyne", "Preloading FileName:" + files[i].getName() + " " + files[i].lastModified());
                    //System.load(files[i].getAbsolutePath());
                }
            }
        }

        Log.d("Mnemosyne", "About to initialise starcore");

        starcore = StarCoreFactory.GetFactory();
        StarSrvGroupClass SrvGroup = starcore._GetSrvGroup(0);
        StarServiceClass Service = SrvGroup._GetService("cle", "123");
        if (Service == null) {  // The service has not been initialized.
            Log.d("Mnemosyne", "Initialising starcore");
            Service = starcore._InitSimple("cle", "123", 0, 0);
            Service._CheckPassword(false);
            SrvGroup = (StarSrvGroupClass) Service._Get("_ServiceGroup");
            SrvGroup._InitRaw("python34", Service);
        }
        python = Service._ImportRawContext("python", "", false, "");

        // Set up extra paths.
        python._Call("import", "sys");
        StarObjectClass pythonSys = python._GetObject("sys");
        StarObjectClass pythonPath = (StarObjectClass) pythonSys._Get("path");
        pythonPath._Call("insert", 0, basedir + "/files");
        pythonPath._Call("insert", 0, basedir + "/files/python3.4.zip");
        pythonPath._Call("insert", 0, basedir + "/files/lib-dynload");
        pythonPath._Call("insert", 0, basedir + "/lib");

        Log.i("Mnemosyne", "Starting Mnemosyne Python script");

        // Start Mnemosyne.
        SrvGroup._LoadRawModule("python", "", basedir +
                "/files/mnemosyne/cle/mnemosyne_android.py", false);

        mnemosyne = python._GetObject("mnemosyne");

        // Determine datadir.
        //
        // A user can set a datadir directory by putting a file 'datadir.txt' with
        // the directory in the default datadir.
        // This file contains e.g. "/storage/3738-3234/Android/data/org.mnemosyne/files"
        // in order to use a true external SD card. Note that /Android/... part is
        // important, otherwise we don't get access.

        String dataDir = Environment.getExternalStorageDirectory().getPath() + "/Mnemosyne/";

        // Strangely enough we need this call first in order to be able to write
        // to the external directories.
        String dirList = "";
        for (File f : ContextCompat.getExternalFilesDirs(UIActivity, null)) {
            if (f != null) {  // Permission failure on some devices.
                dirList += f.getPath() + "\n\n";
            }
        }

        try {
            InputStream is = new FileInputStream(dataDir + "/datadir.txt");
            BufferedReader buf = new BufferedReader(new InputStreamReader(is));
            String line = buf.readLine();
            if (! line.isEmpty()) {
                dataDir = line;
            }
        }
        catch (FileNotFoundException e) {
            Log.i("Mnemosyne", "Redirection file not found:" + e.toString());
        }
        catch (IOException e) {
            Log.i("Mnemosyne", "Redirection file could not be read:" + e.toString());
        }

        File file = new File(dataDir);
        if (! file.exists()) {
            Boolean result = file.mkdirs();
            if (result == false) {
                showInformation("Could not create data dir at " + dataDir + "\n" +
                        "Use a directory like:\n\n" + dirList);
            }
        }
        else {
            try {
                File tmp = new File(dataDir + "/test.txt");
                if (tmp.exists()) {
                    tmp.delete();
                }
                BufferedWriter bwriter = new BufferedWriter(new FileWriter(new File(dataDir + "/test.txt")));
                bwriter.write("123");
                bwriter.close();
            } catch (IOException e) {
                e.printStackTrace();
                showInformation("Could not create file in " + dataDir + "\nMake sure to give Mnemosyne write permission.");
            }
        }

        Log.i("Mnemosyne", "datadir " + dataDir);

        String filename = "default.db";
        python._Call("start_mnemosyne", dataDir, filename, this);

        config = (StarObjectClass) mnemosyne._Call("config");
        database = (StarObjectClass) mnemosyne._Call("database");
        controller = (StarObjectClass) mnemosyne._Call("controller");
        componentManager = python._GetObject("mnemosyne.component_manager");
        reviewController = (StarObjectClass) mnemosyne._Call("review_controller");

        File file2 = new File(dataDir + "/.nomedia");
        if (!file2.exists()){
            try {
                file2.createNewFile();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        Log.i("Mnemosyne", "started Mnemosyne");

        UIHandler.post(new Runnable() {
            public void run() {
                progressDialog.dismiss();
                UIActivity.setFullscreen();
            }
        });

        // Heartbeat: run at startup and then every 5 seconds.
        controller._Call("heartbeat", false);
        this.scheduler.scheduleAtFixedRate(new Runnable() {
            public void run() {
                mnemosyneHandler.post(new Runnable() {
                    public void run() {
                        controller._Call("heartbeat", false);
                    }
                });
            }
        }, 0, 5, TimeUnit.SECONDS);
    }

    public void pauseMnemosyne() {
        python._Call("pause_mnemosyne");
    }

    public void stopMnemosyne() {
        this.scheduler.shutdownNow();
        python._Call("stop_mnemosyne");
        // Wait until the CLE core queue is empty.
        while (starcore._SRPDispatch(false) == true); // Empty loop, consume current queue message.
        starcore._SRPDispatch(true); // Wait a message cycle, the longest time is 10ms.
        Log.d("Mnemosyne", "Mnemosyne stopped");
        // This seems to be the best way to ensure that CLE can be restarted properly.
        android.os.Process.killProcess(android.os.Process.myPid());
    }

    public void Log(String label, String text) {
        Log.d(label, text);
    }

    @Override
    public void run() {
        Log.i("Mnemosyne", "About to run Mnemosyne thread");
        startMnemosyne();
        Looper.prepare();
        mnemosyneHandler = new Handler();
        Looper.loop();
    }

    public void setQuestionLabel(String label) {
        final String _label = label;
        UIHandler.post(new Runnable() {
            public void run() {
                UIActivity.questionLabel.setText(_label);
            }
        });
    }

    public void setQuestion(String html) {
        final String _html = html;
        UIHandler.post(new Runnable() {
            public void run() {
                UIActivity.setQuestion(_html);
            }
        });
    }

    public void setAnswer(String html, Boolean processAudio) {
        final String _html = html;
        final Boolean _processAudio = processAudio;
        UIHandler.post(new Runnable() {
            public void run() {
                UIActivity.setAnswer(_html, _processAudio);
            }
        });
    }

    public void setQuestionBoxVisible(boolean isVisible) {
        final boolean _isVisible = isVisible;
        UIHandler.post(new Runnable() {
            public void run() {
                if (_isVisible) {
                    UIActivity.question.setVisibility(android.view.View.VISIBLE);
                    UIActivity.questionLabel.setVisibility(android.view.View.VISIBLE);
                }
                else {
                    UIActivity.question.setVisibility(android.view.View.GONE);
                    UIActivity.questionLabel.setVisibility(android.view.View.GONE);
                }
            }
        });
    }

    public void setAnswerBoxVisible(boolean isVisible) {
        final boolean _isVisible = isVisible;
        UIHandler.post(new Runnable() {
            public void run() {
                if (_isVisible) {
                    UIActivity.answer.setVisibility(android.view.View.VISIBLE);
                    UIActivity.answerLabel.setVisibility(android.view.View.VISIBLE);
                }
                else {
                    UIActivity.answer.setVisibility(android.view.View.GONE);
                    UIActivity.answerLabel.setVisibility(android.view.View.GONE);
                }
            }
        });
    }

    public void updateShowButton(String text, boolean isDefault, boolean isEnabled) {
        // We completely ignore isEnabled here, and rather chose to set it in
        // 'setGradesEnabled'. The reason is that breaking this up into two 'setVisibility'
        // messages causes screen flicker, probably related to ordering with respect to a
        // system-issued 'layout' call.
        // See http://stackoverflow.com/questions/3544826/android-home-screen-like-effect-flickering-problem-when-set-child-setvisibility
        final String _text = text;
        UIHandler.post(new Runnable() {
            public void run() {
                UIActivity.showAnswerButton.setText(_text);
            }
        });
    }

    public void setGradesEnabled(boolean isEnabled) {
        final boolean _isEnabled = isEnabled;
        UIHandler.post(new Runnable() {
            public void run() {
                if (_isEnabled) {
                    UIActivity.button0.setVisibility(android.view.View.VISIBLE);
                    UIActivity.button1.setVisibility(android.view.View.VISIBLE);
                    UIActivity.button2.setVisibility(android.view.View.VISIBLE);
                    UIActivity.button3.setVisibility(android.view.View.VISIBLE);
                    UIActivity.button4.setVisibility(android.view.View.VISIBLE);
                    UIActivity.button5.setVisibility(android.view.View.VISIBLE);
                    UIActivity.showAnswerButton.setVisibility(android.view.View.GONE);
                }
                else {
                    UIActivity.button0.setVisibility(android.view.View.GONE);
                    UIActivity.button1.setVisibility(android.view.View.GONE);
                    UIActivity.button2.setVisibility(android.view.View.GONE);
                    UIActivity.button3.setVisibility(android.view.View.GONE);
                    UIActivity.button4.setVisibility(android.view.View.GONE);
                    UIActivity.button5.setVisibility(android.view.View.GONE);
                    UIActivity.showAnswerButton.setVisibility(android.view.View.VISIBLE);
                }
            }
        });
    }

    public void setStatusbarText(String text) {
        final String _text = text;
        UIHandler.post(new Runnable() {
            public void run() {
                UIActivity.statusbar.setText(_text);
            }
        });
    }

    public void showInformation(String text) {
        final String _text = text;
        UIHandler.post(new Runnable() {
            public void run() {
                AlertDialog.Builder alert = new AlertDialog.Builder(UIActivity);
                alert.setMessage(_text);
                alert.setCancelable(false);
                alert.setPositiveButton("OK", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int whichButton) {
                        return;
                    }
                });
                alert.show();
            }
        });
    }

    int result = -1;
    Semaphore semaphore = new Semaphore(0);

    public int showQuestion(String text, String option0, String option1, String option2) {
        final String _text = text;
        final String _option0 = option0;
        final String _option1 = option1;
        final String _option2 = option2;

        UIHandler.post(new Runnable() {
            public void run() {
                AlertDialog.Builder alert = new AlertDialog.Builder(UIActivity);
                alert.setMessage(_text);
                alert.setCancelable(false);
                alert.setPositiveButton(_option0, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int whichButton) {
                        result = 0;
                        semaphore.release();
                    }
                });
                alert.setNegativeButton(_option1, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int whichButton) {
                        result = 1;
                        semaphore.release();
                    }
                });
                alert.setNeutralButton(_option2, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int whichButton) {
                        result = 2;
                        semaphore.release();
                    }
                });

                //(AlertDialog)alert.getButton(DialogInterface.BUTTON_NEUTRAL).setMaxLines(2);

                alert.show();
            }
        });

        try {
            semaphore.acquire();
        }
        catch (InterruptedException e) {
        }
        return result;
    }

    public void showSyncDialog() {
        final String server = config._Call("__getitem__", "server_for_sync_as_client").toString();
        final String port = config._Call("__getitem__", "port_for_sync_as_client").toString();
        final String username = config._Call("__getitem__", "username_for_sync_as_client").toString();
        final String password = config._Call("__getitem__", "password_for_sync_as_client").toString();

        UIHandler.post(new Runnable() {
            public void run() {
                Intent startSyncActivity = new Intent(UIActivity, SyncActivity.class);
                startSyncActivity.putExtra("server", server);
                startSyncActivity.putExtra("port", port);
                startSyncActivity.putExtra("username", username);
                startSyncActivity.putExtra("password", password);
                UIActivity.startActivityForResult(startSyncActivity, UIActivity.SYNC_ACTIVITY_RESULT);
            }
        });
    }

    public void showActivateCardsDialog(String savedSets, String activeSet, StarObjectClass dialog) {
        final String[] _savedSets = savedSets.split("____");
        if (_savedSets.length == 1 && _savedSets[0].equals("")) {
            showInformation("You don't have any saved sets defined. Please do so in the desktop app.");
            return;
        }

        final String _activeSet = activeSet;
        activateCardsDialog = dialog;

        UIHandler.post(new Runnable() {
            public void run() {
                Intent activateActivity = new Intent(UIActivity, ActivateCardsActivity.class);
                Bundle bundle = new Bundle();
                bundle.putStringArray("saved_sets", _savedSets);
                bundle.putString("active_set", _activeSet);
                activateActivity.putExtras(bundle);
                UIActivity.startActivityForResult(activateActivity, UIActivity.ACTIVATE_CARDS_ACTIVITY_RESULT);
            }
        });
    }

    private ProgressDialog progressDialog;
    private int progressValue = 0;
    private String progressText = "";

    public void setProgressText(String text) {
        progressText = text;
        final String _text = text;
        UIHandler.post(new Runnable() {
            public void run() {
                if (progressDialog != null) {
                    progressDialog.dismiss();
                }
                progressDialog = new ProgressDialog(UIActivity);
                progressDialog.setCancelable(false);
                progressDialog.setMessage(_text);
                progressDialog.setProgress(0);
                progressValue = 0;
                progressDialog.show();
            }
        });
    }

    public void setProgressRange(int maximum) {
        final int _maximum = maximum;
        UIHandler.post(new Runnable() {
            public void run() {
                // Android doesn't like changing style on the fly, so we recreate the
                // progress dialog.
                if (progressDialog != null) {
                    progressDialog.dismiss();
                }
                progressDialog = new ProgressDialog(UIActivity);
                progressDialog.setCancelable(false);
                progressDialog.setMessage(progressText);
                progressDialog.setProgress(0);
                progressValue = 0;
                progressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
                progressDialog.setMax(_maximum);
                progressDialog.show();
            }
        });
    }

    public void setProgressValue(int value) {
        final int _value = value;
        UIHandler.post(new Runnable() {
            public void run() {
                if (_value >= progressDialog.getMax()) {
                    closeProgress();
                    return;
                }
                progressValue = _value;
                progressDialog.setProgress(progressValue);
            }
        });
    }

    public void closeProgress() {
        UIHandler.post(new Runnable() {
            public void run() {
                if (progressDialog != null) {
                    progressDialog.dismiss();
                }
            }
        });
    }

}