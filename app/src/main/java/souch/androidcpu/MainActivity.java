package souch.androidcpu;

import android.os.Build;
import android.os.Bundle;
import android.support.design.widget.FloatingActionButton;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.view.View;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.TextView;

import java.util.Timer;
import java.util.TimerTask;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        FloatingActionButton fab = (FloatingActionButton) findViewById(R.id.fab);
        fab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                updateUsage();
            }
        });

        Timer timer = new Timer();
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                runOnUiThread(new Runnable() {
                    public void run() {
                        updateUsageAuto();
                    }
                });
            };
        }, 1000, 1000);

    }

    private void updateUsageAuto() {
        TextView textFreq = (TextView) findViewById(R.id.cpuUsageFreq);
        String info = getCpuUsageFreq();
        textFreq.setText(info);
    }

    private void updateUsage() {
        TextView text = (TextView) findViewById(R.id.cpuUsage);
        String info = new String();
        if (Build.VERSION.SDK_INT < 26) {
            info += getCpuUsageDeprecated();
            text.setText(info);
        }
        else
            text.setText("Fetching cpu usage from /proc/stat is deprecated on oreo");
    }


    private String getCpuUsageInfo(int[] cores) {
        String info = new String();
        info += " cores: \n";
        for (int i = 1; i < cores.length; i++) {
            if (cores[i] < 0)
                info += "  " + i + ": x\n";
            else
                info += "  " + i + ": " + cores[i] + "%\n";
        }
        info += "  moy=" + cores[0] + "% \n";
        info += "CPU total: " + CpuInfo.getCpuUsage(cores) + "%";
        return info;
    }

    private String getCpuUsageFreq() {
        String info = new String();
        info += "using getCoresUsageGuessFromFreq";
        info += getCpuUsageInfo(CpuInfo.getCoresUsageGuessFromFreq());
        return info;
    }

    private String getCpuUsageDeprecated() {
        String info = new String();
        info += "using getCoresUsage";
        info += getCpuUsageInfo(CpuInfo.getCoresUsageDeprecated());
        return info;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            return true;
        }

        return super.onOptionsItemSelected(item);
    }
}
