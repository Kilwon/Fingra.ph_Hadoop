/**
 * Copyright 2014 tgrape Inc.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ph.fingra.hadoop.mapred.parts.distribution;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.WritableComparable;
import org.apache.hadoop.io.WritableComparator;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.Partitioner;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;

import ph.fingra.hadoop.common.ConstantVars;
import ph.fingra.hadoop.common.FingraphConfig;
import ph.fingra.hadoop.common.HfsPathInfo;
import ph.fingra.hadoop.common.LfsPathInfo;
import ph.fingra.hadoop.common.ConstantVars.LogParserType;
import ph.fingra.hadoop.common.ConstantVars.LogValidation;
import ph.fingra.hadoop.common.domain.TargetDate;
import ph.fingra.hadoop.common.logger.ErrorLogger;
import ph.fingra.hadoop.common.logger.WorkLogger;
import ph.fingra.hadoop.common.util.ArgsOptionUtil;
import ph.fingra.hadoop.common.util.FormatUtil;
import ph.fingra.hadoop.mapred.common.CopyToLocalFile;
import ph.fingra.hadoop.mapred.common.HdfsFileUtil;
import ph.fingra.hadoop.mapred.parse.CommonLogParser;
import ph.fingra.hadoop.mapred.parse.ComponentLogParser;
import ph.fingra.hadoop.mapred.parts.distribution.domain.DeviceEntity;
import ph.fingra.hadoop.mapred.parts.distribution.domain.DeviceKey;

public class DeviceStatistic extends Configured implements Tool {
    
    @Override
    public int run(String[] args) throws Exception {
        
        String opt_mode = "";
        String opt_target = "";
        int opt_numreduce = 0;
        
        FingraphConfig fingraphConfig = new FingraphConfig();
        TargetDate targetDate = null;
        
        Configuration conf = getConf();
        Path[] inputPaths = null;
        Path outputPath = null;
        
        // get -D optional value
        opt_mode = conf.get(ConstantVars.DOPTION_RUNMODE, "");
        opt_target = conf.get(ConstantVars.DOPTION_TARGETDATE, "");
        opt_numreduce = conf.getInt(ConstantVars.DOPTION_NUMREDUCE, 0);
        
        // runmode & targetdate check
        if (ArgsOptionUtil.checkRunmode(opt_mode)==false) {
            throw new Exception("option value of -Drunmode is not correct");
        }
        if (opt_target.isEmpty()==false) {
            if (ArgsOptionUtil.checkTargetDateByMode(opt_mode, opt_target)==false) {
                throw new Exception("option value of -Dtargetdate is not correct");
            }
        }
        else {
            opt_target = ArgsOptionUtil.getDefaultTargetDateByMode(opt_mode);
        }
        
        // get TargetDate info from opt_target
        targetDate = ArgsOptionUtil.getTargetDate(opt_mode, opt_target);
        
        WorkLogger.log(DeviceStatistic.class.getSimpleName()
                + " : [run mode] " + opt_mode
                + " , [target date] " + targetDate.getFulldate()
                + " , [reducer count] " + opt_numreduce);
        
        // get this job's input path - transform log file
        inputPaths = HdfsFileUtil.getTransformInputPaths(fingraphConfig, opt_mode,
                targetDate.getYear(), targetDate.getMonth(), targetDate.getDay(),
                targetDate.getHour(), targetDate.getWeek());
        
        // get this job's output path
        HfsPathInfo hfsPath = new HfsPathInfo(fingraphConfig, opt_mode);
        outputPath = new Path(hfsPath.getDevice());
        
        // delete previous output path if is exist
        FileSystem fs = FileSystem.get(conf);
        List<Path> deletePaths = new ArrayList<Path>();
        deletePaths.add(outputPath);
        for (Path deletePath : deletePaths) {
            fs.delete(deletePath, true);
        }
        
        Job job = createJob(conf, inputPaths, outputPath, opt_numreduce,
                fingraphConfig);
        
        int status = job.waitForCompletion(true) ? 0 : 1;
        
        // copy to local result paths
        LfsPathInfo lfsPath = new LfsPathInfo(fingraphConfig, targetDate);
        CopyToLocalFile copier = new CopyToLocalFile();
        copier.dirToFile(outputPath.toString(), lfsPath.getDevice());
        
        return status;
    }
    
    public Job createJob(Configuration conf, Path[] inputpaths, Path outputpath,
            int numreduce, FingraphConfig finconfig) throws IOException {
        
        conf.setBoolean("verbose", finconfig.getDebug().isDebug_show_verbose());
        conf.setBoolean("counter", finconfig.getDebug().isDebug_show_counter());
        
        Job job = new Job(conf);
        String jobName = "distribute/device job";
        job.setJobName(jobName);
        
        job.setJarByClass(DeviceStatistic.class);
        
        for (int i=0; i<inputpaths.length; i++) {
            FileInputFormat.addInputPath(job, inputpaths[i]);
        }
        FileOutputFormat.setOutputPath(job, outputpath);
        
        job.setMapperClass(DeviceMapper.class);
        job.setReducerClass(DeviceReducer.class);
        
        job.setMapOutputKeyClass(DeviceKey.class);
        job.setMapOutputValueClass(DeviceEntity.class);
        
        job.setOutputKeyClass(Text.class);
        job.setOutputValueClass(Text.class);
        
        job.setPartitionerClass(DevicePartitioner.class);
        job.setSortComparatorClass(DeviceSortComparator.class);
        job.setGroupingComparatorClass(DeviceGroupComparator.class);
        
        job.setNumReduceTasks(numreduce);
        
        return job;
    }
    
    static class DeviceMapper
        extends Mapper<LongWritable, Text, DeviceKey, DeviceEntity> {
        
        private boolean verbose = false;
        private boolean counter = false;
        
        private CommonLogParser commonparser = new CommonLogParser();
        private ComponentLogParser compoparser = new ComponentLogParser();
        
        private DeviceKey out_key = new DeviceKey();
        private DeviceEntity out_val = new DeviceEntity();
        
        protected void setup(Context context)
                throws IOException, InterruptedException {
            verbose = context.getConfiguration().getBoolean("verbose", false);
            counter = context.getConfiguration().getBoolean("counter", false);
        }
        
        @Override
        protected void map(LongWritable key, Text value, Context context)
                throws IOException, InterruptedException {
            
            // logtype check
            LogParserType logtype = FormatUtil.getLogParserType(value.toString());
            
            if (logtype.equals(LogParserType.CommonLog)) {
                
                // CommonLog : STARTSESS/PAGEVIEW/ENDSESS
                commonparser.parse(value);
                if (commonparser.hasError() == false) {
                    
                    out_key.set(commonparser.getAppkey(), commonparser.getDevice(),
                            commonparser.getToken(), commonparser.getSession());
                    out_val.set(commonparser.getToken(), commonparser.getSession(),
                            commonparser.getCmd());
                    
                    context.write(out_key, out_val);
                }
                else {
                    if (verbose)
                        System.err.println("Ignoring corrupt input: " + value);
                }
                
                if (counter)
                    context.getCounter(commonparser.getErrorLevel()).increment(1);
            }
            else if (logtype.equals(LogParserType.ComponentLog)) {
                
                // ComponentLog : COMPONENT
                compoparser.parse(value);
                if (compoparser.hasError() == false) {
                    
                    out_key.set(compoparser.getAppkey(), compoparser.getDevice(),
                            compoparser.getToken(), compoparser.getSession());
                    out_val.set(compoparser.getToken(), compoparser.getSession(),
                            compoparser.getCmd());
                    
                    context.write(out_key, out_val);
                }
                else {
                    if (verbose)
                        System.err.println("Ignoring corrupt input: " + value);
                }
                
                if (counter)
                    context.getCounter(compoparser.getErrorLevel()).increment(1);
            }
            else {
                if (verbose)
                    System.err.println("Ignoring corrupt input: " + value);
                if (counter)
                    context.getCounter(LogValidation.MALFORMED).increment(1);
            }
        }
    }
    
    static class DeviceReducer
        extends Reducer<DeviceKey, DeviceEntity, Text, Text> {
        
        private Text out_key = new Text();
        private Text out_val = new Text();
        
        @Override
        protected void reduce(DeviceKey key, Iterable<DeviceEntity> values,
                Context context) throws IOException, InterruptedException {
            
            long user_count = 0;
            long session_count = 0;
            String prev_token = "";
            String prev_session = "";
            for (DeviceEntity cur_val : values) {
                
                // values :
                // - grouped by appkey/device
                // - and order by appkey/device/token/session
                
                if (prev_token.equals(cur_val.token) == false) {
                    user_count += 1l;
                }
                if (prev_session.equals(cur_val.session) == false) {
                    session_count += 1l;
                }
                
                prev_token = cur_val.token;
                prev_session = cur_val.session;
            }
            
            out_key.set(key.appkey + ConstantVars.RESULT_FIELD_SEPERATER
                    + key.device);
            out_val.set(String.valueOf(user_count) + ConstantVars.RESULT_FIELD_SEPERATER
                    + String.valueOf(session_count));
            
            context.write(out_key, out_val);
        }
    }
    
    private static class DevicePartitioner
        extends Partitioner<DeviceKey, DeviceEntity> {
        @Override
        public int getPartition(DeviceKey key, DeviceEntity value,
                int numPartitions) {
            return Math.abs((key.appkey+key.device).hashCode() * 127) % numPartitions;
        }
    }
    
    private static class DeviceSortComparator
        extends WritableComparator {
        protected DeviceSortComparator() {
            super(DeviceKey.class, true);
        }
        @SuppressWarnings("rawtypes")
        @Override
        public int compare(WritableComparable w1, WritableComparable w2) {
            DeviceKey k1 = (DeviceKey) w1;
            DeviceKey k2 = (DeviceKey) w2;
            
            // ordered by DeviceKey compareTo
            int ret = k1.compareTo(k2);
            
            return ret;
        }
    }
    
    private static class DeviceGroupComparator
        extends WritableComparator {
        protected DeviceGroupComparator() {
            super(DeviceKey.class, true);
        }
        @SuppressWarnings("rawtypes")
        @Override
        public int compare(WritableComparable w1, WritableComparable w2) {
            DeviceKey k1 = (DeviceKey) w1;
            DeviceKey k2 = (DeviceKey) w2;
            
            // grouped by appkey/device
            int ret = k1.appkey.compareTo(k2.appkey); if (ret != 0) return ret;
            ret = k1.device.compareTo(k2.device);
            
            return ret;
        }
    }
    
    /**
     * 
     * @param args
     */
    public static void main(String[] args) {
        
        long start_time=0, end_time=0;
        int exitCode = 0;
        
        start_time = System.currentTimeMillis();
        
        WorkLogger.log(DeviceStatistic.class.getSimpleName()
                + " : Start mapreduce job");
        
        try {
            exitCode = ToolRunner.run(new DeviceStatistic(), args);
            
            WorkLogger.log(DeviceStatistic.class.getSimpleName()
                    + " : End mapreduce job");
        }
        catch (Exception e) {
            ErrorLogger.log(DeviceStatistic.class.getSimpleName()
                    + " : Error : " + e.getMessage());
            WorkLogger.log(DeviceStatistic.class.getSimpleName()
                    + " : Failed mapreduce job");
        }
        
        end_time = System.currentTimeMillis();
        
        try {
            FingraphConfig config = new FingraphConfig();
            if (config.getDebug().isDebug_show_spenttime())
                WorkLogger.log("DEBUG - run times : "
                        + FormatUtil.getDurationFromMillitimes(end_time - start_time));
        }
        catch (IOException ignore) {}
        
        System.exit(exitCode);
    }
}
