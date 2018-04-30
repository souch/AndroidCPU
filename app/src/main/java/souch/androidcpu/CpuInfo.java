package souch.androidcpu;

import java.io.File;
import java.io.FileFilter;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.regex.Pattern;

public class CpuInfo {

    /*
     * @return total cpu usage (from 0 to 100) since last call of getCpuUsage or getCoresUsage
     */
    public static int getCpuUsage() {
        return getCpuUsage(getCoresUsage());
    }

    /* @return total cpu usage (from 0 to 100) since last call of getCpuUsage or getCoresUsage
     *         first call always returns 0 as previous value is not known
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

    /*
     * @return array of cores usage since last call
     *   (first call always returns -1 as the func has never been called).
     *   array size = nbcore +1 as the first element is for global cpu usage
     *   First element is global CPU usage from stat file (which does not consider offline core !
     *     Use getCpuUsage do get proper global CPU usage)
     *   array element: < 0 => cpu unavailable ; 0 => cpu min ; 1 => cpu max
     */
    public static synchronized float[] getCoresUsage() {
        int numCores = getNumCores() + 1; // +1 for global cpu stat

        // ensure mPrevCores list is big enough
        if (mPrevCoreStats == null)
            mPrevCoreStats = new ArrayList<>();
        while(mPrevCoreStats.size() < numCores)
            mPrevCoreStats.add(null);//new CpuStat(-1, -1));

        // init cpuStats
        ArrayList<CoreStat> coreStats = new ArrayList<>();
        while(coreStats.size() < numCores)
            coreStats.add(null);

        float[] coresUsage = new float[numCores];
        for (byte i = 0; i < numCores; i++)
            coresUsage[i] = -1;

        try {
            /* cat /proc/stat # example of possible output
             *   cpu  193159 118453 118575 7567474 4615 6 2312 0 0 0
             *   cpu0 92389 116352 96662 2125638 2292 5 2021 0 0 0
             *   cpu3 47648 1264 11220 2378965 1286 0 9 0 0 0
             *   ...
             */
            RandomAccessFile reader = new RandomAccessFile("/proc/stat", "r");

            try {
                CoreStat curCoreStat = null;
                String line = reader.readLine();

                for (byte i = 0; i < numCores; i++) {
                    // cpu lines are only at the top of the file
                    if (!line.contains("cpu"))
                        break;

                    // try get core stat number i
                    curCoreStat = readCoreStat(i, line);
                    if (curCoreStat != null) {
                        CoreStat prevCoreStat = mPrevCoreStats.get(i);
                        if (prevCoreStat != null) {
                            float diffActive = curCoreStat.active - prevCoreStat.active;
                            float diffTotal = curCoreStat.total - prevCoreStat.total;
                            // check for strange values
                            if (diffActive > 0 && diffTotal > 0)
                                // compute usage
                                coresUsage[i] = diffActive / diffTotal;
                        }

                        // cur becomes prev (only if cpu online)
                        mPrevCoreStats.set(i, curCoreStat);

                        // load another line only if corresponding core has been found
                        // otherwise try next core number with same line
                        line = reader.readLine();
                    }
                }

            } finally {
                reader.close();
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }

        return coresUsage;
    }

    /* return CpuStat read, or null if it could not be read (e.g. cpu offline)
     * @param coreNum coreNum=0 return global cpu state, coreNum=1 return first core
     *
     * adapted from https://stackoverflow.com/questions/22405403/android-cpu-cores-reported-in-proc-stat
     */
    private static CoreStat readCoreStat(int coreNum, String line) {
        CoreStat coreStat = null;
        try {
            String cpuStr;
            if (coreNum > 0)
                cpuStr = "cpu" + (coreNum - 1) + " ";
            else
                cpuStr = "cpu ";

            if (line.contains(cpuStr)) {
                String[] toks = line.split(" +");

                // we are recording the work being used by the user and
                // system(work) and the total info of cpu stuff (total)
                // http://stackoverflow.com/questions/3017162/how-to-get-total-cpu-usage-in-linux-c/3017438#3017438
                // user  nice  system  idle  iowait  irq  softirq  steal
                long active = Long.parseLong(toks[1]) + Long.parseLong(toks[2])
                        + Long.parseLong(toks[3]);
                long total = Long.parseLong(toks[1]) + Long.parseLong(toks[2])
                        + Long.parseLong(toks[3]) + Long.parseLong(toks[4])
                        + Long.parseLong(toks[5]) + Long.parseLong(toks[6])
                        + Long.parseLong(toks[7]) + Long.parseLong(toks[8]);

                coreStat = new CoreStat(active, total);
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        return coreStat;
    }

    private static class CoreStat {
        float active;
        float total;

        CoreStat(float active, float total) {
            this.active = active;
            this.total = total;
        }
    }

    // previous stat read
    private static ArrayList<CoreStat> mPrevCoreStats;


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
