package souch.androidcpu;

import android.os.Build;

import java.io.File;
import java.io.FileFilter;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.regex.Pattern;

public class CpuInfo {

    /*
     * return current cpu usage (0 to 100) guessed from core frequencies
     */
    public static int getCpuUsageFromFreq() {
        return getCpuUsage(getCoresUsageGuessFromFreq());
    }

    /*
     * @return total cpu usage (from 0 to 100) since last call of getCpuUsage or getCoresUsage
     *         first call always returns 0 as previous value is not known
     * ! deprecated since oreo !
     */
    public static int getCpuUsageSinceLastCall() {
        if (Build.VERSION.SDK_INT < 26)
            return getCpuUsage(getCoresUsageDeprecated());
        else
            return 0;
    }

    /* @return total cpu usage (from 0 to 100) from cores usage array
     * @param coresUsage must come from getCoresUsageXX().
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
        return (int) (cpuUsage / (coresUsage.length - 1));
    }

    /*
     * guess core usage using core frequency (e.g. all core at min freq => 0% usage;
     *   all core at max freq => 100%)
     *
     * This function is compatible with android oreo and later but is less precise than
     *   getCoresUsageDeprecated.
     * This function returns the current cpu usage (not the average usage since last call).
     *
     * @return array of cores usage
     *   array size = nbcore +1 as the first element is for global cpu usage
     *   array element: 0 => cpu at 0% ; 100 => cpu at 100%
     */
    public static synchronized float[] getCoresUsageGuessFromFreq()
    {
        initCoresFreq();
        int nbCores = mCoresFreq.size() + 1;
        float[] coresUsage = new float[nbCores];
        coresUsage[0] = 0;
        for (byte i = 0; i < mCoresFreq.size(); i++) {
            coresUsage[i + 1] = mCoresFreq.get(i).getCurUsage();
            coresUsage[0] += coresUsage[i + 1];
        }
        if (mCoresFreq.size() > 0)
            coresUsage[0] /= mCoresFreq.size();
        return coresUsage;
    }

    public static void initCoresFreq()
    {
        if (mCoresFreq == null) {
            int nbCores = getNbCores();
            mCoresFreq = new ArrayList<>();
            for (byte i = 0; i < nbCores; i++) {
                mCoresFreq.add(new CoreFreq(i));
            }
        }
    }

    private static int getCurCpuFreq(int coreIndex) {
        return readIntegerFile("/sys/devices/system/cpu/cpu" + coreIndex + "/cpufreq/scaling_cur_freq");
    }

    private static int getMinCpuFreq(int coreIndex) {
        return readIntegerFile("/sys/devices/system/cpu/cpu" + coreIndex + "/cpufreq/cpuinfo_min_freq");
    }

    private static int getMaxCpuFreq(int coreIndex) {
        return readIntegerFile("/sys/devices/system/cpu/cpu" + coreIndex + "/cpufreq/cpuinfo_max_freq");
    }


    // return 0 if any pb occurs
    private static int readIntegerFile(String path)
    {
        int ret = 0;
        try {
            RandomAccessFile reader = new RandomAccessFile(path, "r");

            try {
                String line = reader.readLine();
                ret = Integer.parseInt(line);
            } finally {
                reader.close();
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        return ret;
    }


    // from https://stackoverflow.com/questions/7962155/how-can-you-detect-a-dual-core-cpu-on-an-android-device-from-code
    /**
     * Gets the number of cores available in this device, across all processors.
     * Requires: Ability to peruse the filesystem at "/sys/devices/system/cpu"
     * @return The number of cores, or 1 if failed to get result
     */
    public static int getNbCores() {
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

    // current cores frequencies
    private static ArrayList<CoreFreq> mCoresFreq;

    private static class CoreFreq {
        int num, cur, min, max;

        CoreFreq(int num) {
            this.num = num;
            min = getMinCpuFreq(num);
            max = getMaxCpuFreq(num);
        }

        void updateCurFreq() {
            cur = getCurCpuFreq(num);
            min = getMinCpuFreq(num);
            max = getMaxCpuFreq(num);
        }

        /* return usage from 0 to 100 */
        int getCurUsage() {
            updateCurFreq();
            int cpuUsage = 0;
            if (max - min > 0 & max > 0 && cur > 0) {
//                if (cur == min)
//                    cpuUsage = 2; // consider lowest freq as 2% usage (usually core is offline if 0%)
//                else
                cpuUsage = (cur - min) * 100 / (max - min);
            }
            return cpuUsage;
        }
    }


    /*********************************/
    /* !!! deprecated since oreo !!! */

    /*
     * @return array of cores usage since last call
     *   (first call always returns -1 as the func has never been called).
     *   array size = nbcore +1 as the first element is for global cpu usage
     *   First element is global CPU usage from stat file (which does not consider offline core !
     *     Use getCpuUsage do get proper global CPU usage)
     *   array element: < 0 => cpu unavailable ; 0 => cpu min ; 100 => cpu max
     */
    public static synchronized float[] getCoresUsageDeprecated() {
        int numCores = getNbCores() + 1; // +1 for global cpu stat

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
                                coresUsage[i] = diffActive * 100 / diffTotal;
                            // check strange values
                            if (coresUsage[i] > 100)
                                coresUsage[i] = 100;
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
//                long active = total - Long.parseLong(toks[4]);

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

}
