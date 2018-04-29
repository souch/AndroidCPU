package souch.androidcpu;

import java.io.File;
import java.io.FileFilter;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.regex.Pattern;

public class CpuInfo {

    /* @return total cpu usage since last call (from 0 to 100)
     * note:
     *   - first call always returns 0 as previous value is not initialized
     * @param coresUsage must come from getCoresUsage().
     */
    public static int getCpuUsage(float[] coresUsage) {
        // compute total cpu usage from each core as the total cpu usage given by /proc/stat seems
        // not considering offline cores: i.e. 2 cores, 1 is offline, total cpu usage given by /proc/stat
        // is equal to remaining online core (should be remaining online core / 2).
        float cpuUsage = 0;
        if (coresUsage.length < 2)
            return 0;
        for (int i = 1; i < coresUsage.length; i++) {
            if (coresUsage[i] > 0)
                cpuUsage += coresUsage[i];
        }
        return (int) (cpuUsage * 100 / (coresUsage.length - 1));
    }

    public static int getCpuUsage() {
        return getCpuUsage(getCoresUsage());
    }

    private static class CpuStat {
        float active;
        float total;

        CpuStat(float active, float total) {
            this.active = active;
            this.total = total;
        }
    }

    private static ArrayList<CpuStat> mPrevCpuStats;
    /*
     * @return array of cores usage since last call.
     *   array size = nbcore +1 as the first element is for global cpu usage
     *   array element : < 0 -> cpu unavailable ; 0 -> cpu min ; 1 -> cpu max
     */
    public static synchronized float[] getCoresUsage() {
        int numCores = getNumCores() + 1; // +1 for global cpu stat

        // ensure mPrevCores list is big enough
        if (mPrevCpuStats == null)
            mPrevCpuStats = new ArrayList<>();
        while(mPrevCpuStats.size() < numCores)
            mPrevCpuStats.add(null);//new CpuStat(-1, -1));

        // init cpuStats
        ArrayList<CpuStat> cpuStats = new ArrayList<>();
        while(cpuStats.size() < numCores)
            cpuStats.add(null);

        float[] coresUsage = new float[numCores];

        // get current cpu stat
        for (byte i = 0; i < numCores; i++) {
            // todo: do not reopen stat file for each core
            CpuStat curCpuStat = readCpuStat(i);
            coresUsage[i] = -1;
            if (curCpuStat != null) {
                CpuStat prevCpuStat = mPrevCpuStats.get(i);
                if (prevCpuStat != null) {
                    float diffActive = curCpuStat.active - prevCpuStat.active;
                    float diffTotal = curCpuStat.total - prevCpuStat.total;
                    // check for strange values
                    if (diffActive > 0 && diffTotal > 0)
                        // compute usage
                        coresUsage[i] = diffActive / diffTotal;
                }

                // cur becomes prev (only if cpu online)
                mPrevCpuStats.set(i, curCpuStat);
            }
        }

        return coresUsage;
    }

    /* return CpuStat read, or null if it could not be read (e.g. cpu offline)
     * @param coreNum coreNum=0 return global cpu state, coreNum=1 return first core
     *
     * cat /proc/stat # example of possible output
     *   cpu  193159 118453 118575 7567474 4615 6 2312 0 0 0
     *   cpu0 92389 116352 96662 2125638 2292 5 2021 0 0 0
     *   cpu3 47648 1264 11220 2378965 1286 0 9 0 0 0
     *   ...
     *
     * adapted from https://stackoverflow.com/questions/22405403/android-cpu-cores-reported-in-proc-stat
     */
    private static CpuStat readCpuStat(int coreNum) {
        CpuStat cpuStat = null;

        try {
            RandomAccessFile reader = new RandomAccessFile("/proc/stat", "r");

            try {
                String cpuStr;
                if (coreNum > 0)
                    cpuStr = "cpu" + (coreNum - 1) + " ";
                else
                    cpuStr = "cpu ";

                // cores will eventually go offline, so we need to do check every lines
                // read at most coreNum+1 line
                for (int i = 0; i < coreNum + 1; ++i) {
                    String load = reader.readLine();
                    if (load.contains(cpuStr)) {
                        String[] toks = load.split(" +");

                        // we are recording the work being used by the user and
                        // system(work) and the total info of cpu stuff (total)
                        // http://stackoverflow.com/questions/3017162/how-to-get-total-cpu-usage-in-linux-c/3017438#3017438
                        long active = Long.parseLong(toks[1]) + Long.parseLong(toks[2])
                                + Long.parseLong(toks[3]);
                        long total = Long.parseLong(toks[1]) + Long.parseLong(toks[2])
                                + Long.parseLong(toks[3]) + Long.parseLong(toks[4])
                                + Long.parseLong(toks[5]) + Long.parseLong(toks[6])
                                + Long.parseLong(toks[7]) + Long.parseLong(toks[8]);

                        cpuStat = new CpuStat(active, total);
                    }
                }
            } finally {
                reader.close();
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }

        return cpuStat;
    }

    // from https://stackoverflow.com/questions/7962155/how-can-you-detect-a-dual-core-cpu-on-an-android-device-from-code
    /**
     * Gets the number of cores available in this device, across all processors.
     * Requires: Ability to peruse the filesystem at "/sys/devices/system/cpu"
     * @return The number of cores, or 1 if failed to get result
     */
    public static int getNumCores() {
        //Private Class to display only CPU devices in the directory listing
        class CpuFilter implements FileFilter {
            @Override
            public boolean accept(File pathname) {
                //Check if filename is "cpu", followed by one or more digits
                if(Pattern.matches("cpu[0-9]+", pathname.getName())) {
                    return true;
                }
                return false;
            }
        }

        try {
            //Get directory containing CPU info
            File dir = new File("/sys/devices/system/cpu/");
            //Filter to only list the devices we care about
            File[] files = dir.listFiles(new CpuFilter());
            //Return the number of cores (virtual CPU devices)
            return files.length;
        } catch(Exception e) {
            //Default to return 1 core
            return 1;
        }
    }

}
